import { useId, useMemo } from "react";
import { api } from "../../api/client";
import { Card } from "../Card";
import { LoadingPanel } from "../LoadingPanel";
import { useBot } from "../../hooks/BotContext";
import { useLiveRefresh } from "../../hooks/useLiveRefresh";
import { realizedCurve } from "../../lib/equity";
import { cx, fmtPx, fmtSignedPct, fmtSignedUsd, fmtUsd, isNum, usdParts } from "../../lib/format";

function StatTile({ label, value, tone }: { label: string; value: string; tone?: "up" | "down" }) {
  return (
    <div className="tile min-w-0 px-3.5 py-2">
      <div className="label truncate">{label}</div>
      <div className={cx("mt-1 truncate text-[19px] font-semibold leading-none tracking-tight", tone === "up" ? "text-up" : tone === "down" ? "text-down" : "text-slate-100")}>
        {value}
      </div>
    </div>
  );
}

/** Cumulative realized P&L as a filled line. */
export function EquityLine({ points }: { points: number[] }) {
  const gradient = `eq${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  const width = 300;
  const height = 100;
  const lo = Math.min(...points, 0);
  const hi = Math.max(...points, 0);
  const span = hi - lo || 1;
  const xy = points.map((value, index) => [
    (index / (points.length - 1)) * width,
    6 + (1 - (value - lo) / span) * (height - 14),
  ]);
  const line = xy.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const last = xy[xy.length - 1];
  const rising = points[points.length - 1] >= points[0];
  const color = rising ? "var(--up)" : "var(--down)";
  return (
    <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="block h-full w-full" aria-hidden="true">
      <defs>
        <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.32" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`M 0,${height} L ${line.replace(/ /g, " L ")} L ${width},${height} Z`} fill={`url(#${gradient})`} />
      <polyline points={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
      <circle cx={last[0]} cy={last[1]} r="3.4" fill="#fff" stroke={color} strokeWidth="2" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function AccountCard({ className }: { className?: string }) {
  const { snapshot, dataVersion } = useBot();
  const mode = snapshot?.bot.mode;

  const analytics = useLiveRefresh(
    () => (mode ? api.analytics(mode) : Promise.resolve(null)),
    [mode],
    dataVersion,
    mode !== undefined,
  );
  const closed = useLiveRefresh(
    () => (mode ? api.positions(mode, 100) : Promise.resolve([])),
    [mode],
    dataVersion,
    mode !== undefined,
  );

  const curve = useMemo(() => realizedCurve(closed.data ?? []), [closed.data]);

  if (!snapshot) return <LoadingPanel className={className} />;

  const account = snapshot.account;
  const parts = usdParts(account.balance);
  const stats = analytics.data;
  const total = isNum(stats?.total_pnl) ? stats.total_pnl : null;
  const totalPct = total !== null && account.starting_balance > 0 ? (total / account.starting_balance) * 100 : null;
  const live = snapshot.bot.mode === "live";

  return (
    <Card title={live ? "Live account" : "Paper account"} className={className} right={<span>Today</span>}>
      <div className="acc-top flex min-h-0 flex-1 flex-col">
        <div className="acc-balance">
      <div className="label">Balance</div>
      <div className="hero-num mt-1 text-[34px]">
        {parts ? (
          <>
            {parts.sign}${parts.whole}
            <span className="hero-dec">.{parts.dec}</span>
          </>
        ) : (
          "—"
        )}
      </div>

      <div className="mt-2.5 flex flex-wrap items-center gap-2">
        <span
          className={cx(
            "chip",
            account.daily_pnl > 0 ? "border-up/25 bg-up/10 text-up" : account.daily_pnl < 0 ? "border-down/25 bg-down/10 text-down" : "border-white/10 bg-white/5 text-slate-300",
          )}
        >
          {fmtSignedUsd(account.daily_pnl)} today
        </span>
        {totalPct !== null ? (
          <span
            className={cx("chip", totalPct >= 0 ? "border-up/25 bg-up/10 text-up" : "border-down/25 bg-down/10 text-down")}
            title="Realized plus unrealized P&L against the starting balance"
          >
            {fmtSignedPct(totalPct, 1)} total
          </span>
        ) : null}
      </div>
        </div>

      <div className="acc-chart mt-3 flex min-h-[70px] flex-1 flex-col">
        <div className="relative min-h-[44px] flex-1">
          {curve.points.length >= 3 ? (
            <EquityLine points={curve.points} />
          ) : (
            <div className="flex h-full items-center justify-center rounded-[var(--radius-tile)] border border-dashed border-white/10 px-3 text-center text-[12.5px] text-slate-500">
              {closed.data ? "The chart appears after two closed positions" : "Loading closed positions"}
            </div>
          )}
        </div>
        <div className="mt-1 text-[11.5px] text-slate-500">
          Realized P&L, last {curve.count} closed {curve.count === 1 ? "position" : "positions"}
          {curve.points.length >= 3 ? `: ${fmtSignedUsd(curve.points[curve.points.length - 1])}` : ""}
        </div>
      </div>
      </div>

      <div className="acc-stats mt-3 grid grid-cols-2 gap-2.5">
        <StatTile
          label="Win rate"
          value={isNum(stats?.win_rate) ? `${stats.win_rate.toFixed(1)}%` : "—"}
          tone={isNum(stats?.win_rate) ? (stats.win_rate >= 50 ? "up" : "down") : undefined}
        />
        <StatTile label="Trades today" value={String(account.trades_today)} />
        <StatTile label="Avg entry" value={fmtPx(stats?.avg_entry_price)} />
        <StatTile label="Fees paid" value={fmtUsd(stats?.fees_paid)} />
      </div>
    </Card>
  );
}
