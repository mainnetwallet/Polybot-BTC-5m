import type { BookSnapshot } from "../api/types";
import { cx, fmtNum, fmtPx, isNum } from "../lib/format";
import { Sparkline } from "./Sparkline";

interface BookLadderProps {
  side: "UP" | "DOWN";
  book: BookSnapshot | null;
  /** the largest size shown in either ladder, so depth bars compare across Up and Down */
  maxSize: number;
  history: number[];
  /** stale limit in ms from the settings, to flag an old quote */
  staleAfterMs: number | null;
}

function Level({ price, size, maxSize, tone }: { price: number | null; size: number | null; maxSize: number; tone: "ask" | "bid" }) {
  const width = isNum(size) && maxSize > 0 ? clamp01(size / maxSize) * 100 : 0;
  return (
    <div className="grid grid-cols-[3.4rem_1fr] items-center gap-2 text-[13px]">
      <span className={cx("font-semibold", tone === "ask" ? "text-down" : "text-up")}>{fmtPx(price)}</span>
      <div className="relative h-7 overflow-hidden rounded-md bg-white/[0.03]">
        <div
          className={cx("absolute inset-y-0 right-0 rounded-md", tone === "ask" ? "bg-down/25" : "bg-up/25")}
          style={{ width: `${Math.max(width, isNum(size) && size > 0 ? 6 : 0)}%` }}
        />
        <span className="relative z-10 flex h-full items-center justify-end pr-2 text-slate-200">
          {isNum(size) ? fmtNum(size, 0) : "—"}
        </span>
      </div>
    </div>
  );
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** The best ask, the spread and the best bid of one outcome token, with its ask history for this round. */
export function BookLadder({ side, book, maxSize, history, staleAfterMs }: BookLadderProps) {
  const up = side === "UP";
  const ask = book?.best_ask ?? null;
  const bid = book?.best_bid ?? null;
  const spread = isNum(ask) && isNum(bid) ? ask - bid : null;
  const stale = isNum(book?.age_ms) && isNum(staleAfterMs) ? book.age_ms > staleAfterMs : false;

  return (
    <div className="min-w-0">
      <div className="mb-2 flex items-baseline justify-between">
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>{up ? "Up" : "Down"}</span>
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>{fmtPx(ask)}</span>
      </div>
      <div className="space-y-1.5">
        <Level price={ask} size={book?.ask_size ?? null} maxSize={maxSize} tone="ask" />
        <div className="flex items-center justify-between border-y border-dashed border-white/10 py-1 text-[12.5px] text-slate-500">
          <span>Spread</span>
          <span className="text-slate-300">{fmtPx(spread)}</span>
        </div>
        <Level price={bid} size={book?.bid_size ?? null} maxSize={maxSize} tone="bid" />
      </div>
      <div className="mt-3">
        <div className="mb-1 flex items-center justify-between text-[12px] text-slate-500">
          <span>Ask this round</span>
          <span className={stale ? "text-amber" : undefined}>{book ? (stale ? "Stale quote" : "Fresh quote") : "No book"}</span>
        </div>
        <Sparkline data={history} tone={up ? "up" : "down"} height={44} />
      </div>
    </div>
  );
}
