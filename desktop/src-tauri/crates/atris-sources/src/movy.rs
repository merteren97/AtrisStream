use crate::cipher::MovyCipher;
use crate::http::{HttpClient, DEFAULT_USER_AGENT};
use crate::provider::{MediaProvider, SourceError};
use async_trait::async_trait;
use atris_core::{
    Episode, HomeSection, MediaCard, MediaDetails, MediaType, PagedResult, ProviderManifest,
    Season, StreamFormat, StreamResult, StreamSource, SubtitleFormat, SubtitleTrack,
};
use regex::Regex;
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::{Mutex, RwLock, Semaphore};
use tokio::task::JoinSet;

pub const SOURCE_ID: &str = "builtin_movy_sx";
pub const SITE_ORIGIN: &str = "https://www.movy.sx";
pub const SITE_URL: &str = "https://www.movy.sx/tr";
pub const TMDB_PROXY: &str = "https://db.wecollege.net/3";
pub const STREAM_API: &str = "https://api.wecollege.net";
pub const ANIME_API: &str = "https://anime.vidy.st/api";
pub const SUBTITLES_API: &str = "https://subtitles.vidy.st/search";

pub const STREAM_PROVIDERS: &[&str] = &[
    "miami", "boise", "seattle", "atlanta", "phoenix", "berlin", "paris",
];

#[derive(Clone, Debug)]
struct CachedSeed {
    seed: String,
    expires_at_ms: i64,
}

pub struct MovyProvider {
    http: HttpClient,
    manifest: ProviderManifest,
    seed_cache: Arc<RwLock<HashMap<String, CachedSeed>>>,
    seed_mutex: Arc<Mutex<()>>,
}

impl MovyProvider {
    pub fn new(http: HttpClient) -> Self {
        Self {
            http,
            manifest: ProviderManifest {
                id: SOURCE_ID.to_string(),
                name: "Movy.sx (TR)".to_string(),
                version: "1.0.0".to_string(),
                base_url: SITE_URL.to_string(),
                supported_types: vec![MediaType::MOVIE, MediaType::TV, MediaType::ANIME],
                languages: vec!["tr".to_string(), "en".to_string()],
            },
            seed_cache: Arc::new(RwLock::new(HashMap::new())),
            seed_mutex: Arc::new(Mutex::new(())),
        }
    }

    pub async fn trim_memory(&self) {
        self.seed_cache.write().await.clear();
    }

    fn site_headers() -> HashMap<String, String> {
        HashMap::from([
            ("User-Agent".to_string(), DEFAULT_USER_AGENT.to_string()),
            ("Referer".to_string(), format!("{SITE_ORIGIN}/")),
        ])
    }

    fn stream_headers() -> HashMap<String, String> {
        HashMap::from([
            ("Origin".to_string(), SITE_ORIGIN.to_string()),
            ("Referer".to_string(), format!("{SITE_ORIGIN}/")),
            ("User-Agent".to_string(), DEFAULT_USER_AGENT.to_string()),
        ])
    }

