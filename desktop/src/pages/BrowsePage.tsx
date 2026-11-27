import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { MediaCardView } from "../components/MediaCardView";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";
import type { MediaCard, MediaType } from "../types/models";

interface BrowsePageProps {
  mediaType: MediaType;
  title: string;
}

const MAX_BROWSE_ITEMS = 200;

export function BrowsePage({ mediaType, title }: BrowsePageProps) {
  const { t } = usePreferences();
  const activeSourceId = useAppStore((s) => s.activeSourceId);
  const openDetails = useAppStore((s) => s.openDetails);

  const { data: sources = [] } = useQuery({
    queryKey: ["sources"],
    queryFn: api.getSources,
  });

  const targetSourceId =
    activeSourceId ??
    sources.find((s) => s.enabled && s.supportedTypes.includes(mediaType))?.id ??
    sources.find((s) => s.enabled)?.id ??
    "builtin_movy_sx";

  const [page, setPage] = useState(1);
  const [items, setItems] = useState<MediaCard[]>([]);
  const [hasNextPage, setHasNextPage] = useState(false);

  // Reset pagination when mediaType or source changes
  useEffect(() => {
    setPage(1);
    setItems([]);
    setHasNextPage(false);
  }, [mediaType, targetSourceId]);

  const { data, isFetching } = useQuery({
    queryKey: ["browse", targetSourceId, mediaType, page],
    queryFn: () => api.browseMedia(targetSourceId, mediaType, page),
    enabled: Boolean(targetSourceId),
  });

  useEffect(() => {
    if (!data) return;
    setItems((prev) => {
      const base = page === 1 ? [] : prev;
      const seen = new Set(base.map((i) => `${i.sourceId}_${i.type}_${i.id}`));
      const merged = [...base];
      for (const item of data.items) {
        const key = `${item.sourceId}_${item.type}_${item.id}`;
        if (!seen.has(key) && merged.length < MAX_BROWSE_ITEMS) {
          seen.add(key);
          merged.push(item);
        }
      }
      return merged;
    });
    setHasNextPage(data.hasNextPage);
  }, [data, page]);

  return (
    <div className="space-y-6 pb-12">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
            {title}
          </h1>
          <p className="text-xs text-[#94A3B8]">
            {items.length} {t.browse.itemsCount}
          </p>
        </div>
      </div>

      {items.length === 0 && isFetching ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
          {Array.from({ length: 12 }).map((_, idx) => (
            <div
              key={idx}
              className="aspect-[2/3] w-full animate-pulse rounded-xl bg-[#1C2331]"
            />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-2xl border border-[#283347] bg-[#141923] p-12 text-center text-sm text-[#94A3B8]">
          {t.browse.empty}
        </div>
      ) : (
        <>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
            {items.map((item) => (
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
          </div>

          {hasNextPage && items.length < MAX_BROWSE_ITEMS && (
            <div className="flex justify-center pt-4">
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={isFetching}
                className="flex items-center gap-2 rounded-xl border border-[#283347] bg-[#1C2331] px-6 py-2.5 text-sm font-medium text-[#F8FAFC] transition-colors hover:border-[#38BDF8] hover:text-[#38BDF8] disabled:opacity-50"
              >
                {isFetching && <Loader2 className="h-4 w-4 animate-spin" />}
                <span>{isFetching ? t.browse.loading : t.browse.loadMore}</span>
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
