import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../lib/tauri";
import { getTranslations } from "../lib/i18n";
import type { AppPreferences } from "../types/models";

const DEFAULT_PREFS: AppPreferences = {
  playerEngine: "libmpv",
  hardwareDecoding: true,
  preferredQuality: "Auto",
  preferredSubtitleLang: "tr",
  language: "tr",
  subtitleAppearance: {
    fontSizeSp: 20,
    textColorArgb: 0xffffffff,
    backgroundColorArgb: 0x80000000,
    edgeType: 1,
  },
  primaryMovieSourceId: "builtin_movy_sx",
  primaryTvSourceId: "builtin_movy_sx",
  primaryAnimeSourceId: "builtin_reanime",
  trackSeriesNewEpisodes: false,
};

export function usePreferences() {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: ["preferences"],
    queryFn: api.getPreferences,
  });

  const prefs: AppPreferences = query.data
    ? { ...DEFAULT_PREFS, ...query.data }
    : DEFAULT_PREFS;
  const t = getTranslations(prefs.language);

  const saveMutation = useMutation({
    mutationFn: (next: AppPreferences) => api.savePreferences(next),
    onSuccess: (_data, next) => {
      queryClient.setQueryData(["preferences"], next);
    },
  });

  return {
    prefs,
    t,
    isLoading: query.isLoading,
    savePreferences: saveMutation.mutateAsync,
    isSaving: saveMutation.isPending,
  };
}