    async fn browse_tmdb(
        &self,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        let media_seg = if media_type == MediaType::TV {
            "tv"
        } else {
            "movie"
        };
        let url = format!(
            "{TMDB_PROXY}/discover/{media_seg}?page={page}&language=tr-TR&include_adult=false&include_video=false&sort_by=popularity.desc"
        );
        let body = self.http.get_text(&url, &Self::site_headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let total_pages = json_u32(&root, "total_pages").unwrap_or(1);
        let items = root
            .get("results")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|el| parse_tmdb_card(el.as_object()?, media_type))
                    .collect()
            })
            .unwrap_or_default();

        Ok(PagedResult {
            items,
            page,
            has_next_page: page < total_pages,
        })
    }

    async fn browse_anime(&self, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        if page == 1 {
            if let Ok(html) = self
                .http
                .get_text(&format!("{SITE_URL}/browse/anime"), &Self::site_headers())
                .await
            {
                if let Some(next_data) = extract_next_data_json(&html)
                    .and_then(|raw| serde_json::from_str::<Value>(&raw).ok())
                {
                    if let Some(grid) = next_data
                        .pointer("/props/pageProps/initialGrid")
                        .and_then(|v| v.as_array())
                    {
                        let items: Vec<MediaCard> = grid
                            .iter()
                            .filter_map(|el| parse_anime_card(el.as_object()?))
                            .collect();
                        if !items.is_empty() {
                            return Ok(PagedResult {
                                items,
                                page: 1,
                                has_next_page: true,
                            });
                        }
                    }
                }
            }
        }

        let url = format!("{ANIME_API}/genre/action?page={page}");
        let body = self.http.get_text(&url, &Self::site_headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let Some(results) = root.get("results").and_then(|v| v.as_object()) else {
            return Ok(PagedResult {
                items: Vec::new(),
                page,
                has_next_page: false,
            });
        };
        let total_pages = results
            .get("totalPages")
            .and_then(|v| v.as_u64())
            .unwrap_or(1) as u32;
        let items = results
            .get("data")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|el| parse_anime_card(el.as_object()?))
                    .collect()
            })
            .unwrap_or_default();

        Ok(PagedResult {
            items,
            page,
            has_next_page: page < total_pages,
        })
    }

    async fn get_tmdb_details(
        &self,
        id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        let seg = if media_type == MediaType::TV {
            "tv"
        } else {
            "movie"
        };
        let url = format!(
            "{TMDB_PROXY}/{seg}/{id}?language=tr-TR&append_to_response=external_ids,credits"
        );
        if let Ok(body) = self.http.get_text(&url, &Self::site_headers()).await {
            if let Ok(root) = serde_json::from_str::<Value>(&body) {
                let title = json_str(&root, "title")
                    .or_else(|| json_str(&root, "name"))
                    .unwrap_or_else(|| id.to_string());
                let date = json_str(&root, "release_date")
                    .or_else(|| json_str(&root, "first_air_date"));
                let genres = root
                    .get("genres")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|g| json_str(g, "name"))
                            .collect()
                    })
                    .unwrap_or_default();
                let cast = root
                    .pointer("/credits/cast")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .take(15)
                            .filter_map(|c| json_str(c, "name"))
                            .collect()
                    })
                    .unwrap_or_default();
                let runtime_minutes = json_i32(&root, "runtime").or_else(|| {
                    root.get("episode_run_time")
                        .and_then(|v| v.as_array())
                        .and_then(|a| a.first())
                        .and_then(|v| v.as_i64())
                        .map(|n| n as i32)
                });
                let seasons = root
                    .get("seasons")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|s| {
                                let s_num = json_i32(s, "season_number")?;
                                if s_num <= 0 {
                                    return None;
                                }
                                Some(Season {
                                    season_number: s_num,
                                    name: json_str(s, "name")
                                        .unwrap_or_else(|| format!("Sezon {s_num}")),
                                    episode_count: json_i32(s, "episode_count"),
                                    poster_url: json_str(s, "poster_path")
                                        .map(|p| tmdb_poster_url(&p)),
                                    episodes: Vec::new(),
                                })
                            })
                            .collect()
                    })
                    .unwrap_or_default();

                return Ok(MediaDetails {
                    id: id.to_string(),
                    source_id: SOURCE_ID.to_string(),
                    title,
                    r#type: media_type,
                    description: json_str(&root, "overview"),
                    poster_url: json_str(&root, "poster_path").map(|p| tmdb_poster_url(&p)),
                    backdrop_url: json_str(&root, "backdrop_path").map(|p| tmdb_backdrop_url(&p)),
                    year: date.and_then(|d| d.get(..4).and_then(|y| y.parse().ok())),
                    rating: json_f64(&root, "vote_average"),
                    genres,
                    cast,
                    runtime_minutes,
                    seasons,
                });
            }
        }

        // Fallback to site __NEXT_DATA__
        let html = self
            .http
            .get_text(&format!("{SITE_URL}/{seg}/{id}"), &Self::site_headers())
            .await?;
        let raw_next = extract_next_data_json(&html)
            .ok_or_else(|| SourceError::NotFound(format!("No details for {id}")))?;
        let next_data: Value = serde_json::from_str(&raw_next)?;
        let details = next_data
            .pointer("/props/pageProps/details")
            .ok_or_else(|| SourceError::NotFound(format!("No details in __NEXT_DATA__ for {id}")))?;

        let seasons = details
            .get("seasons")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|s| {
                        let s_num = json_i32(s, "season_number")?;
                        if s_num <= 0 {
                            return None;
                        }
                        Some(Season {
                            season_number: s_num,
                            name: json_str(s, "name").unwrap_or_else(|| format!("Sezon {s_num}")),
                            episode_count: json_i32(s, "episode_count"),
                            poster_url: json_str(s, "poster_path").map(|p| tmdb_poster_url(&p)),
                            episodes: Vec::new(),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();

        Ok(MediaDetails {
            id: id.to_string(),
            source_id: SOURCE_ID.to_string(),
            title: json_str(details, "title")
                .or_else(|| json_str(details, "titleOriginal"))
                .unwrap_or_else(|| id.to_string()),
            r#type: media_type,
            description: json_str(details, "desc"),
            poster_url: normalize_image_url(json_str(details, "poster").as_deref()),
            backdrop_url: normalize_image_url(json_str(details, "background").as_deref()),
            year: json_i32(details, "year"),
            rating: json_f64(details, "score"),
            genres: details
                .get("categories")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|g| g.as_str().map(String::from))
                        .collect()
                })
                .unwrap_or_default(),
            cast: Vec::new(),
            runtime_minutes: json_i32(details, "duration"),
            seasons,
        })
    }

    async fn get_anime_details(&self, id: &str) -> Result<MediaDetails, SourceError> {
        let info_url = format!("{ANIME_API}/info?id={id}");
        let headers = Self::site_headers();
        let (info_res, episodes_res) =
            tokio::join!(self.http.get_text(&info_url, &headers), self.get_episodes(id, 1));

        let info_body = info_res?;
        let info_root: Value = serde_json::from_str(&info_body)?;
        let info = info_root.get("results");
        let episodes = episodes_res.unwrap_or_default();

        let genres = info
            .and_then(|v| v.get("genres"))
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|g| g.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let rating = info.and_then(|v| {
            json_str(v, "malScore")
                .and_then(|s| s.parse::<f64>().ok())
                .or_else(|| json_f64(v, "malScore"))
        });
        let year = info
            .and_then(|v| json_str(v, "aired"))
            .and_then(|aired| {
                Regex::new(r"\d{4}")
                    .ok()?
                    .find(&aired)
                    .and_then(|m| m.as_str().parse::<i32>().ok())
            });

        let seasons = if episodes.is_empty() {
            Vec::new()
        } else {
            vec![Season {
                season_number: 1,
                name: "Bölümler".to_string(),
                episode_count: Some(episodes.len() as i32),
                poster_url: None,
                episodes,
            }]
        };

        Ok(MediaDetails {
            id: id.to_string(),
            source_id: SOURCE_ID.to_string(),
            title: info
                .and_then(|v| json_str(v, "title"))
                .unwrap_or_else(|| id.to_string()),
            r#type: MediaType::ANIME,
            description: info.and_then(|v| json_str(v, "synopsis")),
            poster_url: normalize_image_url(
                info.and_then(|v| json_str(v, "poster")).as_deref(),
            ),
            backdrop_url: normalize_image_url(
                info.and_then(|v| json_str(v, "backgroundImage")).as_deref(),
            ),
            year,
            rating,
            genres,
            cast: Vec::new(),
            runtime_minutes: None,
            seasons,
        })
    }

    async fn get_anime_streams(
        &self,
        media_id: &str,
        episode_no: i32,
    ) -> Result<StreamResult, SourceError> {
        let info_fut = async {
            let body = self
                .http
                .get_text(&format!("{ANIME_API}/info?id={media_id}"), &Self::site_headers())
                .await
                .ok()?;
            let v: Value = serde_json::from_str(&body).ok()?;
            v.get("results").and_then(|r| json_str(r, "animeId"))
        };
        let ep_fut = async {
            let eps = self.get_episodes(media_id, 1).await.ok()?;
            eps.iter()
                .find(|e| e.episode_number == episode_no)
                .or_else(|| eps.first())
                .map(|e| e.id.clone())
        };

        let (anime_id_opt, ep_id_opt) = tokio::join!(info_fut, ep_fut);
        let anime_id = anime_id_opt.unwrap_or_else(|| media_id.to_string());
        let episode_id = ep_id_opt.unwrap_or_else(|| episode_no.to_string());

        let fetch_dub = |dub: bool| {
            let http = self.http.clone();
            let anime_id = anime_id.clone();
            let episode_id = episode_id.clone();
            async move {
                let url = format!(
                    "{STREAM_API}/anikoto/sources-id?providerId={}&episodeId={}&dub={dub}",
                    urlencoding::encode(&anime_id),
                    urlencoding::encode(&episode_id),
                );
                let Ok(body) = http.get_text(&url, &Self::stream_headers()).await else {
                    return (Vec::new(), Vec::new());
                };
                let Ok(root) = serde_json::from_str::<Value>(&body) else {
                    return (Vec::new(), Vec::new());
                };
                let media_sources = root.get("mediaSources").unwrap_or(&root);
                let tag = if dub { "Dub" } else { "Sub" };
                let streams = parse_sources_array(
                    media_sources.get("sources").and_then(|v| v.as_array()),
                    &format!("Anikoto ({tag})"),
                    Some(&tag.to_lowercase()),
                );
                let subs = parse_subtitles_array(
                    media_sources.get("subtitles").and_then(|v| v.as_array()),
                    false,
                );
                (streams, subs)
            }
        };

        let ((sub_s, sub_t), (dub_s, dub_t)) = tokio::join!(fetch_dub(false), fetch_dub(true));
        let mut streams = sub_s;
        streams.extend(dub_s);

        let mut subs = sub_t;
        subs.extend(dub_t);
        subs = sort_subtitles(dedup_subtitles(subs));

        Ok(StreamResult {
            streams,
            subtitles: subs,
        })
    }

    async fn get_movie_or_tv_streams(
        &self,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        let tmdb_id: i32 = media_id.parse().map_err(|_| {
            SourceError::InvalidArgument(format!(
                "MovyProvider requires numeric TMDB id, got: {media_id}"
            ))
        })?;

        let (seed_res, meta, ext_subs) = tokio::join!(
            self.get_seed(media_id, false),
            self.fetch_stream_metadata(media_id, media_type),
            self.fetch_external_subtitles(media_id, media_type, season, episode),
        );
        let seed = seed_res?;

        let semaphore = Arc::new(Semaphore::new(3));
        let mut set = JoinSet::new();
        let season_num = season.unwrap_or(1);
        let episode_num = episode.unwrap_or(1);

        for &provider in STREAM_PROVIDERS {
            let sem = Arc::clone(&semaphore);
            let http = self.http.clone();
            let seed_cache = Arc::clone(&self.seed_cache);
            let seed_mutex = Arc::clone(&self.seed_mutex);
            let provider = provider.to_string();
            let media_id = media_id.to_string();
            let title = meta.title.clone();
            let year = meta.year.clone();
            let imdb_id = meta.imdb_id.clone();
            let total_seasons = meta.total_seasons;
            let initial_seed = seed.clone();

            set.spawn(async move {
                let _permit = sem.acquire_owned().await.ok()?;
                Some(
                    fetch_provider_streams(
                        &http,
                        &seed_cache,
                        &seed_mutex,
                        &provider,
                        tmdb_id,
                        &media_id,
                        media_type,
                        &title,
                        &year,
                        &imdb_id,
                        total_seasons,
                        season_num,
                        episode_num,
                        initial_seed,
                    )
                    .await,
                )
            });
        }

        let mut all_streams = Vec::new();
        let mut provider_subs = Vec::new();
        while let Some(res) = set.join_next().await {
            if let Ok(Some((streams, subs))) = res {
                all_streams.extend(streams);
                provider_subs.extend(subs);
            }
        }

        let mut seen_stream_urls = HashSet::new();
        all_streams.retain(|s| seen_stream_urls.insert(s.url.clone()));

        provider_subs.extend(ext_subs);
        let merged_subs = sort_subtitles(dedup_subtitles(provider_subs));

        Ok(StreamResult {
            streams: all_streams,
            subtitles: merged_subs,
        })
    }

    async fn get_seed(&self, media_id: &str, force_refresh: bool) -> Result<String, SourceError> {
        get_seed_shared(&self.http, &self.seed_cache, &self.seed_mutex, media_id, force_refresh).await
    }

    async fn fetch_stream_metadata(&self, media_id: &str, media_type: MediaType) -> StreamMetadata {
        let seg = if media_type == MediaType::TV {
            "tv"
        } else {
            "movie"
        };
        let url = format!("{TMDB_PROXY}/{seg}/{media_id}?language=tr-TR&append_to_response=external_ids");
        if let Ok(body) = self.http.get_text(&url, &Self::site_headers()).await {
            if let Ok(root) = serde_json::from_str::<Value>(&body) {
                let title = json_str(&root, "original_title")
                    .or_else(|| json_str(&root, "original_name"))
                    .or_else(|| json_str(&root, "title"))
                    .or_else(|| json_str(&root, "name"))
                    .unwrap_or_else(|| media_id.to_string());
                let date = json_str(&root, "release_date")
                    .or_else(|| json_str(&root, "first_air_date"))
                    .unwrap_or_default();
                let year = date.chars().take(4).collect();
                let imdb_id = root
                    .pointer("/external_ids/imdb_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let total_seasons = json_i32(&root, "number_of_seasons").unwrap_or(1);
                return StreamMetadata {
                    title,
                    year,
                    imdb_id,
                    total_seasons,
                };
            }
        }
        StreamMetadata {
            title: media_id.to_string(),
            year: String::new(),
            imdb_id: String::new(),
            total_seasons: 1,
        }
    }

    async fn fetch_external_subtitles(
        &self,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Vec<SubtitleTrack> {
        let mut url = format!("{SUBTITLES_API}?id={media_id}");
        if media_type == MediaType::TV {
            if let (Some(s), Some(e)) = (season, episode) {
                url.push_str(&format!("&season={s}&episode={e}"));
            }
        }
        let Ok(body) = self.http.get_text(&url, &Self::stream_headers()).await else {
            return Vec::new();
        };
        let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(&body) else {
            return Vec::new();
        };
        arr.iter()
            .filter_map(|el| {
                let sub_url = json_str(el, "url")?;
                let lang = json_str(el, "language").unwrap_or_else(|| "en".to_string());
                let label = json_str(el, "display").unwrap_or_else(|| lang.to_uppercase());
                let format = match json_str(el, "format")
                    .as_deref()
                    .map(|s| s.to_lowercase())
                    .as_deref()
                {
                    Some("srt") => SubtitleFormat::SRT,
                    Some("ass") => SubtitleFormat::ASS,
                    _ => SubtitleFormat::VTT,
                };
                Some(SubtitleTrack {
                    url: sub_url,
                    language: lang,
                    label,
                    format,
                    headers: Self::stream_headers(),
                    is_external: true,
                })
            })
            .collect()
    }
}

#[async_trait]
impl MediaProvider for MovyProvider {
    fn manifest(&self) -> &ProviderManifest {
        &self.manifest
    }

    async fn get_home(&self) -> Result<Vec<HomeSection>, SourceError> {
        let html = self.http.get_text(SITE_URL, &Self::site_headers()).await?;
        let Some(raw_next) = extract_next_data_json(&html) else {
            return Ok(Vec::new());
        };
        let next_data: Value = serde_json::from_str(&raw_next)?;
        let Some(page_props) = next_data.pointer("/props/pageProps") else {
            return Ok(Vec::new());
        };

        let mut sections = Vec::new();
        for key in ["defaultSections", "trendingSections", "streamingSections"] {
            if let Some(arr) = page_props.get(key).and_then(|v| v.as_array()) {
                for el in arr {
                    let Some(obj) = el.as_object() else { continue };
                    let Some(title) = obj.get("name").and_then(|v| v.as_str()) else {
                        continue;
                    };
                    let items: Vec<MediaCard> = obj
                        .get("movies")
                        .and_then(|v| v.as_array())
                        .map(|m| {
                            m.iter()
                                .filter_map(|it| parse_home_media_item(it.as_object()?))
                                .collect()
                        })
                        .unwrap_or_default();
                    if !items.is_empty() {
                        sections.push(HomeSection {
                            title: title.to_string(),
                            items,
                        });
                    }
                }
            }
        }

        if let Some(arr) = page_props.get("recent4k").and_then(|v| v.as_array()) {
            let items: Vec<MediaCard> = arr
                .iter()
                .filter_map(|it| parse_home_media_item(it.as_object()?))
                .collect();
            if !items.is_empty() {
                sections.push(HomeSection {
                    title: "4K Filmler".to_string(),
                    items,
                });
            }
        }

        if let Some(arr) = page_props.get("upcomingTv").and_then(|v| v.as_array()) {
            let items: Vec<MediaCard> = arr
                .iter()
                .filter_map(|it| parse_home_media_item(it.as_object()?))
                .collect();
            if !items.is_empty() {
                sections.push(HomeSection {
                    title: "Yaklaşan Diziler".to_string(),
                    items,
                });
            }
        }

        Ok(sections)
    }

    async fn browse(
        &self,
        media_type: MediaType,
        page: u32,
    ) -> Result<PagedResult<MediaCard>, SourceError> {
        match media_type {
            MediaType::ANIME => self.browse_anime(page).await,
            MediaType::MOVIE | MediaType::TV | MediaType::LIVE => {
                self.browse_tmdb(media_type, page).await
            }
        }
    }

    async fn search(&self, query: &str, page: u32) -> Result<PagedResult<MediaCard>, SourceError> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return Ok(PagedResult {
                items: Vec::new(),
                page,
                has_next_page: false,
            });
        }
        let encoded = urlencoding::encode(trimmed);

        let tmdb_fut = async {
            let url = format!("{TMDB_PROXY}/search/multi?query={encoded}&language=tr-TR&page={page}");
            let Ok(body) = self.http.get_text(&url, &Self::site_headers()).await else {
                return (Vec::new(), false);
            };
            let Ok(root) = serde_json::from_str::<Value>(&body) else {
                return (Vec::new(), false);
            };
            let total_pages = json_u32(&root, "total_pages").unwrap_or(1);
            let cards = root
                .get("results")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|el| {
                            let obj = el.as_object()?;
                            let mt = json_str(el, "media_type");
                            if mt.as_deref() == Some("person")
                                || json_str(el, "poster_path").unwrap_or_default().is_empty()
                            {
                                return None;
                            }
                            let media_type = if mt.as_deref() == Some("tv") {
                                MediaType::TV
                            } else {
                                MediaType::MOVIE
                            };
                            parse_tmdb_card(obj, media_type)
                        })
                        .collect()
                })
                .unwrap_or_default();
            (cards, page < total_pages)
        };

        let anime_fut = async {
            let url = format!("{ANIME_API}/search?keyword={encoded}&page={page}");
            let Ok(body) = self.http.get_text(&url, &Self::site_headers()).await else {
                return (Vec::new(), false);
            };
            let Ok(root) = serde_json::from_str::<Value>(&body) else {
                return (Vec::new(), false);
            };
            let results = root.get("results");
            let total_pages = results.and_then(|r| json_u32(r, "totalPages")).unwrap_or(1);
            let cards = results
                .and_then(|r| r.get("data"))
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|el| parse_anime_card(el.as_object()?))
                        .collect()
                })
                .unwrap_or_default();
            (cards, page < total_pages)
        };

        let ((mut items, tmdb_next), (anime_items, anime_next)) =
            tokio::join!(tmdb_fut, anime_fut);
        items.extend(anime_items);

        Ok(PagedResult {
            items,
            page,
            has_next_page: tmdb_next || anime_next,
        })
    }

    async fn get_details(
        &self,
        id: &str,
        media_type: MediaType,
    ) -> Result<MediaDetails, SourceError> {
        match media_type {
            MediaType::ANIME => self.get_anime_details(id).await,
            MediaType::MOVIE | MediaType::TV | MediaType::LIVE => {
                self.get_tmdb_details(id, media_type).await
            }
        }
    }

    async fn get_episodes(&self, id: &str, season: i32) -> Result<Vec<Episode>, SourceError> {
        if id.parse::<i32>().is_err() {
            let body = self
                .http
                .get_text(&format!("{ANIME_API}/episodes/{id}"), &Self::site_headers())
                .await?;
            let root: Value = serde_json::from_str(&body)?;
            let Some(arr) = root.pointer("/results/episodes").and_then(|v| v.as_array()) else {
                return Ok(Vec::new());
            };
            return Ok(arr
                .iter()
                .enumerate()
                .filter_map(|(idx, el)| {
                    let ep_no = json_i32(el, "episode_no").unwrap_or((idx + 1) as i32);
                    let ep_id = json_str(el, "id").unwrap_or_else(|| ep_no.to_string());
                    let title = json_str(el, "title")
                        .filter(|t| !t.trim().is_empty())
                        .unwrap_or_else(|| format!("Bölüm {ep_no}"));
                    Some(Episode {
                        id: ep_id,
                        season_number: 1,
                        episode_number: ep_no,
                        title,
                        overview: None,
                        thumbnail_url: None,
                        runtime_minutes: None,
                        is_released: true,
                    })
                })
                .collect());
        }

        let url = format!("{TMDB_PROXY}/tv/{id}/season/{season}?language=tr-TR");
        let body = self.http.get_text(&url, &Self::site_headers()).await?;
        let root: Value = serde_json::from_str(&body)?;
        let Some(arr) = root.get("episodes").and_then(|v| v.as_array()) else {
            return Ok(Vec::new());
        };
        let today_utc = chrono::Utc::now().date_naive();
        Ok(arr
            .iter()
            .filter_map(|el| {
                let ep_num = json_i32(el, "episode_number")?;
                let title = json_str(el, "name")
                    .filter(|t| !t.trim().is_empty())
                    .unwrap_or_else(|| format!("Bölüm {ep_num}"));
                let is_released = match json_str(el, "air_date") {
                    Some(ref date_str) if !date_str.trim().is_empty() => {
                        match chrono::NaiveDate::parse_from_str(date_str.trim(), "%Y-%m-%d") {
                            Ok(air_date) => air_date <= today_utc,
                            Err(_) => true,
                        }
                    }
                    _ => true,
                };
                Some(Episode {
                    id: format!("{id}_s{season}_e{ep_num}"),
                    season_number: season,
                    episode_number: ep_num,
                    title,
                    overview: json_str(el, "overview"),
                    thumbnail_url: json_str(el, "still_path").map(|p| tmdb_backdrop_url(&p)),
                    runtime_minutes: json_i32(el, "runtime"),
                    is_released,
                })
            })
            .collect())
    }

    async fn get_streams(
        &self,
        media_id: &str,
        media_type: MediaType,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<StreamResult, SourceError> {
        match media_type {
            MediaType::ANIME => self.get_anime_streams(media_id, episode.unwrap_or(1)).await,
            MediaType::MOVIE | MediaType::TV | MediaType::LIVE => {
                self.get_movie_or_tv_streams(media_id, media_type, season, episode)
                    .await
            }
        }
    }
}

