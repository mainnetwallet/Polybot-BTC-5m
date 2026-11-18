import { clamp, cx, fmtPx, isNum } from "../lib/format";

interface ExitMeterProps {
  entry: number | null;
  bid: number | null;
  stop: number | null;
  takeProfit: number | null;
  /** one line of text (stop, progress, take profit) without the bar */
  inline?: boolean;
}

/**
 * Where the bid sits between the stop loss and the take profit of one position. Red is the stop side, green
 * the take profit side, the white tick is the entry price and the dot is the current bid.
 */
export function ExitMeter({ entry, bid, stop, takeProfit, inline = false }: ExitMeterProps) {
  if (!isNum(entry) || (!isNum(stop) && !isNum(takeProfit))) {
    return <div className="text-[12.5px] text-slate-500">No take profit or stop loss set for these shares</div>;
  }
  const lo = isNum(stop) ? stop : entry - (takeProfit! - entry);
  const hi = isNum(takeProfit) ? takeProfit : entry + (entry - stop!);
  const span = Math.max(hi - lo, 0.0001);
  const pos = (value: number) => clamp(((value - lo) / span) * 100, 0, 100);
  const entryPos = pos(entry);

  let note = "No bid";
  let tone = "text-slate-400";
  if (isNum(bid)) {
    if (bid >= entry && isNum(takeProfit) && takeProfit > entry) {
      const done = clamp(((bid - entry) / (takeProfit - entry)) * 100, 0, 100);
      note = `${done.toFixed(0)}% to take profit`;
      tone = "text-up";
    } else if (bid < entry && isNum(stop) && entry > stop) {
      const done = clamp(((entry - bid) / (entry - stop)) * 100, 0, 100);
      note = `${done.toFixed(0)}% to stop loss`;
      tone = "text-down";
    } else {
      note = "At entry";
    }
  }

  if (inline) {
    return (
      <span className="flex min-w-0 items-center gap-2 whitespace-nowrap text-[12px]">
        <span className="text-down">{isNum(stop) ? `Stop ${fmtPx(stop)}` : "No stop"}</span>
        <span className={tone}>{note}</span>
        <span className="text-up">{isNum(takeProfit) ? `Take profit ${fmtPx(takeProfit)}` : "No take profit"}</span>
      </span>
    );
  }

  return (
    <div>
      <div className="meter" role="img" aria-label={`Bid ${fmtPx(bid)} between stop ${fmtPx(stop)} and take profit ${fmtPx(takeProfit)}. ${note}`}>
        <div className="absolute inset-y-0 left-0 bg-down/30" style={{ width: `${entryPos}%` }} />
        <div className="absolute inset-y-0 right-0 bg-up/30" style={{ width: `${100 - entryPos}%` }} />
        <div className="absolute inset-y-0 w-px bg-white/70" style={{ left: `${entryPos}%` }} />
        {isNum(bid) ? (
          <div
            className={cx(
              "absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-[var(--card)]",
              bid >= entry ? "bg-up" : "bg-down",
            )}
            style={{ left: `${pos(bid)}%` }}
          />
        ) : null}
      </div>
      <div className="mt-1.5 flex items-center justify-between text-[12px]">
        <span className="text-down">{isNum(stop) ? `Stop ${fmtPx(stop)}` : "No stop"}</span>
        <span className={tone}>{note}</span>
        <span className="text-up">{isNum(takeProfit) ? `Take profit ${fmtPx(takeProfit)}` : "No take profit"}</span>
      </div>
    </div>
  );
}
