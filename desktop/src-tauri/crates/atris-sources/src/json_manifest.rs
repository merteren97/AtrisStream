use crate::http::HttpClient;
use crate::provider::{MediaProvider, SourceError};
use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    Season, StreamFormat, StreamResult, StreamSource, SubtitleFormat, SubtitleTrack,
};
use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JsonSourceManifestConfig {
    pub id: String,
    pub name: String,
    #[serde(default = "default_version")]
    pub version: String,
    pub base_url: String,
    #[serde(default = "default_driver_type")]
    pub driver_type: String,
    #[serde(default = "default_supported_types")]
    pub supported_types: Vec<String>,
    #[serde(default = "default_languages")]
    pub languages: Vec<String>,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub endpoints: HashMap<String, String>,
    #[serde(default)]
    pub selectors: HashMap<String, String>,
}

fn default_version() -> String {
    "1.0.0".to_string()
}
fn default_driver_type() -> String {
    "JSON_MANIFEST".to_string()
}
fn default_supported_types() -> Vec<String> {
    vec!["MOVIE".to_string(), "TV".to_string()]
}
fn default_languages() -> Vec<String> {
    vec!["en".to_string()]
}

type FetchTextFn = Arc<
    dyn Fn(String, HashMap<String, String>) -> Pin<Box<dyn Future<Output = Result<String, SourceError>> + Send>>
        + Send
        + Sync,
>;

use std::sync::Arc;

pub struct JsonManifestProvider {
    config: JsonSourceManifestConfig,
    manifest: ProviderManifest,
    fetch_text: FetchTextFn,
}

impl JsonManifestProvider {
    pub fn parse_config(raw_json: &str) -> Result<JsonSourceManifestConfig, SourceError> {
        Ok(serde_json::from_str(raw_json)?)
    }

    pub fn from_http(config: JsonSourceManifestConfig, http: HttpClient) -> Self {
        let fetch: FetchTextFn = Arc::new(move |url, headers| {
            let client = http.clone();
            Box::pin(async move { client.get_text(&url, &headers).await })
        });
        Self::with_fetcher(config, fetch)
    }

    pub fn with_fetcher(config: JsonSourceManifestConfig, fetch_text: FetchTextFn) -> Self {
        let supported_types: Vec<MediaType> = config
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
        let supported_types = if supported_types.is_empty() {
            vec![MediaType::MOVIE, MediaType::TV]
        } else {
            supported_types
        };
        let languages = if config.languages.is_empty() {
            vec!["en".to_string()]
        } else {
            config.languages.clone()
        };

        let manifest = ProviderManifest {
            id: config.id.clone(),
            name: config.name.clone(),
            version: config.version.clone(),
            base_url: config.base_url.clone(),
            supported_types,
            languages,
        };

        Self {
            config,
            manifest,
            fetch_text,
        }
    }

