import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Loader2, Search as SearchIcon } from "lucide-react";
import { MediaCardView } from "../components/MediaCardView";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";

export function SearchPage() {
  const { t } = usePreferences();
  const searchQuery = useAppStore((s) => s.searchQuery);
  const setSearchQuery = useAppStore((s) => s.setSearchQuery);
  const activeSourceId = useAppStore((s) => s.activeSourceId);
  const openDetails = useAppStore((s) => s.openDetails);

  const [debouncedQuery, setDebouncedQuery] = useState(searchQuery.trim());

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(searchQuery.trim());
    }, 350);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const { data: results = [], isFetching } = useQuery({
    queryKey: ["search", debouncedQuery, activeSourceId],
    queryFn: () => api.searchMedia(debouncedQuery, 1, activeSourceId),
    enabled: debouncedQuery.length >= 2,
  });

  const nonEmptyGroups = results.filter((g) => g.result.items.length > 0);

  return (
    <div className="space-y-6 pb-12">
      <div className="space-y-3">
        <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
          {t.search.title}
        </h1>
        <div className="relative max-w-2xl">
          <SearchIcon className="pointer-events-none absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-[#94A3B8]" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder={t.search.placeholder}
            autoFocus
            className="w-full rounded-2xl border border-[#283347] bg-[#141923] py-3 pl-12 pr-4 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none transition-colors focus:border-[#38BDF8]"
          />
        </div>
      </div>

      {debouncedQuery.length < 2 ? (
        <div className="rounded-2xl border border-[#283347] bg-[#141923] p-10 text-center text-sm text-[#94A3B8]">
          {t.search.hint}
        </div>
      ) : isFetching ? (
        <div className="flex items-center gap-2 text-sm text-[#94A3B8]">
          <Loader2 className="h-4 w-4 animate-spin text-[#38BDF8]" />
          <span>{t.search.searching}</span>
        </div>
      ) : nonEmptyGroups.length === 0 ? (
        <div className="rounded-2xl border border-[#283347] bg-[#141923] p-10 text-center text-sm text-[#94A3B8]">
          {t.search.noResults}
        </div>
      ) : (
        <div className="space-y-8">
          {nonEmptyGroups.map((group) => (
            <section key={group.sourceId} className="space-y-3">
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-semibold text-[#F8FAFC]">
                  {group.sourceName}
                </h2>
                <span className="rounded-full bg-[#1C2331] px-2.5 py-0.5 text-xs text-[#94A3B8]">
                  {group.result.items.length}
                </span>
              </div>

              <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-5">
                {group.result.items.map((item) => (
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
            </section>
          ))}
        </div>
      )}
    </div>
  );
}
