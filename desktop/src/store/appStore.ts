import { create } from "zustand";
import type { MediaType, PlayerSession } from "../types/models";

export type NavRoute =
  | "home"
  | "movies"
  | "series"
  | "anime"
  | "search"
  | "watchlist"
  | "resources"
  | "settings"
  | "details";

interface DetailsTarget {
  sourceId: string;
  mediaId: string;
  mediaType: MediaType;
}

interface AppState {
  route: NavRoute;
  previousRoute: NavRoute;
  sidebarCollapsed: boolean;
  activeSourceId: string | null;
  searchQuery: string;
  detailsTarget: DetailsTarget | null;
  playerSession: PlayerSession | null;

  navigate: (route: NavRoute) => void;
  openDetails: (target: DetailsTarget) => void;
  goBackFromDetails: () => void;
  toggleSidebar: () => void;
  setActiveSourceId: (sourceId: string | null) => void;
  setSearchQuery: (q: string) => void;
  openPlayer: (session: PlayerSession) => void;
  closePlayer: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  route: "home",
  previousRoute: "home",
  sidebarCollapsed: false,
  activeSourceId: null,
  searchQuery: "",
  detailsTarget: null,
  playerSession: null,

  navigate: (route) =>
    set((state) => ({
      route,
      previousRoute: state.route === "details" ? state.previousRoute : state.route,
    })),

  openDetails: (target) =>
    set((state) => ({
      previousRoute: state.route === "details" ? state.previousRoute : state.route,
      route: "details",
      detailsTarget: target,
    })),

  goBackFromDetails: () =>
    set((state) => ({
      route: state.previousRoute === "details" ? "home" : state.previousRoute,
    })),

  toggleSidebar: () =>
    set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),

  setActiveSourceId: (activeSourceId) => set({ activeSourceId }),

  setSearchQuery: (searchQuery) => set({ searchQuery }),

  openPlayer: (playerSession) => set({ playerSession }),

  closePlayer: () => set({ playerSession: null }),
}));
