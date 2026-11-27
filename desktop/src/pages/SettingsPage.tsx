import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Cpu, Globe, Info, Subtitles, Tv } from "lucide-react";
import { usePreferences } from "../hooks/usePreferences";
import { api } from "../lib/tauri";
import type { AppPreferences, PlayerEngine } from "../types/models";

export function SettingsPage() {
  const { prefs, t, savePreferences } = usePreferences();
  const [savedToast, setSavedToast] = useState(false);

  const { data: capabilities } = useQuery({
    queryKey: ["playerCapabilities"],
    queryFn: api.getPlayerCapabilities,
  });

  const updatePrefs = async (patch: Partial<AppPreferences>) => {
    await savePreferences({ ...prefs, ...patch });
    setSavedToast(true);
    setTimeout(() => setSavedToast(false), 2000);
  };

  const bgOpacity = Math.round(
    (((prefs.subtitleAppearance.backgroundColorArgb >>> 24) & 0xff) / 255) * 100
  );

  const textHex = `#${(prefs.subtitleAppearance.textColorArgb & 0xffffff)
    .toString(16)
    .padStart(6, "0")}`;

  return (
    <div className="max-w-3xl space-y-6 pb-12">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-[#F8FAFC]">
          {t.settings.title}
        </h1>
        {savedToast && (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/15 px-3 py-1 text-xs font-medium text-emerald-400">
            <CheckCircle2 className="h-3.5 w-3.5" />
            {t.settings.saved}
          </span>
        )}
      </div>

      {/* Video Player Engine */}
      <section className="space-y-4 rounded-2xl border border-[#283347] bg-[#141923] p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Tv className="h-5 w-5 text-[#38BDF8]" />
            <h2 className="text-base font-semibold text-[#F8FAFC]">
              {t.settings.playerEngine}
            </h2>
          </div>

          {capabilities && (
            <span
              className={`rounded-full px-3 py-1 text-xs font-semibold ${
                capabilities.libmpvAvailable
                  ? "bg-emerald-500/15 text-emerald-400"
                  : "bg-amber-500/15 text-amber-300"
              }`}
            >
              {capabilities.libmpvAvailable
                ? `${t.settings.libmpvReady}${capabilities.libmpvVersion ? ` (${capabilities.libmpvVersion})` : ""}`
                : t.settings.libmpvMissing}
            </span>
          )}
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          {(["libmpv", "webview"] as PlayerEngine[]).map((engine) => {
            const active = prefs.playerEngine === engine;
            return (
              <button
                key={engine}
                onClick={() => updatePrefs({ playerEngine: engine })}
                className={`rounded-xl border p-4 text-left transition-all ${
                  active
                    ? "border-[#38BDF8] bg-[#38BDF8]/10"
                    : "border-[#283347] bg-[#1C2331] hover:border-[#38BDF8]/50"
                }`}
              >
                <p className="text-sm font-semibold text-[#F8FAFC]">
                  {engine === "libmpv"
                    ? t.settings.engineLibmpv
                    : t.settings.engineWebview}
                </p>
              </button>
            );
          })}
        </div>

        {capabilities && !capabilities.libmpvAvailable && (
          <div className="flex items-start gap-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-200">
            <Info className="mt-0.5 h-4 w-4 shrink-0 text-amber-400" />
            <span>{capabilities.installHint || t.settings.libmpvSetupHint}</span>
          </div>
        )}

        {/* Hardware Decoding */}
        <div className="flex items-center justify-between border-t border-[#283347] pt-4">
          <div className="flex items-center gap-3">
            <Cpu className="h-5 w-5 text-[#818CF8]" />
            <div>
              <p className="text-sm font-medium text-[#F8FAFC]">
                {t.settings.hardwareDecoding}
              </p>
              <p className="text-xs text-[#94A3B8]">
                {t.settings.hardwareDecodingDesc}
              </p>
            </div>
          </div>
          <button
            onClick={() =>
              updatePrefs({ hardwareDecoding: !prefs.hardwareDecoding })
            }
            className={`h-6 w-11 rounded-full transition-colors ${
              prefs.hardwareDecoding ? "bg-[#38BDF8]" : "bg-[#283347]"
            }`}
          >
            <span
              className={`block h-5 w-5 rounded-full bg-white transition-transform ${
                prefs.hardwareDecoding ? "translate-x-5" : "translate-x-0.5"
              }`}
            />
          </button>
        </div>

        {/* Preferred Quality */}
        <div className="flex items-center justify-between border-t border-[#283347] pt-4">
          <span className="text-sm font-medium text-[#F8FAFC]">
            {t.settings.preferredQuality}
          </span>
          <select
            value={prefs.preferredQuality}
            onChange={(e) => updatePrefs({ preferredQuality: e.target.value })}
            className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3 py-1.5 text-sm text-[#F8FAFC] outline-none focus:border-[#38BDF8]"
          >
            <option value="Auto">Auto</option>
            <option value="1080p">1080p</option>
            <option value="720p">720p</option>
            <option value="480p">480p</option>
          </select>
        </div>
      </section>

      {/* Subtitles */}
      <section className="space-y-4 rounded-2xl border border-[#283347] bg-[#141923] p-5">
        <div className="flex items-center gap-2">
          <Subtitles className="h-5 w-5 text-[#38BDF8]" />
          <h2 className="text-base font-semibold text-[#F8FAFC]">
            {t.settings.subtitleAppearance}
          </h2>
        </div>

        <div className="flex items-center justify-between">
          <span className="text-sm text-[#F8FAFC]">
            {t.settings.subtitleLang}
          </span>
          <select
            value={prefs.preferredSubtitleLang}
            onChange={(e) =>
              updatePrefs({ preferredSubtitleLang: e.target.value })
            }
            className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3 py-1.5 text-sm text-[#F8FAFC] outline-none focus:border-[#38BDF8]"
          >
            <option value="tr">Türkçe (tr)</option>
            <option value="en">English (en)</option>
            <option value="off">{t.settings.subtitleOff}</option>
          </select>
        </div>

        <div className="flex items-center justify-between">
          <span className="text-sm text-[#F8FAFC]">
            {t.settings.fontSize}: {prefs.subtitleAppearance.fontSizeSp}sp
          </span>
          <input
            type="range"
            min={14}
            max={34}
            value={prefs.subtitleAppearance.fontSizeSp}
            onChange={(e) =>
              updatePrefs({
                subtitleAppearance: {
                  ...prefs.subtitleAppearance,
                  fontSizeSp: Number(e.target.value),
                },
              })
            }
            className="w-44 accent-[#38BDF8]"
          />
        </div>

        <div className="flex items-center justify-between">
          <span className="text-sm text-[#F8FAFC]">{t.settings.textColor}</span>
          <input
            type="color"
            value={textHex}
            onChange={(e) => {
              const rgb = parseInt(e.target.value.slice(1), 16);
              updatePrefs({
                subtitleAppearance: {
                  ...prefs.subtitleAppearance,
                  textColorArgb: (0xff000000 | rgb) >>> 0,
                },
              });
            }}
            className="h-8 w-14 cursor-pointer rounded border border-[#283347] bg-transparent"
          />
        </div>

        <div className="flex items-center justify-between">
          <span className="text-sm text-[#F8FAFC]">
            {t.settings.bgOpacity}: {bgOpacity}%
          </span>
          <input
            type="range"
            min={0}
            max={100}
            value={bgOpacity}
            onChange={(e) => {
              const alpha = Math.round((Number(e.target.value) / 100) * 255);
              updatePrefs({
                subtitleAppearance: {
                  ...prefs.subtitleAppearance,
                  backgroundColorArgb: (alpha << 24) >>> 0,
                },
              });
            }}
            className="w-44 accent-[#38BDF8]"
          />
        </div>
      </section>

      {/* UI Language */}
      <section className="flex items-center justify-between rounded-2xl border border-[#283347] bg-[#141923] p-5">
        <div className="flex items-center gap-2.5">
          <Globe className="h-5 w-5 text-[#818CF8]" />
          <span className="text-base font-semibold text-[#F8FAFC]">
            {t.settings.uiLanguage}
          </span>
        </div>
        <select
          value={prefs.language}
          onChange={(e) =>
            updatePrefs({ language: e.target.value as "tr" | "en" })
          }
          className="rounded-xl border border-[#283347] bg-[#0B0E14] px-3.5 py-2 text-sm text-[#F8FAFC] outline-none focus:border-[#38BDF8]"
        >
          <option value="tr">Türkçe</option>
          <option value="en">English</option>
        </select>
      </section>
    </div>
  );
}
