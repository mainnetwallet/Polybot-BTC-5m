import type { ReactNode } from "react";
import type { Page } from "../lib/navigation";
import { NAV, PAGE_META } from "../lib/navigation";
import { useAuth } from "../hooks/AuthContext";
import { useBot } from "../hooks/BotContext";
import { cx, fmtFreshness, fmtHms, isNum, sentence } from "../lib/format";
import { IconAlert, IconLogout, IconPlay, IconShield, IconZap } from "./Icons";

interface TopbarProps {
  page: Page;
  onNavigate: (page: Page) => void;
  onOpenMode: () => void;
  onStartLive: () => void;
  onOpenKill: () => void;
}

function Pill({ tone, label, value, title }: { tone: "up" | "amber" | "down" | "none"; label: string; value: string; title?: string }) {
  return (
    <span className="chip border-white/10 bg-white/[0.03] !px-3.5 !py-1.5 !text-[13px] text-slate-300" title={title}>
      {tone !== "none" ? (
        <span
          className={cx("h-2 w-2 rounded-full", tone === "up" ? "bg-up" : tone === "amber" ? "bg-amber" : "bg-down")}
          aria-hidden="true"
        />
      ) : null}
      <span className="text-slate-400">{label}</span>
      <span className="min-w-[2.6em] text-right font-semibold tabular-nums text-slate-100">{value}</span>
    </span>
  );
}

