use atris_core::{
    AppPreferences, InstalledSource, MediaType, PairedDevice, SourceDriverType, SyncPayload,
    SyncSummary, WatchProgress, WatchProgressPolicy, WatchlistItem,
};
use rusqlite::{params, Connection, OptionalExtension, Row};
use std::collections::HashSet;
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("storage mutex poisoned")]
    LockPoisoned,
}

#[derive(Clone)]
pub struct AtrisStorage {
    conn: Arc<Mutex<Connection>>,
}

impl AtrisStorage {
    pub fn open(path: &Path) -> Result<Self, StorageError> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             PRAGMA foreign_keys = ON;",
        )?;
        let storage = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        storage.init_schema()?;
        Ok(storage)
    }

    pub fn open_in_memory() -> Result<Self, StorageError> {
        let conn = Connection::open_in_memory()?;
        let storage = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        storage.init_schema()?;
        Ok(storage)
    }

    fn lock(&self) -> Result<MutexGuard<'_, Connection>, StorageError> {
        self.conn.lock().map_err(|_| StorageError::LockPoisoned)
    }

    fn init_schema(&self) -> Result<(), StorageError> {
        let conn = self.lock()?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS installed_sources (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                base_url TEXT NOT NULL,
                manifest_url TEXT,
                driver_type TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                is_built_in INTEGER NOT NULL DEFAULT 0,
                supported_types TEXT NOT NULL,
                languages TEXT NOT NULL,
                raw_config TEXT,
                added_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS watch_progress (
                key_id TEXT PRIMARY KEY,
                media_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                poster_url TEXT,
                season_number INTEGER,
                episode_number INTEGER,
                position_ms INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                is_completed INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_watch_progress_media
                ON watch_progress (source_id, media_id, updated_at DESC);

            CREATE TABLE IF NOT EXISTS watchlist (
                key_id TEXT PRIMARY KEY,
                media_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                poster_url TEXT,
                year INTEGER,
                rating REAL,
                added_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS preferences (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS paired_devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                token TEXT NOT NULL,
                last_synced_at INTEGER
            );",
        )?;
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------------

    pub fn get_all_sources(&self) -> Result<Vec<InstalledSource>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, version, base_url, manifest_url, driver_type, enabled, is_built_in,
                    supported_types, languages, raw_config, added_at
             FROM installed_sources
             ORDER BY is_built_in DESC, added_at ASC",
        )?;
        let rows = stmt.query_map([], map_source_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn get_enabled_sources(&self) -> Result<Vec<InstalledSource>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, version, base_url, manifest_url, driver_type, enabled, is_built_in,
                    supported_types, languages, raw_config, added_at
             FROM installed_sources
             WHERE enabled = 1
             ORDER BY is_built_in DESC, added_at ASC",
        )?;
        let rows = stmt.query_map([], map_source_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn get_source_by_id(&self, id: &str) -> Result<Option<InstalledSource>, StorageError> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT id, name, version, base_url, manifest_url, driver_type, enabled, is_built_in,
                    supported_types, languages, raw_config, added_at
             FROM installed_sources WHERE id = ?1 LIMIT 1",
            params![id],
            map_source_row,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn upsert_source(&self, src: &InstalledSource) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let driver_str = driver_type_to_str(src.driver_type);
        let types_json = serde_json::to_string(&src.supported_types)?;
        let langs_json = serde_json::to_string(&src.languages)?;
        conn.execute(
            "INSERT OR REPLACE INTO installed_sources
             (id, name, version, base_url, manifest_url, driver_type, enabled, is_built_in,
              supported_types, languages, raw_config, added_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                src.id,
                src.name,
                src.version,
                src.base_url,
                src.manifest_url,
                driver_str,
                if src.enabled { 1 } else { 0 },
                if src.is_built_in { 1 } else { 0 },
                types_json,
                langs_json,
                src.raw_config,
                src.added_at,
            ],
        )?;
        Ok(())
    }

    pub fn set_source_enabled(&self, id: &str, enabled: bool) -> Result<(), StorageError> {
        let conn = self.lock()?;
        conn.execute(
            "UPDATE installed_sources SET enabled = ?1 WHERE id = ?2",
            params![if enabled { 1 } else { 0 }, id],
        )?;
        Ok(())
    }

    pub fn delete_source(&self, id: &str) -> Result<(), StorageError> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM installed_sources WHERE id = ?1", params![id])?;
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Watch Progress
    // -------------------------------------------------------------------------

    pub fn get_continue_watching(&self, limit: u32) -> Result<Vec<WatchProgress>, StorageError> {
        let conn = self.lock()?;
        let fetch_limit = (limit.max(1) as i64) * 2;
        let mut stmt = conn.prepare(
            "SELECT wp.media_id, wp.source_id, wp.type, wp.title, wp.poster_url,
                    wp.season_number, wp.episode_number, wp.position_ms, wp.duration_ms,
                    wp.updated_at, wp.is_completed
             FROM watch_progress wp
             INNER JOIN (
                 SELECT source_id, media_id, MAX(updated_at) AS max_updated_at
                 FROM watch_progress
                 WHERE is_completed = 0
                   AND position_ms >= ?1
                   AND type != 'LIVE'
                 GROUP BY source_id, media_id
             ) latest
             ON wp.source_id = latest.source_id
                AND wp.media_id = latest.media_id
                AND wp.updated_at = latest.max_updated_at
             WHERE wp.is_completed = 0
               AND wp.position_ms >= ?1
               AND wp.type != 'LIVE'
             ORDER BY wp.updated_at DESC
             LIMIT ?2",
        )?;
        let rows = stmt.query_map(
            params![WatchProgressPolicy::MIN_RESUME_POSITION_MS, fetch_limit],
            map_watch_progress_row,
        )?;
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        for row in rows {
            let item = row?;
            if seen.insert((item.source_id.clone(), item.media_id.clone())) {
                out.push(item);
                if out.len() >= limit as usize {
                    break;
                }
            }
        }
        Ok(out)
    }

    pub fn get_latest_progress_for_media(
        &self,
        source_id: &str,
        media_id: &str,
    ) -> Result<Option<WatchProgress>, StorageError> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT media_id, source_id, type, title, poster_url, season_number, episode_number,
                    position_ms, duration_ms, updated_at, is_completed
             FROM watch_progress
             WHERE source_id = ?1 AND media_id = ?2
             ORDER BY updated_at DESC
             LIMIT 1",
            params![source_id, media_id],
            map_watch_progress_row,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn get_all_progress_for_media(
        &self,
        source_id: &str,
        media_id: &str,
    ) -> Result<Vec<WatchProgress>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT media_id, source_id, type, title, poster_url, season_number, episode_number,
                    position_ms, duration_ms, updated_at, is_completed
             FROM watch_progress
             WHERE source_id = ?1 AND media_id = ?2
             ORDER BY updated_at DESC",
        )?;
        let rows = stmt.query_map(params![source_id, media_id], map_watch_progress_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn upsert_watch_progress(&self, progress: &WatchProgress) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let key_id = WatchProgressPolicy::build_watch_key(
            &progress.source_id,
            &progress.media_id,
            progress.season_number,
            progress.episode_number,
        );
        conn.execute(
            "INSERT OR REPLACE INTO watch_progress
             (key_id, media_id, source_id, type, title, poster_url, season_number, episode_number,
              position_ms, duration_ms, updated_at, is_completed)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
            params![
                key_id,
                progress.media_id,
                progress.source_id,
                media_type_to_str(progress.r#type),
                progress.title,
                progress.poster_url,
                progress.season_number,
                progress.episode_number,
                progress.position_ms,
                progress.duration_ms,
                progress.updated_at,
                if progress.is_completed { 1 } else { 0 },
            ],
        )?;
        Ok(())
    }

    pub fn mark_watch_progress_completed(
        &self,
        source_id: &str,
        media_id: &str,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let key_id = WatchProgressPolicy::build_watch_key(source_id, media_id, season, episode);
        let now = now_ms();
        conn.execute(
            "UPDATE watch_progress SET is_completed = 1, updated_at = ?1 WHERE key_id = ?2",
            params![now, key_id],
        )?;
        Ok(())
    }

    pub fn delete_watch_progress(
        &self,
        source_id: &str,
        media_id: &str,
        season: Option<i32>,
        episode: Option<i32>,
    ) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let key_id = WatchProgressPolicy::build_watch_key(source_id, media_id, season, episode);
        conn.execute("DELETE FROM watch_progress WHERE key_id = ?1", params![key_id])?;
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Watchlist
    // -------------------------------------------------------------------------

    pub fn get_watchlist(&self) -> Result<Vec<WatchlistItem>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT media_id, source_id, type, title, poster_url, year, rating, added_at
             FROM watchlist
             ORDER BY added_at DESC",
        )?;
        let rows = stmt.query_map([], map_watchlist_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn is_saved_to_watchlist(
        &self,
        source_id: &str,
        media_id: &str,
    ) -> Result<bool, StorageError> {
        let conn = self.lock()?;
        let key_id = format!("{source_id}:{media_id}");
        let count: i64 = conn.query_row(
            "SELECT COUNT(1) FROM watchlist WHERE key_id = ?1",
            params![key_id],
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    pub fn upsert_watchlist_item(&self, item: &WatchlistItem) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let key_id = format!("{}:{}", item.source_id, item.media_id);
        conn.execute(
            "INSERT OR REPLACE INTO watchlist
             (key_id, media_id, source_id, type, title, poster_url, year, rating, added_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                key_id,
                item.media_id,
                item.source_id,
                media_type_to_str(item.r#type),
                item.title,
                item.poster_url,
                item.year,
                item.rating,
                item.added_at,
            ],
        )?;
        Ok(())
    }

    pub fn remove_from_watchlist(
        &self,
        source_id: &str,
        media_id: &str,
    ) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let key_id = format!("{source_id}:{media_id}");
        conn.execute("DELETE FROM watchlist WHERE key_id = ?1", params![key_id])?;
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Preferences
    // -------------------------------------------------------------------------

    pub fn get_preferences(&self) -> Result<AppPreferences, StorageError> {
        let conn = self.lock()?;
        let raw: Option<String> = conn
            .query_row(
                "SELECT json FROM preferences WHERE id = 1 LIMIT 1",
                [],
                |row| row.get(0),
            )
            .optional()?;
        match raw {
            Some(s) => Ok(serde_json::from_str(&s)?),
            None => Ok(AppPreferences::default()),
        }
    }

    pub fn save_preferences(&self, prefs: &AppPreferences) -> Result<(), StorageError> {
        let conn = self.lock()?;
        let raw = serde_json::to_string(prefs)?;
        conn.execute(
            "INSERT OR REPLACE INTO preferences (id, json) VALUES (1, ?1)",
            params![raw],
        )?;
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Paired Devices
    // -------------------------------------------------------------------------

    pub fn get_paired_devices(&self) -> Result<Vec<PairedDevice>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, name, host, port, token, last_synced_at
             FROM paired_devices
             ORDER BY name ASC",
        )?;
        let rows = stmt.query_map([], map_paired_device_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    pub fn upsert_paired_device(&self, dev: &PairedDevice) -> Result<(), StorageError> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT OR REPLACE INTO paired_devices (id, name, host, port, token, last_synced_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                dev.id,
                dev.name,
                dev.host,
                dev.port as i64,
                dev.token,
                dev.last_synced_at,
            ],
        )?;
        Ok(())
    }

    pub fn remove_paired_device(&self, id: &str) -> Result<(), StorageError> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM paired_devices WHERE id = ?1", params![id])?;
        Ok(())
    }

    pub fn get_paired_device(&self, id: &str) -> Result<Option<PairedDevice>, StorageError> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT id, name, host, port, token, last_synced_at
             FROM paired_devices WHERE id = ?1 LIMIT 1",
            params![id],
            map_paired_device_row,
        )
        .optional()
        .map_err(Into::into)
    }

    // -------------------------------------------------------------------------
    // LAN Sync Export / Merge
    // -------------------------------------------------------------------------

    pub fn export_sync_payload(&self) -> Result<SyncPayload, StorageError> {
        let sources = self
            .get_all_sources()?
            .into_iter()
            .filter(|s| !s.is_built_in)
            .collect();
        let watch_progress = self.get_all_watch_progress()?;
        let watchlist = self.get_watchlist()?;
        Ok(SyncPayload {
            sources,
            watch_progress,
            watchlist,
        })
    }

    pub fn merge_sync_payload(&self, remote: &SyncPayload) -> Result<SyncSummary, StorageError> {
        let mut summary = SyncSummary::default();

        // 1. Merge non-built-in sources
        for src in &remote.sources {
            if src.is_built_in {
                continue;
            }
            let existing = self.get_source_by_id(&src.id)?;
            let should_upsert = match existing {
                None => true,
                Some(ref local) if !local.is_built_in && src.added_at > local.added_at => true,
                _ => false,
            };
            if should_upsert {
                self.upsert_source(src)?;
                summary.pulled_sources += 1;
            }
        }

        // 2. Merge watch_progress (LWW by updated_at, tie-breaker is_completed)
        for wp in &remote.watch_progress {
            let key_id = WatchProgressPolicy::build_watch_key(
                &wp.source_id,
                &wp.media_id,
                wp.season_number,
                wp.episode_number,
            );
            let existing = self.get_watch_progress_by_key(&key_id)?;
            let should_upsert = match existing {
                None => true,
                Some(ref local) => {
                    wp.updated_at > local.updated_at
                        || (wp.updated_at == local.updated_at
                            && wp.is_completed
                            && !local.is_completed)
                }
            };
            if should_upsert {
                self.upsert_watch_progress(wp)?;
                summary.merged_progress += 1;
            }
        }

        // 3. Merge watchlist (union / newer added_at)
        for item in &remote.watchlist {
            let existing = self.get_watchlist_item(&item.source_id, &item.media_id)?;
            let should_upsert = match existing {
                None => true,
                Some(ref local) => item.added_at > local.added_at,
            };
            if should_upsert {
                self.upsert_watchlist_item(item)?;
                summary.merged_watchlist += 1;
            }
        }

        Ok(summary)
    }

    fn get_all_watch_progress(&self) -> Result<Vec<WatchProgress>, StorageError> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT media_id, source_id, type, title, poster_url, season_number, episode_number,
                    position_ms, duration_ms, updated_at, is_completed
             FROM watch_progress
             ORDER BY updated_at DESC",
        )?;
        let rows = stmt.query_map([], map_watch_progress_row)?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    fn get_watch_progress_by_key(
        &self,
        key_id: &str,
    ) -> Result<Option<WatchProgress>, StorageError> {
        let conn = self.lock()?;
        conn.query_row(
            "SELECT media_id, source_id, type, title, poster_url, season_number, episode_number,
                    position_ms, duration_ms, updated_at, is_completed
             FROM watch_progress WHERE key_id = ?1 LIMIT 1",
            params![key_id],
            map_watch_progress_row,
        )
        .optional()
        .map_err(Into::into)
    }

    fn get_watchlist_item(
        &self,
        source_id: &str,
        media_id: &str,
    ) -> Result<Option<WatchlistItem>, StorageError> {
        let conn = self.lock()?;
        let key_id = format!("{source_id}:{media_id}");
        conn.query_row(
            "SELECT media_id, source_id, type, title, poster_url, year, rating, added_at
             FROM watchlist WHERE key_id = ?1 LIMIT 1",
            params![key_id],
            map_watchlist_row,
        )
        .optional()
        .map_err(Into::into)
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn media_type_to_str(t: MediaType) -> &'static str {
    match t {
        MediaType::MOVIE => "MOVIE",
        MediaType::TV => "TV",
        MediaType::ANIME => "ANIME",
        MediaType::LIVE => "LIVE",
    }
}

