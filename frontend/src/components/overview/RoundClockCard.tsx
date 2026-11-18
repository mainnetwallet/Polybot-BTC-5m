import { Card } from "../Card";
import { RoundRing } from "../RoundRing";
import { useBot } from "../../hooks/BotContext";
import { useRemainingSeconds } from "../../hooks/useCountdown";
import { cx, fmtGapUsd, fmtPx, fmtHms, isNum } from "../../lib/format";
import {
  STAGES,
  STAGE_VAR,
  checkContext,
  gapTarget,
  stageStatus,
  stageWindows,
  statusLabel,
} from "../../lib/stages";
import { LoadingPanel } from "../LoadingPanel";

function AskTile({ side, ask, bid }: { side: "UP" | "DOWN"; ask: number | null; bid: number | null }) {
  const up = side === "UP";
  return (
    <div className="tile px-4 py-3">
      <div className="label">{up ? "Up ask" : "Down ask"}</div>
      <div className={cx("hero-num mt-1.5 text-[30px]", up ? "!text-up" : "!text-down")}>{fmtPx(ask)}</div>
      <div className="mt-1.5 text-[12.5px] text-slate-500">bid {fmtPx(bid)}</div>
    </div>
  );
}

export function RoundClockCard({ className }: { className?: string }) {
  const { snapshot } = useBot();
  const remaining = useRemainingSeconds();
  if (!snapshot) return <LoadingPanel className={className} />;

  const { strategy, market, btc, books, config } = snapshot;
  const windows = stageWindows(strategy);
  const ctx = checkContext(snapshot);
  const target = gapTarget(windows, remaining, strategy, config, ctx.ask);

  const diff = isNum(btc?.diff) ? btc.diff : null;
  const chip =
    diff === null ? null : (
      <span
        className={cx(
          "chip",
          diff > 0 ? "border-up/25 bg-up/10 text-up" : diff < 0 ? "border-down/25 bg-down/10 text-down" : "border-white/10 bg-white/5 text-slate-300",
        )}
      >
        {diff > 0 ? "Up is ahead" : diff < 0 ? "Down is ahead" : "Level with price to beat"}
      </span>
    );

  const gapNote = (() => {
    if (!btc || !btc.enabled) return "BTC price feed is switched off";
    if (diff === null) return btc.price_to_beat_error ? "Waiting for the official price to beat" : "Waiting for the BTC price";
    if (!target) return "No stage is open or coming up";
    const stage = `stage ${target.stage}`;
    if (target.need <= 0) return `no gap rule for ${stage}`;
    const met = isNum(ctx.gap) && ctx.gap + 1e-9 >= target.need;
    return `${met ? "meets" : "needs"} ${fmtGapUsd(target.need)} for ${stage}`;
  })();
  const gapMet = target !== null && target.need > 0 && isNum(ctx.gap) && ctx.gap + 1e-9 >= target.need;

  return (
    <Card
      title="Round clock"
      className={cx("cq", className)}
      right={market ? <span>Ends {fmtHms(market.window_end)}</span> : <span>No active market</span>}
    >
      <div className="rc-body">
        <RoundRing remaining={remaining} duration={market?.duration_seconds ?? null} windows={windows} chip={chip} />

        <div className="rc-side min-w-0">
          <ul className="rc-stages" aria-label="Stage status">
            {STAGES.map((stage) => {
              const w = windows[stage];
              const status = stageStatus(w, remaining);
              const watching = status.kind === "watching";
              return (
                <li key={stage} className={cx("flex items-center justify-between gap-3 text-[14px]", status.kind === "off" && "opacity-50")}>
                  <span className="flex items-center gap-2.5 whitespace-nowrap text-slate-300">
                    <span className="h-3 w-3 rounded-[4px]" style={{ background: STAGE_VAR[stage] }} aria-hidden="true" />
                    Stage {stage}
                  </span>
                  <span
                    className={cx("font-medium", watching ? "" : status.kind === "done" || status.kind === "off" ? "text-slate-100" : "text-slate-200")}
                    style={watching ? { color: STAGE_VAR[stage] } : undefined}
                  >
                    {statusLabel(status)}
                  </span>
                </li>
              );
            })}
          </ul>

          <div className="rc-tiles">
            <AskTile side="UP" ask={books.up?.best_ask ?? null} bid={books.up?.best_bid ?? null} />
            <AskTile side="DOWN" ask={books.down?.best_ask ?? null} bid={books.down?.best_bid ?? null} />
          </div>

          <div className="tile rc-gap px-4 py-3">
            <div className="label">Gap to price to beat</div>
            <div
              className={cx(
                "hero-num mt-1.5 text-[30px]",
                diff === null ? "!text-slate-500" : diff >= 0 ? "!text-up" : "!text-down",
              )}
            >
              {diff === null ? "—" : `${diff >= 0 ? "+" : "-"}$${Math.abs(diff).toFixed(2)}`}
            </div>
            <div className={cx("mt-1.5 text-[12.5px]", gapMet ? "text-up" : "text-slate-500")}>{gapNote}</div>
          </div>
        </div>
      </div>
    </Card>
  );
}
