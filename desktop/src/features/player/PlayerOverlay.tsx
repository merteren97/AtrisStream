import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Hls from "hls.js";
import {
  ArrowLeft,
  Gauge,
  Loader2,
  Maximize2,
  Minimize2,
  Pause,
  Play,
  Plus,
  Server,
  SkipForward,
  Subtitles,
  Volume2,
  VolumeX,
  X,
} from "lucide-react";
import { usePreferences } from "../../hooks/usePreferences";
import { api } from "../../lib/tauri";
import { useAppStore } from "../../store/appStore";
import type {
  PlayerSession,
  StreamSource,
  SubtitleTrack,
} from "../../types/models";

interface PlayerOverlayProps {
  session: PlayerSession;
}

function formatTime(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  }
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function PlayerOverlay({ session }: PlayerOverlayProps) {
  const { prefs, t } = usePreferences();
  const queryClient = useQueryClient();
  const closePlayer = useAppStore((s) => s.closePlayer);
  const openPlayer = useAppStore((s) => s.openPlayer);

  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const hideHudTimerRef = useRef<number | null>(null);

  const [hudVisible, setHudVisible] = useState(true);
  const [drawer, setDrawer] = useState<"servers" | "subtitles" | null>(null);
  const [fallbackDismissed, setFallbackDismissed] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  // Playback state (unified for both engines)
  const [paused, setPaused] = useState(false);
  const [positionMs, setPositionMs] = useState(session.resumePositionMs);
  const [durationMs, setDurationMs] = useState(0);
  const [volume, setVolume] = useState(100);
  const [muted, setMuted] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [buffering, setBuffering] = useState(true);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Selected stream & subtitles
  const [selectedStreamIdx, setSelectedStreamIdx] = useState(0);
  const [selectedSubIdx, setSelectedSubIdx] = useState<number | null>(null);
  const [extraSubtitles, setExtraSubtitles] = useState<SubtitleTrack[]>([]);
  const [proxySubUrls, setProxySubUrls] = useState<Record<number, string>>({});
  const [extSubUrl, setExtSubUrl] = useState("");

  const latestPosRef = useRef(positionMs);
  const latestDurRef = useRef(durationMs);
  latestPosRef.current = positionMs;
  latestDurRef.current = durationMs;

  const { data: capabilities } = useQuery({
    queryKey: ["playerCapabilities"],
    queryFn: api.getPlayerCapabilities,
  });

  const useLibmpv =
    prefs.playerEngine === "libmpv" && Boolean(capabilities?.libmpvAvailable);
  const showFallbackBanner =
    prefs.playerEngine === "libmpv" &&
    capabilities !== undefined &&
    !capabilities.libmpvAvailable &&
    !fallbackDismissed;

  const {
    data: streamResult,
    isLoading: loadingStreams,
    error: streamError,
  } = useQuery({
    queryKey: [
      "streams",
      session.sourceId,
      session.mediaId,
      session.mediaType,
      session.seasonNumber,
      session.episodeNumber,
    ],
    queryFn: () =>
      api.getStreams(
        session.sourceId,
        session.mediaId,
        session.mediaType,
        session.seasonNumber,
        session.episodeNumber
      ),
  });

  const streams = streamResult?.streams ?? [];
  const allSubtitles = useMemo(
    () => [...(streamResult?.subtitles ?? []), ...extraSubtitles],
    [streamResult?.subtitles, extraSubtitles]
  );

  // Pick preferred quality on initial load
  useEffect(() => {
    if (streams.length === 0) return;
    if (prefs.preferredQuality !== "Auto") {
      const idx = streams.findIndex((s) =>
        s.quality.toLowerCase().includes(prefs.preferredQuality.toLowerCase())
      );
      if (idx >= 0) {
        setSelectedStreamIdx(idx);
        return;
      }
    }
    setSelectedStreamIdx(0);
  }, [streams, prefs.preferredQuality]);

  // Auto-select preferred subtitle
  useEffect(() => {
    if (allSubtitles.length === 0 || prefs.preferredSubtitleLang === "off") {
      setSelectedSubIdx(null);
      return;
    }
    const idx = allSubtitles.findIndex((s) =>
      s.language.toLowerCase().startsWith(prefs.preferredSubtitleLang.toLowerCase())
    );
    if (idx >= 0) setSelectedSubIdx(idx);
  }, [allSubtitles, prefs.preferredSubtitleLang]);

  const activeStream: StreamSource | undefined = streams[selectedStreamIdx];

  const persistProgress = useCallback(
    async (isEnded: boolean) => {
      const pos = latestPosRef.current;
      const dur = latestDurRef.current;
      if (!isEnded && pos < 10_000) return;
      try {
        await api.saveWatchProgress(
          {
            mediaId: session.mediaId,
            sourceId: session.sourceId,
            type: session.mediaType,
            title: session.title,
            posterUrl: session.posterUrl,
            seasonNumber: session.seasonNumber,
            episodeNumber: session.episodeNumber,
            positionMs: pos,
            durationMs: dur,
            updatedAt: Date.now(),
            isCompleted: isEnded,
          },
          isEnded
        );
        queryClient.invalidateQueries({ queryKey: ["continueWatching"] });
        queryClient.invalidateQueries({ queryKey: ["watchlist"] });
        queryClient.invalidateQueries({
          queryKey: ["allProgress", session.sourceId, session.mediaId],
        });
        queryClient.invalidateQueries({
          queryKey: ["latestProgress", session.sourceId, session.mediaId],
        });
      } catch {
        // Ignore transient save errors
      }
    },
    [queryClient, session]
  );

  // Periodic 5s progress save
  useEffect(() => {
    const interval = window.setInterval(() => {
      void persistProgress(false);
    }, 5000);
    return () => window.clearInterval(interval);
  }, [persistProgress]);

  // Save & stop on exit
  const handleExit = useCallback(async () => {
    await persistProgress(false);
    if (useLibmpv) {
      await api.mpvStop().catch(() => {});
    }
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    if (document.fullscreenElement) {
      await document.exitFullscreen().catch(() => {});
    }
    closePlayer();
  }, [closePlayer, persistProgress, useLibmpv]);

  // Next episode resolution
  const nextEpisode = useMemo(() => {
    if (!session.episodes?.length || session.episodeNumber == null) return null;
    const idx = session.episodes.findIndex(
      (e) =>
        e.seasonNumber === session.seasonNumber &&
        e.episodeNumber === session.episodeNumber
    );
    if (idx >= 0) {
      return (
        session.episodes.slice(idx + 1).find((e) => e.isReleased !== false) ??
        null
      );
    }
    return null;
  }, [session.episodes, session.seasonNumber, session.episodeNumber]);

  const handleNextEpisode = useCallback(async () => {
    if (!nextEpisode) return;
    await api
      .markWatchProgressCompleted(
        session.sourceId,
        session.mediaId,
        session.seasonNumber,
        session.episodeNumber
      )
      .catch(() => {});
    queryClient.invalidateQueries({ queryKey: ["continueWatching"] });
    queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    queryClient.invalidateQueries({
      queryKey: ["allProgress", session.sourceId, session.mediaId],
    });
    queryClient.invalidateQueries({
      queryKey: ["latestProgress", session.sourceId, session.mediaId],
    });
    if (useLibmpv) {
      await api.mpvStop().catch(() => {});
    }
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
    openPlayer({
      ...session,
      seasonNumber: nextEpisode.seasonNumber,
      episodeNumber: nextEpisode.episodeNumber,
      episodeTitle: nextEpisode.title,
      resumePositionMs: 0,
    });
  }, [nextEpisode, openPlayer, queryClient, session, useLibmpv]);

  const allSubtitlesRef = useRef(allSubtitles);
  allSubtitlesRef.current = allSubtitles;

  // Register newly added external subtitles for WebView without restarting playback
  useEffect(() => {
    if (useLibmpv || !activeStream) return;
    let cancelled = false;
    (async () => {
      const subEntries = await Promise.all(
        allSubtitles.map(async (sub, i) => {
          const url = await api.registerProxySubtitle(
            sub.url,
            sub.headers,
            sub.format
          );
          return [i, url] as const;
        })
      );
      if (!cancelled) {
        setProxySubUrls(Object.fromEntries(subEntries));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [useLibmpv, activeStream, allSubtitles]);

  // Engine A: libmpv initialization & event listener
  useEffect(() => {
    if (!useLibmpv || !activeStream) return;
    let cancelled = false;
    let unlisten: (() => void) | undefined;

    (async () => {
      setBuffering(true);
      setErrorMsg(null);
      try {
        unlisten = await api.onMpvState((st) => {
          if (cancelled) return;
          setPaused(st.paused);
          setPositionMs(st.positionMs);
          setDurationMs(st.durationMs);
          setVolume(st.volume);
          setSpeed(st.speed);
          setBuffering(st.buffering);
          if (st.error) setErrorMsg(st.error);
          if (st.eofReached) {
            void persistProgress(true);
          }
        });

        await api.mpvLoad({
          url: activeStream.url,
          headers: activeStream.headers,
          resumePositionMs: session.resumePositionMs,
          subtitles: allSubtitlesRef.current,
          subtitleAppearance: prefs.subtitleAppearance,
        });
      } catch (err) {
        if (!cancelled) setErrorMsg(String(err));
      }
    })();

    return () => {
      cancelled = true;
      unlisten?.();
      api.mpvStop().catch(() => {});
    };
  }, [
    useLibmpv,
    activeStream,
    session.resumePositionMs,
    prefs.subtitleAppearance,
    persistProgress,
  ]);

  // Engine B: WebView HLS.js initialization
  useEffect(() => {
    if (useLibmpv || !activeStream || !videoRef.current) return;
    let cancelled = false;
    const video = videoRef.current;

    (async () => {
      setBuffering(true);
      setErrorMsg(null);
      try {
        const proxyUrl = await api.registerProxyStream(
          activeStream.url,
          activeStream.headers
        );
        if (cancelled) return;

        if (hlsRef.current) {
          hlsRef.current.destroy();
          hlsRef.current = null;
        }

        const isHls =
          activeStream.format === "HLS" ||
          activeStream.url.toLowerCase().includes(".m3u8");

        if (isHls && Hls.isSupported()) {
          const hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
          });
          hlsRef.current = hls;
          hls.loadSource(proxyUrl);
          hls.attachMedia(video);
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            if (session.resumePositionMs > 0) {
              video.currentTime = session.resumePositionMs / 1000;
            }
            video.play().catch(() => {});
          });
          hls.on(Hls.Events.ERROR, (_ev, data) => {
            if (data.fatal) {
              setErrorMsg(`HLS Error: ${data.details}`);
            }
          });
        } else {
          video.src = proxyUrl;
          if (session.resumePositionMs > 0) {
            video.currentTime = session.resumePositionMs / 1000;
          }
          video.play().catch(() => {});
        }
      } catch (err) {
        if (!cancelled) setErrorMsg(String(err));
      }
    })();

    return () => {
      cancelled = true;
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
    };
  }, [useLibmpv, activeStream, session.resumePositionMs]);

  // Unified control actions
  const togglePlay = useCallback(() => {
    if (useLibmpv) {
      api.mpvSetPaused(!paused).catch(() => {});
      setPaused(!paused);
    } else if (videoRef.current) {
      if (videoRef.current.paused) {
        videoRef.current.play().catch(() => {});
      } else {
        videoRef.current.pause();
      }
    }
  }, [paused, useLibmpv]);

  const seekTo = useCallback(
    (ms: number) => {
      const clamped = Math.max(0, Math.min(durationMs || ms, ms));
      setPositionMs(clamped);
      if (useLibmpv) {
        api.mpvSeek(clamped).catch(() => {});
      } else if (videoRef.current) {
        videoRef.current.currentTime = clamped / 1000;
      }
    },
    [durationMs, useLibmpv]
  );

  const changeVolume = useCallback(
    (vol: number) => {
      const clamped = Math.max(0, Math.min(100, vol));
      setVolume(clamped);
      setMuted(clamped === 0);
      if (useLibmpv) {
        api.mpvSetVolume(clamped).catch(() => {});
      } else if (videoRef.current) {
        videoRef.current.volume = clamped / 100;
        videoRef.current.muted = clamped === 0;
      }
    },
    [useLibmpv]
  );

  const toggleMute = useCallback(() => {
    const nextMuted = !muted;
    setMuted(nextMuted);
    if (useLibmpv) {
      api.mpvSetVolume(nextMuted ? 0 : volume || 100).catch(() => {});
    } else if (videoRef.current) {
      videoRef.current.muted = nextMuted;
    }
  }, [muted, useLibmpv, volume]);

  const changeSpeed = useCallback(
    (nextSpeed: number) => {
      setSpeed(nextSpeed);
      if (useLibmpv) {
        api.mpvSetSpeed(nextSpeed).catch(() => {});
      } else if (videoRef.current) {
        videoRef.current.playbackRate = nextSpeed;
      }
    },
    [useLibmpv]
  );

  const toggleFullscreen = useCallback(() => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  }, []);

  // Keyboard shortcuts
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (
        e.target instanceof HTMLInputElement ||
        e.target instanceof HTMLTextAreaElement
      ) {
        return;
      }
      switch (e.key) {
        case " ":
          e.preventDefault();
          togglePlay();
          break;
        case "f":
        case "F":
          e.preventDefault();
          toggleFullscreen();
          break;
        case "m":
        case "M":
          e.preventDefault();
          toggleMute();
          break;
        case "ArrowLeft":
          e.preventDefault();
          seekTo(latestPosRef.current - 10_000);
          break;
        case "ArrowRight":
          e.preventDefault();
          seekTo(latestPosRef.current + 10_000);
          break;
        case "ArrowUp":
          e.preventDefault();
          changeVolume(volume + 10);
          break;
        case "ArrowDown":
          e.preventDefault();
          changeVolume(volume - 10);
          break;
        case "Escape":
          e.preventDefault();
          if (drawer) {
            setDrawer(null);
          } else {
            void handleExit();
          }
          break;
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    changeVolume,
    drawer,
    handleExit,
    seekTo,
    toggleFullscreen,
    toggleMute,
    togglePlay,
    volume,
  ]);

  // Auto-hide HUD on idle
  const bumpHud = useCallback(() => {
    setHudVisible(true);
    if (hideHudTimerRef.current) {
      window.clearTimeout(hideHudTimerRef.current);
    }
    hideHudTimerRef.current = window.setTimeout(() => {
      if (!paused && !drawer) setHudVisible(false);
    }, 3000);
  }, [drawer, paused]);

  const handleAddExternalSub = async () => {
    const url = extSubUrl.trim();
    if (!url) return;
    const format = url.toLowerCase().endsWith(".srt")
      ? "SRT"
      : url.toLowerCase().endsWith(".ass")
        ? "ASS"
        : "VTT";
    const track: SubtitleTrack = {
      url,
      language: "ext",
      label: "External",
      format,
      headers: {},
      isExternal: true,
    };
    setExtraSubtitles((prev) => [...prev, track]);
    setExtSubUrl("");
    if (useLibmpv) {
      await api.mpvAddSubtitle(url, {}).catch(() => {});
    }
  };

  return (
    <div
      onMouseMove={bumpHud}
      className={`fixed inset-0 z-50 flex flex-col justify-between ${
        useLibmpv ? "bg-black/40" : "bg-black"
      }`}
    >
      {/* WebView <video> element */}
      {!useLibmpv && (
        <video
          ref={videoRef}
          onClick={togglePlay}
          onPlay={() => setPaused(false)}
          onPause={() => setPaused(true)}
          onWaiting={() => setBuffering(true)}
          onPlaying={() => setBuffering(false)}
          onCanPlay={() => setBuffering(false)}
          onTimeUpdate={() => {
            if (videoRef.current) {
              setPositionMs(Math.floor(videoRef.current.currentTime * 1000));
            }
          }}
          onLoadedMetadata={() => {
            if (videoRef.current && Number.isFinite(videoRef.current.duration)) {
              setDurationMs(Math.floor(videoRef.current.duration * 1000));
            }
          }}
          onEnded={() => {
            void persistProgress(true);
          }}
          className="absolute inset-0 h-full w-full object-contain"
          crossOrigin="anonymous"
        >
          {allSubtitles.map((sub, i) =>
            proxySubUrls[i] ? (
              <track
                key={`${sub.url}_${i}`}
                kind="subtitles"
                label={sub.label}
                srcLang={sub.language}
                src={proxySubUrls[i]}
                default={selectedSubIdx === i}
              />
            ) : null
          )}
        </video>
      )}

      {/* Fallback Banner */}
      {showFallbackBanner && (
        <div className="relative z-20 mx-auto mt-3 flex items-center gap-3 rounded-xl border border-amber-500/40 bg-[#141923]/95 px-4 py-2 text-xs text-amber-200 shadow-lg">
          <span>{t.player.fallbackBanner}</span>
          <button
            onClick={() => setFallbackDismissed(true)}
            className="rounded p-0.5 hover:bg-white/10"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Top HUD */}
      <div
        className={`relative z-10 flex items-center justify-between bg-gradient-to-b from-black/85 via-black/40 to-transparent px-6 py-4 transition-opacity duration-300 ${
          hudVisible ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      >
        <div className="flex items-center gap-4">
          <button
            onClick={() => void handleExit()}
            className="flex items-center gap-2 rounded-xl bg-white/10 px-3 py-2 text-xs font-medium text-white backdrop-blur-sm hover:bg-white/20"
          >
            <ArrowLeft className="h-4 w-4" />
            <span>{t.player.back}</span>
          </button>
          <div>
            <h2 className="text-base font-semibold text-white">
              {session.title}
              {session.seasonNumber != null && session.episodeNumber != null
                ? ` · S${session.seasonNumber} E${session.episodeNumber}`
                : ""}
            </h2>
            {session.episodeTitle && (
              <p className="text-xs text-slate-300">{session.episodeTitle}</p>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span className="rounded-full border border-[#38BDF8]/40 bg-[#38BDF8]/15 px-3 py-1 text-xs font-semibold text-[#38BDF8]">
            {useLibmpv
              ? t.player.engineBadgeLibmpv
              : t.player.engineBadgeWebview}
          </span>
        </div>
      </div>

      {/* Center Loading / Error */}
      <div className="pointer-events-none relative z-10 flex flex-1 items-center justify-center">
        {(loadingStreams || buffering) && !errorMsg && (
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-black/70 px-6 py-4 backdrop-blur-md">
            <Loader2 className="h-8 w-8 animate-spin text-[#38BDF8]" />
            <span className="text-xs text-slate-200">
              {loadingStreams ? t.player.loadingStream : ""}
            </span>
          </div>
        )}
        {(errorMsg || streamError || (!loadingStreams && streams.length === 0)) && (
          <div className="pointer-events-auto max-w-md rounded-2xl border border-rose-500/40 bg-[#141923]/95 p-6 text-center">
            <p className="text-sm font-medium text-rose-300">
              {errorMsg ||
                (streamError ? String(streamError) : t.player.noStreams)}
            </p>
            <button
              onClick={() => void handleExit()}
              className="mt-4 rounded-xl bg-[#38BDF8] px-4 py-2 text-xs font-semibold text-[#0B0E14]"
            >
              {t.player.back}
            </button>
          </div>
        )}
      </div>

      {/* Drawers (Servers / Subtitles) */}
      {drawer && (
        <div className="relative z-20 mx-6 mb-2 ml-auto w-80 rounded-2xl border border-[#283347] bg-[#141923]/95 p-4 shadow-2xl backdrop-blur-md">
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-sm font-semibold text-[#F8FAFC]">
              {drawer === "servers" ? t.player.servers : t.player.subtitles}
            </h3>
            <button
              onClick={() => setDrawer(null)}
              className="rounded p-1 text-[#94A3B8] hover:text-white"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          {drawer === "servers" ? (
            <div className="max-h-60 space-y-1.5 overflow-y-auto">
              {streams.map((s, idx) => (
                <button
                  key={`${s.serverName}_${idx}`}
                  onClick={() => {
                    setSelectedStreamIdx(idx);
                    setDrawer(null);
                  }}
                  className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-xs ${
                    idx === selectedStreamIdx
                      ? "bg-[#38BDF8]/20 font-semibold text-[#38BDF8]"
                      : "bg-[#1C2331] text-[#F8FAFC] hover:bg-[#283347]"
                  }`}
                >
                  <span>{s.serverName}</span>
                  <span className="text-[11px] opacity-80">
                    {s.quality} · {s.format}
                  </span>
                </button>
              ))}
            </div>
          ) : (
            <div className="space-y-3">
              <div className="max-h-48 space-y-1.5 overflow-y-auto">
                <button
                  onClick={() => {
                    setSelectedSubIdx(null);
                    if (useLibmpv) api.mpvSelectSubtitle(null).catch(() => {});
                  }}
                  className={`w-full rounded-xl px-3 py-2 text-left text-xs ${
                    selectedSubIdx === null
                      ? "bg-[#38BDF8]/20 font-semibold text-[#38BDF8]"
                      : "bg-[#1C2331] text-[#F8FAFC]"
                  }`}
                >
                  {t.player.off}
                </button>
                {allSubtitles.map((sub, idx) => (
                  <button
                    key={`${sub.url}_${idx}`}
                    onClick={() => {
                      setSelectedSubIdx(idx);
                      if (useLibmpv) {
                        api.mpvSelectSubtitle(idx + 1).catch(() => {});
                      }
                    }}
                    className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-xs ${
                      selectedSubIdx === idx
                        ? "bg-[#38BDF8]/20 font-semibold text-[#38BDF8]"
                        : "bg-[#1C2331] text-[#F8FAFC]"
                    }`}
                  >
                    <span>{sub.label || sub.language}</span>
                    <span className="text-[10px] opacity-70">{sub.format}</span>
                  </button>
                ))}
              </div>

              <div className="flex gap-1.5 border-t border-[#283347] pt-3">
                <input
                  type="url"
                  value={extSubUrl}
                  onChange={(e) => setExtSubUrl(e.target.value)}
                  placeholder={t.player.addExternalSub}
                  className="min-w-0 flex-1 rounded-lg border border-[#283347] bg-[#0B0E14] px-2.5 py-1.5 text-xs text-[#F8FAFC] outline-none focus:border-[#38BDF8]"
                />
                <button
                  onClick={() => void handleAddExternalSub()}
                  className="rounded-lg bg-[#38BDF8] px-2.5 py-1.5 text-xs font-semibold text-[#0B0E14]"
                >
                  <Plus className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Bottom HUD Controls */}
      <div
        className={`relative z-10 space-y-3 bg-gradient-to-t from-black/90 via-black/55 to-transparent px-6 pb-5 pt-8 transition-opacity duration-300 ${
          hudVisible ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      >
        {/* Seekbar */}
        <div className="flex items-center gap-3">
          <span className="w-12 text-right font-mono text-xs text-slate-300">
            {formatTime(positionMs)}
          </span>
          <input
            type="range"
            min={0}
            max={Math.max(durationMs, 1)}
            value={Math.min(positionMs, Math.max(durationMs, 1))}
            onChange={(e) => seekTo(Number(e.target.value))}
            className="h-1.5 flex-1 cursor-pointer accent-[#38BDF8]"
          />
          <span className="w-12 font-mono text-xs text-slate-300">
            {formatTime(durationMs)}
          </span>
        </div>

        {/* Control Buttons */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <button
              onClick={togglePlay}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-[#38BDF8] text-[#0B0E14] transition-transform hover:scale-105"
            >
              {paused ? (
                <Play className="ml-0.5 h-5 w-5 fill-current" />
              ) : (
                <Pause className="h-5 w-5 fill-current" />
              )}
            </button>

            <div className="flex items-center gap-2">
              <button
                onClick={toggleMute}
                className="rounded-lg p-2 text-white hover:bg-white/10"
              >
                {muted || volume === 0 ? (
                  <VolumeX className="h-5 w-5" />
                ) : (
                  <Volume2 className="h-5 w-5" />
                )}
              </button>
              <input
                type="range"
                min={0}
                max={100}
                value={muted ? 0 : volume}
                onChange={(e) => changeVolume(Number(e.target.value))}
                className="w-24 accent-[#38BDF8]"
              />
            </div>

            {nextEpisode && (
              <button
                onClick={() => void handleNextEpisode()}
                className="flex items-center gap-1.5 rounded-xl bg-white/10 px-3 py-1.5 text-xs font-semibold text-white hover:bg-[#38BDF8] hover:text-[#0B0E14]"
              >
                <SkipForward className="h-4 w-4" />
                <span>{t.player.nextEpisode}</span>
              </button>
            )}
          </div>

          <div className="flex items-center gap-2">
            {/* Speed */}
            <div className="flex items-center gap-1 rounded-xl bg-white/10 px-2.5 py-1.5 text-xs text-white">
              <Gauge className="h-3.5 w-3.5 text-[#38BDF8]" />
              <select
                value={speed}
                onChange={(e) => changeSpeed(Number(e.target.value))}
                className="bg-transparent text-xs font-medium outline-none"
              >
                {[0.75, 1, 1.25, 1.5, 2].map((sp) => (
                  <option key={sp} value={sp} className="bg-[#141923]">
                    {sp}x
                  </option>
                ))}
              </select>
            </div>

            <button
              onClick={() =>
                setDrawer((d) => (d === "subtitles" ? null : "subtitles"))
              }
              className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium ${
                drawer === "subtitles"
                  ? "bg-[#38BDF8] text-[#0B0E14]"
                  : "bg-white/10 text-white hover:bg-white/20"
              }`}
            >
              <Subtitles className="h-4 w-4" />
              <span>{t.player.subtitles}</span>
            </button>

            <button
              onClick={() =>
                setDrawer((d) => (d === "servers" ? null : "servers"))
              }
              className={`flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-medium ${
                drawer === "servers"
                  ? "bg-[#38BDF8] text-[#0B0E14]"
                  : "bg-white/10 text-white hover:bg-white/20"
              }`}
            >
              <Server className="h-4 w-4" />
              <span>{t.player.servers}</span>
            </button>

            <button
              onClick={toggleFullscreen}
              className="rounded-xl bg-white/10 p-2 text-white hover:bg-white/20"
            >
              {isFullscreen ? (
                <Minimize2 className="h-4 w-4" />
              ) : (
                <Maximize2 className="h-4 w-4" />
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
