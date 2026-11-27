import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Trash2 } from "lucide-react";
import { MediaCardView } from "../components/MediaCardView";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";
import type { WatchlistItem } from "../types/models";

export function WatchlistPage() {
  const { t } = usePreferences();
  const queryClient = useQueryClient();
  const openDetails = useAppStore((s) => s.openDetails);

  const { data: items = [], isLoading } = useQuery({
    queryKey: ["watchlist"],
    queryFn: api.getWatchlist,
  });

  const removeMutation = useMutation({
    mutationFn: (item: WatchlistItem) => api.toggleWatchlist(item),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    },
  });

  return (
    <div className="space-y-6 pb-12">
      <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
        {t.watchlist.title}
      </h1>

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
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
          {items.map((item) => (
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
          ))}
        </div>
      )}
    </div>
  );
}
