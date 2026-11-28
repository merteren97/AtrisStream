use crate::models::{Episode, WatchProgress, WatchlistItem};
use std::cmp::Reverse;

/// Policy constants and completion heuristics for watch progress and Continue Watching.
pub struct WatchProgressPolicy;

impl WatchProgressPolicy {
    pub const MIN_RESUME_POSITION_MS: i64 = 10_000;

    /// Determines if playback should be considered completed using the 3-tier
    /// duration-aware credits heuristic matching Android's `UserModels.kt`.
    pub fn is_playback_completed(position_ms: i64, duration_ms: i64, is_ended: bool) -> bool {
        if is_ended {
            return true;
        }
        if duration_ms <= 0 || position_ms <= 0 {
            return false;
        }
        let fraction = (position_ms as f32) / (duration_ms as f32);
        let remaining_ms = (duration_ms - position_ms).max(0);
        if duration_ms < 30 * 60_000 {
            fraction >= 0.88 || (remaining_ms <= 150_000 && fraction >= 0.80)
        } else if duration_ms < 75 * 60_000 {
            fraction >= 0.90 || (remaining_ms <= 600_000 && fraction >= 0.80)
        } else {
            fraction >= 0.92 || (remaining_ms <= 720_000 && fraction >= 0.85)
        }
    }

    pub fn build_watch_key(
        source_id: &str,
        media_id: &str,
        season_number: Option<i32>,
        episode_number: Option<i32>,
    ) -> String {
        match (season_number, episode_number) {
            (Some(s), Some(e)) => format!("{source_id}:{media_id}:s{s}:e{e}"),
            _ => format!("{source_id}:{media_id}"),
        }
    }
}

/// Policy for tracking newly released episodes on saved Watchlist series/anime.
pub struct WatchlistTrackingPolicy;

impl WatchlistTrackingPolicy {
    pub fn evaluate_watchlist_update(
        item: &WatchlistItem,
        released_episodes: &[Episode],
        progress_list: &[WatchProgress],
        now_ms: i64,
    ) -> WatchlistItem {
        let mut updated = item.clone();
        updated.last_checked_at = now_ms;

        let mut released: Vec<&Episode> = released_episodes
            .iter()
            .filter(|ep| ep.is_released)
            .collect();
        released.sort_by_key(|ep| (ep.season_number, ep.episode_number));

        let released_count = released.len() as i32;
        if released_count == 0 {
            return updated;
        }

        // Recalculate last_watched from highest completed progress
        if let Some(highest_completed) = progress_list
            .iter()
            .filter(|p| p.is_completed)
            .max_by_key(|p| (p.season_number.unwrap_or(1), p.episode_number.unwrap_or(0)))
        {
            updated.last_watched_season = highest_completed.season_number.or(Some(1));
            updated.last_watched_episode = highest_completed.episode_number;
        } else if !progress_list.is_empty() {
            updated.last_watched_season = None;
            updated.last_watched_episode = None;
        }

        let latest = released[released.len() - 1];

        if updated.known_episode_count == 0 {
            updated.known_episode_count = released_count;
            updated.latest_available_season = Some(latest.season_number);
            updated.latest_available_episode = Some(latest.episode_number);
            updated.latest_episode_count = released_count;
            updated.has_new_episode = false;
            return updated;
        }

        if released_count > updated.known_episode_count {
            let latest_key = (latest.season_number, latest.episode_number);
            let latest_already_completed = match (
                updated.last_watched_season,
                updated.last_watched_episode,
            ) {
                (Some(s), Some(e)) => (s, e) >= latest_key,
                (None, Some(e)) => (1, e) >= latest_key,
                _ => false,
            };

            if latest_already_completed {
                updated.known_episode_count = updated.known_episode_count.max(released_count);
                updated.latest_available_season = Some(latest.season_number);
                updated.latest_available_episode = Some(latest.episode_number);
                updated.latest_episode_count = released_count;
                updated.has_new_episode = false;
                return updated;
            }

            let none_watched = updated.last_watched_episode.is_none()
                && !progress_list.iter().any(|p| p.is_completed);

            let previous_finished = if released.len() >= 2 {
                let prev = released[released.len() - 2];
                let prev_key = (prev.season_number, prev.episode_number);
                let watched_ge_prev = match (
                    updated.last_watched_season,
                    updated.last_watched_episode,
                ) {
                    (Some(s), Some(e)) => (s, e) >= prev_key,
                    (None, Some(e)) => (1, e) >= prev_key,
                    _ => false,
                };
                let progress_has_prev = progress_list.iter().any(|p| {
                    p.is_completed
                        && (p.season_number.unwrap_or(1), p.episode_number.unwrap_or(0)) >= prev_key
                });
                watched_ge_prev || progress_has_prev
            } else {
                true
            };

            if none_watched || previous_finished {
                updated.has_new_episode = true;
                updated.latest_available_season = Some(latest.season_number);
                updated.latest_available_episode = Some(latest.episode_number);
                updated.latest_episode_count = released_count;
            }
        } else {
            updated.latest_episode_count = updated.latest_episode_count.max(released_count);
            if updated.latest_available_episode.is_none() {
                updated.latest_available_season = Some(latest.season_number);
                updated.latest_available_episode = Some(latest.episode_number);
            }
        }

        updated
    }