    fn resolve_url(&self, path_or_url: &str) -> String {
        let trimmed = path_or_url.trim();
        let base = self.config.base_url.trim_end_matches('/');
        if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
            trimmed.to_string()
        } else if trimmed.starts_with("//") {
            format!("https:{trimmed}")
        } else if trimmed.starts_with('/') {
            format!("{base}{trimmed}")
        } else {
            format!("{base}/{trimmed}")
        }
    }

    fn build_details_url(&self, id: &str) -> String {
        if id.starts_with("http://") || id.starts_with("https://") {
            return id.to_string();
        }
        let template = self
            .config
            .endpoints
            .get("details")
            .map(|s| s.as_str())
            .unwrap_or("/title/{id}");
        self.resolve_url(&template.replace("{id}", id))
    }

    fn parse_cards(&self, root: ElementRef<'_>, default_type: MediaType) -> Vec<MediaCard> {
        let Some(item_sel_str) = self.config.selectors.get("cardItem") else {
            return Vec::new();
        };
        let Ok(item_sel) = Selector::parse(item_sel_str) else {
            return Vec::new();
        };

        let title_sel = self.config.selectors.get("cardTitle").map(String::as_str);
        let poster_sel = self.config.selectors.get("cardPoster").map(String::as_str);
        let url_sel = self.config.selectors.get("cardUrl").map(String::as_str);
        let year_sel = self.config.selectors.get("cardYear").map(String::as_str);
        let rating_sel = self.config.selectors.get("cardRating").map(String::as_str);

        root.select(&item_sel)
            .filter_map(|card_el| {
                let title = title_sel
                    .and_then(|s| extract_value(card_el, s))
                    .or_else(|| {
                        let t = card_el.text().collect::<String>().trim().to_string();
                        if t.is_empty() {
                            None
                        } else {
                            Some(t)
                        }
                    })?;
                let raw_url = url_sel.and_then(|s| extract_value(card_el, s))?;
                let id = extract_id_from_url(&raw_url);
                let poster = poster_sel
                    .and_then(|s| extract_value(card_el, s))
                    .map(|p| self.resolve_url(&p));
                let year = year_sel
                    .and_then(|s| extract_value(card_el, s))
                    .and_then(|y| extract_year(&y));
                let rating = rating_sel
                    .and_then(|s| extract_value(card_el, s))
                    .and_then(|r| r.parse::<f64>().ok());

                Some(MediaCard {
                    id,
                    source_id: self.config.id.clone(),
                    title,
                    poster_url: poster,
                    backdrop_url: None,
                    r#type: default_type,
                    year,
                    rating,
                    subtitle: None,
                })
            })
            .collect()
    }

    fn parse_episodes(&self, root: ElementRef<'_>, season: i32) -> Vec<Episode> {
        let Some(ep_sel_str) = self.config.selectors.get("episodeItem") else {
            return Vec::new();
        };
        let Ok(ep_sel) = Selector::parse(ep_sel_str) else {
            return Vec::new();
        };
        let ep_title_sel = self.config.selectors.get("episodeTitle").map(String::as_str);
        let ep_url_sel = self.config.selectors.get("episodeUrl").map(String::as_str);

        root.select(&ep_sel)
            .enumerate()
            .map(|(idx, el)| {
                let title = ep_title_sel
                    .and_then(|s| extract_value(el, s))
                    .unwrap_or_else(|| {
                        let t = el.text().collect::<String>().trim().to_string();
                        if t.is_empty() {
                            format!("Episode {}", idx + 1)
                        } else {
                            t
                        }
                    });
                let id = ep_url_sel
                    .and_then(|s| extract_value(el, s))
                    .map(|u| extract_id_from_url(&u))
                    .unwrap_or_else(|| format!("ep_{}_{}", season, idx + 1));

                Episode {
                    id,
                    season_number: season,
                    episode_number: (idx + 1) as i32,
                    title,
                    overview: None,
                    thumbnail_url: None,
                    runtime_minutes: None,
                }
            })
            .collect()
    }
}

#[async_trait]
impl MediaProvider for JsonManifestProvider {
    fn manifest(&self) -> &ProviderManifest {
        &self.manifest
    }

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError> {
        let home_path = self
            .config
            .endpoints
            .get("home")
            .map(String::as_str)
            .unwrap_or("/");
        let url = self.resolve_url(home_path);
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);
        let default_type = self
            .manifest
            .supported_types
            .first()
            .copied()
            .unwrap_or(MediaType::MOVIE);

        let Some(section_sel_str) = self
            .config
            .selectors
            .get("homeSection")
            .filter(|s| !s.trim().is_empty())
        else {
            let cards = self.parse_cards(doc.root_element(), default_type);
            return Ok(if cards.is_empty() {
                Vec::new()
            } else {
                vec![HomeSection {
                    title: self.config.name.clone(),
                    items: cards,
                }]
            });
        };

        let Ok(section_sel) = Selector::parse(section_sel_str) else {
            return Ok(Vec::new());
        };
        let title_sel = self
            .config
            .selectors
            .get("homeSectionTitle")
            .map(String::as_str)
            .filter(|s| !s.trim().is_empty());

