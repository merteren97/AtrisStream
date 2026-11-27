use crate::http::HttpClient;
use crate::provider::{MediaProvider, SourceError};
use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    StreamResult,
};
use regex::Regex;
use rquickjs::function::Opt;
use rquickjs::{Context, Function, Object, Runtime};
use serde::Deserialize;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::oneshot;

const JS_MEMORY_LIMIT_BYTES: usize = 32 * 1024 * 1024;
const JS_TIMEOUT_SECS: u64 = 15;

const HTTP_SHIM: &str = r#"
function httpGet(url, headersJson) {
    return atrisHttp.get(url, headersJson || "{}");
}
function httpPost(url, body, headersJson) {
    return atrisHttp.post(url, body, "application/json", headersJson || "{}");
}
"#;

enum JsCommand {
    Eval {
        expression: String,
        reply: oneshot::Sender<Result<String, SourceError>>,
    },
    Shutdown,
}

pub struct JsPluginProvider {
    manifest: ProviderManifest,
    tx: mpsc::Sender<JsCommand>,
}

impl JsPluginProvider {
    pub fn new(manifest: ProviderManifest, script: String, http: HttpClient) -> Self {
        let (tx, rx) = mpsc::channel::<JsCommand>();
        let rt_handle = tokio::runtime::Handle::try_current().ok();
        let thread_name = format!("QuickJs-{}", manifest.id);

        std::thread::Builder::new()
            .name(thread_name)
            .spawn(move || {
                let fallback_rt = if rt_handle.is_none() {
                    tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                        .ok()
                } else {
                    None
                };
                let handle = rt_handle
                    .or_else(|| fallback_rt.as_ref().map(|r| r.handle().clone()))
                    .expect("failed to acquire tokio runtime handle for QuickJS");

                let eval_started = Arc::new(Mutex::new(Instant::now()));
                let Ok(runtime) = Runtime::new() else {
                    return;
                };
                runtime.set_memory_limit(JS_MEMORY_LIMIT_BYTES);
                let timer_ref = Arc::clone(&eval_started);
                runtime.set_interrupt_handler(Some(Box::new(move || {
                    timer_ref
                        .lock()
                        .map(|t| t.elapsed() > Duration::from_secs(JS_TIMEOUT_SECS))
                        .unwrap_or(false)
                })));

                let Ok(ctx) = Context::full(&runtime) else {
                    return;
                };

                let init_ok = ctx.with(|ctx| -> rquickjs::Result<()> {
                    let atris_http = Object::new(ctx.clone())?;

                    let http_get = http.clone();
                    let handle_get = handle.clone();
                    atris_http.set(
                        "get",
                        Function::new(
                            ctx.clone(),
                            move |url: String, headers_json: Opt<String>| -> String {
                                let hdrs = parse_headers(headers_json.0.as_deref());
                                handle_get
                                    .block_on(http_get.get_text(&url, &hdrs))
                                    .unwrap_or_default()
                            },
                        )?,
                    )?;

                    let http_post = http.clone();
                    let handle_post = handle.clone();
                    atris_http.set(
                        "post",
                        Function::new(
                            ctx.clone(),
                            move |url: String,
                                  body: String,
                                  content_type: Opt<String>,
                                  headers_json: Opt<String>|
                                  -> String {
                                let ct = content_type
                                    .0
                                    .filter(|s| !s.trim().is_empty())
                                    .unwrap_or_else(|| "application/json".to_string());
                                let hdrs = parse_headers(headers_json.0.as_deref());
                                handle_post
                                    .block_on(http_post.post_text(&url, &body, &ct, &hdrs))
                                    .unwrap_or_default()
                            },
                        )?,
                    )?;

                    ctx.globals().set("atrisHttp", atris_http)?;
                    ctx.eval::<(), _>(HTTP_SHIM)?;
                    ctx.eval::<(), _>(script.as_str())?;
                    Ok(())
                });

                let init_err = init_ok.err().map(|e| format!("QuickJS init error: {e}"));

                while let Ok(cmd) = rx.recv() {
                    match cmd {
                        JsCommand::Eval { expression, reply } => {
                            if let Some(ref err_msg) = init_err {
                                let _ = reply.send(Err(SourceError::Plugin(err_msg.clone())));
                                continue;
                            }
                            if let Ok(mut guard) = eval_started.lock() {
                                *guard = Instant::now();
                            }
                            let res = ctx.with(|ctx| {
                                ctx.eval::<String, _>(expression.as_str())
                                    .map_err(|e| SourceError::Plugin(format!("QuickJS eval error: {e}")))
                            });
                            let _ = reply.send(res);
                        }
                        JsCommand::Shutdown => break,
                    }
                }
            })
            .expect("failed to spawn QuickJS worker thread");

        Self { manifest, tx }
    }

