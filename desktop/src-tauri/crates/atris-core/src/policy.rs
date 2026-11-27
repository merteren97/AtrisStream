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
}