struct StreamMetadata {
    title: String,
    year: String,
    imdb_id: String,
    total_seasons: i32,
}

#[allow(clippy::too_many_arguments)]
async fn fetch_provider_streams(
    http: &HttpClient,
    seed_cache: &Arc<RwLock<HashMap<String, CachedSeed>>>,
    seed_mutex: &Arc<Mutex<()>>,
    provider: &str,
    tmdb_id: i32,
    media_id: &str,
    media_type: MediaType,
    title: &str,
    year: &str,
    imdb_id: &str,
    total_seasons: i32,
    season: i32,
    episode: i32,
    initial_seed: String,
) -> (Vec<StreamSource>, Vec<SubtitleTrack>) {
    let mut current_seed = initial_seed;
    let media_type_str = if media_type == MediaType::TV {
        "tv"
    } else {
        "movie"
    };

    for attempt in 0..=1 {
        let mut params = format!(
            "title={}&mediaType={}&year={}&tmdbId={}&imdbId={}&enc=2&seed={}",
            urlencoding::encode(title),
            media_type_str,
            urlencoding::encode(year),
            media_id,
            urlencoding::encode(imdb_id),
            urlencoding::encode(&current_seed),
        );
        if media_type == MediaType::TV {
            params.push_str(&format!(
                "&totalSeasons={total_seasons}&seasonId={season}&episodeId={episode}"
            ));
        }
        let url = format!("{STREAM_API}/{provider}/sources?{params}");
        match http.get_text(&url, &MovyProvider::stream_headers()).await {
            Ok(encoded) => {
                if let Ok(decoded_json) = MovyCipher::decode(&encoded, &current_seed, tmdb_id) {
                    if let Ok(root) = serde_json::from_str::<Value>(&decoded_json) {
                        let server_name = capitalize_first(provider);
                        let streams = parse_sources_array(
                            root.get("sources").and_then(|v| v.as_array()),
                            &server_name,
                            None,
                        );
                        let subs = parse_subtitles_array(
                            root.get("subtitles").and_then(|v| v.as_array()),
                            false,
                        );
                        return (streams, subs);
                    }
                }
                break;
            }
            Err(err) => {
                if attempt == 0 && err.to_string().contains("401") {
                    if let Ok(refreshed) =
                        get_seed_shared(http, seed_cache, seed_mutex, media_id, true).await
                    {
                        current_seed = refreshed;
                        continue;
                    }
                }
                break;
            }
        }
    }
    (Vec::new(), Vec::new())
}

