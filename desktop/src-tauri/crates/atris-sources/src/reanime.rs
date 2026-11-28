use crate::flixcloud::{self, decode_sveltekit_merged};
use crate::http::HttpClient;
use crate::provider::{MediaProvider, SourceError};
use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    Season, StreamResult,
};
use serde_json::Value;
use std::collections::HashMap;

pub const SOURCE_ID: &str = "builtin_reanime";
pub const SITE_URL: &str = "https://reanime.cz";

pub struct ReAnimeProvider {
    http: HttpClient,
    manifest: ProviderManifest,
}

impl ReAnimeProvider {
    pub fn new(http: HttpClient) -> Self {
        Self {
            http,
            manifest: ProviderManifest {
                id: SOURCE_ID.to_string(),
                name: "ReAnime (Anime)".to_string(),
                version: "1.0.0".to_string(),
                base_url: SITE_URL.to_string(),
                supported_types: vec![MediaType::ANIME],
                languages: vec!["en".to_string(), "ja".to_string()],
            },
        }
    }

    fn headers() -> HashMap<String, String> {
        let mut h = HashMap::new();
        h.insert("Referer".to_string(), format!("{SITE_URL}/"));
        h.insert("Origin".to_string(), SITE_URL.to_string());
        h
    }
}

#[async_trait]
impl MediaProvider for ReAnimeProvider {
    fn manifest(&self) -> &ProviderManifest {
        &self.manifest
    }

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError> {
        let url = format!("{SITE_URL}/api/v1/home");
        let body = self.http.get_text(&url, &Self::headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let data = root.get("data").unwrap_or(&root);

        let mut sections = Vec::new();
        for (key, title) in [
            ("latest_aired", "Latest Aired"),
            ("new_on_site", "New on Site"),
            ("trending", "Trending"),
        ] {
            if let Some(arr) = data.get(key).and_then(|v| v.as_array()) {
                let items: Vec<MediaCard> = arr.iter().filter_map(parse_anime_card).collect();
                if !items.is_empty() {
                    sections.push(HomeSection {
                        title: title.to_string(),
                        items,
                    });
                }
            }
        }
        Ok(sections)
    }

    async fn browse(
        &self,
        _media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        let offset = page.saturating_sub(1) * 24;
        let url = format!("{SITE_URL}/api/v1/search?limit=24&offset={offset}");
        let body = self.http.get_text(&url, &Self::headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let arr = extract_items_array(&root);
        let items: Vec<MediaCard> = arr.iter().filter_map(parse_anime_card).collect();
        let has_next_page = items.len() >= 24;
        Ok(PagedResult {
            items,
            page,
            has_next_page,
        })
    }

    async fn search(&self, query: &str, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        let offset = page.saturating_sub(1) * 24;
        let encoded = urlencoding::encode(query.trim());
        let url = format!("{SITE_URL}/api/v1/search?q={encoded}&limit=24&offset={offset}");
        let body = self.http.get_text(&url, &Self::headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let arr = extract_items_array(&root);
        let items: Vec<MediaCard> = arr.iter().filter_map(parse_anime_card).collect();
        let has_next_page = items.len() >= 24;
        Ok(PagedResult {
            items,
            page,
            has_next_page,
        })
    }

    async fn get_details(
        &self,
        id: &str,
        _media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        let anime_url = format!("{SITE_URL}/api/v1/anime/{id}");
        let headers = Self::headers();
        let (anime_res, episodes_res) = tokio::join!(
            self.http.get_text(&anime_url, &headers),
            self.get_episodes(id, 1)
        );
        let body = anime_res?;
        let root: Value = serde_json::from_str(&body)?;
        let info = root.get("data").or_else(|| root.get("anime")).unwrap_or(&root);
        let episodes = episodes_res.unwrap_or_default();

        let title = str_field(info, &["title", "titleEnglish", "titleRomaji", "name"])
            .unwrap_or_else(|| id.to_string());
        let description = str_field(info, &["synopsis", "description", "overview"]);
        let poster_url = str_field(info, &["poster", "posterUrl", "coverImage", "image"]);
        let backdrop_url = str_field(info, &["banner", "bannerImage", "backdropUrl"]);
        let year = int_field(info, &["year", "seasonYear"]);
        let rating = float_field(info, &["rating", "score", "malScore", "averageScore"]);
        let genres = info
            .get("genres")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|g| g.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();

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
            source_id: SOURCE_ID.to_string(),
            title,
            r#type: MediaType::ANIME,
            description,
            poster_url,
            backdrop_url,
            year,
            rating,
            genres,
            cast: Vec::new(),
            runtime_minutes: int_field(info, &["duration", "episodeDuration"]),
            seasons,
        })
    }

    async fn get_episodes(&self, id: &str, _season: i32) -> Result<Vec<Episode>, SourceError> {
        let url = format!("{SITE_URL}/watch/{id}/__data.json?ep=1");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;
        Ok(parse_reanime_episodes(&merged, id))
    }

    async fn get_streams(
        &self,
        media_id: &str,
        _media_type: MediaType,
        _season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let ep_num = episode.unwrap_or(1);
        let anime_url = format!("{SITE_URL}/api/v1/anime/{media_id}");
        let body = self.http.get_text(&anime_url, &Self::headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let info = root.get("data").or_else(|| root.get("anime")).unwrap_or(&root);

        let anilist_id = str_field(info, &["anilistId", "anilist_id", "id"])
            .or_else(|| int_field(info, &["anilistId", "anilist_id"]).map(|n| n.to_string()))
            .unwrap_or_else(|| media_id.to_string());
        let mal_id = str_field(info, &["malId", "mal_id"])
            .or_else(|| int_field(info, &["malId", "mal_id"]).map(|n| n.to_string()))
            .unwrap_or_default();

        let flix_api = if mal_id.is_empty() {
            format!("{SITE_URL}/api/flix/{anilist_id}/{ep_num}")
        } else {
            format!("{SITE_URL}/api/flix/{anilist_id}/{ep_num}?mal={mal_id}")
        };
        let flix_raw = self.http.get_text(&flix_api, &Self::headers()).await?;
        let flix_json: Value = serde_json::from_str(&flix_raw)?;
        let embed_url = str_field(&flix_json, &["url", "embedUrl", "embed", "id"])
            .or_else(|| {
                flix_json
                    .get("data")
                    .and_then(|d| str_field(d, &["url", "embedUrl", "embed", "id"]))
            })
            .ok_or_else(|| SourceError::NotFound("Missing FlixCloud embed URL".to_string()))?;

        flixcloud::extract_streams(&self.http, &embed_url, "ReAnime").await
    }
}

pub fn parse_reanime_episodes(merged: &Value, media_id: &str) -> Vec<Episode> {
    let eps_val = merged
        .get("episodes")
        .or_else(|| merged.pointer("/anime/episodes"))
        .or_else(|| merged.pointer("/data/episodes"));
    let Some(arr) = eps_val.and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    arr.iter()
        .enumerate()
        .map(|(idx, el)| {
            let ep_num = int_field(el, &["number", "episode", "episodeNumber", "ep"])
                .unwrap_or((idx + 1) as i32);
            let title = str_field(el, &["title", "name"])
                .unwrap_or_else(|| format!("Episode {ep_num}"));
            let is_released = el
                .get("playable")
                .and_then(|v| v.as_bool())
                .unwrap_or(true);
            Episode {
                id: str_field(el, &["id", "slug"]).unwrap_or_else(|| format!("{media_id}_e{ep_num}")),
                season_number: 1,
                episode_number: ep_num,
                title,
                overview: str_field(el, &["overview", "description"]),
                thumbnail_url: str_field(el, &["thumbnail", "image", "still"]),
                runtime_minutes: int_field(el, &["duration", "runtime"]),
                is_released,
            }
        })
        .collect()
}

fn extract_items_array(root: &Value) -> Vec<Value> {
    root.get("items")
        .or_else(|| root.get("results"))
        .or_else(|| root.get("data"))
        .or_else(|| root.pointer("/data/items"))
        .or_else(|| root.pointer("/data/results"))
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default()
}

fn parse_anime_card(val: &Value) -> Option<MediaCard> {
    let id = str_field(val, &["slug", "id"])?;
    let title = str_field(val, &["title", "titleEnglish", "titleRomaji", "name"])?;
    Some(MediaCard {
        id,
        source_id: SOURCE_ID.to_string(),
        title,
        poster_url: str_field(val, &["poster", "posterUrl", "coverImage", "image"]),
        backdrop_url: str_field(val, &["banner", "bannerImage", "backdropUrl"]),
        r#type: MediaType::ANIME,
        year: int_field(val, &["year", "seasonYear"]),
        rating: float_field(val, &["rating", "score", "malScore"]),
        subtitle: str_field(val, &["episodeLabel", "latestEpisode"]),
    })
}

fn str_field(val: &Value, keys: &[&str]) -> Option<String> {
    for k in keys {
        if let Some(s) = val.get(*k).and_then(|v| v.as_str()) {
            let t = s.trim();
            if !t.is_empty() {
                return Some(t.to_string());
            }
        }
    }
    None
}

fn int_field(val: &Value, keys: &[&str]) -> Option<i32> {
    for k in keys {
        if let Some(n) = val.get(*k).and_then(|v| v.as_i64()) {
            return Some(n as i32);
        }
    }
    None
}

fn float_field(val: &Value, keys: &[&str]) -> Option<f64> {
    for k in keys {
        if let Some(n) = val.get(*k).and_then(|v| v.as_f64()) {
            return Some(n);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_reanime_episodes_with_playable_flag() {
        let merged = serde_json::json!({
            "episodes": [
                {"id": "ep1", "number": 1, "title": "Ep 1", "playable": true},
                {"id": "ep2", "number": 2, "title": "Ep 2", "playable": false}
            ]
        });
        let eps = parse_reanime_episodes(&merged, "solo-leveling");
        assert_eq!(eps.len(), 2);
        assert!(eps[0].is_released);
        assert!(!eps[1].is_released);
    }
}