        let mut sections = Vec::new();
        for section_el in doc.select(&section_sel) {
            let title = title_sel
                .and_then(|s| extract_value(section_el, s))
                .unwrap_or_else(|| self.config.name.clone());
            let cards = self.parse_cards(section_el, default_type);
            if !cards.is_empty() {
                sections.push(HomeSection { title, items: cards });
            }
        }
        Ok(sections)
    }

    async fn browse(
        &self,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        let endpoint_key = match media_type {
            MediaType::MOVIE => "browseMovie",
            MediaType::TV => "browseTv",
            MediaType::ANIME => "browseAnime",
            MediaType::LIVE => "browseLive",
        };
        let template = self
            .config
            .endpoints
            .get(endpoint_key)
            .or_else(|| self.config.endpoints.get("browse"))
            .or_else(|| self.config.endpoints.get("home"))
            .map(String::as_str)
            .unwrap_or("/");
        let path = template.replace("{page}", &page.to_string());
        let url = self.resolve_url(&path);
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);

        let cards = self.parse_cards(doc.root_element(), media_type);
        let has_next_page = match self
            .config
            .selectors
            .get("nextPage")
            .filter(|s| !s.trim().is_empty())
        {
            Some(sel) => Selector::parse(sel.split('@').next().unwrap_or(sel))
                .map(|parsed| doc.select(&parsed).next().is_some())
                .unwrap_or(false),
            None => !cards.is_empty(),
        };

        Ok(PagedResult {
            items: cards,
            page,
            has_next_page,
        })
    }

    async fn search(&self, query: &str, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        let Some(template) = self.config.endpoints.get("search") else {
            return Ok(PagedResult {
                items: Vec::new(),
                page,
                has_next_page: false,
            });
        };
        let encoded = urlencoding::encode(query.trim());
        let path = template
            .replace("{query}", &encoded)
            .replace("{page}", &page.to_string());
        let url = self.resolve_url(&path);
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);
        let default_type = self
            .manifest
            .supported_types
            .first()
            .copied()
            .unwrap_or(MediaType::MOVIE);

        let cards = self.parse_cards(doc.root_element(), default_type);
        let has_next_page = self
            .config
            .selectors
            .get("nextPage")
            .filter(|s| !s.trim().is_empty())
            .and_then(|sel| {
                Selector::parse(sel.split('@').next().unwrap_or(sel))
                    .ok()
                    .map(|parsed| doc.select(&parsed).next().is_some())
            })
            .unwrap_or(false);

        Ok(PagedResult {
            items: cards,
            page,
            has_next_page,
        })
    }

    async fn get_details(
        &self,
        id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        let url = self.build_details_url(id);
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);
        let root = doc.root_element();

        let title = self
            .config
            .selectors
            .get("detailsTitle")
            .and_then(|s| extract_value(root, s))
            .unwrap_or_else(|| id.to_string());
        let description = self
            .config
            .selectors
            .get("detailsDescription")
            .and_then(|s| extract_value(root, s));
        let poster_url = self
            .config
            .selectors
            .get("detailsPoster")
            .and_then(|s| extract_value(root, s))
            .map(|p| self.resolve_url(&p));
        let backdrop_url = self
            .config
            .selectors
            .get("detailsBackdrop")
            .and_then(|s| extract_value(root, s))
            .map(|p| self.resolve_url(&p));
        let genres = self
            .config
            .selectors
            .get("detailsGenres")
            .and_then(|s| Selector::parse(s.split('@').next().unwrap_or(s)).ok())
            .map(|sel| {
                root.select(&sel)
                    .filter_map(|el| {
                        let t = el.text().collect::<String>().trim().to_string();
                        if t.is_empty() {
                            None
                        } else {
                            Some(t)
                        }
                    })
                    .collect()
            })
            .unwrap_or_default();
        let year = self
            .config
            .selectors
            .get("detailsYear")
            .and_then(|s| extract_value(root, s))
            .and_then(|y| extract_year(&y));
        let rating = self
            .config
            .selectors
            .get("detailsRating")
            .and_then(|s| extract_value(root, s))
            .and_then(|r| r.parse::<f64>().ok());

        let episodes = self.parse_episodes(root, 1);
        let seasons = if episodes.is_empty() {
            Vec::new()
        } else {
            vec![Season {
                season_number: 1,
                name: "Episodes".to_string(),
                episode_count: Some(episodes.len() as i32),
                poster_url: None,
                episodes,
            }]
        };

        Ok(MediaDetails {
            id: id.to_string(),
            source_id: self.config.id.clone(),
            title,
            r#type: media_type,
            description,
            poster_url,
            backdrop_url,
            year,
            rating,
            genres,
            cast: Vec::new(),
            runtime_minutes: None,
            seasons,
        })
    }

    async fn get_episodes(&self, id: &str, season: i32) -> Result<Vec<Episode>, SourceError> {
        let url = match self
            .config
            .endpoints
            .get("episodes")
            .filter(|s| !s.trim().is_empty())
        {
            Some(tpl) => {
                self.resolve_url(&tpl.replace("{id}", id).replace("{season}", &season.to_string()))
            }
            None => self.build_details_url(id),
        };
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);
        Ok(self.parse_episodes(doc.root_element(), season))
    }

    async fn get_streams(
        &self,
        media_id: &str,
        _media_type: MediaType,
        _season: Option<i32>,
        _episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let url = self.build_details_url(media_id);
        let html = (self.fetch_text)(url, self.config.headers.clone()).await?;
        let doc = Html::parse_document(&html);
        let root = doc.root_element();

        let mut streams = Vec::new();
        let mut seen_streams = HashSet::new();
        if let Some(video_sel_expr) = self
            .config
            .selectors
            .get("streamVideo")
            .filter(|s| !s.trim().is_empty())
        {
            if let Ok(sel) = Selector::parse(video_sel_expr.split('@').next().unwrap_or(video_sel_expr)) {
                for el in root.select(&sel) {
                    let Some(raw_url) = extract_from_element(el, video_sel_expr) else {
                        continue;
                    };
                    let resolved = self.resolve_url(&raw_url);
                    if !seen_streams.insert(resolved.clone()) {
                        continue;
                    }
                    let quality = el
                        .value()
                        .attr("label")
                        .or_else(|| el.value().attr("data-quality"))
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .unwrap_or("Auto")
                        .to_string();
                    streams.push(StreamSource {
                        format: infer_stream_format(&resolved),
                        url: resolved,
                        quality,
                        server_name: self.config.name.clone(),
                        audio_lang: None,
                        headers: self.config.headers.clone(),
                    });
                }
            }
        }

        let mut subtitles = Vec::new();
        let mut seen_subs = HashSet::new();
        if let Some(sub_sel_expr) = self
            .config
            .selectors
            .get("subtitleTrack")
            .filter(|s| !s.trim().is_empty())
        {
            if let Ok(sel) = Selector::parse(sub_sel_expr.split('@').next().unwrap_or(sub_sel_expr)) {
                for el in root.select(&sel) {
                    let Some(raw_sub) = extract_from_element(el, sub_sel_expr) else {
                        continue;
                    };
                    let resolved = self.resolve_url(&raw_sub);
                    if !seen_subs.insert(resolved.clone()) {
                        continue;
                    }
                    let lang = el
                        .value()
                        .attr("srclang")
                        .or_else(|| el.value().attr("lang"))
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .unwrap_or("en")
                        .to_string();
                    let label = el
                        .value()
                        .attr("label")
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(String::from)
                        .unwrap_or_else(|| lang.to_uppercase());
                    let lower = resolved.to_lowercase();
                    let format = if lower.contains(".srt") {
                        SubtitleFormat::SRT
                    } else if lower.contains(".ass") {
                        SubtitleFormat::ASS
                    } else {
                        SubtitleFormat::VTT
                    };
                    subtitles.push(SubtitleTrack {
                        url: resolved,
                        language: lang,
                        label,
                        format,
                        headers: self.config.headers.clone(),
                        is_external: false,
                    });
                }
            }
        }

        Ok(StreamResult { streams, subtitles })
    }
}