fn str_to_media_type(s: &str) -> MediaType {
    match s.to_uppercase().as_str() {
        "TV" => MediaType::TV,
        "ANIME" => MediaType::ANIME,
        "LIVE" => MediaType::LIVE,
        _ => MediaType::MOVIE,
    }
}

fn driver_type_to_str(d: SourceDriverType) -> &'static str {
    match d {
        SourceDriverType::BUILTIN => "BUILTIN",
        SourceDriverType::JSON_MANIFEST => "JSON_MANIFEST",
        SourceDriverType::JS_PLUGIN => "JS_PLUGIN",
    }
}

fn str_to_driver_type(s: &str) -> SourceDriverType {
    match s.to_uppercase().as_str() {
        "JSON_MANIFEST" => SourceDriverType::JSON_MANIFEST,
        "JS_PLUGIN" => SourceDriverType::JS_PLUGIN,
        _ => SourceDriverType::BUILTIN,
    }
}

fn map_source_row(row: &Row<'_>) -> rusqlite::Result<InstalledSource> {
    let driver_str: String = row.get(5)?;
    let enabled_int: i64 = row.get(6)?;
    let built_in_int: i64 = row.get(7)?;
    let types_json: String = row.get(8)?;
    let langs_json: String = row.get(9)?;
    let supported_types: Vec<MediaType> = serde_json::from_str(&types_json).unwrap_or_default();
    let languages: Vec<String> = serde_json::from_str(&langs_json).unwrap_or_default();

    Ok(InstalledSource {
        id: row.get(0)?,
        name: row.get(1)?,
        version: row.get(2)?,
        base_url: row.get(3)?,
        manifest_url: row.get(4)?,
        driver_type: str_to_driver_type(&driver_str),
        enabled: enabled_int != 0,
        is_built_in: built_in_int != 0,
        supported_types,
        languages,
        raw_config: row.get(10)?,
        added_at: row.get(11)?,
    })
}

