import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Info, Play, RefreshCw, Sparkles } from "lucide-react";
import { HorizontalRow } from "../components/HorizontalRow";
import { MediaCardView } from "../components/MediaCardView";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";
import type { WatchProgress } from "../types/models";

export function HomePage() {
  const { t } = usePreferences();
  const queryClient = useQueryClient();
  const activeSourceId = useAppStore((s) => s.activeSourceId);
  const openDetails = useAppStore((s) => s.openDetails);
  const openPlayer = useAppStore((s) => s.openPlayer);

  const {
    data: homeSections = [],
    isLoading: loadingHome,
    refetch: refetchHome,
  } = useQuery({
    queryKey: ["home", activeSourceId],
    queryFn: () => api.getHome(activeSourceId ?? undefined),
  });

  const { data: continueWatching = [] } = useQuery({
    queryKey: ["continueWatching"],
    queryFn: () => api.getContinueWatching(20),
  });

  const removeMutation = useMutation({
    mutationFn: (item: WatchProgress) =>
      api.deleteWatchProgress(
        item.sourceId,
        item.mediaId,
        item.seasonNumber,
        item.episodeNumber
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["continueWatching"] });
    },
  });

  const markWatchedMutation = useMutation({
    mutationFn: (item: WatchProgress) =>
      api.markWatchProgressCompleted(
        item.sourceId,
        item.mediaId,
        item.seasonNumber,
        item.episodeNumber
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["continueWatching"] });
    },
  });

  const heroItem = homeSections.flatMap((s) => s.items)[0];

  return (
    <div className="space-y-8 pb-12">
      {/* Hero Spotlight */}
      {heroItem && (
        <div className="relative h-[340px] w-full overflow-hidden rounded-2xl border border-[#283347] bg-[#141923]">
          {(heroItem.backdropUrl || heroItem.posterUrl) && (
            <img
              src={heroItem.backdropUrl ?? heroItem.posterUrl ?? ""}
              alt={heroItem.title}
              className="h-full w-full object-cover opacity-55"
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-r from-[#0B0E14] via-[#0B0E14]/80 to-transparent" />
          <div className="absolute inset-0 bg-gradient-to-t from-[#0B0E14] via-transparent to-transparent" />

          <div className="absolute bottom-8 left-8 max-w-xl space-y-4">
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center gap-1 rounded-full bg-[#38BDF8]/20 px-2.5 py-0.5 text-xs font-semibold text-[#38BDF8]">
                <Sparkles className="h-3 w-3" />
                {heroItem.type}
              </span>
              {heroItem.year && (
                <span className="text-xs font-medium text-[#94A3B8]">
                  {heroItem.year}
                </span>
              )}
              {heroItem.rating != null && heroItem.rating > 0 && (
                <span className="text-xs font-semibold text-amber-400">
                  ★ {heroItem.rating.toFixed(1)}
                </span>
              )}
            </div>

            <h1 className="text-3xl font-bold tracking-tight text-[#F8FAFC]">
              {heroItem.title}
            </h1>

            <div className="flex items-center gap-3 pt-1">
              <button
                onClick={() =>
                  openDetails({
                    sourceId: heroItem.sourceId,
                    mediaId: heroItem.id,
                    mediaType: heroItem.type,
                  })
                }
                className="flex items-center gap-2 rounded-xl bg-[#38BDF8] px-5 py-2.5 text-sm font-semibold text-[#0B0E14] shadow-lg shadow-[#38BDF8]/25 transition-transform hover:scale-[1.02]"
              >
                <Play className="h-4 w-4 fill-current" />
                <span>{t.home.watchNow}</span>
              </button>
              <button
                onClick={() =>
                  openDetails({
                    sourceId: heroItem.sourceId,
                    mediaId: heroItem.id,
                    mediaType: heroItem.type,
                  })
                }
                className="flex items-center gap-2 rounded-xl border border-[#283347] bg-[#1C2331]/90 px-4 py-2.5 text-sm font-medium text-[#F8FAFC] transition-colors hover:border-[#38BDF8]"
              >
                <Info className="h-4 w-4" />
                <span>{t.home.details}</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Continue Watching */}
      {continueWatching.length > 0 && (
        <HorizontalRow title={t.home.continueWatching}>
          {continueWatching.map((item) => {
            const ratio =
              item.durationMs > 0 ? item.positionMs / item.durationMs : 0;
            const epBadge =
              item.seasonNumber != null && item.episodeNumber != null
                ? `S${item.seasonNumber} E${item.episodeNumber}`
                : null;

            return (
              <MediaCardView
                key={`${item.sourceId}_${item.mediaId}_${item.seasonNumber ?? 0}_${item.episodeNumber ?? 0}`}
                title={item.title}
                posterUrl={item.posterUrl}
                type={item.type}
                progressRatio={ratio}
                episodeBadge={epBadge}
                onClick={() =>
                  openPlayer({
                    sourceId: item.sourceId,
                    mediaId: item.mediaId,
                    mediaType: item.type,
                    title: item.title,
                    posterUrl: item.posterUrl,
                    seasonNumber: item.seasonNumber,
                    episodeNumber: item.episodeNumber,
                    resumePositionMs: item.positionMs,
                  })
                }
                contextActions={{
                  markWatchedLabel: t.home.markAsWatched,
                  onMarkWatched: () => markWatchedMutation.mutate(item),
                  removeLabel: t.home.removeFromContinue,
                  onRemove: () => removeMutation.mutate(item),
                }}
              />
            );
          })}
        </HorizontalRow>
      )}

      {/* Loading skeleton */}
      {loadingHome && (
        <div className="space-y-6">
          {[1, 2].map((row) => (
            <div key={row} className="space-y-3">
              <div className="h-6 w-48 animate-pulse rounded-lg bg-[#1C2331]" />
              <div className="flex gap-4 overflow-hidden">
                {[1, 2, 3, 4, 5, 6].map((card) => (
                  <div
                    key={card}
                    className="h-[255px] w-[170px] shrink-0 animate-pulse rounded-xl bg-[#1C2331]"
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Home Sections */}
      {!loadingHome &&
        homeSections.map((section) => (
          <HorizontalRow key={section.title} title={section.title}>
            {section.items.map((item) => (
              <MediaCardView
                key={`${item.sourceId}_${item.type}_${item.id}`}
                title={item.title}
                posterUrl={item.posterUrl}
                type={item.type}
                year={item.year}
                rating={item.rating}
                subtitle={item.subtitle}
                onClick={() =>
                  openDetails({
                    sourceId: item.sourceId,
                    mediaId: item.id,
                    mediaType: item.type,
                  })
                }
              />
            ))}
          </HorizontalRow>
        ))}

      {/* Empty state */}
      {!loadingHome && homeSections.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-2xl border border-[#283347] bg-[#141923] p-12 text-center">
          <p className="text-lg font-semibold text-[#F8FAFC]">
            {t.home.emptyTitle}
          </p>
          <p className="mt-1 max-w-md text-sm text-[#94A3B8]">
            {t.home.emptyHint}
          </p>
          <button
            onClick={() => refetchHome()}
            className="mt-4 flex items-center gap-2 rounded-xl bg-[#38BDF8] px-4 py-2 text-sm font-semibold text-[#0B0E14]"
          >
            <RefreshCw className="h-4 w-4" />
            <span>{t.common.retry}</span>
          </button>
        </div>
      )}
    </div>
  );
}