fn extract_value(root: ElementRef<'_>, selector_expr: &str) -> Option<String> {
    let css = selector_expr.split('@').next().unwrap_or("").trim();
    if css.is_empty() {
        return extract_from_element(root, selector_expr);
    }
    let sel = Selector::parse(css).ok()?;
    let el = root.select(&sel).next()?;
    extract_from_element(el, selector_expr)
}

fn extract_from_element(el: ElementRef<'_>, selector_expr: &str) -> Option<String> {
    let attr_part = selector_expr
        .split_once('@')
        .map(|(_, a)| a.trim())
        .unwrap_or("");
    if attr_part.is_empty() {
        let txt = el.text().collect::<String>().trim().to_string();
        return if txt.is_empty() { None } else { Some(txt) };
    }
    for attr in attr_part.split('|') {
        let trimmed = attr.trim();
        let val = if trimmed == "text" {
            el.text().collect::<String>().trim().to_string()
        } else {
            el.value().attr(trimmed).unwrap_or("").trim().to_string()
        };
        if !val.is_empty() {
            return Some(val);
        }
    }
    None
}

fn extract_id_from_url(raw_url: &str) -> String {
    let clean = raw_url.trim().trim_end_matches('/');
    clean
        .rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or(clean)
        .to_string()
}

fn extract_year(text: &str) -> Option<i32> {
    Regex::new(r"\d{4}")
        .ok()?
        .find(text)?
        .as_str()
        .parse::<i32>()
        .ok()
}

