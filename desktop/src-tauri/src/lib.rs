mod commands;
mod mpv;
mod state;

use atris_proxy::StreamProxyServer;
use atris_sources::SourceManager;
use atris_storage::AtrisStorage;
use mpv::MpvController;
use state::AppState;
use std::sync::Arc;
use tauri::{Manager, WindowEvent};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .map_err(|e| format!("Failed to resolve app data dir: {e}"))?;
            std::fs::create_dir_all(&data_dir)?;

            let db_path = data_dir.join("atrisstream.db");
            let storage = Arc::new(
                AtrisStorage::open(&db_path)
                    .map_err(|e| format!("Failed to open SQLite database: {e}"))?,
            );

            let sources = Arc::new(SourceManager::new(storage.clone()));
            let mpv = Arc::new(MpvController::new());

            let (proxy, init_res) = tauri::async_runtime::block_on(async {
                let init_res = sources.initialize().await;
                let proxy = StreamProxyServer::start().await;
                (proxy, init_res)
            });

            if let Err(err) = init_res {
                eprintln!("Warning: SourceManager initialization failed: {err}");
            }
            let proxy = proxy.map_err(|e| format!("Failed to start local stream proxy: {e}"))?;

            app.manage(AppState {
                storage,
                sources,
                proxy,
                mpv,
            });

            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::Destroyed = event {
                if let Some(state) = window.try_state::<AppState>() {
                    let _ = state.mpv.stop();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_sources,
            commands::add_source_from_url,
            commands::set_source_enabled,
            commands::remove_source,
            commands::test_source,
            commands::get_home,
            commands::browse_media,
            commands::search_media,
            commands::get_details,
            commands::get_episodes,
            commands::get_streams,
            commands::get_continue_watching,
            commands::get_latest_progress,
            commands::get_all_progress,
            commands::save_watch_progress,
            commands::mark_watch_progress_completed,
            commands::set_episode_completed,
            commands::delete_watch_progress,
            commands::get_watchlist,
            commands::is_saved_to_watchlist,
            commands::toggle_watchlist,
            commands::refresh_watchlist_episodes,
            commands::get_preferences,
            commands::save_preferences,
            commands::get_player_capabilities,
            commands::register_proxy_stream,
            commands::register_proxy_subtitle,
            commands::mpv_load,
            commands::mpv_set_paused,
            commands::mpv_seek,
            commands::mpv_set_volume,
            commands::mpv_set_speed,
            commands::mpv_select_subtitle,
            commands::mpv_add_subtitle,
            commands::mpv_stop,
            commands::mpv_get_state,
            commands::discover_tv_devices,
            commands::pair_tv_device,
            commands::get_paired_devices,
            commands::remove_paired_device,
            commands::sync_with_tv,
            commands::send_source_url_to_tv,
        ])
        .run(tauri::generate_context!())
        .expect("error while running AtrisStream Desktop");
}
