import { useMemo } from "react";
import type { OrderRow } from "../../api/types";
import { Card } from "../Card";
import { BookLadder } from "../BookLadder";
import { CandleChart, type BuyMarker } from "../CandleChart";
import { LoadingPanel } from "../LoadingPanel";
import { useBot } from "../../hooks/BotContext";
import { useBtcCandles } from "../../hooks/BtcTape";
import { cx, fmtPx, fmtSignedPct, fmtHms, isNum, usdParts } from "../../lib/format";
import type { StageId } from "../../lib/stages";

export function BtcTapeCard({ orders, className }: { orders: OrderRow[]; className?: string }) {
  const { snapshot, history } = useBot();
  const market = snapshot?.market ?? null;
  const startMs = market ? Date.parse(market.window_start) : NaN;
  const endMs = market ? Date.parse(market.window_end) : NaN;
  const start = Number.isFinite(startMs) ? startMs : null;
  const end = Number.isFinite(endMs) ? endMs : null;
  const tape = useBtcCandles(start, end);

  const marketId = market?.market_id ?? null;
  const buys = useMemo<BuyMarker[]>(() => {
    if (!marketId) return [];
    const out: BuyMarker[] = [];
    for (const order of orders) {
      if (order.market_id !== marketId || order.action !== "BUY" || !(order.filled_size > 0)) continue;
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
  }, [orders, marketId]);

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

  const lateStart =
    start !== null && tape.firstTickMs !== null && tape.firstTickMs - start > 12_000 ? tape.firstTickMs : null;

  return (
    <Card
      title="BTC tape"
      className={cx("cq", className)}
      right={<span>5 second candles, Chainlink</span>}
    >
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

      <div className="tape-body">
        <div className="tape-chart min-w-0">
          <div className="chart-fill relative min-h-[220px]">
          <CandleChart
            candles={tape.candles}
            startMs={start}
            endMs={end}
            priceToBeat={beat}
            price={price}
            buys={buys}
            fill
          />
          </div>
          <div className="mt-2 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-[12px] text-slate-500">
            <span>
              {tape.candles.length > 0
                ? lateStart !== null
                  ? `Candles start at ${fmtHms(lateStart)}, when this browser received its first price`
                  : `${tape.candles.length} candles this round`
                : "Waiting for the first price"}
            </span>
            <span className="flex items-center gap-1.5">
              <span className="inline-block h-0 w-0 border-x-[5px] border-b-[8px] border-x-transparent border-b-slate-400" aria-hidden="true" />
              Triangles mark buys, colored by stage
            </span>
          </div>
        </div>

        <div className="grid min-w-0 grid-cols-2 gap-x-3 gap-y-2 self-start">
          <BookLadder compact side="UP" book={snapshot.books.up} maxSize={maxSize} history={history.up} staleAfterMs={stale} />
          <BookLadder compact side="DOWN" book={snapshot.books.down} maxSize={maxSize} history={history.down} staleAfterMs={stale} />
        </div>
      </div>
    </Card>
  );
}