export function Topbar({ page, onNavigate, onOpenMode, onStartLive, onOpenKill }: TopbarProps) {
  const { snapshot, feedState, start, stop, killSwitch: setKillSwitch, clockOffsetMs } = useBot();
  const { authRequired, username, logout } = useAuth();
  const meta = PAGE_META[page];
  const bot = snapshot?.bot;
  const running = bot ? bot.status === "RUNNING" : null;
  const killActive = bot ? bot.kill_switch : null;
  const mode = bot?.mode;
  const live = mode === "live";

  const market = snapshot?.market ?? null;
  const btc = snapshot?.btc;
  const data = snapshot?.market_data;
  const lastBook = data?.last_message_ms ? Date.now() + clockOffsetMs - data.last_message_ms : null;
  const minutes = market && market.duration_seconds > 0 ? market.duration_seconds / 60 : null;
  const subtitle =
    page === "overview" && minutes !== null
      ? `Bitcoin up or down, ${Number.isInteger(minutes) ? minutes : minutes.toFixed(1)} minutes`
      : meta.subtitle;

  const handleStart = () => {
    if (!snapshot) return;
    if (mode === "live") {
      onStartLive();
      return;
    }
    void start();
  };

  const modeButton = (
    <button
      type="button"
      onClick={() => {
        if (mode) onOpenMode();
      }}
      disabled={!mode}
      title={live ? "Live execution with real funds. Click to switch back to paper." : "Paper execution on live market data. Click to switch mode (live asks for confirmation)."}
      className={cx(
        "chip !px-3.5 !py-1.5 !text-[13px] font-semibold",
        live ? "border-down/40 bg-down/10 text-down" : "border-up/30 bg-up/10 text-up",
      )}
    >
      {live ? <IconZap size={11} /> : <span className="h-2 w-2 rounded-full bg-up" aria-hidden="true" />}
      {mode ? (live ? "Live mode" : "Paper mode") : "Loading"}
    </button>
  );

  const feedTone = feedState === "open" ? "up" : feedState === "connecting" ? "amber" : "down";
  const pills: ReactNode[] = [];
  if (feedState !== "open") {
    pills.push(<Pill key="ws" tone={feedTone} label="Dashboard feed" value={feedState === "connecting" ? "connecting" : "offline"} title="Backend state feed" />);
  }
  pills.push(
    <Pill
      key="pm"
      tone={data ? (data.connected ? "up" : "down") : "none"}
      label="Polymarket feed"
      value={data ? (data.connected ? fmtFreshness(lastBook) : "offline") : "—"}
      title="Time since the last order book message"
    />,
  );
  if (btc?.enabled !== false) {
    pills.push(
      <Pill
        key="btc"
        tone={btc ? (btc.connected && !btc.stale ? "up" : btc.connected ? "amber" : "down") : "none"}
        label="Chainlink BTC"
        value={btc ? (isNum(btc.age_ms) ? fmtFreshness(btc.age_ms) : btc.connected ? "—" : "offline") : "—"}
        title={btc?.last_error ?? "Age of the latest Chainlink BTC price"}
      />,
    );
  }
  pills.push(
    <Pill key="rd" tone="none" label="Round ends" value={market ? fmtHms(market.window_end) : "—"} title={market?.slug ?? "No active market"} />,
  );

  return (
    <header className="sticky top-0 z-30 border-b hairline bg-[var(--bg)]/85 backdrop-blur-xl">
      <div className="flex min-h-[var(--topbar-h)] flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:px-6 xl:flex-nowrap">
        <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1 xl:flex-1 xl:flex-nowrap">
          <h1 className="shrink-0 whitespace-nowrap text-[26px] font-semibold leading-none tracking-tight text-slate-100">{meta.title}</h1>
          <span className="hidden min-w-0 truncate text-[15px] text-slate-500 sm:inline" title={subtitle}>{subtitle}</span>
          <span className="shrink-0">{modeButton}</span>
          {bot?.risk_halt ? (
            <span className="chip border-amber/30 bg-amber/10 text-amber" title="Risk engine halt reason">
              <IconAlert size={11} />
              {sentence(bot.risk_halt)}
            </span>
          ) : null}
        </div>

        <div className="flex w-full flex-wrap items-center gap-2 xl:ml-auto xl:w-auto xl:shrink-0 xl:flex-nowrap">
          <div className="flex w-full flex-wrap items-center gap-2 xl:w-auto xl:flex-nowrap">{pills}</div>

          {killActive ? (
            <span className="chip border-down/35 bg-down/10 text-down">
              <span className="h-1.5 w-1.5 rounded-full bg-down" aria-hidden="true" />
              Kill switch on
            </span>
          ) : null}

          {running ? (
            <button type="button" onClick={() => void stop()} className="btn btn-stop h-10">
              <span className="h-2.5 w-2.5 rounded-[3px] bg-down" aria-hidden="true" />
              <span>Stop bot</span>
            </button>
          ) : (
            <button
              type="button"
              onClick={handleStart}
              disabled={!snapshot || killActive === true}
              title={
                killActive
                  ? "Clear the kill switch first"
                  : mode === "live"
                    ? "Start live engine (confirmation required)"
                    : "Start paper engine"
              }
              className="btn btn-primary h-10"
            >
              <IconPlay size={13} />
              <span>Start bot</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => (killActive ? void setKillSwitch(false) : onOpenKill())}
            disabled={!bot}
            className={cx("btn h-10", killActive ? "border-amber/35 bg-amber/10 text-amber" : "btn-danger")}
            title={killActive ? "Clear the emergency kill switch" : "Emergency kill switch"}
            aria-label={killActive ? "Clear the emergency kill switch" : "Emergency kill switch"}
          >
            <IconShield size={14} />
            <span className="hidden 2xl:inline">{killActive ? "Clear kill switch" : "Kill switch"}</span>
          </button>

          {authRequired ? (
            <button
              type="button"
              onClick={() => void logout()}
              className="btn btn-ghost h-10 px-2.5 lg:hidden"
              title={`Sign out${username ? ` (${username})` : ""}`}
              aria-label="Sign out"
            >
              <IconLogout size={15} />
            </button>
          ) : null}
        </div>
      </div>

      <div className="flex gap-1 overflow-x-auto px-3 pb-2 lg:hidden" role="tablist" aria-label="Pages">
        {NAV.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={item.id === page}
              onClick={() => onNavigate(item.id)}
              className={cx(
                "flex shrink-0 items-center gap-1.5 rounded-lg px-3 py-1.5 text-[13px] font-medium",
                item.id === page ? "bg-white/10 text-slate-100" : "text-slate-500",
              )}
            >
              <Icon size={13} />
              {item.label}
            </button>
          );
        })}
      </div>
    </header>
  );
}