fn map_watch_progress_row(row: &Row<'_>) -> rusqlite::Result<WatchProgress> {
    let type_str: String = row.get(2)?;
    let completed_int: i64 = row.get(10)?;
    Ok(WatchProgress {
        media_id: row.get(0)?,
        source_id: row.get(1)?,
        r#type: str_to_media_type(&type_str),
        title: row.get(3)?,
        poster_url: row.get(4)?,
        season_number: row.get(5)?,
        episode_number: row.get(6)?,
        position_ms: row.get(7)?,
        duration_ms: row.get(8)?,
        updated_at: row.get(9)?,
        is_completed: completed_int != 0,
    })
}

fn map_watchlist_row(row: &Row<'_>) -> rusqlite::Result<WatchlistItem> {
    let type_str: String = row.get(2)?;
    Ok(WatchlistItem {
        media_id: row.get(0)?,
        source_id: row.get(1)?,
        r#type: str_to_media_type(&type_str),
        title: row.get(3)?,
        poster_url: row.get(4)?,
        year: row.get(5)?,
        rating: row.get(6)?,
        added_at: row.get(7)?,
    })
}

fn map_paired_device_row(row: &Row<'_>) -> rusqlite::Result<PairedDevice> {
    let port_i64: i64 = row.get(3)?;
    Ok(PairedDevice {
        id: row.get(0)?,
        name: row.get(1)?,
        host: row.get(2)?,
        port: port_i64 as u16,
        token: row.get(4)?,
        last_synced_at: row.get(5)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_ep(
        media_id: &str,
        season: i32,
        ep: i32,
        pos: i64,
        dur: i64,
        updated_at: i64,
        completed: bool,
    ) -> WatchProgress {
        WatchProgress {
            media_id: media_id.to_string(),
            source_id: "builtin_movy_sx".to_string(),
            r#type: MediaType::TV,
            title: "Breaking Bad".to_string(),
            poster_url: None,
            season_number: Some(season),
            episode_number: Some(ep),
            position_ms: pos,
            duration_ms: dur,
            updated_at,
            is_completed: completed,
        }
    }

    #[test]
    fn continue_watching_deduplicates_series_to_latest_episode() {
        let storage = AtrisStorage::open_in_memory().unwrap();
        // E1 watched at t=1000, E2 watched at t=2000 (both unfinished, >= 10s)
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 1, 300_000, 2_700_000, 1000, false))
            .unwrap();
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 2, 450_000, 2_700_000, 2000, false))
            .unwrap();

        let cw = storage.get_continue_watching(20).unwrap();
        assert_eq!(cw.len(), 1);
        assert_eq!(cw[0].episode_number, Some(2));
    }

    #[test]
    fn continue_watching_tie_breaker_deduplicates_equal_timestamps() {
        let storage = AtrisStorage::open_in_memory().unwrap();
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 1, 300_000, 2_700_000, 5000, false))
            .unwrap();
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 2, 450_000, 2_700_000, 5000, false))
            .unwrap();

        let cw = storage.get_continue_watching(20).unwrap();
        assert_eq!(cw.len(), 1);
    }

    #[test]
    fn continue_watching_excludes_completed_short_and_live() {
        let storage = AtrisStorage::open_in_memory().unwrap();
        // Completed -> excluded
        storage
            .upsert_watch_progress(&sample_ep("1", 1, 1, 2_500_000, 2_700_000, 1000, true))
            .unwrap();
        // Under 10s -> excluded
        storage
            .upsert_watch_progress(&sample_ep("2", 1, 1, 5_000, 2_700_000, 2000, false))
            .unwrap();
        // LIVE -> excluded
        storage
            .upsert_watch_progress(&WatchProgress {
                media_id: "live1".into(),
                source_id: "src".into(),
                r#type: MediaType::LIVE,
                title: "News".into(),
                poster_url: None,
                season_number: None,
                episode_number: None,
                position_ms: 60_000,
                duration_ms: 0,
                updated_at: 3000,
                is_completed: false,
            })
            .unwrap();
        // Valid -> included
        storage
            .upsert_watch_progress(&sample_ep("3", 1, 1, 25_000, 2_700_000, 4000, false))
            .unwrap();

        let cw = storage.get_continue_watching(20).unwrap();
        assert_eq!(cw.len(), 1);
        assert_eq!(cw[0].media_id, "3");
    }

    #[test]
    fn rewatch_older_episode_moves_it_to_top() {
        let storage = AtrisStorage::open_in_memory().unwrap();
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 5, 400_000, 2_700_000, 2000, false))
            .unwrap();
        // Rewatch E1 later at t=9000
        storage
            .upsert_watch_progress(&sample_ep("1396", 1, 1, 120_000, 2_700_000, 9000, false))
            .unwrap();

        let cw = storage.get_continue_watching(20).unwrap();
        assert_eq!(cw.len(), 1);
        assert_eq!(cw[0].episode_number, Some(1));
    }

    #[test]
    fn lww_sync_merge_respects_timestamps_and_completion_tie_breaker() {
        let storage = AtrisStorage::open_in_memory().unwrap();
        // Local builtin source
        storage
            .upsert_source(&InstalledSource {
                id: "builtin_movy_sx".into(),
                name: "Movy".into(),
                version: "1.0.0".into(),
                base_url: "https://www.movy.sx/tr".into(),
                manifest_url: None,
                driver_type: SourceDriverType::BUILTIN,
                enabled: true,
                is_built_in: true,
                supported_types: vec![MediaType::MOVIE],
                languages: vec!["tr".into()],
                raw_config: None,
                added_at: 100,
            })
            .unwrap();

        // Local newer progress (t=5000) and equal-timestamp unfinished (t=3000)
        storage
            .upsert_watch_progress(&sample_ep("100", 1, 1, 900_000, 2_700_000, 5000, false))
            .unwrap();
        storage
            .upsert_watch_progress(&sample_ep("200", 1, 1, 900_000, 2_700_000, 3000, false))
            .unwrap();

        let remote = SyncPayload {
            sources: vec![
                // Builtin should be ignored
                InstalledSource {
                    id: "builtin_movy_sx".into(),
                    name: "Tampered".into(),
                    version: "9.9.9".into(),
                    base_url: "https://evil.example".into(),
                    manifest_url: None,
                    driver_type: SourceDriverType::BUILTIN,
                    enabled: false,
                    is_built_in: true,
                    supported_types: vec![],
                    languages: vec![],
                    raw_config: None,
                    added_at: 99999,
                },
                // Custom source should be pulled
                InstalledSource {
                    id: "custom_json".into(),
                    name: "Custom".into(),
                    version: "1.0.0".into(),
                    base_url: "https://example.com".into(),
                    manifest_url: Some("https://example.com/src.json".into()),
                    driver_type: SourceDriverType::JSON_MANIFEST,
                    enabled: true,
                    is_built_in: false,
                    supported_types: vec![MediaType::MOVIE],
                    languages: vec!["en".into()],
                    raw_config: Some("{}".into()),
                    added_at: 2000,
                },
            ],
            watch_progress: vec![
                // Older than local (t=1000 < 5000) -> ignored
                sample_ep("100", 1, 1, 100_000, 2_700_000, 1000, false),
                // Equal timestamp, completed=true -> wins tie-breaker
                sample_ep("200", 1, 1, 2_600_000, 2_700_000, 3000, true),
                // Brand new -> merged
                sample_ep("300", 1, 1, 50_000, 2_700_000, 4000, false),
            ],
            watchlist: vec![WatchlistItem {
                media_id: "550".into(),
                source_id: "builtin_movy_sx".into(),
                r#type: MediaType::MOVIE,
                title: "Fight Club".into(),
                poster_url: None,
                year: Some(1999),
                rating: Some(8.4),
                added_at: 1000,
            }],
        };

        let summary = storage.merge_sync_payload(&remote).unwrap();
        assert_eq!(summary.pulled_sources, 1);
        assert_eq!(summary.merged_progress, 2);
        assert_eq!(summary.merged_watchlist, 1);

        let builtin = storage.get_source_by_id("builtin_movy_sx").unwrap().unwrap();
        assert_eq!(builtin.name, "Movy");
        assert_eq!(
            storage
                .get_latest_progress_for_media("builtin_movy_sx", "100")
                .unwrap()
                .unwrap()
                .position_ms,
            900_000
        );
        assert!(storage
            .get_latest_progress_for_media("builtin_movy_sx", "200")
            .unwrap()
            .unwrap()
            .is_completed);
        assert!(storage
            .is_saved_to_watchlist("builtin_movy_sx", "550")
            .unwrap());
    }
}