async fn get_seed_shared(
    http: &HttpClient,
    seed_cache: &Arc<RwLock<HashMap<String, CachedSeed>>>,
    seed_mutex: &Arc<Mutex<()>>,
    media_id: &str,
    force_refresh: bool,
) -> Result<String, SourceError> {
    let now = now_ms();
    if !force_refresh {
        if let Some(cached) = seed_cache.read().await.get(media_id) {
            if now < cached.expires_at_ms {
                return Ok(cached.seed.clone());
            }
        }
    }

    let _guard = seed_mutex.lock().await;
    let again_now = now_ms();
    {
        let mut cache = seed_cache.write().await;
        cache.retain(|_, v| v.expires_at_ms > again_now);
        if !force_refresh {
            if let Some(cached) = cache.get(media_id) {
                if again_now < cached.expires_at_ms {
                    return Ok(cached.seed.clone());
                }
            }
        }
    }

    let body = http
        .get_text(
            &format!("{STREAM_API}/seed?mediaId={media_id}"),
            &MovyProvider::stream_headers(),
        )
        .await?;
    let obj: Value = serde_json::from_str(&body)?;
    let seed = json_str(&obj, "seed")
        .ok_or_else(|| SourceError::Plugin("Missing seed in response".to_string()))?;
    let ttl_ms = obj.get("ttlMs").and_then(|v| v.as_i64()).unwrap_or(60_000);
    let safe_ttl = (ttl_ms - 5_000).max(5_000);

    seed_cache.write().await.insert(
        media_id.to_string(),
        CachedSeed {
            seed: seed.clone(),
            expires_at_ms: again_now + safe_ttl,
        },
    );
    Ok(seed)
}