fn infer_stream_format(url: &str) -> StreamFormat {
    let lower = url.to_lowercase();
    if lower.contains(".m3u8") {
        StreamFormat::HLS
    } else if lower.contains(".mpd") {
        StreamFormat::DASH
    } else if lower.contains(".mp4") {
        StreamFormat::MP4
    } else {
        StreamFormat::UNKNOWN
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_JSON: &str =
        include_str!("../../../../../docs/source-templates/sample-json-source.json");

    #[tokio::test]
    async fn parses_sample_json_manifest_and_extracts_home_and_streams() {
        let config = JsonManifestProvider::parse_config(SAMPLE_JSON).unwrap();
        let home_html = r#"
            <html><body>
              <section class="media-row">
                <h2 class="row-title">Featured</h2>
                <article class="media-card">
                  <h3 class="title">Dune</h3>
                  <img class="poster" src="/fallback.jpg" data-src="/dune.jpg" />
                  <a class="card-link" href="/title/dune-2021"></a>
                  <span class="year">2021</span>
                  <span class="rating">8.1</span>
                </article>
                <article class="media-card">
                  <h3 class="title">Fallback Poster</h3>
                  <img class="poster" src="https://cdn.example.com/fb.jpg" />
                  <a class="card-link" href="/title/fb-1"></a>
                </article>
              </section>
            </body></html>
        "#;
        let details_html = r#"
            <html><body>
              <h1 class="media-title">Dune</h1>
              <p class="synopsis">Arrakis story.</p>
              <div class="hero" data-bg="/bg.jpg"></div>
              <video><source src="/video/master.m3u8" label="1080p" /></video>
              <track kind="subtitles" src="/subs/tr.vtt" srclang="tr" label="Turkish" />
            </body></html>
        "#;

        let fetcher: FetchTextFn = Arc::new(move |url, _headers| {
            let h = home_html.to_string();
            let d = details_html.to_string();
            Box::pin(async move {
                if url.ends_with("/title/dune-2021") {
                    Ok(d)
                } else {
                    Ok(h)
                }
            })
        });

        let provider = JsonManifestProvider::with_fetcher(config, fetcher);
        let sections = provider.get_home().await.unwrap();
        assert_eq!(sections.len(), 1);
        assert_eq!(sections[0].title, "Featured");
        assert_eq!(sections[0].items.len(), 2);
        assert_eq!(sections[0].items[0].id, "dune-2021");
        assert_eq!(
            sections[0].items[0].poster_url.as_deref(),
            Some("https://example.com/dune.jpg")
        );
        assert_eq!(
            sections[0].items[1].poster_url.as_deref(),
            Some("https://cdn.example.com/fb.jpg")
        );
        assert_eq!(sections[0].items[0].year, Some(2021));
        assert_eq!(sections[0].items[0].rating, Some(8.1));

        let details = provider
            .get_details("dune-2021", MediaType::MOVIE)
            .await
            .unwrap();
        assert_eq!(details.title, "Dune");
        assert_eq!(details.description.as_deref(), Some("Arrakis story."));
        assert_eq!(
            details.backdrop_url.as_deref(),
            Some("https://example.com/bg.jpg")
        );

        let streams = provider
            .get_streams("dune-2021", MediaType::MOVIE, None, None)
            .await
            .unwrap();
        assert_eq!(streams.streams.len(), 1);
        assert_eq!(
            streams.streams[0].url,
            "https://example.com/video/master.m3u8"
        );
        assert_eq!(streams.streams[0].format, StreamFormat::HLS);
        assert_eq!(streams.subtitles.len(), 1);
        assert_eq!(streams.subtitles[0].language, "tr");
    }
}