    pub fn on_episode_marked_completed(
        item: &WatchlistItem,
        season: Option<i32>,
        episode: Option<i32>,
        released_count: Option<i32>,
        now_ms: i64,
    ) -> WatchlistItem {
        let mut updated = item.clone();

        if let Some(ep) = episode {
            let s = season.unwrap_or(1);
            let cur = (
                updated.last_watched_season.unwrap_or(0),
                updated.last_watched_episode.unwrap_or(0),
            );
            if (s, ep) >= cur {
                updated.last_watched_season = Some(s);
                updated.last_watched_episode = Some(ep);
            }
        }

        if let Some(rc) = released_count {
            updated.known_episode_count = updated.known_episode_count.max(rc);
            updated.latest_episode_count = updated.latest_episode_count.max(rc);
        }

        if updated.has_new_episode {
            let completed_latest = match (
                updated.latest_available_season,
                updated.latest_available_episode,
                season,
                episode,
            ) {
                (Some(ls), Some(le), Some(s), Some(e)) => (s, e) >= (ls, le),
                (Some(ls), Some(le), None, Some(e)) => (1, e) >= (ls, le),
                (None, Some(le), _, Some(e)) => e >= le,
                (_, None, _, _) => true,
                _ => false,
            };
            if completed_latest {
                updated.has_new_episode = false;
                updated.known_episode_count =
                    updated.known_episode_count.max(updated.latest_episode_count);
            }
        }

        updated.last_checked_at = updated.last_checked_at.max(now_ms);
        updated
    }

    pub fn on_episode_unmarked(
        item: &WatchlistItem,
        remaining_progress: &[WatchProgress],
        now_ms: i64,
    ) -> WatchlistItem {
        let mut updated = item.clone();

        if let Some(highest_completed) = remaining_progress
            .iter()
            .filter(|p| p.is_completed)
            .max_by_key(|p| (p.season_number.unwrap_or(1), p.episode_number.unwrap_or(0)))
        {
            updated.last_watched_season = highest_completed.season_number.or(Some(1));
            updated.last_watched_episode = highest_completed.episode_number;
        } else {
            updated.last_watched_season = None;
            updated.last_watched_episode = None;
        }

        if let Some(latest_ep) = updated.latest_available_episode {
            let latest_key = (updated.latest_available_season.unwrap_or(1), latest_ep);
            let watched_ge_latest = match (updated.last_watched_season, updated.last_watched_episode) {
                (Some(s), Some(e)) => (s, e) >= latest_key,
                (None, Some(e)) => (1, e) >= latest_key,
                _ => false,
            };
            updated.has_new_episode =
                updated.latest_episode_count > updated.known_episode_count && !watched_ge_latest;
        }

        updated.last_checked_at = updated.last_checked_at.max(now_ms);
        updated
    }