fn parse_sources_array(
    arr: Option<&Vec<Value>>,
    server_name: &str,
    audio_lang: Option<&str>,
) -> Vec<StreamSource> {
    let Some(arr) = arr else { return Vec::new() };
    arr.iter()
        .filter_map(|el| {
            let url = json_str(el, "url")?;
            let quality = json_str(el, "quality").unwrap_or_else(|| "Auto".to_string());
            Some(StreamSource {
                format: infer_stream_format(&url),
                url,
                quality,
                server_name: server_name.to_string(),
                audio_lang: audio_lang.map(String::from),
                headers: MovyProvider::stream_headers(),
            })
        })
        .collect()
}

fn parse_subtitles_array(arr: Option<&Vec<Value>>, is_external: bool) -> Vec<SubtitleTrack> {
    let Some(arr) = arr else { return Vec::new() };
    arr.iter()
        .filter_map(|el| {
            let url = json_str(el, "url")?;
            let lang = json_str(el, "lang")
                .or_else(|| json_str(el, "language"))
                .unwrap_or_else(|| "en".to_string());
            let label = json_str(el, "language")
                .or_else(|| json_str(el, "label"))
                .unwrap_or_else(|| lang.to_uppercase());
            let lower = url.to_lowercase();
            let format = if lower.contains(".srt") {
                SubtitleFormat::SRT
            } else if lower.contains(".ass") {
                SubtitleFormat::ASS
            } else {
                SubtitleFormat::VTT
            };
            Some(SubtitleTrack {
                url,
                language: lang,
                label,
                format,
                headers: MovyProvider::stream_headers(),
                is_external,
            })
        })
        .collect()
}

