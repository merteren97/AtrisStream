use crate::{
    mpv::{MpvPlaybackState, PlayerCapabilities},
    state::AppState,
};
use atris_core::{
    AppPreferences, DiscoveredDevice, Episode, HomeSection, InstalledSource, MediaCard,
    MediaDetails, MediaType, PagedResult, PairedDevice, SourceHealthResult, SourceSearchResult,
    StreamResult, SubtitleAppearance, SubtitleFormat, SubtitleTrack, SyncSummary, WatchProgress,
    WatchProgressPolicy, WatchlistItem,
};
use std::{
    collections::HashMap,
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, State};

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[tauri::command]
pub async fn get_sources(state: State<'_, AppState>) -> Result<Vec<InstalledSource>, String> {
    state.storage.get_all_sources().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn add_source_from_url(
    state: State<'_, AppState>,
    url: String,
) -> Result<Vec<InstalledSource>, String> {
    state
        .sources
        .add_source_from_url(&url)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn set_source_enabled(
    state: State<'_, AppState>,
    id: String,
    enabled: bool,
) -> Result<(), String> {
    state
        .sources
        .set_source_enabled(&id, enabled)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_source(state: State<'_, AppState>, id: String) -> Result<bool, String> {
    state
        .sources
        .remove_source(&id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn test_source(
    state: State<'_, AppState>,
    id: String,
) -> Result<SourceHealthResult, String> {
    Ok(state.sources.test_source(&id).await)
}

#[tauri::command]
pub async fn get_home(
    state: State<'_, AppState>,
    source_id: Option<String>,
) -> Result<Vec<HomeSection>, String> {
    let resolved_id = match source_id.filter(|s| !s.trim().is_empty()) {
        Some(id) => id,
        None => state
            .storage
            .get_enabled_sources()
            .ok()
            .and_then(|list| list.into_iter().next().map(|s| s.id))
            .unwrap_or_else(|| "builtin_movy_sx".to_string()),
    };
    state
        .sources
        .get_home(&resolved_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn browse_media(
    state: State<'_, AppState>,
    source_id: String,
    media_type: MediaType,
    page: u32,
) -> Result<PagedResult<MediaCard>, String> {
    state
        .sources
        .browse(&source_id, media_type, page)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn search_media(
    state: State<'_, AppState>,
    query: String,
    page: u32,
    source_id: Option<String>,
) -> Result<Vec<SourceSearchResult>, String> {
    state
        .sources
        .search(&query, page, source_id.as_deref())
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_details(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
    media_type: MediaType,
) -> Result<MediaDetails, String> {
    state
        .sources
        .get_details(&source_id, &media_id, media_type)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_episodes(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
    season: i32,
) -> Result<Vec<Episode>, String> {
    state
        .sources
        .get_episodes(&source_id, &media_id, season)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_streams(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
    media_type: MediaType,
    season: Option<i32>,
    episode: Option<i32>,
) -> Result<StreamResult, String> {
    state
        .sources
        .get_streams(&source_id, &media_id, media_type, season, episode)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_continue_watching(
    state: State<'_, AppState>,
    limit: Option<u32>,
) -> Result<Vec<WatchProgress>, String> {
    state
        .storage
        .get_continue_watching(limit.unwrap_or(20))
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_latest_progress(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
) -> Result<Option<WatchProgress>, String> {
    state
        .storage
        .get_latest_progress_for_media(&source_id, &media_id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_all_progress(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
) -> Result<Vec<WatchProgress>, String> {
    state
        .storage
        .get_all_progress_for_media(&source_id, &media_id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_watch_progress(
    state: State<'_, AppState>,
    mut progress: WatchProgress,
    is_ended: Option<bool>,
) -> Result<(), String> {
    if progress.r#type == MediaType::LIVE {
        return Ok(());
    }

    let ended = is_ended.unwrap_or(false);
    let completed = WatchProgressPolicy::is_playback_completed(
        progress.position_ms,
        progress.duration_ms,
        ended,
    );

    if !completed && progress.position_ms < WatchProgressPolicy::MIN_RESUME_POSITION_MS {
        return Ok(());
    }

    progress.is_completed = completed;
    if progress.updated_at <= 0 {
        progress.updated_at = now_millis();
    }

    state
        .storage
        .upsert_watch_progress(&progress)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn mark_watch_progress_completed(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
    season_number: Option<i32>,
    episode_number: Option<i32>,
) -> Result<(), String> {
    state
        .storage
        .mark_watch_progress_completed(&source_id, &media_id, season_number, episode_number)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_watch_progress(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
    season_number: Option<i32>,
    episode_number: Option<i32>,
) -> Result<(), String> {
    state
        .storage
        .delete_watch_progress(&source_id, &media_id, season_number, episode_number)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_watchlist(state: State<'_, AppState>) -> Result<Vec<WatchlistItem>, String> {
    state.storage.get_watchlist().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn is_saved_to_watchlist(
    state: State<'_, AppState>,
    source_id: String,
    media_id: String,
) -> Result<bool, String> {
    state
        .storage
        .is_saved_to_watchlist(&source_id, &media_id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn toggle_watchlist(
    state: State<'_, AppState>,
    mut item: WatchlistItem,
) -> Result<bool, String> {
    let exists = state
        .storage
        .is_saved_to_watchlist(&item.source_id, &item.media_id)
        .map_err(|e| e.to_string())?;

    if exists {
        state
            .storage
            .remove_from_watchlist(&item.source_id, &item.media_id)
            .map_err(|e| e.to_string())?;
        Ok(false)
    } else {
        if item.added_at <= 0 {
            item.added_at = now_millis();
        }
        state
            .storage
            .upsert_watchlist_item(&item)
            .map_err(|e| e.to_string())?;
        Ok(true)
    }
}

#[tauri::command]
pub async fn get_preferences(state: State<'_, AppState>) -> Result<AppPreferences, String> {
    state.storage.get_preferences().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn save_preferences(
    state: State<'_, AppState>,
    prefs: AppPreferences,
) -> Result<(), String> {
    state
        .storage
        .save_preferences(&prefs)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_player_capabilities(
    state: State<'_, AppState>,
) -> Result<PlayerCapabilities, String> {
    Ok(crate::mpv::MpvController::probe(state.proxy.port()))
}

#[tauri::command]
pub async fn register_proxy_stream(
    state: State<'_, AppState>,
    url: String,
    headers: Option<HashMap<String, String>>,
) -> Result<String, String> {
    Ok(state
        .proxy
        .register_stream(url, headers.unwrap_or_default()))
}

#[tauri::command]
pub async fn register_proxy_subtitle(
    state: State<'_, AppState>,
    url: String,
    headers: Option<HashMap<String, String>>,
    format: SubtitleFormat,
) -> Result<String, String> {
    Ok(state
        .proxy
        .register_subtitle(url, headers.unwrap_or_default(), format))
}

#[tauri::command]
pub async fn mpv_load(
    app_handle: AppHandle,
    state: State<'_, AppState>,
    url: String,
    headers: Option<HashMap<String, String>>,
    resume_position_ms: Option<i64>,
    subtitles: Option<Vec<SubtitleTrack>>,
    subtitle_appearance: Option<SubtitleAppearance>,
) -> Result<(), String> {
    let prefs = state.storage.get_preferences().unwrap_or_default();
    let appearance = subtitle_appearance.unwrap_or(prefs.subtitle_appearance);
    state.mpv.load(
        app_handle,
        None,
        url,
        headers.unwrap_or_default(),
        resume_position_ms,
        subtitles.unwrap_or_default(),
        appearance,
        prefs.hardware_decoding,
    )
}

#[tauri::command]
pub async fn mpv_set_paused(state: State<'_, AppState>, paused: bool) -> Result<(), String> {
    state.mpv.set_paused(paused)
}

#[tauri::command]
pub async fn mpv_seek(state: State<'_, AppState>, position_ms: i64) -> Result<(), String> {
    state.mpv.seek(position_ms)
}

#[tauri::command]
pub async fn mpv_set_volume(state: State<'_, AppState>, volume: f64) -> Result<(), String> {
    state.mpv.set_volume(volume)
}

#[tauri::command]
pub async fn mpv_set_speed(state: State<'_, AppState>, speed: f64) -> Result<(), String> {
    state.mpv.set_speed(speed)
}

#[tauri::command]
pub async fn mpv_select_subtitle(
    state: State<'_, AppState>,
    track_id: Option<i64>,
) -> Result<(), String> {
    state
        .mpv
        .select_subtitle(track_id.map(|id| id.to_string()))
}

#[tauri::command]
pub async fn mpv_add_subtitle(
    state: State<'_, AppState>,
    url: String,
    _headers: Option<HashMap<String, String>>,
) -> Result<(), String> {
    state.mpv.add_subtitle(&url)
}

#[tauri::command]
pub async fn mpv_stop(state: State<'_, AppState>) -> Result<(), String> {
    state.mpv.stop()
}

#[tauri::command]
pub async fn mpv_get_state(state: State<'_, AppState>) -> Result<MpvPlaybackState, String> {
    Ok(state.mpv.get_state())
}

#[tauri::command]
pub async fn discover_tv_devices() -> Result<Vec<DiscoveredDevice>, String> {
    Ok(atris_sync::discover_tv_devices(2000).await)
}

#[tauri::command]
pub async fn pair_tv_device(
    state: State<'_, AppState>,
    host: String,
    port: u16,
    pin: String,
) -> Result<PairedDevice, String> {
    let desktop_name = std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "AtrisStream Desktop".to_string());
    let device = atris_sync::pair_tv_device(&host, port, &pin, &desktop_name).await?;
    state
        .storage
        .upsert_paired_device(&device)
        .map_err(|e| e.to_string())?;
    Ok(device)
}

#[tauri::command]
pub async fn get_paired_devices(state: State<'_, AppState>) -> Result<Vec<PairedDevice>, String> {
    state.storage.get_paired_devices().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_paired_device(state: State<'_, AppState>, id: String) -> Result<(), String> {
    state
        .storage
        .remove_paired_device(&id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn sync_with_tv(
    state: State<'_, AppState>,
    device_id: String,
) -> Result<SyncSummary, String> {
    let device = state
        .storage
        .get_paired_device(&device_id)
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "Paired TV device not found".to_string())?;
    atris_sync::sync_with_device(&state.storage, &device).await
}

#[tauri::command]
pub async fn send_source_url_to_tv(
    host: String,
    port: u16,
    url: String,
) -> Result<String, String> {
    atris_sync::send_source_url_to_tv(&host, port, &url).await
}
