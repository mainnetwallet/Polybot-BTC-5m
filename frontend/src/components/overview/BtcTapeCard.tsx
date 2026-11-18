import { useMemo, useState } from "react";
import type { OrderRow } from "../../api/types";
import { Card } from "../Card";
import { BookLadder } from "../BookLadder";
import { CandleChart, type BuyMarker } from "../CandleChart";
import { LoadingPanel } from "../LoadingPanel";
import { useBot } from "../../hooks/BotContext";
import { useBtcCandles, useBtcHistoryState } from "../../hooks/BtcTape";
import { cx, fmtPx, fmtSignedPct, fmtHms, isNum, usdParts } from "../../lib/format";
import type { StageId } from "../../lib/stages";

const ROUND_MS = 300_000;
const TF_KEY = "polybot.tape.timeframe";

/** How the tape is drawn: candle length and the width of one candle slot (candles keep this size and the chart scrolls). */
const TIMEFRAMES = [
  { id: "5s", label: "5s", candleMs: 5_000, slotPx: 10 },
  { id: "1m", label: "1m", candleMs: 60_000, slotPx: 16 },
  { id: "5m", label: "5m", candleMs: 300_000, slotPx: 22 },
] as const;
/** the server keeps about 2 hours of ticks, so history is asked for 24 rounds back */
const HISTORY_ROUNDS = 24;
type TimeframeId = (typeof TIMEFRAMES)[number]["id"];

function readTimeframe(): TimeframeId {
  try {
    const saved = window.localStorage.getItem(TF_KEY);
    if (saved === "5s" || saved === "1m" || saved === "5m") return saved;
  } catch {
    /* storage blocked: use the default */
  }
  return "5s";
}