fn dedup_subtitles(tracks: Vec<SubtitleTrack>) -> Vec<SubtitleTrack> {
    let mut seen = HashSet::new();
    tracks
        .into_iter()
        .filter(|t| seen.insert(t.url.clone()))
        .collect()
}

fn sort_subtitles(mut tracks: Vec<SubtitleTrack>) -> Vec<SubtitleTrack> {
    tracks.sort_by_key(|track| {
        let lang = track.language.to_lowercase();
        let label = track.label.to_lowercase();
        if lang.starts_with("tr") || label.contains("turk") || label.contains("türk") {
            0
        } else if lang.starts_with("en") || label.contains("eng") {
            1
        } else {
            2
        }
    });
    tracks
}

fn parse_home_media_item(obj: &Map<String, Value>) -> Option<MediaCard> {
    let val = Value::Object(obj.clone());
    let slug = json_str(&val, "slug").unwrap_or_default();
    let raw_media_type = json_str(&val, "mediaType").map(|s| s.to_lowercase());
    let media_type = if raw_media_type.as_deref() == Some("tv") || slug.starts_with("/tv/") {
        MediaType::TV
    } else if raw_media_type.as_deref() == Some("anime")
        || slug.starts_with("/anime/")
        || slug.starts_with("/tr/anime/")
    {
        MediaType::ANIME
    } else {
        MediaType::MOVIE
    };

    let raw_id = json_str(&val, "id");
    let id = if media_type == MediaType::ANIME {
        normalize_anime_slug(Some(&slug)).or(raw_id)?
    } else {
        raw_id.or_else(|| {
            slug.rsplit('/')
                .next()
                .filter(|s| !s.is_empty())
                .map(String::from)
        })?
    };
    let title = json_str(&val, "title")?;
    let poster_raw = json_str(&val, "poster").or_else(|| json_str(&val, "image"));

    Some(MediaCard {
        id,
        source_id: SOURCE_ID.to_string(),
        title,
        poster_url: normalize_image_url(poster_raw.as_deref()),
        backdrop_url: normalize_image_url(json_str(&val, "background").as_deref()),
        r#type: media_type,
        year: json_str(&val, "release_date").and_then(|d| d.get(..4).and_then(|y| y.parse().ok())),
        rating: json_f64(&val, "rating"),
        subtitle: json_str(&val, "episodeCode"),
    })
}

