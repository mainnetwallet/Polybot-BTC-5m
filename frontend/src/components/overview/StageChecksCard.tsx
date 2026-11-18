import type { OrderRow } from "../../api/types";
import { Card } from "../Card";
import { LoadingPanel } from "../LoadingPanel";
import { useBot } from "../../hooks/BotContext";
import { useRemainingSeconds } from "../../hooks/useCountdown";
import { cx, fmtClock, fmtNum, fmtPx } from "../../lib/format";
import {
  STAGES,
  STAGE_NAME,
  STAGE_VAR,
  checkContext,
  stageChecks,
  stageFills,
  stageStatus,
  stageWindows,
  windowText,
  type CheckTone,
} from "../../lib/stages";

const CHIP_TONE: Record<CheckTone, string> = {
  pass: "border-up/25 bg-up/10 text-up",
  fail: "border-down/25 bg-down/10 text-down",
  neutral: "border-white/8 bg-white/[0.04] text-slate-400",
};

/** "Take profit +8%" -> ["Take profit", "+8%"]; "Ask 0.70 to 0.85" -> ["Ask", "0.70 to 0.85"] */
function splitChip(label: string): [string, string] {
  const m = label.match(/^(Take profit|Ask|Gap|Spread|Above)\s+(.*)$/);
  return m ? [m[1], m[2]] : ["", label];
}

export function StageChecksCard({ orders, className }: { orders: OrderRow[]; className?: string }) {
  const { snapshot } = useBot();
  const remaining = useRemainingSeconds();
  if (!snapshot) return <LoadingPanel className={className} />;

  const { strategy, config, market } = snapshot;
  const windows = stageWindows(strategy);
  const ctx = checkContext(snapshot);
  const fills = stageFills(orders, snapshot.open_orders, market?.market_id ?? null);
  const hold = snapshot.strategy.reason ? snapshot.strategy.reason.replace(/_/g, " ") : null;

  return (
    <Card
      title="Stage checks"
      className={className}
      right={
        <span className="sc-sub truncate">
          Live, this round
          {ctx.side ? ` · ${ctx.side === "UP" ? "Up" : "Down"} side` : ""}
          {hold ? <span className="sc-engine"> · Engine: <span className="text-slate-300">{hold}</span></span> : null}
        </span>
      }
    >
      <ul className="sc-list flex min-h-0 flex-1 flex-col divide-y divide-white/[0.06]">
        {STAGES.map((stage) => {
          const w = windows[stage];
          const status = stageStatus(w, remaining);
          const fill = fills[stage];
          const chips = stageChecks(w, status, strategy, config, ctx);
          const color = STAGE_VAR[stage];

          let right: { text: string; cls: string };
          if (status.kind === "off") right = { text: "Off", cls: "border-white/8 bg-white/[0.03] text-slate-500" };
          else if (fill.shares > 0)
            right = {
              text: `${fmtNum(fill.shares, 1)} sh at ${fmtPx(fill.avg)}`,
              cls: "border-up/30 bg-up/10 text-up",
            };
          else if (fill.pending) right = { text: "Buying now", cls: "border-amber/40 bg-amber/10 text-amber" };
          else if (status.kind === "opens") right = { text: `Opens in ${fmtClock(status.inSeconds)}`, cls: "border-white/10 bg-white/[0.05] text-slate-200" };
          else if (status.kind === "watching") right = { text: "Watching", cls: "border-white/10 bg-white/[0.05] text-slate-200" };
          else if (status.kind === "done") right = { text: "No buy", cls: "border-white/8 bg-white/[0.03] text-slate-400" };
          else right = { text: "Waiting", cls: "border-white/8 bg-white/[0.03] text-slate-400" };

          return (
            <li key={stage} className={cx("sc-row flex flex-1 items-center gap-3 py-2 first:pt-0 last:pb-0", status.kind === "off" && "opacity-55")}>
              <span
                className="sc-badge flex h-8 w-8 shrink-0 items-center justify-center rounded-[9px] text-[14px] font-bold text-[#0a141b]"
                style={{ background: color }}
                aria-hidden="true"
              >
                {stage}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5">
                  <div className="sc-title min-w-0 text-[14px]">
                    <span className="sc-name font-semibold text-slate-100">{STAGE_NAME[stage]}</span>
                    <span className="sc-window">{status.kind === "off" ? "switched off" : windowText(w)}</span>
                  </div>
                  <span className={cx("chip sc-status shrink-0", right.cls)}>{right.text}</span>
                </div>
                <div className="sc-chips mt-1.5 flex flex-wrap gap-1">
                  {chips.map((chip) => (
                    <span key={chip.key} className={cx("chip sc-chip", CHIP_TONE[chip.tone])}>
                      <span className="sc-chip-k">{splitChip(chip.label)[0]}</span>
                      <span className="sc-chip-v">{splitChip(chip.label)[1]}</span>
                    </span>
                  ))}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
      {hold ? (
        <div className="sc-foot mt-auto shrink-0 pt-2 text-[12px] text-slate-500">
          Engine says: <span className="text-slate-300">{hold}</span>
        </div>
      ) : null}
    </Card>
  );
}
