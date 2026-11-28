import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  CheckCircle2,
  Loader2,
  Plus,
  Radio,
  RefreshCw,
  Send,
  ShieldCheck,
  Trash2,
  Tv,
  XCircle,
} from "lucide-react";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import type {
  DiscoveredDevice,
  MediaType,
  SourceHealthResult,
  SyncSummary,
} from "../types/models";

type ResourceCategoryFilter = "ALL" | MediaType;

export function ResourcesPage() {
  const { prefs, t } = usePreferences();
  const queryClient = useQueryClient();

  const [categoryFilter, setCategoryFilter] =
    useState<ResourceCategoryFilter>("ALL");

  const [sourceUrl, setSourceUrl] = useState("");
  const [sourceError, setSourceError] = useState<string | null>(null);
  const [healthMap, setHealthMap] = useState<Record<string, SourceHealthResult>>(
    {}
  );
  const [testingId, setTestingId] = useState<string | null>(null);

  // TV Sync State
  const [discovered, setDiscovered] = useState<DiscoveredDevice[]>([]);
  const [host, setHost] = useState("");
  const [port, setPort] = useState("8787");
  const [pin, setPin] = useState("");
  const [tvStatus, setTvStatus] = useState<string | null>(null);
  const [syncSummary, setSyncSummary] = useState<SyncSummary | null>(null);
  const [sendUrl, setSendUrl] = useState("");

  const { data: sources = [] } = useQuery({
    queryKey: ["sources"],
    queryFn: api.getSources,
  });

  const { data: pairedDevices = [] } = useQuery({
    queryKey: ["pairedDevices"],
    queryFn: api.getPairedDevices,
  });

  const addSourceMutation = useMutation({
    mutationFn: (url: string) => api.addSourceFromUrl(url),
    onSuccess: () => {
      setSourceUrl("");
      setSourceError(null);
      queryClient.invalidateQueries({ queryKey: ["sources"] });
      queryClient.invalidateQueries({ queryKey: ["home"] });
    },
    onError: (err: unknown) => {
      setSourceError(String(err));
    },
  });

  const toggleSourceMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      api.setSourceEnabled(id, enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sources"] });
      queryClient.invalidateQueries({ queryKey: ["home"] });
    },
  });

  const removeSourceMutation = useMutation({
    mutationFn: (id: string) => api.removeSource(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["sources"] });
    },
  });

  const handleTestSource = async (id: string) => {
    setTestingId(id);
    try {
      const res = await api.testSource(id);
      setHealthMap((prev) => ({ ...prev, [id]: res }));
    } catch (err) {
      setHealthMap((prev) => ({
        ...prev,
        [id]: { ok: false, latencyMs: 0, itemCount: 0, error: String(err) },
      }));
    } finally {
      setTestingId(null);
    }
  };

  const discoverMutation = useMutation({
    mutationFn: api.discoverTvDevices,
    onSuccess: (devices) => {
      setDiscovered(devices);
      if (devices.length > 0 && !host) {
        setHost(devices[0].host);
        setPort(String(devices[0].port));
      }
      setTvStatus(
        devices.length > 0
          ? `${devices.length} cihaz bulundu`
          : "Ağda TV bulunamadı"
      );
    },
    onError: (err) => setTvStatus(String(err)),
  });

  const pairMutation = useMutation({
    mutationFn: () => api.pairTvDevice(host.trim(), Number(port) || 8787, pin.trim()),
    onSuccess: (dev) => {
      setPin("");
      setTvStatus(`${dev.name} eşleştirildi!`);
      queryClient.invalidateQueries({ queryKey: ["pairedDevices"] });
    },
    onError: (err) => setTvStatus(String(err)),
  });

  const syncMutation = useMutation({
    mutationFn: (deviceId: string) => api.syncWithTv(deviceId),
    onSuccess: (summary) => {
      setSyncSummary(summary);
      queryClient.invalidateQueries({ queryKey: ["pairedDevices"] });
      queryClient.invalidateQueries({ queryKey: ["sources"] });
      queryClient.invalidateQueries({ queryKey: ["continueWatching"] });
      queryClient.invalidateQueries({ queryKey: ["watchlist"] });
    },
    onError: (err) => setTvStatus(String(err)),
  });

  const removeDeviceMutation = useMutation({
    mutationFn: (id: string) => api.removePairedDevice(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["pairedDevices"] });
    },
  });

  const sendUrlMutation = useMutation({
    mutationFn: () =>
      api.sendSourceUrlToTv(
        host.trim() || pairedDevices[0]?.host || "",
        Number(port) || pairedDevices[0]?.port || 8787,
        sendUrl.trim()
      ),
    onSuccess: (msg) => {
      setSendUrl("");
      setTvStatus(msg || "URL TV'ye gönderildi!");
    },
    onError: (err) => setTvStatus(String(err)),
  });

  return (
    <div className="space-y-8 pb-12">
      <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
        {t.resources.title}
      </h1>

      {/* Add Source URL */}
      <section className="rounded-2xl border border-[#283347] bg-[#141923] p-5 space-y-3">
        <h2 className="text-base font-semibold text-[#F8FAFC]">
          {t.resources.addSourceTitle}
        </h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (sourceUrl.trim()) {
              addSourceMutation.mutate(sourceUrl.trim());
            }
          }}
          className="flex flex-col gap-3 sm:flex-row"
        >
          <input
            type="url"
            value={sourceUrl}
            onChange={(e) => setSourceUrl(e.target.value)}
            placeholder={t.resources.addSourcePlaceholder}
            className="flex-1 rounded-xl border border-[#283347] bg-[#0B0E14] px-4 py-2.5 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none focus:border-[#38BDF8]"
          />
          <button
            type="submit"
            disabled={addSourceMutation.isPending || !sourceUrl.trim()}
            className="flex items-center justify-center gap-2 rounded-xl bg-[#38BDF8] px-5 py-2.5 text-sm font-semibold text-[#0B0E14] transition-opacity disabled:opacity-50"
          >
            {addSourceMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Plus className="h-4 w-4" />
            )}
            <span>
              {addSourceMutation.isPending
                ? t.resources.adding
                : t.resources.addSourceBtn}
            </span>
          </button>
        </form>
        {sourceError && (
          <p className="text-xs text-rose-400">{sourceError}</p>
        )}
      </section>

      {/* Installed Sources */}
      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold text-[#F8FAFC]">
            {t.resources.installedSources}
          </h2>
          <div className="flex flex-wrap items-center gap-1.5">
            {(
              [
                { id: "ALL", label: t.resources.filterAll },
                { id: "MOVIE", label: t.resources.filterMovies },
                { id: "TV", label: t.resources.filterSeries },
                { id: "ANIME", label: t.resources.filterAnime },
              ] as { id: ResourceCategoryFilter; label: string }[]
            ).map((tab) => (
              <button
                key={tab.id}
                onClick={() => setCategoryFilter(tab.id)}
                className={`rounded-full px-3 py-1 text-xs font-semibold transition-colors ${
                  categoryFilter === tab.id
                    ? "bg-[#38BDF8] text-[#0B0E14]"
                    : "border border-[#283347] bg-[#141923] text-[#94A3B8] hover:text-[#F8FAFC]"
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>
        <div className="grid gap-3">
          {sources
            .filter(
              (src) =>
                categoryFilter === "ALL" ||
                src.supportedTypes.includes(categoryFilter)
            )
            .map((src) => {
              const health = healthMap[src.id];
              const isPrimary =
                src.id === prefs.primaryMovieSourceId ||
                src.id === prefs.primaryTvSourceId ||
                src.id === prefs.primaryAnimeSourceId;
              return (
                <div
                  key={src.id}
                  className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-[#283347] bg-[#141923] p-4"
                >
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-semibold text-[#F8FAFC]">
                        {src.name}
                      </span>
                      <span className="rounded-md bg-[#1C2331] px-2 py-0.5 text-[11px] font-medium text-[#94A3B8]">
                        v{src.version}
                      </span>
                      <span className="rounded-md bg-[#818CF8]/15 px-2 py-0.5 text-[11px] font-medium text-[#818CF8]">
                        {src.driverType}
                      </span>
                      {src.isBuiltIn && (
                        <span className="rounded-md bg-[#38BDF8]/15 px-2 py-0.5 text-[11px] font-medium text-[#38BDF8]">
                          {t.resources.builtIn}
                        </span>
                      )}
                      {isPrimary && (
                        <span className="rounded-md bg-emerald-500/20 px-2 py-0.5 text-[11px] font-semibold text-emerald-400">
                          {t.resources.primaryBadge}
                        </span>
                      )}
                      {src.supportedTypes.map((st) => (
                        <span
                          key={st}
                          className="rounded-md border border-[#283347] bg-[#0B0E14] px-1.5 py-0.5 text-[10px] font-semibold text-[#94A3B8]"
                        >
                          {st}
                        </span>
                      ))}
                    </div>
                  <p className="truncate text-xs text-[#94A3B8]">
                    {src.baseUrl}
                  </p>
                  {health && (
                    <div className="flex items-center gap-2 pt-1 text-xs">
                      {health.ok ? (
                        <span className="inline-flex items-center gap-1 text-emerald-400">
                          <CheckCircle2 className="h-3.5 w-3.5" />
                          OK · {health.latencyMs}ms · {health.itemCount} öğe
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-rose-400">
                          <XCircle className="h-3.5 w-3.5" />
                          {health.error || "Failed"}
                        </span>
                      )}
                    </div>
                  )}
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleTestSource(src.id)}
                    disabled={testingId === src.id}
                    className="flex items-center gap-1.5 rounded-xl border border-[#283347] bg-[#1C2331] px-3 py-1.5 text-xs font-medium text-[#F8FAFC] hover:border-[#38BDF8]"
                  >
                    {testingId === src.id ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    ) : (
                      <Activity className="h-3.5 w-3.5 text-[#38BDF8]" />
                    )}
                    <span>
                      {testingId === src.id
                        ? t.resources.testing
                        : t.resources.testHealth}
                    </span>
                  </button>

                  <button
                    onClick={() =>
                      toggleSourceMutation.mutate({
                        id: src.id,
                        enabled: !src.enabled,
                      })
                    }
                    className={`rounded-xl px-3 py-1.5 text-xs font-semibold transition-colors ${
                      src.enabled
                        ? "bg-emerald-500/15 text-emerald-400 hover:bg-emerald-500/25"
                        : "bg-[#1C2331] text-[#94A3B8] hover:text-[#F8FAFC]"
                    }`}
                  >
                    {src.enabled ? t.resources.enabled : t.resources.disabled}
                  </button>

                  {!src.isBuiltIn && (
                    <button
                      onClick={() => removeSourceMutation.mutate(src.id)}
                      className="rounded-xl border border-[#283347] bg-[#1C2331] p-1.5 text-rose-400 hover:bg-rose-500/15"
                      title={t.resources.remove}
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </section>

      {/* Android TV Sync & Pair Panel */}
      <section className="space-y-5 rounded-2xl border border-[#283347] bg-[#141923] p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#38BDF8]/15 text-[#38BDF8]">
              <Tv className="h-5 w-5" />
            </div>
            <h2 className="text-lg font-semibold text-[#F8FAFC]">
              {t.resources.tvSyncTitle}
            </h2>
          </div>

          <button
            onClick={() => discoverMutation.mutate()}
            disabled={discoverMutation.isPending}
            className="flex items-center gap-2 rounded-xl border border-[#38BDF8]/40 bg-[#38BDF8]/10 px-4 py-2 text-xs font-semibold text-[#38BDF8] hover:bg-[#38BDF8]/20 disabled:opacity-50"
          >
            {discoverMutation.isPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Radio className="h-4 w-4" />
            )}
            <span>
              {discoverMutation.isPending
                ? t.resources.discovering
                : t.resources.discoverBtn}
            </span>
          </button>
        </div>

        {/* Discovered TVs */}
        {discovered.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {discovered.map((dev) => (
              <button
                key={`${dev.host}:${dev.port}`}
                onClick={() => {
                  setHost(dev.host);
                  setPort(String(dev.port));
                }}
                className="flex items-center gap-2 rounded-xl border border-[#38BDF8]/40 bg-[#1C2331] px-3 py-2 text-xs text-[#F8FAFC] hover:border-[#38BDF8]"
              >
                <Tv className="h-3.5 w-3.5 text-[#38BDF8]" />
                <span>
                  {dev.name} ({dev.host}:{dev.port})
                </span>
              </button>
            ))}
          </div>
        )}

        {/* Manual Pair Form */}
        <div className="space-y-2">
          <p className="text-xs font-medium text-[#94A3B8]">
            {t.resources.manualPairTitle}
          </p>
          <div className="grid gap-3 sm:grid-cols-4">
            <input
              type="text"
              value={host}
              onChange={(e) => setHost(e.target.value)}
              placeholder={t.resources.hostPlaceholder}
              className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3.5 py-2 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none focus:border-[#38BDF8]"
            />
            <input
              type="text"
              value={port}
              onChange={(e) => setPort(e.target.value)}
              placeholder={t.resources.portPlaceholder}
              className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3.5 py-2 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none focus:border-[#38BDF8]"
            />
            <input
              type="text"
              maxLength={6}
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              placeholder={t.resources.pinPlaceholder}
              className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3.5 py-2 text-sm tracking-widest text-[#F8FAFC] placeholder-[#94A3B8] outline-none focus:border-[#38BDF8]"
            />
            <button
              onClick={() => pairMutation.mutate()}
              disabled={pairMutation.isPending || !host.trim() || !pin.trim()}
              className="flex items-center justify-center gap-2 rounded-xl bg-[#818CF8] px-4 py-2 text-sm font-semibold text-[#0B0E14] disabled:opacity-50"
            >
              <ShieldCheck className="h-4 w-4" />
              <span>
                {pairMutation.isPending
                  ? t.resources.pairing
                  : t.resources.pairBtn}
              </span>
            </button>
          </div>
        </div>

        {/* Paired Devices */}
        <div className="space-y-2.5 pt-2">
          <h3 className="text-sm font-semibold text-[#F8FAFC]">
            {t.resources.pairedDevices}
          </h3>
          {pairedDevices.length === 0 ? (
            <p className="text-xs text-[#94A3B8]">
              {t.resources.noPairedDevices}
            </p>
          ) : (
            <div className="space-y-2">
              {pairedDevices.map((dev) => (
                <div
                  key={dev.id}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-[#283347] bg-[#1C2331] p-3.5"
                >
                  <div>
                    <p className="text-sm font-semibold text-[#F8FAFC]">
                      {dev.name}
                    </p>
                    <p className="text-xs text-[#94A3B8]">
                      {dev.host}:{dev.port} · {t.resources.lastSynced}{" "}
                      {dev.lastSyncedAt
                        ? new Date(dev.lastSyncedAt).toLocaleString()
                        : t.resources.never}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => syncMutation.mutate(dev.id)}
                      disabled={syncMutation.isPending}
                      className="flex items-center gap-1.5 rounded-xl bg-[#38BDF8] px-3.5 py-2 text-xs font-semibold text-[#0B0E14] disabled:opacity-50"
                    >
                      <RefreshCw
                        className={`h-3.5 w-3.5 ${
                          syncMutation.isPending ? "animate-spin" : ""
                        }`}
                      />
                      <span>
                        {syncMutation.isPending
                          ? t.resources.syncing
                          : t.resources.syncNow}
                      </span>
                    </button>
                    <button
                      onClick={() => removeDeviceMutation.mutate(dev.id)}
                      className="rounded-xl border border-[#283347] bg-[#141923] p-2 text-rose-400 hover:bg-rose-500/15"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Send Source URL to TV */}
        <div className="space-y-2 border-t border-[#283347] pt-4">
          <p className="text-xs font-medium text-[#94A3B8]">
            {t.resources.sendUrlTitle}
          </p>
          <div className="flex gap-2">
            <input
              type="url"
              value={sendUrl}
              onChange={(e) => setSendUrl(e.target.value)}
              placeholder="https://..."
              className="flex-1 rounded-xl border border-[#283347] bg-[#0B0E14] px-3.5 py-2 text-sm text-[#F8FAFC] placeholder-[#94A3B8] outline-none focus:border-[#38BDF8]"
            />
            <button
              onClick={() => sendUrlMutation.mutate()}
              disabled={
                sendUrlMutation.isPending ||
                !sendUrl.trim() ||
                (!host.trim() && pairedDevices.length === 0)
              }
              className="flex items-center gap-1.5 rounded-xl border border-[#283347] bg-[#1C2331] px-4 py-2 text-xs font-semibold text-[#F8FAFC] hover:border-[#38BDF8] disabled:opacity-50"
            >
              <Send className="h-3.5 w-3.5 text-[#38BDF8]" />
              <span>{t.resources.sendUrlBtn}</span>
            </button>
          </div>
        </div>

        {/* Status / SyncSummary Feedback */}
        {tvStatus && (
          <p className="text-xs font-medium text-[#38BDF8]">{tvStatus}</p>
        )}
        {syncSummary && (
          <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3 text-xs text-emerald-300">
            <span className="font-semibold">{t.resources.syncResult}:</span>{" "}
            ↑ {syncSummary.pushedSources} kaynak, ↓ {syncSummary.pulledSources}{" "}
            kaynak, ⏱ {syncSummary.mergedProgress} ilerleme, ★{" "}
            {syncSummary.mergedWatchlist} izleme listesi
          </div>
        )}
      </section>
    </div>
  );
}
