use crate::http::HttpClient;
use crate::js_plugin::JsPluginProvider;
use crate::json_manifest::JsonManifestProvider;
use crate::movy::{self, MovyProvider};
use crate::provider::{MediaProvider, SourceError};
use atris_core::{
    Episode, HomeSection, InstalledSource, MediaCard, MediaDetails, MediaType, PagedResult,
    ProviderManifest, SourceDriverType, SourceHealthResult, SourceSearchResult, StreamResult,
};
use atris_storage::AtrisStorage;
use serde::Deserialize;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;

struct CachedProvider {
    config_key: String,
    provider: Arc<dyn MediaProvider>,
}

pub struct SourceManager {
    storage: Arc<AtrisStorage>,
    http: HttpClient,
    cache: RwLock<HashMap<String, CachedProvider>>,
}

impl SourceManager {
    pub fn new(storage: Arc<AtrisStorage>) -> Self {
        Self {
            storage,
            http: HttpClient::new(),
            cache: RwLock::new(HashMap::new()),
        }
    }

    pub async fn initialize(&self) -> Result<(), SourceError> {
        if self.storage.get_source_by_id(movy::SOURCE_ID)?.is_none() {
            self.storage.upsert_source(&InstalledSource {
                id: movy::SOURCE_ID.to_string(),
                name: "Movy.sx (TR)".to_string(),
                version: "1.0.0".to_string(),
                base_url: movy::SITE_URL.to_string(),
                manifest_url: None,
                driver_type: SourceDriverType::BUILTIN,
                enabled: true,
                is_built_in: true,
                supported_types: vec![MediaType::MOVIE, MediaType::TV, MediaType::ANIME],
                languages: vec!["tr".to_string(), "en".to_string()],
                raw_config: None,
                added_at: now_ms(),
            })?;
        }
        Ok(())
    }

    async fn get_provider(&self, source_id: &str) -> Result<Arc<dyn MediaProvider>, SourceError> {
        if let Some(src) = self.storage.get_source_by_id(source_id)? {
            return self.build_provider(&src).await;
        }
        if source_id == movy::SOURCE_ID {
            let mut cache = self.cache.write().await;
            if let Some(cached) = cache.get(source_id) {
                return Ok(Arc::clone(&cached.provider));
            }
            let provider: Arc<dyn MediaProvider> = Arc::new(MovyProvider::new(self.http.clone()));
            cache.insert(
                source_id.to_string(),
                CachedProvider {
                    config_key: "builtin".to_string(),
                    provider: Arc::clone(&provider),
                },
            );
            return Ok(provider);
        }
        Err(SourceError::NotFound(source_id.to_string()))
    }

    async fn build_provider(
        &self,
        source: &InstalledSource,
    ) -> Result<Arc<dyn MediaProvider>, SourceError> {
        let key = format!(
            "{:?}:{}:{}",
            source.driver_type,
            source.version,
            source.raw_config.as_deref().unwrap_or("")
        );
        {
            let cache = self.cache.read().await;
            if let Some(cached) = cache.get(&source.id) {
                if cached.config_key == key {
                    return Ok(Arc::clone(&cached.provider));
                }
            }
        }

        let created: Arc<dyn MediaProvider> = match source.driver_type {
            SourceDriverType::BUILTIN => {
                if source.id == movy::SOURCE_ID {
                    Arc::new(MovyProvider::new(self.http.clone()))
                } else {
                    return Err(SourceError::NotFound(source.id.clone()));
                }
            }
            SourceDriverType::JSON_MANIFEST => {
                let raw = source
                    .raw_config
                    .as_deref()
                    .ok_or_else(|| SourceError::InvalidArgument("Missing raw_config".to_string()))?;
                let cfg = JsonManifestProvider::parse_config(raw)?;
                Arc::new(JsonManifestProvider::from_http(cfg, self.http.clone()))
            }
            SourceDriverType::JS_PLUGIN => {
                let raw = source
                    .raw_config
                    .as_deref()
                    .ok_or_else(|| SourceError::InvalidArgument("Missing raw_config".to_string()))?;
                let manifest = ProviderManifest {
                    id: source.id.clone(),
                    name: source.name.clone(),
                    version: source.version.clone(),
                    base_url: source.base_url.clone(),
                    supported_types: source.supported_types.clone(),
                    languages: source.languages.clone(),
                };
                Arc::new(JsPluginProvider::new(
                    manifest,
                    raw.to_string(),
                    self.http.clone(),
                ))
            }
        };

        self.cache.write().await.insert(
            source.id.clone(),
            CachedProvider {
                config_key: key,
                provider: Arc::clone(&created),
            },
        );
        Ok(created)
    }

