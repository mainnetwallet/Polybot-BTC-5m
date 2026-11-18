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
  /** the one-screen Overview: price levels without the ask history chart */
  compact?: boolean;
}

type Level = { price: number | null; size: number | null };

function Row({ price, size, maxSize, tone }: Level & { maxSize: number; tone: "ask" | "bid" }) {
  const width = isNum(size) && maxSize > 0 ? clamp01(size / maxSize) * 100 : 0;
  return (
    <div className="grid grid-cols-[3rem_1fr] items-center gap-2 text-[12.5px]">
      <span className={cx("font-semibold", tone === "ask" ? "text-down" : "text-up")}>{fmtPx(price)}</span>
      <div className="relative h-[22px] overflow-hidden rounded-md bg-white/[0.03]">
        <div
          className={cx("absolute inset-y-0 right-0 rounded-md", tone === "ask" ? "bg-down/25" : "bg-up/25")}
          style={{ width: `${Math.max(width, isNum(size) && size > 0 ? 6 : 0)}%` }}
        />
        <span className="relative z-10 flex h-full items-center justify-end pr-2 text-slate-200">
          {isNum(size) ? fmtSize(size) : "—"}
        </span>
      </div>
    </div>
  );
}

function fmtSize(size: number): string {
  return size >= 10_000 ? `${fmtNum(size / 1000, 0)}k` : size >= 1000 ? `${fmtNum(size / 1000, 1)}k` : fmtNum(size, 0);
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

/** The largest size among the levels both ladders show (top of book only when the server sends no depth). */
export function ladderMaxSize(books: Array<BookSnapshot | null>): number {
  let max = 0;
  for (const book of books) {
    if (!book) continue;
    const sizes = [book.ask_size, book.bid_size, ...(book.asks ?? []).map((l) => l[1]), ...(book.bids ?? []).map((l) => l[1])];
    for (const size of sizes) if (isNum(size) && size > max) max = size;
  }
  return max;
}

/** The nearest asks, the spread and the nearest bids of one outcome token, with its ask history for this round. */
export function BookLadder({ side, book, maxSize, history, staleAfterMs, compact = false }: BookLadderProps) {
  const up = side === "UP";
  const ask = book?.best_ask ?? null;
  const bid = book?.best_bid ?? null;
  const spread = isNum(ask) && isNum(bid) ? ask - bid : null;
  const stale = isNum(book?.age_ms) && isNum(staleAfterMs) ? book.age_ms > staleAfterMs : false;

  // real depth when the server sends it: farthest ask on top, the best ask next to the spread, then the bids
  const hasDepth = Boolean(book?.asks?.length || book?.bids?.length);
  const asks: Level[] = hasDepth
    ? [...(book?.asks ?? [])].reverse().map(([price, size]) => ({ price, size }))
    : [{ price: ask, size: book?.ask_size ?? null }];
  const bids: Level[] = hasDepth
    ? (book?.bids ?? []).map(([price, size]) => ({ price, size }))
    : [{ price: bid, size: book?.bid_size ?? null }];

  return (
    <div className="min-w-0">
      <div className="mb-1.5 flex items-baseline justify-between">
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>
          {up ? "Up" : "Down"}
          {stale ? <span className="ml-2 text-[11px] font-medium text-amber">stale</span> : null}
        </span>
        <span className={cx("text-[15px] font-semibold", up ? "text-up" : "text-down")}>{fmtPx(ask)}</span>
      </div>
      <div className="space-y-1">
        {asks.map((level, index) => (
          <Row key={`a${index}`} {...level} maxSize={maxSize} tone="ask" />
        ))}
        <div className="flex items-center justify-between border-y border-dashed border-white/10 py-1 text-[12px] text-slate-500">
          <span>Spread</span>
          <span className="text-slate-300">{fmtPx(spread)}</span>
        </div>
        {bids.map((level, index) => (
          <Row key={`b${index}`} {...level} maxSize={maxSize} tone="bid" />
        ))}
      </div>
      {compact ? null : (
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
