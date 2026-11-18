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
  /** top of book only, no ask history chart (the one-screen Overview) */
  compact?: boolean;
}

/** how many price levels are drawn on each side of the spread */
const LEVELS = 4;

type Level = [number | null, number | null];
const EMPTY: Level = [null, null];

function LevelRow({ price, size, maxSize, tone }: { price: number | null; size: number | null; maxSize: number; tone: "ask" | "bid" }) {
  const width = isNum(size) && maxSize > 0 ? clamp01(size / maxSize) * 100 : 0;
  return (
    <div className="lvl grid grid-cols-[3.4rem_1fr] items-center gap-2 text-[12.5px]">
      <span className={cx("font-semibold", tone === "ask" ? "text-down" : "text-up")}>{fmtPx(price)}</span>
      <div className="lvl-bar relative h-6 overflow-hidden rounded-md bg-white/[0.03]">
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

/** The top ask levels, the spread and the top bid levels of one outcome token, with its ask history for this round. */
export function BookLadder({ side, book, maxSize, history, staleAfterMs, compact = false }: BookLadderProps) {
  const up = side === "UP";
  const ask = book?.best_ask ?? null;
  const bid = book?.best_bid ?? null;
  const spread = isNum(ask) && isNum(bid) ? ask - bid : null;
  const stale = isNum(book?.age_ms) && isNum(staleAfterMs) ? book.age_ms > staleAfterMs : false;

  // Always LEVELS rows on each side, so Up and Down line up and the spread sits at the same height.
  // A side with fewer real levels (a one-sided book near the end of a round) is padded with empty rows:
  // asks are drawn highest first, so the best ask sits right above the spread and the padding goes on top;
  // bids are drawn best first, so the padding goes at the bottom.
  const askRaw: Level[] = book?.asks?.length ? book.asks.slice(0, LEVELS) : isNum(ask) ? [[ask, book?.ask_size ?? 0]] : [];
  const bidRaw: Level[] = book?.bids?.length ? book.bids.slice(0, LEVELS) : isNum(bid) ? [[bid, book?.bid_size ?? 0]] : [];
  const askLevels: Level[] = [...Array<Level>(Math.max(0, LEVELS - askRaw.length)).fill(EMPTY), ...[...askRaw].reverse()];
  const bidLevels: Level[] = [...bidRaw, ...Array<Level>(Math.max(0, LEVELS - bidRaw.length)).fill(EMPTY)];

  return (
    <div className="min-w-0">
      <div className="lvl-head mb-1.5 flex items-baseline justify-between">
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>{up ? "Up" : "Down"}</span>
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>{fmtPx(ask)}</span>
      </div>
      <div className={compact ? "space-y-1" : "space-y-1.5"}>
        {askLevels.map(([price, size], i) => (
          <LevelRow key={`a${i}-${price}`} price={price} size={size} maxSize={maxSize} tone="ask" />
        ))}
        <div className="lvl-spread flex items-center justify-between border-y border-dashed border-white/10 py-1 text-[12.5px] text-slate-500">
          <span>Spread</span>
          <span className="text-slate-300">{fmtPx(spread)}</span>
        </div>
        {bidLevels.map(([price, size], i) => (
          <LevelRow key={`b${i}-${price}`} price={price} size={size} maxSize={maxSize} tone="bid" />
        ))}
      </div>
      {compact ? (
        <div className={cx("lvl-foot mt-1.5 text-[11.5px]", stale ? "text-amber" : "text-slate-500")}>
          {book ? (stale ? "Stale quote" : "Fresh quote") : "No book"}
        </div>
      ) : (
        <div className="mt-3">
          <div className="mb-1 flex items-center justify-between text-[12px] text-slate-500">
            <span>Ask this round</span>
            <span className={stale ? "text-amber" : undefined}>{book ? (stale ? "Stale quote" : "Fresh quote") : "No book"}</span>
          </div>
          <Sparkline data={history} tone={up ? "up" : "down"} height={72} />
        </div>
      )}
    </div>
  );
}
