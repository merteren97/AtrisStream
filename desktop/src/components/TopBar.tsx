import { useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search, Tv } from "lucide-react";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import { useAppStore } from "../store/appStore";

export function TopBar() {
  const { t } = usePreferences();
  const searchInputRef = useRef<HTMLInputElement>(null);

  const activeSourceId = useAppStore((s) => s.activeSourceId);
  const setActiveSourceId = useAppStore((s) => s.setActiveSourceId);
  const searchQuery = useAppStore((s) => s.searchQuery);
  const setSearchQuery = useAppStore((s) => s.setSearchQuery);
  const navigate = useAppStore((s) => s.navigate);

  const { data: sources = [] } = useQuery({
    queryKey: ["sources"],
    queryFn: api.getSources,
  });

  const { data: pairedDevices = [] } = useQuery({
    queryKey: ["pairedDevices"],
    queryFn: api.getPairedDevices,
  });

  const enabledSources = sources.filter((s) => s.enabled);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        searchInputRef.current?.focus();
        navigate("search");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [navigate]);

  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-[#283347] bg-[#141923]/80 px-6 backdrop-blur-md">
      {/* Quick Search */}
      <div className="relative flex max-w-md flex-1 items-center">
        <Search className="pointer-events-none absolute left-3.5 h-4 w-4 text-[#94A3B8]" />
        <input
          ref={searchInputRef}
          type="text"
          value={searchQuery}
          onChange={(e) => {
            setSearchQuery(e.target.value);
            if (e.target.value.trim().length > 0) {
              navigate("search");
            }
          }}
          onFocus={() => navigate("search")}
          placeholder={t.topbar.quickSearchPlaceholder}
          className="w-full rounded-xl border border-[#283347] bg-[#0B0E14] py-2 pl-10 pr-4 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none transition-colors focus:border-[#38BDF8]"
        />
      </div>

      {/* Right controls */}
      <div className="flex items-center gap-3">
        {/* Source Selector */}
        <select
          value={activeSourceId ?? ""}
          onChange={(e) => setActiveSourceId(e.target.value || null)}
          className="rounded-xl border border-[#283347] bg-[#1C2331] px-3 py-2 text-xs font-medium text-[#F8FAFC] outline-none transition-colors hover:border-[#38BDF8]/60 focus:border-[#38BDF8]"
        >
          <option value="">{t.topbar.allSources}</option>
          {enabledSources.map((src) => (
            <option key={src.id} value={src.id}>
              {src.name}
            </option>
          ))}
        </select>

        {/* TV Sync Status Pill */}
        <button
          onClick={() => navigate("resources")}
          className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-medium transition-colors ${
            pairedDevices.length > 0
              ? "border-[#38BDF8]/40 bg-[#38BDF8]/10 text-[#38BDF8] hover:bg-[#38BDF8]/20"
              : "border-[#283347] bg-[#1C2331] text-[#94A3B8] hover:text-[#F8FAFC]"
          }`}
        >
          <Tv className="h-3.5 w-3.5" />
          <span>
            {pairedDevices.length > 0
              ? `${t.topbar.tvSynced} (${pairedDevices.length})`
              : t.topbar.tvNotPaired}
          </span>
        </button>
      </div>
    </header>
  );
}
