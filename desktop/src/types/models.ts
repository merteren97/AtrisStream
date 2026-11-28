export type MediaType = "MOVIE" | "TV" | "ANIME" | "LIVE";
export type StreamFormat = "HLS" | "DASH" | "MP4" | "UNKNOWN";
export type SubtitleFormat = "VTT" | "SRT" | "ASS";
export type SourceDriverType = "BUILTIN" | "JSON_MANIFEST" | "JS_PLUGIN";
export type PlayerEngine = "libmpv" | "webview";

export interface MediaCard {
  id: string;
  sourceId: string;
  title: string;
  posterUrl?: string | null;
  backdropUrl?: string | null;
  type: MediaType;
  year?: number | null;
  rating?: number | null;
  subtitle?: string | null;
}

export interface HomeSection {
  title: string;
  items: MediaCard[];
}

export interface PagedResult<T> {
  items: T[];
  page: number;
  hasNextPage: boolean;
}

export interface Episode {
  id: string;
  seasonNumber: number;
  episodeNumber: number;
  title: string;
  overview?: string | null;
  thumbnailUrl?: string | null;
  runtimeMinutes?: number | null;
  isReleased?: boolean;
}

export interface Season {
  seasonNumber: number;
  name: string;
  episodeCount?: number | null;
  posterUrl?: string | null;
  episodes: Episode[];
}

export interface MediaDetails {
  id: string;
  sourceId: string;
  title: string;
  type: MediaType;
  description?: string | null;
  posterUrl?: string | null;
  backdropUrl?: string | null;
  year?: number | null;
  rating?: number | null;
  genres: string[];
  cast: string[];
  runtimeMinutes?: number | null;
  seasons: Season[];
}

export interface StreamSource {
  url: string;
  quality: string;
  format: StreamFormat;
  serverName: string;
  audioLang?: string | null;
  headers: Record<string, string>;
}

export interface SubtitleTrack {
  url: string;
  language: string;
  label: string;
  format: SubtitleFormat;
  headers: Record<string, string>;
  isExternal: boolean;
}

export interface StreamResult {
  streams: StreamSource[];
  subtitles: SubtitleTrack[];
}

export interface InstalledSource {
  id: string;
  name: string;
  version: string;
  baseUrl: string;
  manifestUrl?: string | null;
  driverType: SourceDriverType;
  enabled: boolean;
  isBuiltIn: boolean;
  supportedTypes: MediaType[];
  languages: string[];
  rawConfig?: string | null;
  addedAt: number;
}

export interface SourceHealthResult {
  ok: boolean;
  latencyMs: number;
  itemCount: number;
  error?: string | null;
}

export interface SourceSearchResult {
  sourceId: string;
  sourceName: string;
  result: PagedResult<MediaCard>;
}

export interface WatchProgress {
  mediaId: string;
  sourceId: string;
  type: MediaType;
  title: string;
  posterUrl?: string | null;
  seasonNumber?: number | null;
  episodeNumber?: number | null;
  positionMs: number;
  durationMs: number;
  updatedAt: number;
  isCompleted: boolean;
}

export interface WatchlistItem {
  mediaId: string;
  sourceId: string;
  type: MediaType;
  title: string;
  posterUrl?: string | null;
  year?: number | null;
  rating?: number | null;
  addedAt: number;
  lastWatchedSeason?: number | null;
  lastWatchedEpisode?: number | null;
  knownEpisodeCount?: number;
  latestAvailableSeason?: number | null;
  latestAvailableEpisode?: number | null;
  latestEpisodeCount?: number;
  hasNewEpisode?: boolean;
  lastCheckedAt?: number;
}

export interface SubtitleAppearance {
  fontSizeSp: number;
  textColorArgb: number;
  backgroundColorArgb: number;
  edgeType: number;
}

export interface AppPreferences {
  playerEngine: PlayerEngine;
  hardwareDecoding: boolean;
  preferredQuality: string;
  preferredSubtitleLang: string;
  language: "tr" | "en";
  subtitleAppearance: SubtitleAppearance;
  primaryMovieSourceId?: string | null;
  primaryTvSourceId?: string | null;
  primaryAnimeSourceId?: string | null;
  trackSeriesNewEpisodes?: boolean;
}

export interface PlayerCapabilities {
  libmpvAvailable: boolean;
  libmpvVersion?: string | null;
  installHint?: string | null;
  libmpvPath?: string | null;
  webviewProxyPort?: number;
}

export interface MpvPlaybackState {
  active?: boolean;
  paused: boolean;
  positionMs: number;
  durationMs: number;
  volume: number;
  speed: number;
  buffering: boolean;
  eofReached: boolean;
  activeSubtitleId?: number | null;
  error?: string | null;
}

export interface DiscoveredDevice {
  name: string;
  host: string;
  port: number;
  version: string;
}

export interface PairedDevice {
  id: string;
  name: string;
  host: string;
  port: number;
  token: string;
  lastSyncedAt?: number | null;
}

export interface SyncSummary {
  pushedSources: number;
  pulledSources: number;
  mergedProgress: number;
  mergedWatchlist: number;
}

export interface PlayerSession {
  sourceId: string;
  mediaId: string;
  mediaType: MediaType;
  title: string;
  posterUrl?: string | null;
  seasonNumber?: number | null;
  episodeNumber?: number | null;
  episodeTitle?: string | null;
  resumePositionMs: number;
  episodes?: Episode[];
}
