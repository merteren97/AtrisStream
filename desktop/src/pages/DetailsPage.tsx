import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Bookmark,
  BookmarkCheck,
  CheckCircle2,
  Clock,
  Play,
  Star,
} from "lucide-react";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";
import type { Episode, WatchProgress } from "../types/models";

export function DetailsPage() {
  const { t } = usePreferences();
  const queryClient = useQueryClient();
  const target = useAppStore((s) => s.detailsTarget);
  const goBack = useAppStore((s) => s.goBackFromDetails);
  const openPlayer = useAppStore((s) => s.openPlayer);

  const [selectedSeason, setSelectedSeason] = useState<number>(1);

  const { data: details, isLoading } = useQuery({
    queryKey: ["details", target?.sourceId, target?.mediaId, target?.mediaType],
    queryFn: () =>
      api.getDetails(target!.sourceId, target!.mediaId, target!.mediaType),
    enabled: Boolean(target),
  });

  const { data: allProgress = [] } = useQuery({
    queryKey: ["allProgress", target?.sourceId, target?.mediaId],
    queryFn: () => api.getAllProgress(target!.sourceId, target!.mediaId),
    enabled: Boolean(target),
  });

  const { data: latestProgress } = useQuery({
    queryKey: ["latestProgress", target?.sourceId, target?.mediaId],
    queryFn: () => api.getLatestProgress(target!.sourceId, target!.mediaId),
    enabled: Boolean(target),
  });

  const { data: isSaved = false } = useQuery({
    queryKey: ["watchlistSaved", target?.sourceId, target?.mediaId],
    queryFn: () => api.isSavedToWatchlist(target!.sourceId, target!.mediaId),
    enabled: Boolean(target),
  });

  useEffect(() => {
    if (details?.seasons?.length) {
      const preferredSeason =
        latestProgress?.seasonNumber ?? details.seasons[0].seasonNumber;
      setSelectedSeason(preferredSeason);
    }
  }, [details, latestProgress?.seasonNumber]);

  const currentSeasonObj = details?.seasons.find(
    (s) => s.seasonNumber === selectedSeason
  );

  const { data: fetchedEpisodes = [] } = useQuery({
    queryKey: ["episodes", target?.sourceId, target?.mediaId, selectedSeason],
    queryFn: () =>
      api.getEpisodes(target!.sourceId, target!.mediaId, selectedSeason),
    enabled:
      Boolean(target) &&
      Boolean(details) &&
      (details?.seasons.length ?? 0) > 0 &&
      (currentSeasonObj?.episodes.length ?? 0) === 0,
  });

  const episodes: Episode[] = useMemo(() => {
    if (currentSeasonObj && currentSeasonObj.episodes.length > 0) {
      return currentSeasonObj.episodes;
    }
    return fetchedEpisodes;
  }, [currentSeasonObj, fetchedEpisodes]);

  const toggleWatchlistMutation = useMutation({
    mutationFn: () =>
      api.toggleWatchlist({
        mediaId: details!.id,
        sourceId: details!.sourceId,
        type: details!.type,
        title: details!.title,
        posterUrl: details!.posterUrl,
        year: details!.year,
        rating: details!.rating,
        addedAt: Date.now(),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["watchlistSaved", target?.sourceId, target?.mediaId],
      });
      queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    },
  });

  const progressMap = useMemo(() => {
    const map = new Map<string, WatchProgress>();
    for (const p of allProgress) {
      map.set(`${p.seasonNumber ?? 0}_${p.episodeNumber ?? 0}`, p);
    }
    return map;
  }, [allProgress]);

  // Smart Primary CTA computation
  const smartCta = useMemo(() => {
    if (!details) return null;
    const isEpisodic = details.seasons.length > 0;

    if (!isEpisodic) {
      if (
        latestProgress &&
        !latestProgress.isCompleted &&
        latestProgress.positionMs >= 10_000
      ) {
        return {
          label: t.details.resume,
          seasonNumber: null,
          episodeNumber: null,
          resumePositionMs: latestProgress.positionMs,
        };
      }
      return {
        label: t.details.play,
        seasonNumber: null,
        episodeNumber: null,
        resumePositionMs: 0,
      };
    }

    if (latestProgress) {
      if (!latestProgress.isCompleted && latestProgress.positionMs >= 10_000) {
        return {
          label: `${t.details.resume} S${latestProgress.seasonNumber ?? 1} E${latestProgress.episodeNumber ?? 1}`,
          seasonNumber: latestProgress.seasonNumber ?? 1,
          episodeNumber: latestProgress.episodeNumber ?? 1,
          resumePositionMs: latestProgress.positionMs,
        };
      }
      if (latestProgress.isCompleted) {
        const allEps = details.seasons.flatMap((s) => s.episodes);
        const idx = allEps.findIndex(
          (e) =>
            e.seasonNumber === latestProgress.seasonNumber &&
            e.episodeNumber === latestProgress.episodeNumber
        );
        if (idx >= 0 && idx + 1 < allEps.length) {
          const nextEp = allEps[idx + 1];
          return {
            label: `${t.details.nextEpisode} S${nextEp.seasonNumber} E${nextEp.episodeNumber}`,
            seasonNumber: nextEp.seasonNumber,
            episodeNumber: nextEp.episodeNumber,
            resumePositionMs: 0,
          };
        }
      }
    }

    const firstEp = details.seasons[0]?.episodes[0] ?? episodes[0];
    return {
      label: firstEp
        ? `${t.details.play} S${firstEp.seasonNumber} E${firstEp.episodeNumber}`
        : t.details.play,
      seasonNumber: firstEp?.seasonNumber ?? 1,
      episodeNumber: firstEp?.episodeNumber ?? 1,
      resumePositionMs: 0,
    };
  }, [details, latestProgress, episodes, t]);

  if (!target) return null;

  if (isLoading || !details) {
    return (
      <div className="space-y-6">
        <div className="h-[320px] w-full animate-pulse rounded-2xl bg-[#1C2331]" />
        <div className="h-8 w-64 animate-pulse rounded-lg bg-[#1C2331]" />
      </div>
    );
  }

  const allLoadedEpisodes = details.seasons.flatMap((s) =>
    s.seasonNumber === selectedSeason ? episodes : s.episodes
  );

  return (
    <div className="space-y-8 pb-12">
      {/* Back button */}
      <button
        onClick={goBack}
        className="inline-flex items-center gap-2 rounded-xl border border-[#283347] bg-[#141923] px-3.5 py-2 text-xs font-medium text-[#94A3B8] transition-colors hover:border-[#38BDF8] hover:text-[#F8FAFC]"
      >
        <ArrowLeft className="h-4 w-4" />
        <span>{t.player.back}</span>
      </button>

      {/* Backdrop Hero */}
      <div className="relative overflow-hidden rounded-2xl border border-[#283347] bg-[#141923]">
        {(details.backdropUrl || details.posterUrl) && (
          <img
            src={details.backdropUrl ?? details.posterUrl ?? ""}
            alt={details.title}
            className="h-[340px] w-full object-cover opacity-40"
          />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-[#0B0E14] via-[#0B0E14]/75 to-transparent" />

        <div className="relative z-10 -mt-44 flex flex-col gap-6 p-8 md:flex-row md:items-end">
          {details.posterUrl && (
            <img
              src={details.posterUrl}
              alt={details.title}
              className="h-56 w-38 shrink-0 rounded-xl border border-[#283347] object-cover shadow-2xl"
            />
          )}

          <div className="flex-1 space-y-3">
            <div className="flex flex-wrap items-center gap-2.5 text-xs font-medium text-[#94A3B8]">
              <span className="rounded-md bg-[#38BDF8]/20 px-2 py-0.5 font-semibold text-[#38BDF8]">
                {details.type}
              </span>
              {details.year && <span>{details.year}</span>}
              {details.rating != null && details.rating > 0 && (
                <span className="flex items-center gap-1 text-amber-400">
                  <Star className="h-3.5 w-3.5 fill-amber-400" />
                  {details.rating.toFixed(1)}
                </span>
              )}
              {details.runtimeMinutes && (
                <span className="flex items-center gap-1">
                  <Clock className="h-3.5 w-3.5" />
                  {details.runtimeMinutes} {t.details.minutes}
                </span>
              )}
            </div>

            <h1 className="text-3xl font-bold tracking-tight text-[#F8FAFC]">
              {details.title}
            </h1>

            {details.genres.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {details.genres.map((g) => (
                  <span
                    key={g}
                    className="rounded-full border border-[#283347] bg-[#1C2331] px-2.5 py-0.5 text-xs text-[#94A3B8]"
                  >
                    {g}
                  </span>
                ))}
              </div>
            )}

            <p className="max-w-3xl text-sm leading-relaxed text-[#94A3B8]">
              {details.description || t.details.noDescription}
            </p>

            {/* Primary Actions */}
            <div className="flex flex-wrap items-center gap-3 pt-2">
              {smartCta && (
                <button
                  onClick={() =>
                    openPlayer({
                      sourceId: details.sourceId,
                      mediaId: details.id,
                      mediaType: details.type,
                      title: details.title,
                      posterUrl: details.posterUrl,
                      seasonNumber: smartCta.seasonNumber,
                      episodeNumber: smartCta.episodeNumber,
                      resumePositionMs: smartCta.resumePositionMs,
                      episodes: allLoadedEpisodes,
                    })
                  }
                  className="flex items-center gap-2 rounded-xl bg-[#38BDF8] px-6 py-2.5 text-sm font-semibold text-[#0B0E14] shadow-lg shadow-[#38BDF8]/25 transition-transform hover:scale-[1.02]"
                >
                  <Play className="h-4 w-4 fill-current" />
                  <span>{smartCta.label}</span>
                </button>
              )}

              <button
                onClick={() => toggleWatchlistMutation.mutate()}
                className={`flex items-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition-colors ${
                  isSaved
                    ? "border-[#818CF8] bg-[#818CF8]/15 text-[#818CF8]"
                    : "border-[#283347] bg-[#1C2331] text-[#F8FAFC] hover:border-[#38BDF8]"
                }`}
              >
                {isSaved ? (
                  <BookmarkCheck className="h-4 w-4" />
                ) : (
                  <Bookmark className="h-4 w-4" />
                )}
                <span>
                  {isSaved ? t.details.inWatchlist : t.details.addToWatchlist}
                </span>
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Seasons & Episodes */}
      {details.seasons.length > 0 && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            {details.seasons.map((season) => (
              <button
                key={season.seasonNumber}
                onClick={() => setSelectedSeason(season.seasonNumber)}
                className={`rounded-xl px-4 py-2 text-sm font-medium transition-colors ${
                  selectedSeason === season.seasonNumber
                    ? "bg-[#38BDF8] text-[#0B0E14]"
                    : "border border-[#283347] bg-[#141923] text-[#94A3B8] hover:text-[#F8FAFC]"
                }`}
              >
                {season.name || `Sezon ${season.seasonNumber}`}
              </button>
            ))}
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            {episodes.map((ep) => {
              const prog = progressMap.get(
                `${ep.seasonNumber}_${ep.episodeNumber}`
              );
              const isCompleted = prog?.isCompleted ?? false;
              const ratio =
                prog && prog.durationMs > 0
                  ? prog.positionMs / prog.durationMs
                  : 0;

              return (
                <button
                  key={`${ep.seasonNumber}_${ep.episodeNumber}_${ep.id}`}
                  onClick={() =>
                    openPlayer({
                      sourceId: details.sourceId,
                      mediaId: details.id,
                      mediaType: details.type,
                      title: details.title,
                      posterUrl: details.posterUrl,
                      seasonNumber: ep.seasonNumber,
                      episodeNumber: ep.episodeNumber,
                      episodeTitle: ep.title,
                      // Replaying a completed episode starts from 0ms
                      resumePositionMs: isCompleted ? 0 : (prog?.positionMs ?? 0),
                      episodes: allLoadedEpisodes,
                    })
                  }
                  className="group relative flex items-center gap-4 overflow-hidden rounded-xl border border-[#283347] bg-[#141923] p-3.5 text-left transition-colors hover:border-[#38BDF8] hover:bg-[#1C2331]"
                >
                  <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-[#1C2331] text-sm font-bold text-[#38BDF8] group-hover:bg-[#38BDF8] group-hover:text-[#0B0E14]">
                    {ep.episodeNumber}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <p className="truncate text-sm font-semibold text-[#F8FAFC]">
                        {ep.title || `Bölüm ${ep.episodeNumber}`}
                      </p>
                      {isCompleted && (
                        <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-emerald-500/15 px-2 py-0.5 text-[11px] font-medium text-emerald-400">
                          <CheckCircle2 className="h-3 w-3" />
                          {t.details.watched}
                        </span>
                      )}
                    </div>
                    {ep.overview && (
                      <p className="mt-0.5 line-clamp-2 text-xs text-[#94A3B8]">
                        {ep.overview}
                      </p>
                    )}
                  </div>

                  {!isCompleted && ratio > 0 && (
                    <div className="absolute bottom-0 left-0 right-0 h-1 bg-[#0B0E14]">
                      <div
                        className="h-full bg-[#38BDF8]"
                        style={{ width: `${Math.min(100, ratio * 100)}%` }}
                      />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
