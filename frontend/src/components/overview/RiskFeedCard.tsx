import { Card } from "../Card";
import { IconActivity, IconAlert } from "../Icons";
import { LoadingPanel } from "../LoadingPanel";
import { useBot } from "../../hooks/BotContext";
import { clamp, cx, fmtSignedUsd, fmtUsd, pnlClass } from "../../lib/format";

function Row({ label, value, hint, className }: { label: string; value: string; hint?: string; className?: string }) {
  return (
    <div className="tile flex min-w-0 items-center justify-between gap-2 px-3 py-1.5">
      <div className="min-w-0">
        <div className="label truncate !text-[12px]">{label}</div>
        {hint ? <div className="truncate text-[11px] leading-tight text-slate-500">{hint}</div> : null}
      </div>
      <div className={cx("shrink-0 text-[17px] font-semibold leading-none", className ?? "text-slate-100")}>{value}</div>
    </div>
  );
}

/** The daily loss limit and profit target, plus the market data feed health that used to live in separate panels. */
export function RiskFeedCard({ className }: { className?: string }) {
  const { snapshot, clockOffsetMs } = useBot();
  if (!snapshot) return <LoadingPanel className={className} />;
  const { account, config, market_data: data } = snapshot;
  const daily = account.daily_pnl;
  const loss = typeof config.max_daily_loss === "number" ? config.max_daily_loss : null;
  const target = typeof config.daily_profit_target === "number" ? config.daily_profit_target : null;
  const span = loss !== null && target !== null ? loss + target : null;
  const fill = span !== null && span > 0 ? clamp(((daily + loss!) / span) * 100, 0, 100) : null;
  const zero = span !== null && span > 0 ? (loss! / span) * 100 : null;
  const age = data.last_message_ms ? Date.now() + clockOffsetMs - data.last_message_ms : null;

  return (
    <Card
      title="Risk and feed"
      className={className}
      right={
        <span className={cx("chip", data.connected ? "border-up/30 bg-up/10 text-up" : "border-down/30 bg-down/10 text-down")}>
          {data.connected ? "Feed connected" : "Feed disconnected"}
        </span>
      }
    >
      <div>
        <div className="flex items-baseline justify-between text-[13px]">
          <span className="text-slate-400">Daily result</span>
          <span className={cx("font-semibold", pnlClass(daily))}>{fmtSignedUsd(daily)}</span>
        </div>
        <div className="meter mt-2">
          {zero !== null ? <div className="absolute inset-y-0 left-0 bg-down/25" style={{ width: `${zero}%` }} /> : null}
          {zero !== null ? <div className="absolute inset-y-0 right-0 bg-up/20" style={{ width: `${100 - zero}%` }} /> : null}
          {zero !== null ? <div className="absolute inset-y-0 w-px bg-white/40" style={{ left: `${zero}%` }} /> : null}
          {fill !== null ? (
            <div
              className={cx(
                "absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-[var(--card)]",
                daily < 0 ? "bg-down" : "bg-up",
              )}
              style={{ left: `${fill}%` }}
            />
          ) : null}
        </div>
        <div className="mt-1 flex items-center justify-between text-[11.5px]">
          <span className="text-down">Stop -{loss !== null ? fmtUsd(loss) : "—"}</span>
          <span className="text-slate-500">{fmtUsd(account.starting_balance)} start</span>
          <span className="text-up">Target +{target !== null ? fmtUsd(target) : "—"}</span>
        </div>
      </div>

      <div className="mt-2.5 grid grid-cols-2 gap-2">
        <Row label="Open positions" value={String(snapshot.positions.filter((p) => p.tag !== "tap").length)} hint={`max ${String(config.max_open_positions ?? "—")}`} />
        <Row label="Trades today" value={String(account.trades_today)} hint={`max ${String(config.max_trades_per_day ?? "—")} a day`} />
        <Row label="Last book message" value={age === null ? "—" : age < 1000 ? "now" : `${Math.round(age / 1000)} s ago`} hint={`${data.reconnects} reconnects`} />
        <Row
          label="Unrealized"
          value={fmtSignedUsd(account.unrealized_pnl)}
          className={pnlClass(account.unrealized_pnl)}
          hint={
            account.positions_awaiting_settlement
              ? `${account.positions_awaiting_settlement} settling`
              : account.pending_redeems
                ? `${account.pending_redeems} redeeming`
                : "realized " + fmtSignedUsd(account.realized_pnl)
          }
        />
      </div>

      {data.last_error ? (
        <div className="mt-2.5 flex gap-2 rounded-xl border border-down/20 bg-down/[0.06] px-3 py-1.5 text-[11.5px] text-down/90">
          <IconAlert size={13} className="mt-0.5 shrink-0" />
          <span className="break-all">{data.last_error}</span>
        </div>
      ) : (
        <div className="mt-2.5 flex items-center gap-2 rounded-xl border hairline bg-black/15 px-3 py-1.5 text-[11.5px] text-slate-500">
          <IconActivity size={13} className="text-up/70" />
          Event driven: feed, then strategy, then risk, then order
        </div>
      )}
    </Card>
  );
}