    pub fn sort_watchlist_with_priority(items: &[WatchlistItem]) -> Vec<WatchlistItem> {
        let mut sorted = items.to_vec();
        sorted.sort_by(|a, b| {
            (!a.has_new_episode, Reverse(a.added_at))
                .cmp(&(!b.has_new_episode, Reverse(b.added_at)))
        });
        sorted
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ended_always_completed() {
        assert!(WatchProgressPolicy::is_playback_completed(1_000, 100_000, true));
        assert!(WatchProgressPolicy::is_playback_completed(0, 0, true));
    }

    #[test]
    fn invalid_or_zero_duration_not_completed() {
        assert!(!WatchProgressPolicy::is_playback_completed(10_000, 0, false));
        assert!(!WatchProgressPolicy::is_playback_completed(0, 100_000, false));
    }

    #[test]
    fn short_episode_tier_under_30_min() {
        let dur = 24 * 60_000; // 24m anime
        // 88% -> completed
        assert!(WatchProgressPolicy::is_playback_completed((dur as f64 * 0.88) as i64 + 1, dur, false));
        // 2m remaining (<= 150s) and >= 80% -> completed
        assert!(WatchProgressPolicy::is_playback_completed(dur - 120_000, dur, false));
        // 50% -> not completed
        assert!(!WatchProgressPolicy::is_playback_completed(dur / 2, dur, false));
    }

    #[test]
    fn medium_episode_tier_30_to_75_min() {
        let dur = 45 * 60_000; // 45m drama
        // 90% -> completed
        assert!(WatchProgressPolicy::is_playback_completed((dur as f64 * 0.90) as i64 + 1, dur, false));
        // 8m remaining (<= 600s) and >= 80% -> completed
        assert!(WatchProgressPolicy::is_playback_completed(dur - 480_000, dur, false));
        // 75% -> not completed
        assert!(!WatchProgressPolicy::is_playback_completed((dur as f64 * 0.75) as i64, dur, false));
    }

    #[test]
    fn long_movie_tier_75_plus_min() {
        let dur = 120 * 60_000; // 2h movie
        // 92% -> completed
        assert!(WatchProgressPolicy::is_playback_completed((dur as f64 * 0.92) as i64 + 1, dur, false));
        // 10m remaining (<= 720s) and >= 85% -> completed
        assert!(WatchProgressPolicy::is_playback_completed(dur - 600_000, dur, false));
        // 82% -> not completed
        assert!(!WatchProgressPolicy::is_playback_completed((dur as f64 * 0.82) as i64, dur, false));
    }

    #[test]
    fn watch_key_formatting() {
        assert_eq!(
            WatchProgressPolicy::build_watch_key("src", "550", None, None),
            "src:550"
        );
        assert_eq!(
            WatchProgressPolicy::build_watch_key("src", "1399", Some(1), Some(3)),
            "src:1399:s1:e3"
        );
    }

    fn make_ep(s: i32, e: i32, is_released: bool) -> Episode {
        Episode {
            id: format!("s{s}e{e}"),
            season_number: s,
            episode_number: e,
            title: format!("Ep {e}"),
            overview: None,
            thumbnail_url: None,
            runtime_minutes: None,
            is_released,
        }
    }

    fn make_watchlist(known: i32) -> WatchlistItem {
        WatchlistItem {
            media_id: "solo-leveling".into(),
            source_id: "builtin_reanime".into(),
            r#type: crate::models::MediaType::ANIME,
            title: "Solo Leveling".into(),
            poster_url: None,
            year: Some(2024),
            rating: Some(8.7),
            added_at: 1000,
            last_watched_season: None,
            last_watched_episode: None,
            known_episode_count: known,
            latest_available_season: None,
            latest_available_episode: None,
            latest_episode_count: 0,
            has_new_episode: false,
            last_checked_at: 0,
        }
    }

    #[test]
    fn watchlist_policy_initializes_without_false_positive_and_ignores_unreleased() {
        let item = make_watchlist(0);
        let eps = vec![
            make_ep(1, 1, true),
            make_ep(1, 2, true),
            make_ep(1, 3, false),
        ];
        let updated = WatchlistTrackingPolicy::evaluate_watchlist_update(&item, &eps, &[], 5000);
        assert_eq!(updated.known_episode_count, 2);
        assert_eq!(updated.latest_available_episode, Some(2));
        assert_eq!(updated.latest_episode_count, 2);
        assert!(!updated.has_new_episode);
    }

    #[test]
    fn watchlist_policy_flags_new_episode_and_clears_on_completion() {
        let mut item = make_watchlist(2);
        item.last_watched_season = Some(1);
        item.last_watched_episode = Some(2);

        let eps = vec![make_ep(1, 1, true), make_ep(1, 2, true), make_ep(1, 3, true)];
        let flagged = WatchlistTrackingPolicy::evaluate_watchlist_update(&item, &eps, &[], 6000);
        assert!(flagged.has_new_episode);
        assert_eq!(flagged.latest_available_season, Some(1));
        assert_eq!(flagged.latest_available_episode, Some(3));
        assert_eq!(flagged.latest_episode_count, 3);

        let cleared = WatchlistTrackingPolicy::on_episode_marked_completed(
            &flagged,
            Some(1),
            Some(3),
            Some(3),
            7000,
        );
        assert!(!cleared.has_new_episode);
        assert_eq!(cleared.last_watched_episode, Some(3));
        assert_eq!(cleared.known_episode_count, 3);
    }

    #[test]
    fn watchlist_policy_sorts_new_episodes_first() {
        let a = make_watchlist(2);
        let mut b = make_watchlist(2);
        b.media_id = "frieren".into();
        b.added_at = 500;
        b.has_new_episode = true;

        let sorted = WatchlistTrackingPolicy::sort_watchlist_with_priority(&[a, b]);
        assert_eq!(sorted[0].media_id, "frieren");
        assert_eq!(sorted[1].media_id, "solo-leveling");
    }
}
