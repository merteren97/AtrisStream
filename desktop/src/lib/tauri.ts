import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  AppPreferences,
  DiscoveredDevice,
  Episode,
  HomeSection,
  InstalledSource,
  MediaCard,
  MediaDetails,
  MediaType,
  MpvPlaybackState,
  PagedResult,
  PairedDevice,
  PlayerCapabilities,
  SourceHealthResult,
  SourceSearchResult,
  StreamResult,
  SubtitleAppearance,
  SubtitleFormat,
  SubtitleTrack,
  SyncSummary,
  WatchlistItem,
  WatchProgress,
} from "../types/models";

export const api = {
  // Sources
  getSources: () => invoke<InstalledSource[]>("get_sources"),
  addSourceFromUrl: (url: string) =>
    invoke<InstalledSource[]>("add_source_from_url", { url }),
  setSourceEnabled: (id: string, enabled: boolean) =>
    invoke<void>("set_source_enabled", { id, enabled }),
  removeSource: (id: string) => invoke<boolean>("remove_source", { id }),
  testSource: (id: string) => invoke<SourceHealthResult>("test_source", { id }),

  // Media Catalog
  getHome: (sourceId?: string) =>
    invoke<HomeSection[]>("get_home", { sourceId: sourceId ?? null }),
  browseMedia: (sourceId: string, mediaType: MediaType, page: number) =>
    invoke<PagedResult<MediaCard>>("browse_media", {
      sourceId,
      mediaType,
      page,
    }),
  searchMedia: (query: string, page: number, sourceId?: string | null) =>
    invoke<SourceSearchResult[]>("search_media", {
      query,
      page,
      sourceId: sourceId ?? null,
    }),
  getDetails: (sourceId: string, mediaId: string, mediaType: MediaType) =>
    invoke<MediaDetails>("get_details", { sourceId, mediaId, mediaType }),
  getEpisodes: (sourceId: string, mediaId: string, season: number) =>
    invoke<Episode[]>("get_episodes", { sourceId, mediaId, season }),
  getStreams: (
    sourceId: string,
    mediaId: string,
    mediaType: MediaType,
    season?: number | null,
    episode?: number | null
  ) =>
    invoke<StreamResult>("get_streams", {
      sourceId,
      mediaId,
      mediaType,
      season: season ?? null,
      episode: episode ?? null,
    }),

  // Watch Progress
  getContinueWatching: (limit = 20) =>
    invoke<WatchProgress[]>("get_continue_watching", { limit }),
  getLatestProgress: (sourceId: string, mediaId: string) =>
    invoke<WatchProgress | null>("get_latest_progress", { sourceId, mediaId }),
  getAllProgress: (sourceId: string, mediaId: string) =>
    invoke<WatchProgress[]>("get_all_progress", { sourceId, mediaId }),
  saveWatchProgress: (progress: WatchProgress, isEnded = false) =>
    invoke<void>("save_watch_progress", { progress, isEnded }),
  markWatchProgressCompleted: (
    sourceId: string,
    mediaId: string,
    seasonNumber?: number | null,
    episodeNumber?: number | null
  ) =>
    invoke<void>("mark_watch_progress_completed", {
      sourceId,
      mediaId,
      seasonNumber: seasonNumber ?? null,
      episodeNumber: episodeNumber ?? null,
    }),
  deleteWatchProgress: (
    sourceId: string,
    mediaId: string,
    seasonNumber?: number | null,
    episodeNumber?: number | null
  ) =>
    invoke<void>("delete_watch_progress", {
      sourceId,
      mediaId,
      seasonNumber: seasonNumber ?? null,
      episodeNumber: episodeNumber ?? null,
    }),
  setEpisodeCompleted: (params: {
    sourceId: string;
    mediaId: string;
    mediaType: MediaType;
    title: string;
    posterUrl?: string | null;
    seasonNumber?: number | null;
    episodeNumber?: number | null;
    completed: boolean;
    releasedCount?: number | null;
  }) =>
    invoke<void>("set_episode_completed", {
      sourceId: params.sourceId,
      mediaId: params.mediaId,
      mediaType: params.mediaType,
      title: params.title,
      posterUrl: params.posterUrl ?? null,
      seasonNumber: params.seasonNumber ?? null,
      episodeNumber: params.episodeNumber ?? null,
      completed: params.completed,
      releasedCount: params.releasedCount ?? null,
    }),

  // Watchlist
  getWatchlist: () => invoke<WatchlistItem[]>("get_watchlist"),
  isSavedToWatchlist: (sourceId: string, mediaId: string) =>
    invoke<boolean>("is_saved_to_watchlist", { sourceId, mediaId }),
  toggleWatchlist: (item: WatchlistItem) =>
    invoke<boolean>("toggle_watchlist", { item }),
  refreshWatchlistEpisodes: () =>
    invoke<WatchlistItem[]>("refresh_watchlist_episodes"),

  // Preferences
  getPreferences: () => invoke<AppPreferences>("get_preferences"),
  savePreferences: (prefs: AppPreferences) =>
    invoke<void>("save_preferences", { prefs }),

  // Player Capabilities & Proxy
  getPlayerCapabilities: () =>
    invoke<PlayerCapabilities>("get_player_capabilities"),
  registerProxyStream: (url: string, headers: Record<string, string>) =>
    invoke<string>("register_proxy_stream", { url, headers }),
  registerProxySubtitle: (
    url: string,
    headers: Record<string, string>,
    format: SubtitleFormat
  ) => invoke<string>("register_proxy_subtitle", { url, headers, format }),

  // libmpv Controls
  mpvLoad: (params: {
    url: string;
    headers: Record<string, string>;
    resumePositionMs: number;
    subtitles: SubtitleTrack[];
    subtitleAppearance: SubtitleAppearance;
  }) => invoke<void>("mpv_load", params),
  mpvSetPaused: (paused: boolean) => invoke<void>("mpv_set_paused", { paused }),
  mpvSeek: (positionMs: number) => invoke<void>("mpv_seek", { positionMs }),
  mpvSetVolume: (volume: number) => invoke<void>("mpv_set_volume", { volume }),
  mpvSetSpeed: (speed: number) => invoke<void>("mpv_set_speed", { speed }),
  mpvSelectSubtitle: (trackId: number | null) =>
    invoke<void>("mpv_select_subtitle", { trackId }),
  mpvAddSubtitle: (url: string, headers: Record<string, string>) =>
    invoke<void>("mpv_add_subtitle", { url, headers }),
  mpvStop: () => invoke<void>("mpv_stop"),
  mpvGetState: () => invoke<MpvPlaybackState>("mpv_get_state"),
  onMpvState: (handler: (state: MpvPlaybackState) => void): Promise<UnlistenFn> =>
    listen<MpvPlaybackState>("mpv-state", (event) => handler(event.payload)),

  // LAN TV Sync
  discoverTvDevices: () => invoke<DiscoveredDevice[]>("discover_tv_devices"),
  pairTvDevice: (host: string, port: number, pin: string) =>
    invoke<PairedDevice>("pair_tv_device", { host, port, pin }),
  getPairedDevices: () => invoke<PairedDevice[]>("get_paired_devices"),
  removePairedDevice: (id: string) =>
    invoke<void>("remove_paired_device", { id }),
  syncWithTv: (deviceId: string) =>
    invoke<SyncSummary>("sync_with_tv", { deviceId }),
  sendSourceUrlToTv: (host: string, port: number, url: string) =>
    invoke<string>("send_source_url_to_tv", { host, port, url }),
};