fn parse_tmdb_card(obj: &Map<String, Value>, media_type: MediaType) -> Option<MediaCard> {
    let val = Value::Object(obj.clone());
    let id = json_str(&val, "id")?;
    let title = json_str(&val, "title").or_else(|| json_str(&val, "name"))?;
    let date = json_str(&val, "release_date").or_else(|| json_str(&val, "first_air_date"));
    Some(MediaCard {
        id,
        source_id: SOURCE_ID.to_string(),
        title,
        poster_url: json_str(&val, "poster_path").map(|p| tmdb_poster_url(&p)),
        backdrop_url: json_str(&val, "backdrop_path").map(|p| tmdb_backdrop_url(&p)),
        r#type: media_type,
        year: date.and_then(|d| d.get(..4).and_then(|y| y.parse().ok())),
        rating: json_f64(&val, "vote_average"),
        subtitle: None,
    })
}

fn parse_anime_card(obj: &Map<String, Value>) -> Option<MediaCard> {
    let val = Value::Object(obj.clone());
    let slug = json_str(&val, "slug");
    let id = normalize_anime_slug(slug.as_deref())
        .or_else(|| json_str(&val, "id"))
        .or_else(|| json_str(&val, "animeId"))?;
    let title = json_str(&val, "title")?;
    let rating = json_f64(&val, "rating")
        .or_else(|| json_str(&val, "rating").and_then(|s| s.parse().ok()));
    Some(MediaCard {
        id,
        source_id: SOURCE_ID.to_string(),
        title,
        poster_url: normalize_image_url(json_str(&val, "poster").as_deref()),
        backdrop_url: None,
        r#type: MediaType::ANIME,
        year: None,
        rating,
        subtitle: None,
    })
}

