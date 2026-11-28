import { useEffect, useRef, useState } from "react";
import { CheckCircle2, MoreVertical, Play, Star, Trash2 } from "lucide-react";
import type { MediaType } from "../types/models";

interface MediaCardViewProps {
  title: string;
  posterUrl?: string | null;
  type: MediaType;
  year?: number | null;
  rating?: number | null;
  subtitle?: string | null;
  progressRatio?: number;
  episodeBadge?: string | null;
  highlightBadge?: string | null;
  onClick: () => void;
  contextActions?: {
    removeLabel?: string;
    onRemove?: () => void;
    markWatchedLabel?: string;
    onMarkWatched?: () => void;
  };
}

export function MediaCardView({
  title,
  posterUrl,
  type,
  year,
  rating,
  subtitle,
  progressRatio,
  episodeBadge,
  highlightBadge,
  onClick,
  contextActions,
}: MediaCardViewProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    window.addEventListener("mousedown", onOutside);
    return () => window.removeEventListener("mousedown", onOutside);
  }, [menuOpen]);

  return (
    <div
      className="group relative flex w-[170px] shrink-0 cursor-pointer flex-col gap-2"
      onClick={onClick}
      onContextMenu={(e) => {
        if (!contextActions) return;
        e.preventDefault();
        setMenuOpen((v) => !v);
      }}
    >
      {/* Poster Container */}
      <div className="relative aspect-[2/3] w-full overflow-hidden rounded-xl border border-[#283347] bg-[#1C2331] shadow-md transition-all duration-200 group-hover:scale-[1.03] group-hover:border-[#38BDF8] group-hover:shadow-xl group-hover:shadow-[#38BDF8]/15">
        {posterUrl ? (
          <img
            src={posterUrl}
            alt={title}
            loading="lazy"
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-105"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-[#1C2331] to-[#141923] p-3 text-center text-xs font-medium text-[#94A3B8]">
            {title}
          </div>
        )}

        {/* Hover play overlay */}
        <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition-opacity group-hover:opacity-100">
          <div className="flex h-11 w-11 items-center justify-center rounded-full bg-[#38BDF8] text-[#0B0E14] shadow-lg">
            <Play className="ml-0.5 h-5 w-5 fill-current" />
          </div>
        </div>

        {/* Top badges */}
        <div className="absolute left-2 top-2 flex flex-wrap items-center gap-1.5 pr-12">
          <span className="rounded-md bg-[#0B0E14]/80 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-[#38BDF8] backdrop-blur-sm">
            {type}
          </span>
          {highlightBadge && (
            <span className="rounded-md bg-emerald-400 px-1.5 py-0.5 text-[10px] font-bold text-[#0B0E14] shadow-md shadow-emerald-400/30">
              {highlightBadge}
            </span>
          )}
          {episodeBadge && (
            <span className="rounded-md bg-[#818CF8]/90 px-1.5 py-0.5 text-[10px] font-bold text-[#0B0E14]">
              {episodeBadge}
            </span>
          )}
        </div>

        {/* Rating or Context Menu button */}
        <div className="absolute right-2 top-2 flex items-center gap-1">
          {rating != null && rating > 0 && (
            <span className="flex items-center gap-0.5 rounded-md bg-[#0B0E14]/80 px-1.5 py-0.5 text-[10px] font-semibold text-amber-400 backdrop-blur-sm">
              <Star className="h-2.5 w-2.5 fill-amber-400" />
              {rating.toFixed(1)}
            </span>
          )}
          {contextActions && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                setMenuOpen((v) => !v);
              }}
              className="rounded-md bg-[#0B0E14]/85 p-1 text-[#F8FAFC] opacity-80 backdrop-blur-sm transition-opacity hover:bg-[#38BDF8] hover:text-[#0B0E14] group-hover:opacity-100"
            >
              <MoreVertical className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        {/* Progress Bar */}
        {progressRatio != null && progressRatio > 0 && (
          <div className="absolute bottom-0 left-0 right-0 h-1.5 bg-[#0B0E14]/80">
            <div
              className="h-full bg-[#38BDF8] transition-all"
              style={{ width: `${Math.min(100, Math.max(2, progressRatio * 100))}%` }}
            />
          </div>
        )}
      </div>

      {/* Title + Meta */}
      <div className="px-0.5">
        <p className="truncate text-sm font-medium text-[#F8FAFC] group-hover:text-[#38BDF8]">
          {title}
        </p>
        {(year || subtitle) && (
          <p className="truncate text-xs text-[#94A3B8]">
            {subtitle ?? year}
          </p>
        )}
      </div>

      {/* Context Menu Popover */}
      {menuOpen && contextActions && (
        <div
          ref={menuRef}
          onClick={(e) => e.stopPropagation()}
          className="absolute right-2 top-10 z-30 w-48 overflow-hidden rounded-xl border border-[#283347] bg-[#141923] p-1 shadow-2xl"
        >
          {contextActions.onMarkWatched && (
            <button
              onClick={() => {
                setMenuOpen(false);
                contextActions.onMarkWatched?.();
              }}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-[#F8FAFC] transition-colors hover:bg-[#1C2331] hover:text-[#38BDF8]"
            >
              <CheckCircle2 className="h-3.5 w-3.5 text-[#38BDF8]" />
              <span>{contextActions.markWatchedLabel}</span>
            </button>
          )}
          {contextActions.onRemove && (
            <button
              onClick={() => {
                setMenuOpen(false);
                contextActions.onRemove?.();
              }}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs font-medium text-rose-400 transition-colors hover:bg-rose-500/15"
            >
              <Trash2 className="h-3.5 w-3.5" />
              <span>{contextActions.removeLabel}</span>
            </button>
          )}
        </div>
      )}
    </div>
  );
}