    pub async fn get_home(&self, source_id: &str) -> Result<Vec<HomeSection>, SourceError> {
        let provider = self.get_provider(source_id).await?;
        provider.get_home().await
    }

    pub async fn browse(
        &self,
        source_id: &str,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        let provider = self.get_provider(source_id).await?;
        provider.browse(media_type, page).await
    }

    pub async fn search(
        &self,
        query: &str,
        page: u32,
        source_id: Option<&str>,
    ) -> Result<Vec<SourceSearchResult>, SourceError> {
        let targets: Vec<InstalledSource> = match source_id {
            Some(id) => self
                .storage
                .get_source_by_id(id)?
                .into_iter()
                .collect(),
            None => self.storage.get_enabled_sources()?,
        };

        let mut results = Vec::new();
        for src in targets {
            let Ok(provider) = self.build_provider(&src).await else {
                continue;
            };
            if let Ok(paged) = provider.search(query, page).await {
                results.push(SourceSearchResult {
                    source_id: src.id,
                    source_name: src.name,
                    result: paged,
                });
            }
        }
        Ok(results)
    }

    pub async fn get_details(
        &self,
        source_id: &str,
        media_id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        let provider = self.get_provider(source_id).await?;
        provider.get_details(media_id, media_type).await
    }

    pub async fn get_episodes(
        &self,
        source_id: &str,
        media_id: &str,
        season: i32,
    ) -> Result<Vec<Episode>, SourceError> {
        let provider = self.get_provider(source_id).await?;
        provider.get_episodes(media_id, season).await
    }

    pub async fn get_streams(
        &self,
        source_id: &str,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let provider = self.get_provider(source_id).await?;
        provider
            .get_streams(media_id, media_type, season, episode)
            .await
    }

    pub async fn add_source_from_url(&self, url: &str) -> Result<Vec<InstalledSource>, SourceError> {
        let trimmed = url.trim();
        if !trimmed.starts_with("http://") && !trimmed.starts_with("https://") {
            return Err(SourceError::InvalidArgument(format!(
                "Invalid source URL: {url}"
            )));
        }
        let empty_headers = HashMap::new();
        let content = self.http.get_text(trimmed, &empty_headers).await?;
        let parsed = self
            .parse_sources_from_content(trimmed, &content, now_ms())
            .await?;
        if parsed.is_empty() {
            return Err(SourceError::InvalidArgument(format!(
                "No valid sources found at {trimmed}"
            )));
        }
        for src in &parsed {
            self.storage.upsert_source(src)?;
        }
        Ok(parsed)
    }

