import { Sidebar } from "./components/Sidebar";
import { TopBar } from "./components/TopBar";
import { PlayerOverlay } from "./features/player/PlayerOverlay";
import { usePreferences } from "./hooks/usePreferences";
import { BrowsePage } from "./pages/BrowsePage";
import { DetailsPage } from "./pages/DetailsPage";
import { HomePage } from "./pages/HomePage";
import { ResourcesPage } from "./pages/ResourcesPage";
import { SearchPage } from "./pages/SearchPage";
import { SettingsPage } from "./pages/SettingsPage";
import { WatchlistPage } from "./pages/WatchlistPage";
import { useAppStore } from "./store/appStore";

export default function App() {
  const { t } = usePreferences();
  const route = useAppStore((s) => s.route);
  const playerSession = useAppStore((s) => s.playerSession);

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#0B0E14] text-[#F8FAFC]">
      <Sidebar />

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <TopBar />

        <main className="flex-1 overflow-y-auto px-6 pt-6">
          {route === "home" && <HomePage />}
          {route === "movies" && (
            <BrowsePage mediaType="MOVIE" title={t.nav.movies} />
          )}
          {route === "series" && (
            <BrowsePage mediaType="TV" title={t.nav.series} />
          )}
          {route === "anime" && (
            <BrowsePage mediaType="ANIME" title={t.nav.anime} />
          )}
          {route === "search" && <SearchPage />}
          {route === "watchlist" && <WatchlistPage />}
          {route === "resources" && <ResourcesPage />}
          {route === "settings" && <SettingsPage />}
          {route === "details" && <DetailsPage />}
        </main>
      </div>

      {playerSession && <PlayerOverlay session={playerSession} />}
    </div>
  );
}
