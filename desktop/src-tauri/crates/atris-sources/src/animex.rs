use crate::flixcloud::{self, decode_sveltekit_merged, decode_sveltekit_ndjson};
use crate::http::HttpClient;
use crate::provider::{MediaProvider, SourceError};
use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    Season, StreamResult,
};
use serde_json::Value;
use std::collections::HashMap;

pub const SOURCE_ID: &str = "builtin_animex";
pub const SITE_URL: &str = "https://animex.one";

pub struct AnimeXProvider {
    http: HttpClient,
    manifest: ProviderManifest,
}

impl AnimeXProvider {
    pub fn new(http: HttpClient) -> Self {
        Self {
            http,
            manifest: ProviderManifest {
                id: SOURCE_ID.to_string(),
                name: "AnimeX (Anime)".to_string(),
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
impl MediaProvider for AnimeXProvider {
    fn manifest(&self) -> &ProviderManifest {
        &self.manifest
    }

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError> {
        let url = format!("{SITE_URL}/home/__data.json");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;

        let mut sections = Vec::new();
        for (key, title) in [
            ("trending", "Trending"),
            ("popular", "Popular"),
            ("recent", "Recent Episodes"),
            ("latest", "Latest"),
            ("airing", "Airing"),
        ] {
            if let Some(arr) = merged.get(key).and_then(|v| v.as_array()) {
                let items: Vec<MediaCard> = arr.iter().filter_map(parse_animex_card).collect();
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
        let url = format!("{SITE_URL}/browse/__data.json?page={page}");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;
        let items = extract_animex_cards(&merged);
        let has_next_page = merged
            .get("hasNextPage")
            .and_then(|v| v.as_bool())
            .unwrap_or(!items.is_empty());
        Ok(PagedResult {
            items,
            page,
            has_next_page,
        })
    }

    async fn search(&self, query: &str, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        let encoded = urlencoding::encode(query.trim());
        let url = format!("{SITE_URL}/search/__data.json?q={encoded}&page={page}");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;
        let items = extract_animex_cards(&merged);
        let has_next_page = merged
            .get("hasNextPage")
            .and_then(|v| v.as_bool())
            .unwrap_or(!items.is_empty());
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
        let url = format!("{SITE_URL}/anime/{id}/__data.json");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;
        let info = merged.get("anime").unwrap_or(&merged);

        let title = str_field(info, &["title", "titleEnglish", "titleRomaji", "name"])
            .unwrap_or_else(|| id.to_string());
        let description = str_field(info, &["description", "synopsis", "overview"]);
        let poster_url = extract_image_url(info, &["poster", "coverImage", "image"]);
        let backdrop_url = extract_image_url(info, &["bannerImage", "banner", "backdrop"]);
        let year = int_field(info, &["year", "seasonYear"]);
        let rating = extract_rating(info);
        let genres = info
            .get("genres")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|g| g.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();

        let episodes = parse_animex_episodes(&merged, id);
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
            runtime_minutes: int_field(info, &["duration"]),
            seasons,
        })
    }

    async fn get_episodes(&self, id: &str, _season: i32) -> Result<Vec<Episode>, SourceError> {
        let url = format!("{SITE_URL}/anime/{id}/__data.json");
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_merged(&raw)?;
        Ok(parse_animex_episodes(&merged, id))
    }

    async fn get_streams(
        &self,
        media_id: &str,
        _media_type: MediaType,
        _season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let ep_num = episode.unwrap_or(1);
        let url = format!(
            "{SITE_URL}/watch/{media_id}-episode-{ep_num}/__data.json?x-sveltekit-invalidated=01"
        );
        let raw = self.http.get_text(&url, &Self::headers()).await?;
        let merged = decode_sveltekit_ndjson(&raw)?;
        let embed_url = extract_animex_player_url(&merged)
            .ok_or_else(|| SourceError::NotFound("Missing AnimeX FlixCloud embed URL".to_string()))?;

        flixcloud::extract_streams(&self.http, &embed_url, "AnimeX").await
    }
}

pub fn extract_animex_player_url(merged: &Value) -> Option<String> {
    merged
        .pointer("/zen/data/0/player_url")
        .or_else(|| merged.pointer("/data/0/player_url"))
        .or_else(|| merged.pointer("/zen/player_url"))
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from)
        .or_else(|| str_field(merged, &["player_url", "embedUrl", "flixcloudUrl", "url", "streamUrl"]))
        .or_else(|| {
            merged
                .get("sources")
                .or_else(|| merged.get("servers"))
                .and_then(|v| v.as_array())
                .and_then(|arr| arr.first())
                .and_then(|item| str_field(item, &["player_url", "url", "embedUrl", "src", "id"]))
        })
}

pub fn parse_animex_episodes(merged: &Value, media_id: &str) -> Vec<Episode> {
    let info = merged.get("anime").unwrap_or(merged);
    let next_airing_ep = info
        .get("nextAiringEpisode")
        .or_else(|| merged.get("nextAiringEpisode"))
        .and_then(|v| v.get("episode"))
        .and_then(|v| v.as_i64())
        .map(|n| n as i32);

    let eps_val = merged.get("episodes").or_else(|| info.get("episodes"));
    if let Some(arr) = eps_val.and_then(|v| v.as_array()) {
        return arr
            .iter()
            .enumerate()
            .map(|(idx, el)| {
                let ep_num = int_field(el, &["number", "episode", "episodeNumber", "ep"])
                    .unwrap_or((idx + 1) as i32);
                let title = str_field(el, &["title", "name"])
                    .unwrap_or_else(|| format!("Episode {ep_num}"));
                let is_released = el
                    .get("isReleased")
                    .or_else(|| el.get("playable"))
                    .and_then(|v| v.as_bool())
                    .unwrap_or_else(|| match next_airing_ep {
                        Some(next_ep) => ep_num < next_ep,
                        None => true,
                    });
                Episode {
                    id: str_field(el, &["id", "slug"])
                        .unwrap_or_else(|| format!("{media_id}_e{ep_num}")),
                    season_number: 1,
                    episode_number: ep_num,
                    title,
                    overview: str_field(el, &["overview", "description"]),
                    thumbnail_url: str_field(el, &["thumbnail", "image", "still"]),
                    runtime_minutes: int_field(el, &["duration", "runtime"]),
                    is_released,
                }
            })
            .collect();
    }

    let count = eps_val
        .and_then(|v| v.as_i64())
        .or_else(|| info.get("totalEpisodes").and_then(|v| v.as_i64()))
        .or_else(|| merged.get("totalEpisodes").and_then(|v| v.as_i64()))
        .unwrap_or(0)
        .max(0) as i32;

    (1..=count)
        .map(|ep_num| {
            let is_released = match next_airing_ep {
                Some(next_ep) => ep_num < next_ep,
                None => true,
            };
            Episode {
                id: format!("{media_id}_e{ep_num}"),
                season_number: 1,
                episode_number: ep_num,
                title: format!("Episode {ep_num}"),
                overview: None,
                thumbnail_url: None,
                runtime_minutes: None,
                is_released,
            }
        })
        .collect()
}

fn extract_animex_cards(merged: &Value) -> Vec<MediaCard> {
    merged
        .get("results")
        .or_else(|| merged.get("items"))
        .or_else(|| merged.get("anime"))
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(parse_animex_card).collect())
        .unwrap_or_default()
}

fn parse_animex_card(val: &Value) -> Option<MediaCard> {
    let id = str_field(val, &["slug", "id"])?;
    let title = str_field(val, &["title", "titleEnglish", "titleRomaji", "name"])?;
    Some(MediaCard {
        id,
        source_id: SOURCE_ID.to_string(),
        title,
        poster_url: extract_image_url(val, &["poster", "coverImage", "image"]),
        backdrop_url: extract_image_url(val, &["bannerImage", "banner", "backdrop"]),
        r#type: MediaType::ANIME,
        year: int_field(val, &["year", "seasonYear"]),
        rating: extract_rating(val),
        subtitle: str_field(val, &["episodeLabel", "latestEpisode"]),
    })
}

fn extract_image_url(val: &Value, keys: &[&str]) -> Option<String> {
    for k in keys {
        if let Some(field) = val.get(*k) {
            if let Some(s) = field.as_str() {
                let t = s.trim();
                if !t.is_empty() {
                    return Some(t.to_string());
                }
            } else if let Some(obj) = field.as_object() {
                for sub_k in ["extraLarge", "large", "medium", "url"] {
                    if let Some(s) = obj.get(sub_k).and_then(|v| v.as_str()) {
                        let t = s.trim();
                        if !t.is_empty() {
                            return Some(t.to_string());
                        }
                    }
                }
            }
        }
    }
    None
}

fn extract_rating(val: &Value) -> Option<f64> {
    if let Some(avg) = val.get("averageScore").and_then(|v| v.as_f64()) {
        return Some(if avg > 10.0 { avg / 10.0 } else { avg });
    }
    float_field(val, &["rating", "score"])
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
    fn parses_animex_episodes_with_next_airing_episode() {
        let merged = serde_json::json!({
            "anime": {
                "nextAiringEpisode": { "episode": 3 }
            },
            "episodes": [
                {"id": "e1", "number": 1, "title": "One"},
                {"id": "e2", "number": 2, "title": "Two"},
                {"id": "e3", "number": 3, "title": "Three"}
            ]
        });
        let eps = parse_animex_episodes(&merged, "frieren");
        assert_eq!(eps.len(), 3);
        assert!(eps[0].is_released);
        assert!(eps[1].is_released);
        assert!(!eps[2].is_released);
    }

    #[test]
    fn parses_animex_total_episodes_and_zen_player_url() {
        let merged = serde_json::json!({
            "anime": {
                "totalEpisodes": 4,
                "nextAiringEpisode": { "episode": 4 },
                "coverImage": { "extraLarge": "https://cdn.example/xl.jpg" },
                "averageScore": 86
            },
            "zen": {
                "data": [{ "player_url": "https://flixcloud.cc/e/xyz?v=2" }]
            }
        });
        let eps = parse_animex_episodes(&merged, "dandadan");
        assert_eq!(eps.len(), 4);
        assert!(eps[2].is_released);
        assert!(!eps[3].is_released);
        assert_eq!(
            extract_animex_player_url(&merged).as_deref(),
            Some("https://flixcloud.cc/e/xyz?v=2")
        );
        assert_eq!(
            extract_image_url(&merged["anime"], &["coverImage"]).as_deref(),
            Some("https://cdn.example/xl.jpg")
        );
        assert_eq!(extract_rating(&merged["anime"]), Some(8.6));
    }
}
