import {
  Bookmark,
  ChevronLeft,
  ChevronRight,
  Clapperboard,
  Film,
  Home,
  Search,
  Server,
  Settings,
  Sparkles,
  Tv,
} from "lucide-react";
import { usePreferences } from "../hooks/usePreferences";
import { useAppStore, type NavRoute } from "../store/appStore";

export function Sidebar() {
  const { t } = usePreferences();
  const route = useAppStore((s) => s.route);
  const collapsed = useAppStore((s) => s.sidebarCollapsed);
  const navigate = useAppStore((s) => s.navigate);
  const toggleSidebar = useAppStore((s) => s.toggleSidebar);

  const items: { id: NavRoute; label: string; icon: typeof Home }[] = [
    { id: "home", label: t.nav.home, icon: Home },
    { id: "movies", label: t.nav.movies, icon: Film },
    { id: "series", label: t.nav.series, icon: Tv },
    { id: "anime", label: t.nav.anime, icon: Sparkles },
    { id: "search", label: t.nav.search, icon: Search },
    { id: "watchlist", label: t.nav.watchlist, icon: Bookmark },
    { id: "resources", label: t.nav.resources, icon: Server },
    { id: "settings", label: t.nav.settings, icon: Settings },
  ];

  return (
    <aside
      className={`flex h-full shrink-0 flex-col border-r border-[#283347] bg-[#141923] transition-all duration-200 ${
        collapsed ? "w-[72px]" : "w-[228px]"
      }`}
    >
      {/* Brand */}
      <div className="flex h-16 items-center justify-between border-b border-[#283347] px-4">
        <div className="flex items-center gap-2.5 overflow-hidden">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-[#38BDF8] to-[#818CF8] text-[#0B0E14] shadow-lg shadow-[#38BDF8]/20">
            <Clapperboard className="h-5 w-5" />
          </div>
          {!collapsed && (
            <span className="truncate text-lg font-bold tracking-tight text-[#F8FAFC]">
              {t.appName}
            </span>
          )}
        </div>
        <button
          onClick={toggleSidebar}
          className="rounded-lg p-1.5 text-[#94A3B8] transition-colors hover:bg-[#1C2331] hover:text-[#F8FAFC]"
          title="Toggle sidebar"
        >
          {collapsed ? (
            <ChevronRight className="h-4 w-4" />
          ) : (
            <ChevronLeft className="h-4 w-4" />
          )}
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 space-y-1 overflow-y-auto p-3">
        {items.map((item) => {
          const Icon = item.icon;
          const active = route === item.id;
          return (
            <button
              key={item.id}
              onClick={() => navigate(item.id)}
              title={collapsed ? item.label : undefined}
              className={`group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all ${
                active
                  ? "bg-[#38BDF8]/15 text-[#38BDF8] shadow-inner shadow-[#38BDF8]/10"
                  : "text-[#94A3B8] hover:bg-[#1C2331] hover:text-[#F8FAFC]"
              }`}
            >
              <Icon
                className={`h-5 w-5 shrink-0 transition-transform group-hover:scale-105 ${
                  active ? "text-[#38BDF8]" : "text-[#94A3B8] group-hover:text-[#F8FAFC]"
                }`}
              />
              {!collapsed && <span className="truncate">{item.label}</span>}
            </button>
          );
        })}
      </nav>

      {/* Version badge */}
      {!collapsed && (
        <div className="border-t border-[#283347] px-4 py-3 text-xs text-[#94A3B8]">
          AtrisStream Desktop v0.1.0
        </div>
      )}
    </aside>
  );
}