pub fn normalize_anime_slug(raw_slug: Option<&str>) -> Option<String> {
    let s = raw_slug?.trim().trim_start_matches('/');
    let s = s.strip_prefix("tr/").unwrap_or(s);
    let s = s.strip_prefix("anime/").unwrap_or(s);
    let cleaned = s.split('/').next()?.trim();
    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned.to_string())
    }
}

pub fn extract_next_data_json(html: &str) -> Option<String> {
    let lower = html.to_ascii_lowercase();
    let mut search_from = 0usize;
    loop {
        let rel_start = lower[search_from..].find("<script")?;
        let script_start = search_from + rel_start;
        let rel_end = html[script_start..].find('>')?;
        let tag_end = script_start + rel_end;
        let open_tag = &html[script_start..=tag_end];
        if open_tag.contains(r#"id="__NEXT_DATA__""#) || open_tag.contains("id='__NEXT_DATA__'") {
            let content_start = tag_end + 1;
            let rel_close = lower[content_start..].find("</script>")?;
            let script_end = content_start + rel_close;
            return Some(html[content_start..script_end].to_string());
        }
        search_from = tag_end + 1;
    }
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

fn normalize_image_url(raw: Option<&str>) -> Option<String> {
    let s = raw?.trim();
    if s.is_empty() {
        return None;
    }
    if s.starts_with("http://") || s.starts_with("https://") {
        Some(s.to_string())
    } else if s.starts_with("//") {
        Some(format!("https:{s}"))
    } else if s.starts_with('/') {
        Some(format!("{SITE_ORIGIN}{s}"))
    } else {
        Some(format!("{SITE_ORIGIN}/{s}"))
    }
}

fn tmdb_poster_url(path: &str) -> String {
    let normalized = if path.starts_with('/') {
        path.to_string()
    } else {
        format!("/{path}")
    };
    format!("{SITE_ORIGIN}/img/w342{normalized}/q50.webp")
}

fn tmdb_backdrop_url(path: &str) -> String {
    let normalized = if path.starts_with('/') {
        path.to_string()
    } else {
        format!("/{path}")
    };
    format!("{SITE_ORIGIN}/img/w780{normalized}/q65.webp")
}

fn capitalize_first(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        None => String::new(),
        Some(c) => c.to_uppercase().collect::<String>() + chars.as_str(),
    }
}

fn json_str(v: &Value, key: &str) -> Option<String> {
    match v.get(key)? {
        Value::String(s) if s != "null" => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn json_i32(v: &Value, key: &str) -> Option<i32> {
    v.get(key)?.as_i64().map(|n| n as i32)
}

fn json_u32(v: &Value, key: &str) -> Option<u32> {
    v.get(key)?.as_u64().map(|n| n as u32)
}

fn json_f64(v: &Value, key: &str) -> Option<f64> {
    v.get(key)?.as_f64()
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_anime_slug_strips_prefixes() {
        assert_eq!(
            normalize_anime_slug(Some("/tr/anime/one-piece/episode-1")),
            Some("one-piece".to_string())
        );
        assert_eq!(
            normalize_anime_slug(Some("/anime/jujutsu-kaisen")),
            Some("jujutsu-kaisen".to_string())
        );
        assert_eq!(
            normalize_anime_slug(Some("attack-on-titan")),
            Some("attack-on-titan".to_string())
        );
        assert_eq!(normalize_anime_slug(Some("   ")), None);
        assert_eq!(normalize_anime_slug(None), None);
    }

    #[test]
    fn extract_next_data_json_finds_script_payload() {
        let html = r#"<html><head></head><body><script src="/app.js"></script><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"ok":true}}}</script></body></html>"#;
        assert_eq!(
            extract_next_data_json(html).as_deref(),
            Some(r#"{"props":{"pageProps":{"ok":true}}}"#)
        );
    }

    #[test]
    fn extract_next_data_json_returns_none_when_missing() {
        assert_eq!(extract_next_data_json("<html><body>No data</body></html>"), None);
    }
}