    async fn eval_string(&self, expression: String) -> Result<String, SourceError> {
        let (reply_tx, reply_rx) = oneshot::channel();
        self.tx
            .send(JsCommand::Eval {
                expression,
                reply: reply_tx,
            })
            .map_err(|_| SourceError::Plugin("QuickJS worker thread terminated".to_string()))?;
        reply_rx
            .await
            .map_err(|_| SourceError::Plugin("QuickJS reply channel dropped".to_string()))?
    }

    pub fn extract_manifest(script: &str, fallback_url: &str) -> ProviderManifest {
        if let Ok(re) = Regex::new(r"(?m)(?:var|let|const)\s+manifest\s*=\s*(\{[\s\S]*?\})\s*;") {
            if let Some(caps) = re.captures(script) {
                if let Some(raw_match) = caps.get(1) {
                    if let Ok(key_re) = Regex::new(r"(?m)(^|[{,])\s*(\w+)\s*:") {
                        let json_like = key_re
                            .replace_all(raw_match.as_str(), r#"$1"$2":"#)
                            .replace('\'', "\"");
                        if let Ok(parsed) = serde_json::from_str::<JsManifestDto>(&json_like) {
                            if let Some(id) = parsed.id.filter(|s| !s.trim().is_empty()) {
                                let supported_types: Vec<MediaType> = parsed
                                    .supported_types
                                    .iter()
                                    .filter_map(|t| match t.to_uppercase().as_str() {
                                        "MOVIE" => Some(MediaType::MOVIE),
                                        "TV" => Some(MediaType::TV),
                                        "ANIME" => Some(MediaType::ANIME),
                                        "LIVE" => Some(MediaType::LIVE),
                                        _ => None,
                                    })
                                    .collect();
                                return ProviderManifest {
                                    name: parsed.name.unwrap_or_else(|| id.clone()),
                                    id,
                                    version: parsed.version,
                                    base_url: if parsed.base_url.trim().is_empty() {
                                        fallback_url.to_string()
                                    } else {
                                        parsed.base_url
                                    },
                                    supported_types: if supported_types.is_empty() {
                                        vec![MediaType::MOVIE, MediaType::TV]
                                    } else {
                                        supported_types
                                    },
                                    languages: if parsed.languages.is_empty() {
                                        vec!["en".to_string()]
                                    } else {
                                        parsed.languages
                                    },
                                };
                            }
                        }
                    }
                }
            }
        }

        let fallback_id = fallback_url
            .rsplit('/')
            .next()
            .unwrap_or("")
            .split('?')
            .next()
            .unwrap_or("")
            .strip_suffix(".js")
            .unwrap_or("")
            .trim();
        let id = if fallback_id.is_empty() {
            "js_plugin".to_string()
        } else {
            fallback_id.to_string()
        };

        ProviderManifest {
            id: id.clone(),
            name: id,
            version: "1.0.0".to_string(),
            base_url: fallback_url.to_string(),
            supported_types: vec![MediaType::MOVIE, MediaType::TV],
            languages: vec!["en".to_string()],
        }
    }
}

impl Drop for JsPluginProvider {
    fn drop(&mut self) {
        let _ = self.tx.send(JsCommand::Shutdown);
    }
}

#[async_trait]
impl MediaProvider for JsPluginProvider {
    fn manifest(&self) -> &ProviderManifest {
        &self.manifest
    }

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError> {
        let raw = self
            .eval_string("(function(){ var r = getHome(); return typeof r === 'string' ? r : JSON.stringify(r); })()".to_string())
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }

    async fn browse(
        &self,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        let type_arg = serde_json::to_string(&media_type)?;
        let raw = self
            .eval_string(format!(
                "(function(){{ var r = browse({type_arg}, {page}); return typeof r === 'string' ? r : JSON.stringify(r); }})()"
            ))
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }

    async fn search(&self, query: &str, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        let q_arg = serde_json::to_string(query)?;
        let raw = self
            .eval_string(format!(
                "(function(){{ var r = search({q_arg}, {page}); return typeof r === 'string' ? r : JSON.stringify(r); }})()"
            ))
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }

    async fn get_details(
        &self,
        id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        let id_arg = serde_json::to_string(id)?;
        let type_arg = serde_json::to_string(&media_type)?;
        let raw = self
            .eval_string(format!(
                "(function(){{ var r = getDetails({id_arg}, {type_arg}); return typeof r === 'string' ? r : JSON.stringify(r); }})()"
            ))
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }

    async fn get_episodes(&self, id: &str, season: i32) -> Result<Vec<Episode>, SourceError> {
        let id_arg = serde_json::to_string(id)?;
        let raw = self
            .eval_string(format!(
                "(function(){{ var r = getEpisodes({id_arg}, {season}); return typeof r === 'string' ? r : JSON.stringify(r); }})()"
            ))
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }

    async fn get_streams(
        &self,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let id_arg = serde_json::to_string(media_id)?;
        let type_arg = serde_json::to_string(&media_type)?;
        let s_arg = season
            .map(|s| s.to_string())
            .unwrap_or_else(|| "null".to_string());
        let e_arg = episode
            .map(|e| e.to_string())
            .unwrap_or_else(|| "null".to_string());
        let raw = self
            .eval_string(format!(
                "(function(){{ var r = getStreams({id_arg}, {type_arg}, {s_arg}, {e_arg}); return typeof r === 'string' ? r : JSON.stringify(r); }})()"
            ))
            .await?;
        Ok(serde_json::from_str(&raw)?)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct JsManifestDto {
    id: Option<String>,
    name: Option<String>,
    #[serde(default = "default_version")]
    version: String,
    #[serde(default)]
    base_url: String,
    #[serde(default = "default_types")]
    supported_types: Vec<String>,
    #[serde(default = "default_langs")]
    languages: Vec<String>,
}

fn default_version() -> String {
    "1.0.0".to_string()
}
fn default_types() -> Vec<String> {
    vec!["MOVIE".to_string(), "TV".to_string()]
}
fn default_langs() -> Vec<String> {
    vec!["en".to_string()]
}

fn parse_headers(headers_json: Option<&str>) -> HashMap<String, String> {
    let Some(raw) = headers_json.filter(|s| !s.trim().is_empty()) else {
        return HashMap::new();
    };
    let Ok(Value::Object(map)) = serde_json::from_str::<Value>(raw) else {
        return HashMap::new();
    };
    map.into_iter()
        .filter_map(|(k, v)| v.as_str().map(|s| (k, s.to_string())))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use atris_core::StreamFormat;

    const SAMPLE_JS: &str =
        include_str!("../../../../../docs/source-templates/sample-js-plugin.js");

    #[tokio::test]
    async fn executes_sample_js_plugin_end_to_end() {
        let manifest = JsPluginProvider::extract_manifest(SAMPLE_JS, "https://example.com/plugin.js");
        assert_eq!(manifest.id, "example-js-source");
        assert_eq!(manifest.name, "Example JS Provider");
        assert_eq!(
            manifest.supported_types,
            vec![MediaType::MOVIE, MediaType::TV, MediaType::ANIME]
        );

        let provider = JsPluginProvider::new(manifest, SAMPLE_JS.to_string(), HttpClient::new());

        let home = provider.get_home().await.unwrap();
        assert_eq!(home.len(), 1);
        assert_eq!(home[0].title, "Trending");
        assert_eq!(home[0].items[0].id, "sample-movie-1");

        let browse = provider.browse(MediaType::MOVIE, 2).await.unwrap();
        assert_eq!(browse.page, 2);
        assert!(!browse.has_next_page);

        let details = provider
            .get_details("sample-movie-1", MediaType::MOVIE)
            .await
            .unwrap();
        assert_eq!(details.title, "Sample Movie");
        assert_eq!(details.genres, vec!["Action", "Sci-Fi"]);

        let streams = provider
            .get_streams("sample-movie-1", MediaType::MOVIE, None, None)
            .await
            .unwrap();
        assert_eq!(streams.streams.len(), 1);
        assert_eq!(streams.streams[0].format, StreamFormat::HLS);
        assert_eq!(
            streams.streams[0].headers.get("Referer").map(String::as_str),
            Some("https://example.com/")
        );
    }
}