export function BtcTapeCard({ orders, className }: { orders: OrderRow[]; className?: string }) {
  const { snapshot, history } = useBot();
  const market = snapshot?.market ?? null;
  const startMs = market ? Date.parse(market.window_start) : NaN;
  const endMs = market ? Date.parse(market.window_end) : NaN;
  const start = Number.isFinite(startMs) ? startMs : null;
  const end = Number.isFinite(endMs) ? endMs : null;
  const [tfId, setTfId] = useState<TimeframeId>(readTimeframe);
  const tf = TIMEFRAMES.find((item) => item.id === tfId) ?? TIMEFRAMES[0];
  const historyStart = start !== null ? start - HISTORY_ROUNDS * ROUND_MS : null;
  const tape = useBtcCandles(historyStart, end, tf.candleMs);
  const historyState = useBtcHistoryState();

  const pickTimeframe = (id: TimeframeId) => {
    setTfId(id);
    try {
      window.localStorage.setItem(TF_KEY, id);
    } catch {
      /* storage blocked: the choice just is not remembered */
    }
  };

  const buys = useMemo<BuyMarker[]>(() => {
    const out: BuyMarker[] = [];
    for (const order of orders) {
      if (order.action !== "BUY" || !(order.filled_size > 0)) continue;
      const t = Date.parse(order.created_at);
      if (!Number.isFinite(t)) continue;
      const stage = Math.min(3, Math.max(0, order.stage ?? 1)) as StageId;
      const price = order.avg_fill_price ?? order.submitted_price ?? order.requested_price;
      out.push({
        key: String(order.id),
        t,
        stage,
        label: `Stage ${stage} ${order.side} buy: ${order.filled_size.toFixed(1)} shares at ${fmtPx(price)}, ${fmtHms(order.created_at)}`,
      });
    }
    return out;
  }, [orders]);

  if (!snapshot) return <LoadingPanel className={className} />;

  const btc = snapshot.btc;
  const price = btc?.price ?? null;
  const beat = btc?.price_to_beat ?? null;
  const parts = usdParts(price);
  const pct = isNum(price) && isNum(beat) && beat > 0 ? ((price - beat) / beat) * 100 : null;
  const staleCfg = snapshot.config.stale_data_max_age_ms;
  const stale = typeof staleCfg === "number" ? staleCfg : null;

  const levelSizes = (book: typeof snapshot.books.up) => [
    book?.ask_size,
    book?.bid_size,
    ...(book?.asks ?? []).map(([, size]) => size),
    ...(book?.bids ?? []).map(([, size]) => size),
  ];
  const maxSize = Math.max(0, ...[...levelSizes(snapshot.books.up), ...levelSizes(snapshot.books.down)].filter(isNum));

  const firstTick = tape.firstTickMs;

  return (
    <Card
      title="BTC tape"
      className={cx("cq", className)}
      right={
        <span className="flex items-center gap-2">
          <span className="inline-flex gap-0.5 rounded-lg border hairline bg-black/15 p-0.5" role="group" aria-label="Candle length">
            {TIMEFRAMES.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => pickTimeframe(item.id)}
                aria-pressed={item.id === tfId}
                className={cx(
                  "rounded-md px-2 py-0.5 text-[12px] font-semibold transition-colors",
                  item.id === tfId ? "bg-white/[0.1] text-slate-100" : "text-slate-500 hover:text-slate-200",
                )}
              >
                {item.label}
              </button>
            ))}
          </span>
          <span>candles, Chainlink</span>
        </span>
      }
    >
      <div className="tape-body">
        <div className="tape-main min-w-0">
      <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <div className={cx("hero-num text-[34px]", btc?.stale && "!text-slate-500")}>
          {parts ? (
            <>
              ${parts.whole}
              <span className="hero-dec">.{parts.dec}</span>
            </>
          ) : (
            "—"
          )}
        </div>
        {pct !== null ? (
          <span
            className={cx("chip", pct >= 0 ? "border-up/25 bg-up/10 text-up" : "border-down/25 bg-down/10 text-down")}
            title="Change of the BTC price since the price to beat was set at the start of the round"
          >
            {fmtSignedPct(pct)}
          </span>
        ) : null}
        {isNum(beat) ? (
          <span className="chip border-amber/30 bg-amber/10 text-amber" title={btc?.price_to_beat_source ?? undefined}>
            Price to beat ${beat.toLocaleString("en-US", { minimumFractionDigits: 0, maximumFractionDigits: 2 })}
          </span>
        ) : btc?.enabled ? (
          <span className="chip border-amber/30 bg-amber/10 text-amber" title={btc.price_to_beat_error ?? undefined}>
            Waiting for the official price to beat
          </span>
        ) : null}
        {btc && btc.enabled && (!btc.connected || btc.stale) ? (
          <span className="chip border-down/30 bg-down/10 text-down" title={btc.last_error ?? undefined}>
            {btc.connected ? "Stale price" : "Price feed offline"}
          </span>
        ) : null}
      </div>
        <div className="tape-chart min-w-0">
          <div className="chart-fill relative min-h-[220px]">
          <CandleChart
            candles={tape.candles}
            candleMs={tf.candleMs}
            slotPx={tf.slotPx}
            roundStartMs={start}
            priceToBeat={beat}
            price={price}
            buys={buys}
            fill
          />
          </div>
          <div className="mt-2 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-[12px] text-slate-500">
            <span>
              {tape.candles.length > 0
                ? historyState === "unavailable"
                  ? `Server history not loaded (restart the backend). Candles start at ${fmtHms(firstTick)}`
                  : `${tape.candles.length} candles from ${fmtHms(firstTick)}. Drag the chart to see older ones`
                : "Waiting for the first price"}
            </span>
            <span className="flex items-center gap-1.5">
              <span className="inline-block h-0 w-0 border-x-[5px] border-b-[8px] border-x-transparent border-b-slate-400" aria-hidden="true" />
              Triangles mark buys, colored by stage
            </span>
          </div>
        </div>
        </div>

        <div className="tape-book grid min-w-0 grid-cols-2 gap-x-3 gap-y-2 self-start">
          <BookLadder compact side="UP" book={snapshot.books.up} maxSize={maxSize} history={history.up} staleAfterMs={stale} />
          <BookLadder compact side="DOWN" book={snapshot.books.down} maxSize={maxSize} history={history.down} staleAfterMs={stale} />
        </div>
      </div>
    </Card>
  );
}
