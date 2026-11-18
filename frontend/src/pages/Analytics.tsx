import { api } from "../api/client";
import { ModeBadge } from "../components/Badges";
import { IconActivity, IconAnalytics, IconTarget } from "../components/Icons";
import { Stat } from "../components/Stat";
import { EquityLine } from "../components/overview/AccountCard";
import { realizedCurve } from "../lib/equity";
import { useBot } from "../hooks/BotContext";
import { useLiveRefresh } from "../hooks/useLiveRefresh";
import { clamp, cx, fmtCents, fmtSignedUsd, fmtUsd, pnlClass } from "../lib/format";

export function Analytics() {
  const { snapshot, dataVersion } = useBot();
  const mode = snapshot?.bot.mode;
  const polled = useLiveRefresh(async () => {
    if (!mode) return null;
    const [analytics, positions] = await Promise.all([api.analytics(mode), api.positions(mode, 200)]);
    return { analytics, positions };
  }, [mode], dataVersion, mode !== undefined);

  const analytics = polled.data?.analytics;
  const curve = realizedCurve(polled.data?.positions ?? []);
  const unrealized = snapshot && snapshot.bot.mode === mode ? snapshot.account.unrealized_pnl : analytics?.unrealized_pnl;
  const totalPnl = analytics && unrealized != null ? analytics.realized_pnl + unrealized : null;
  const closed = analytics ? analytics.wins + analytics.losses : null;
  const winPct = closed != null && closed > 0 ? (analytics!.wins / closed) * 100 : null;

  const pnlRows = [
    { label: "Realized", value: analytics?.realized_pnl ?? null },
    { label: "Unrealized", value: unrealized ?? null },
    { label: "Total", value: totalPnl },
  ];
  const maxAbs = Math.max(0.01, ...pnlRows.flatMap((row) => row.value == null ? [] : [Math.abs(row.value)]));

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-12">
      <section className="panel flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between xl:col-span-12">
        <div className="flex items-center gap-3">
          {snapshot ? <ModeBadge mode={snapshot.bot.mode} /> : null}
          <div>
            <div className="text-[13px] font-semibold text-slate-100">Mode-separated performance</div>
            <div className="mt-0.5 text-[12.5px] text-slate-500">
              Paper and live balances, fills and statistics never mix. This view reads only the {mode ?? "—"} ledger.
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2 text-[12px] text-slate-500">
          {polled.error ? <span className="chip border-down/25 bg-down/10 text-down">api error</span> : null}
          <span className="chip border-white/8 bg-white/[0.03] text-slate-500">
            <IconActivity size={10} />
            {analytics ? analytics.total_trades : "—"} trades
          </span>
        </div>
      </section>

      <section className="panel p-5 xl:col-span-12">
        <div className="grid grid-cols-2 gap-6 sm:grid-cols-3 lg:grid-cols-5">
          <Stat label="Realized PnL" value={fmtSignedUsd(analytics?.realized_pnl)} tone={analytics ? (analytics.realized_pnl >= 0 ? "up" : "down") : "default"} hint="settled markets" />
          <Stat label="Unrealized PnL" value={fmtSignedUsd(unrealized)} tone={unrealized != null ? (unrealized >= 0 ? "up" : "down") : "default"} hint="marked at best bid" />
          <Stat label="Fees paid" value={fmtUsd(analytics?.fees_paid, 3)} hint="all fills" />
          <Stat label="Volume" value={fmtUsd(analytics?.volume_usdc)} hint="price × size" />
          <Stat label="Avg entry" value={fmtCents(analytics?.avg_entry_price)} hint="size-weighted" />
        </div>
      </section>

      <section className="panel p-5 xl:col-span-4">
        <div className="flex items-start justify-between">
          <div>
            <div className="label">Outcomes</div>
            <div className="mt-1 text-[14px] font-semibold tracking-tight text-slate-100">Win rate</div>
          </div>
          <IconTarget size={18} className="text-slate-600" />
        </div>
        <div className="mt-5 flex items-baseline gap-2">
          <span className={cx("mono text-[38px] font-semibold leading-none", winPct != null && winPct >= 50 ? "text-up" : "text-slate-200")}>
            {winPct != null ? `${winPct.toFixed(1)}%` : "—"}
          </span>
          <span className="text-[12.5px] text-slate-500">{closed ?? "—"} settled</span>
        </div>
        <div className="mt-4 flex h-2.5 overflow-hidden rounded-full bg-white/[0.05]">
          {closed && winPct !== null ? (
            <>
              <div className="bg-up/80" style={{ width: `${clamp(winPct, 0, 100)}%` }} />
              <div className="bg-down/70" style={{ width: `${clamp(100 - winPct, 0, 100)}%` }} />
            </>
          ) : (
            <div className="h-full w-full bg-white/[0.06]" />
          )}
        </div>
        <div className="mt-3 flex items-center justify-between text-[12.5px]">
          <span className="text-up">{analytics?.wins ?? "—"} wins</span>
          <span className="text-down">{analytics?.losses ?? "—"} losses</span>
        </div>
        <div className="mt-4 border-t hairline pt-3 text-[12px] leading-relaxed text-slate-500">
          A position wins when its realized payout exceeds cost basis and fees after resolution.
        </div>
      </section>

      <section className="panel p-5 xl:col-span-4">
        <div className="flex items-start justify-between">
          <div>
            <div className="label">PnL composition</div>
            <div className="mt-1 text-[14px] font-semibold tracking-tight text-slate-100">Realized vs marked</div>
          </div>
          <IconAnalytics size={18} className="text-slate-600" />
        </div>
        <div className="mt-5 space-y-4">
          {pnlRows.map((row) => {
            const width = row.value == null ? 0 : clamp((Math.abs(row.value) / maxAbs) * 100, 0, 100);
            return (
              <div key={row.label}>
                <div className="flex items-center justify-between text-[12.5px]">
                  <span className={row.label === "Total" ? "font-semibold text-slate-200" : "text-slate-500"}>
                    {row.label}
                  </span>
                  <span className={cx("mono", row.value == null ? "text-slate-500" : pnlClass(row.value))}>{fmtSignedUsd(row.value)}</span>
                </div>
                <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/[0.05]">
                  <div
                    className={cx("h-full rounded-full", row.value != null && row.value >= 0 ? "bg-up" : "bg-down", row.label === "Total" ? "" : "opacity-70")}
                    style={{ width: `${width}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
        <div className="mt-4 border-t hairline pt-3 text-[12px] leading-relaxed text-slate-500">
          Unrealized PnL marks open positions at the current best bid; it is not withdrawable until resolution.
        </div>
      </section>

      <section className="panel p-5 xl:col-span-4">
        <div className="mb-4">
          <div className="label">Equity</div>
          <div className="mt-1 text-[14px] font-semibold tracking-tight text-slate-100">Realized P&amp;L by closed position</div>
        </div>
        <div className="h-[150px]">
          {curve.points.length >= 3 ? (
            <EquityLine points={curve.points} />
          ) : (
            <div className="flex h-full items-center justify-center rounded-[var(--radius-tile)] border border-dashed border-white/10 px-3 text-center text-[12.5px] text-slate-500">
              {polled.data ? "The chart appears after two closed positions" : "Loading closed positions"}
            </div>
          )}
        </div>
        <div className="mt-3 border-t hairline pt-3 text-[12.5px] leading-relaxed text-slate-500">
          {curve.points.length >= 3
            ? `${curve.count} closed ${curve.count === 1 ? "position" : "positions"}, ending at ${fmtSignedUsd(curve.points[curve.points.length - 1])}.`
            : "Built from the closed positions the API returns."}
        </div>
      </section>
    </div>
  );
}
