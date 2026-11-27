use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum MediaType {
    MOVIE,
    TV,
    ANIME,
    LIVE,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash, Default)]
pub enum StreamFormat {
    HLS,
    DASH,
    MP4,
    #[default]
    UNKNOWN,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum SubtitleFormat {
    VTT,
    SRT,
    ASS,
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum SourceDriverType {
    BUILTIN,
    JSON_MANIFEST,
    JS_PLUGIN,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash, Default)]
#[serde(rename_all = "lowercase")]
pub enum PlayerEngine {
    #[default]
    Libmpv,
    Webview,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaCard {
    pub id: String,
    pub source_id: String,
    pub title: String,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub backdrop_url: Option<String>,
    pub r#type: MediaType,
    #[serde(default)]
    pub year: Option<i32>,
    #[serde(default)]
    pub rating: Option<f64>,
    #[serde(default)]
    pub subtitle: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HomeSection {
    pub title: String,
    #[serde(default)]
    pub items: Vec<MediaCard>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", bound(deserialize = "T: Deserialize<'de>"))]
pub struct PagedResult<T> {
    #[serde(default = "Vec::new")]
    pub items: Vec<T>,
    pub page: u32,
    #[serde(default)]
    pub has_next_page: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Episode {
    pub id: String,
    pub season_number: i32,
    pub episode_number: i32,
    pub title: String,
    #[serde(default)]
    pub overview: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub runtime_minutes: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Season {
    pub season_number: i32,
    pub name: String,
    #[serde(default)]
    pub episode_count: Option<i32>,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub episodes: Vec<Episode>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MediaDetails {
    pub id: String,
    pub source_id: String,
    pub title: String,
    pub r#type: MediaType,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub backdrop_url: Option<String>,
    #[serde(default)]
    pub year: Option<i32>,
    #[serde(default)]
    pub rating: Option<f64>,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub cast: Vec<String>,
    #[serde(default)]
    pub runtime_minutes: Option<i32>,
    #[serde(default)]
    pub seasons: Vec<Season>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct StreamSource {
    pub url: String,
    pub quality: String,
    pub format: StreamFormat,
    pub server_name: String,
    #[serde(default)]
    pub audio_lang: Option<String>,
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SubtitleTrack {
    pub url: String,
    pub language: String,
    pub label: String,
    pub format: SubtitleFormat,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub is_external: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct StreamResult {
    #[serde(default)]
    pub streams: Vec<StreamSource>,
    #[serde(default)]
    pub subtitles: Vec<SubtitleTrack>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProviderManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub base_url: String,
    #[serde(default)]
    pub supported_types: Vec<MediaType>,
    #[serde(default)]
    pub languages: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct InstalledSource {
    pub id: String,
    pub name: String,
    pub version: String,
    pub base_url: String,
    #[serde(default)]
    pub manifest_url: Option<String>,
    pub driver_type: SourceDriverType,
    pub enabled: bool,
    pub is_built_in: bool,
    #[serde(default)]
    pub supported_types: Vec<MediaType>,
    #[serde(default)]
    pub languages: Vec<String>,
    #[serde(default)]
    pub raw_config: Option<String>,
    pub added_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SourceHealthResult {
    pub ok: bool,
    pub latency_ms: u64,
    pub item_count: usize,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SourceSearchResult {
    pub source_id: String,
    pub source_name: String,
    pub result: PagedResult<MediaCard>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WatchProgress {
    pub media_id: String,
    pub source_id: String,
    pub r#type: MediaType,
    pub title: String,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub season_number: Option<i32>,
    #[serde(default)]
    pub episode_number: Option<i32>,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub is_completed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WatchlistItem {
    pub media_id: String,
    pub source_id: String,
    pub r#type: MediaType,
    pub title: String,
    #[serde(default)]
    pub poster_url: Option<String>,
    #[serde(default)]
    pub year: Option<i32>,
    #[serde(default)]
    pub rating: Option<f64>,
    pub added_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SubtitleAppearance {
    pub font_size_sp: f32,
    pub text_color_argb: i64,
    pub background_color_argb: i64,
    pub edge_type: i32,
}

impl Default for SubtitleAppearance {
    fn default() -> Self {
        Self {
            font_size_sp: 20.0,
            text_color_argb: 0xFFFFFFFF,
            background_color_argb: 0x80000000,
            edge_type: 1,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AppPreferences {
    pub player_engine: PlayerEngine,
    pub hardware_decoding: bool,
    pub preferred_quality: String,
    pub preferred_subtitle_lang: String,
    pub language: String,
    pub subtitle_appearance: SubtitleAppearance,
}

impl Default for AppPreferences {
    fn default() -> Self {
        Self {
            player_engine: PlayerEngine::Libmpv,
            hardware_decoding: true,
            preferred_quality: "Auto".to_string(),
            preferred_subtitle_lang: "tr".to_string(),
            language: "tr".to_string(),
            subtitle_appearance: SubtitleAppearance::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredDevice {
    pub name: String,
    pub host: String,
    pub port: u16,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub token: String,
    #[serde(default)]
    pub last_synced_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SyncPayload {
    #[serde(default)]
    pub sources: Vec<InstalledSource>,
    #[serde(default)]
    pub watch_progress: Vec<WatchProgress>,
    #[serde(default)]
    pub watchlist: Vec<WatchlistItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct SyncSummary {
    pub pushed_sources: usize,
    pub pulled_sources: usize,
    pub merged_progress: usize,
    pub merged_watchlist: usize,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn media_type_serializes_uppercase() {
        assert_eq!(serde_json::to_string(&MediaType::MOVIE).unwrap(), "\"MOVIE\"");
        assert_eq!(serde_json::to_string(&MediaType::TV).unwrap(), "\"TV\"");
        assert_eq!(serde_json::to_string(&MediaType::ANIME).unwrap(), "\"ANIME\"");
        assert_eq!(serde_json::to_string(&MediaType::LIVE).unwrap(), "\"LIVE\"");
    }

    #[test]
    fn player_engine_serializes_lowercase() {
        assert_eq!(serde_json::to_string(&PlayerEngine::Libmpv).unwrap(), "\"libmpv\"");
        assert_eq!(serde_json::to_string(&PlayerEngine::Webview).unwrap(), "\"webview\"");
        assert_eq!(PlayerEngine::default(), PlayerEngine::Libmpv);
    }

    #[test]
    fn driver_and_formats_serialize_uppercase() {
        assert_eq!(
            serde_json::to_string(&SourceDriverType::JSON_MANIFEST).unwrap(),
            "\"JSON_MANIFEST\""
        );
        assert_eq!(
            serde_json::to_string(&SourceDriverType::JS_PLUGIN).unwrap(),
            "\"JS_PLUGIN\""
        );
        assert_eq!(serde_json::to_string(&StreamFormat::HLS).unwrap(), "\"HLS\"");
        assert_eq!(serde_json::to_string(&SubtitleFormat::VTT).unwrap(), "\"VTT\"");
    }

    #[test]
    fn watch_progress_round_trip() {
        let wp = WatchProgress {
            media_id: "550".into(),
            source_id: "builtin_movy_sx".into(),
            r#type: MediaType::MOVIE,
            title: "Fight Club".into(),
            poster_url: Some("https://example.com/p.jpg".into()),
            season_number: None,
            episode_number: None,
            position_ms: 42_000,
            duration_ms: 120_000,
            updated_at: 1_700_000_000_000,
            is_completed: false,
        };
        let json = serde_json::to_string(&wp).unwrap();
        assert!(json.contains("\"mediaId\":\"550\""));
        assert!(json.contains("\"type\":\"MOVIE\""));
        let decoded: WatchProgress = serde_json::from_str(&json).unwrap();
        assert_eq!(wp, decoded);
    }

    #[test]
    fn app_preferences_defaults_and_round_trip() {
        let prefs = AppPreferences::default();
        assert_eq!(prefs.player_engine, PlayerEngine::Libmpv);
        assert_eq!(prefs.subtitle_appearance.font_size_sp, 20.0);
        assert_eq!(prefs.subtitle_appearance.text_color_argb, 0xFFFFFFFF);
        assert_eq!(prefs.subtitle_appearance.background_color_argb, 0x80000000);
        assert_eq!(prefs.subtitle_appearance.edge_type, 1);

        let json = serde_json::to_string(&prefs).unwrap();
        let decoded: AppPreferences = serde_json::from_str(&json).unwrap();
        assert_eq!(prefs, decoded);
    }
}