    fn parse_sources_from_content<'a>(
        &'a self,
        url: &'a str,
        content: &'a str,
        added_at: i64,
    ) -> std::pin::Pin<
        Box<dyn std::future::Future<Output = Result<Vec<InstalledSource>, SourceError>> + Send + 'a>,
    > {
        Box::pin(async move {
            match detect_source_kind(url, content) {
                DetectedSourceKind::RepoIndex => {
                    let repo: RepoIndexDto = serde_json::from_str(content)?;
                    let mut installed = Vec::new();
                    let empty_headers = HashMap::new();
                    for entry in repo.sources {
                        let Some(child_url) = entry
                            .manifest_url
                            .or(entry.script_url)
                            .or(entry.url)
                        else {
                            continue;
                        };
                        let child_content = self.http.get_text(&child_url, &empty_headers).await?;
                        let child_sources = self
                            .parse_sources_from_content(&child_url, &child_content, added_at)
                            .await?;
                        installed.extend(child_sources);
                    }
                    Ok(installed)
                }
                DetectedSourceKind::JsonManifest => {
                    let config = JsonManifestProvider::parse_config(content)?;
                    let supported_types = parse_media_types(&config.supported_types);
                    let languages = if config.languages.is_empty() {
                        vec!["en".to_string()]
                    } else {
                        config.languages
                    };
                    Ok(vec![InstalledSource {
                        id: config.id,
                        name: config.name,
                        version: config.version,
                        base_url: config.base_url,
                        manifest_url: Some(url.to_string()),
                        driver_type: SourceDriverType::JSON_MANIFEST,
                        enabled: true,
                        is_built_in: false,
                        supported_types,
                        languages,
                        raw_config: Some(content.to_string()),
                        added_at,
                    }])
                }
                DetectedSourceKind::JsPlugin => {
                    let trimmed = content.trim();
                    let (script_url, script_content) = if trimmed.starts_with('{') {
                        let obj: Value = serde_json::from_str(trimmed)?;
                        let s_url = obj
                            .get("scriptUrl")
                            .or_else(|| obj.get("manifestUrl"))
                            .and_then(|v| v.as_str())
                            .ok_or_else(|| {
                                SourceError::InvalidArgument(
                                    "JS plugin manifest missing scriptUrl".to_string(),
                                )
                            })?
                            .to_string();
                        let s_content = self.http.get_text(&s_url, &HashMap::new()).await?;
                        (s_url, s_content)
                    } else {
                        (url.to_string(), content.to_string())
                    };
                    let manifest =
                        JsPluginProvider::extract_manifest(&script_content, &script_url);
                    Ok(vec![InstalledSource {
                        id: manifest.id,
                        name: manifest.name,
                        version: manifest.version,
                        base_url: manifest.base_url,
                        manifest_url: Some(script_url),
                        driver_type: SourceDriverType::JS_PLUGIN,
                        enabled: true,
                        is_built_in: false,
                        supported_types: manifest.supported_types,
                        languages: manifest.languages,
                        raw_config: Some(script_content),
                        added_at,
                    }])
                }
            }
        })
    }

    pub async fn set_source_enabled(&self, id: &str, enabled: bool) -> Result<(), SourceError> {
        self.storage.set_source_enabled(id, enabled)?;
        if !enabled {
            self.cache.write().await.remove(id);
        }
        Ok(())
    }

    pub async fn remove_source(&self, id: &str) -> Result<bool, SourceError> {
        let Some(existing) = self.storage.get_source_by_id(id)? else {
            return Ok(false);
        };
        if existing.is_built_in {
            return Ok(false);
        }
        self.storage.delete_source(id)?;
        self.cache.write().await.remove(id);
        Ok(true)
    }

    pub async fn test_source(&self, id: &str) -> SourceHealthResult {
        let Ok(provider) = self.get_provider(id).await else {
            return SourceHealthResult {
                ok: false,
                latency_ms: 0,
                item_count: 0,
                error: Some(format!("Source not found: {id}")),
            };
        };
        let start = Instant::now();
        match provider.get_home().await {
            Ok(sections) => {
                let count: usize = sections.iter().map(|s| s.items.len()).sum();
                SourceHealthResult {
                    ok: true,
                    latency_ms: start.elapsed().as_millis() as u64,
                    item_count: count,
                    error: None,
                }
            }
            Err(err) => SourceHealthResult {
                ok: false,
                latency_ms: start.elapsed().as_millis() as u64,
                item_count: 0,
                error: Some(err.to_string()),
            },
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RepoIndexDto {
    #[serde(default)]
    sources: Vec<RepoEntryDto>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RepoEntryDto {
    manifest_url: Option<String>,
    script_url: Option<String>,
    url: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
enum DetectedSourceKind {
    RepoIndex,
    JsonManifest,
    JsPlugin,
}

fn detect_source_kind(url: &str, content: &str) -> DetectedSourceKind {
    let trimmed = content.trim();
    if trimmed.starts_with('{') {
        if let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(trimmed) {
            if obj.contains_key("sources") {
                return DetectedSourceKind::RepoIndex;
            }
            let driver_type = obj
                .get("driverType")
                .and_then(|v| v.as_str())
                .map(|s| s.to_uppercase());
            if driver_type.as_deref() == Some("JS_PLUGIN") || obj.contains_key("scriptUrl") {
                return DetectedSourceKind::JsPlugin;
            }
            if driver_type.as_deref() == Some("JSON_MANIFEST")
                || obj.contains_key("selectors")
                || obj.contains_key("endpoints")
            {
                return DetectedSourceKind::JsonManifest;
            }
        }
    }

    let url_path = url.split('?').next().unwrap_or("");
    if url_path.to_ascii_lowercase().ends_with(".js")
        || trimmed.contains("function getHome")
        || trimmed.contains("var manifest")
    {
        return DetectedSourceKind::JsPlugin;
    }

    DetectedSourceKind::JsonManifest
}

fn parse_media_types(raw: &[String]) -> Vec<MediaType> {
    let parsed: Vec<MediaType> = raw
        .iter()
        .filter_map(|t| match t.to_uppercase().as_str() {
            "MOVIE" => Some(MediaType::MOVIE),
            "TV" => Some(MediaType::TV),
            "ANIME" => Some(MediaType::ANIME),
            "LIVE" => Some(MediaType::LIVE),
            _ => None,
        })
        .collect();
    if parsed.is_empty() {
        vec![MediaType::MOVIE, MediaType::TV]
    } else {
        parsed
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
