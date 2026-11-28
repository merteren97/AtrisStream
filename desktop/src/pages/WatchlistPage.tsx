import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Trash2 } from "lucide-react";
import { MediaCardView } from "../components/MediaCardView";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";
import type { WatchlistItem } from "../types/models";

type WatchlistFilter = "ALL" | "NEW" | "ANIME" | "TV" | "MOVIE";
const STALE_CHECK_MS = 15 * 60 * 1000;

export function WatchlistPage() {
  const { prefs, t } = usePreferences();
  const queryClient = useQueryClient();
  const openDetails = useAppStore((s) => s.openDetails);
  const [filter, setFilter] = useState<WatchlistFilter>("ALL");
  const autoCheckedRef = useRef(false);

  const { data: items = [], isLoading } = useQuery({
    queryKey: ["watchlist"],
    queryFn: api.getWatchlist,
  });

  const refreshMutation = useMutation({
    mutationFn: api.refreshWatchlistEpisodes,
    onSuccess: (updated) => {
      queryClient.setQueryData(["watchlist"], updated);
      queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    },
  });

  useEffect(() => {
    if (isLoading || autoCheckedRef.current || items.length === 0) return;
    const now = Date.now();
    const hasStale = items.some((item) => {
      const trackable =
        item.type === "ANIME" ||
        (item.type === "TV" && Boolean(prefs.trackSeriesNewEpisodes));
      if (!trackable) return false;
      const last = item.lastCheckedAt ?? 0;
      return last === 0 || now - last > STALE_CHECK_MS;
    });
    if (hasStale) {
      autoCheckedRef.current = true;
      refreshMutation.mutate();
    }
  }, [isLoading, items, prefs.trackSeriesNewEpisodes, refreshMutation]);

  const removeMutation = useMutation({
    mutationFn: (item: WatchlistItem) => api.toggleWatchlist(item),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    },
  });

  const newCount = useMemo(
    () => items.filter((i) => i.hasNewEpisode).length,
    [items]
  );

  const filteredItems = useMemo(() => {
    switch (filter) {
      case "NEW":
        return items.filter((i) => i.hasNewEpisode);
      case "ANIME":
        return items.filter((i) => i.type === "ANIME");
      case "TV":
        return items.filter((i) => i.type === "TV");
      case "MOVIE":
        return items.filter((i) => i.type === "MOVIE");
      default:
        return items;
    }
  }, [items, filter]);

  const epPrefix = prefs.language === "en" ? "E" : "B";

  return (
    <div className="space-y-6 pb-12">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
            {t.watchlist.title}
          </h1>
          <span className="rounded-full border border-[#283347] bg-[#141923] px-2.5 py-0.5 text-xs font-semibold text-[#94A3B8]">
            {filteredItems.length}
            {newCount > 0 ? ` · ${newCount} ${t.watchlist.newEpisodeBadge}` : ""}
          </span>
        </div>

        <button
          onClick={() => refreshMutation.mutate()}
          disabled={refreshMutation.isPending}
          className="flex items-center gap-2 rounded-xl border border-[#283347] bg-[#141923] px-4 py-2 text-xs font-semibold text-[#F8FAFC] transition-colors hover:border-[#38BDF8] hover:text-[#38BDF8] disabled:opacity-50"
        >
          <RefreshCw
            className={`h-3.5 w-3.5 ${
              refreshMutation.isPending ? "animate-spin text-[#38BDF8]" : ""
            }`}
          />
          <span>
            {refreshMutation.isPending
              ? t.watchlist.refreshing
              : t.watchlist.refreshEpisodes}
          </span>
        </button>
      </div>

      {/* Filter Pills */}
      {items.length > 0 && (
        <div className="flex flex-wrap items-center gap-2">
          {(
            [
              { id: "ALL", label: t.watchlist.filterAll },
              {
                id: "NEW",
                label:
                  newCount > 0
                    ? `${t.watchlist.filterNew} (${newCount})`
                    : t.watchlist.filterNew,
              },
              { id: "ANIME", label: t.watchlist.filterAnime },
              { id: "TV", label: t.watchlist.filterSeries },
              { id: "MOVIE", label: t.watchlist.filterMovies },
            ] as { id: WatchlistFilter; label: string }[]
          ).map((pill) => (
            <button
              key={pill.id}
              onClick={() => setFilter(pill.id)}
              className={`rounded-full px-3.5 py-1.5 text-xs font-semibold transition-colors ${
                filter === pill.id
                  ? "bg-[#38BDF8] text-[#0B0E14]"
                  : "border border-[#283347] bg-[#141923] text-[#94A3B8] hover:text-[#F8FAFC]"
              }`}
            >
              {pill.label}
            </button>
          ))}
        </div>
      )}

      {isLoading ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
          {Array.from({ length: 6 }).map((_, idx) => (
            <div
              key={idx}
              className="aspect-[2/3] w-full animate-pulse rounded-xl bg-[#1C2331]"
            />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-2xl border border-[#283347] bg-[#141923] p-12 text-center text-sm text-[#94A3B8]">
          {t.watchlist.empty}
        </div>
      ) : filteredItems.length === 0 ? (
        <div className="rounded-2xl border border-[#283347] bg-[#141923] p-12 text-center text-sm text-[#94A3B8]">
          {t.watchlist.emptyFiltered}
        </div>
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
          {filteredItems.map((item) => {
            const latestEp =
              item.latestAvailableEpisode ?? item.latestEpisodeCount ?? 0;
            const highlightBadge = item.hasNewEpisode
              ? latestEp > 0
                ? `${t.watchlist.newEpisodeBadge} · ${epPrefix}${latestEp}`
                : t.watchlist.newEpisodeBadge
              : null;

            const watchedSubtitle =
              item.lastWatchedEpisode != null && item.lastWatchedEpisode > 0
                ? `${epPrefix}${item.lastWatchedEpisode} ${t.watchlist.watchedProgress}`
                : null;

            return (
              <div
                key={`${item.sourceId}_${item.mediaId}`}
                className="group relative"
              >
                <MediaCardView
                  title={item.title}
                  posterUrl={item.posterUrl}
                  type={item.type}
                  year={item.year}
                  rating={item.rating}
                  subtitle={watchedSubtitle}
                  highlightBadge={highlightBadge}
                  onClick={() =>
                    openDetails({
                      sourceId: item.sourceId,
                      mediaId: item.mediaId,
                      mediaType: item.type,
                    })
                  }
                />
                <button
                  onClick={() => removeMutation.mutate(item)}
                  title={t.watchlist.remove}
                  className="absolute bottom-14 right-2 z-20 rounded-lg bg-[#0B0E14]/90 p-1.5 text-rose-400 opacity-0 shadow-md backdrop-blur-sm transition-opacity hover:bg-rose-500 hover:text-white group-hover:opacity-100"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
