import { useState } from "react";
import type { OrderRow, PositionRow, SellableHolding } from "../../api/types";
import { Card } from "../Card";
import { EmptyState } from "../EmptyState";
import { ExitMeter } from "../ExitMeter";
import { IconLayers } from "../Icons";
import { LoadingPanel } from "../LoadingPanel";
import { Modal } from "../Modal";
import { useBot } from "../../hooks/BotContext";
import { useRemainingSeconds } from "../../hooks/useCountdown";
import { cx, fmtNum, fmtPx, fmtSignedUsd } from "../../lib/format";
import { useConfirmSells } from "../../lib/prefs";
import { STAGE_VAR, TAP_COLOR, TAP_STAGE, stopLossPrice, takeProfitPrice, type StageId } from "../../lib/stages";
import { LimitButton } from "./LimitButton";
import { LimitOrderButton } from "./LimitOrderButton";

type StageHolding = {
  key: string;
  side: "UP" | "DOWN";
  stage: number | null;
  /** a one-tap position: held to settlement or sold by hand, no take profit / stop loss */
  tap: boolean;
  size: number;
  entry: number | null;
  bid: number | null;
  pnl: number | null;
  holding: SellableHolding | null;
};

/** Volume-weighted entry price of the filled BUY orders of one stage of one market side. */
function stageEntryPrice(orders: OrderRow[], marketId: string, side: string, stage: number): number | null {
  let cost = 0;
  let qty = 0;
  for (const o of orders) {
    if (o.market_id !== marketId || o.side !== side || o.action !== "BUY" || !(o.filled_size > 0)) continue;
    if ((o.stage ?? 0) !== stage) continue;
    const price = o.avg_fill_price ?? o.submitted_price ?? o.requested_price;
    cost += price * o.filled_size;
    qty += o.filled_size;
  }
  return qty > 0 ? cost / qty : null;
}

export function PositionsCard({ orders, className }: { orders: OrderRow[]; className?: string }) {
  const { snapshot, sellNow, sellAll, cancelBuyLimit } = useBot();
  const remaining = useRemainingSeconds();
  const [selling, setSelling] = useState<Set<string>>(new Set());
  const [confirmSells] = useConfirmSells();
  const [pending, setPending] = useState<{ kind: "one"; row: StageHolding } | { kind: "all" } | { kind: "tap" } | null>(null);
  const marketId = snapshot?.market?.market_id ?? null;

  if (!snapshot) return <LoadingPanel className={className} />;

  const sellable = snapshot.sellable ?? [];
  const rows: StageHolding[] = [];
  const open: PositionRow[] = marketId
    ? snapshot.positions.filter((p) => p.market_id === marketId && p.status === "open" && p.size > 0)
    : [];
  for (const p of open) {
    const bid = (p.side === "UP" ? snapshot.books.up : snapshot.books.down)?.best_bid ?? null;
    const isTap = p.tag === "tap";
    // the one-tap row and the strategy row of one side are separate positions: each only gets its own holdings
    const stages = sellable.filter(
      (h) => h.market_id === p.market_id && h.side === p.side && (h.stage === TAP_STAGE) === isTap,
    );
    if (stages.length === 0) {
      // held, but nothing sellable right now (sell in flight, kill switch, ...): show the whole position
      rows.push({
        key: `${p.market_id}:${p.side}`,
        side: p.side,
        stage: null,
        tap: isTap,
        size: p.size,
        entry: p.avg_price,
        bid,
        pnl: p.unrealized_pnl,
        holding: null,
      });
      continue;
    }
    for (const h of stages) {
      // the server's own per-stage average cost first, the filled buys as a fallback
      const entry = h.entry_price ?? stageEntryPrice(orders, p.market_id, p.side, h.stage);
      rows.push({
        key: `${h.market_id}:${h.side}:${h.stage}`,
        side: p.side,
        stage: h.stage,
        tap: isTap,
        size: h.size,
        entry,
        bid,
        pnl: entry !== null && bid !== null ? (bid - entry) * h.size : null,
        holding: h,
      });
    }
  }
  // strategy stages first, one-tap rows last
  rows.sort((x, y) => Number(x.tap) - Number(y.tap) || (x.stage ?? 99) - (y.stage ?? 99) || x.side.localeCompare(y.side));

  // limit buys of this market that have not filled yet: they show here as pending until the ask reaches the limit
  // (then the bot buys once, the limit is gone and the shares appear as a one-tap row above)
  const pendingBuys = marketId ? (snapshot.buy_limits ?? []).filter((l) => l.market_id === marketId) : [];

  const cancelPendingBuy = (side: "UP" | "DOWN") => {
    const key = `buy:${side}`;
    if (selling.has(key)) return;
    setSelling((prev) => new Set(prev).add(key));
    void Promise.resolve(cancelBuyLimit(side)).finally(() =>
      setSelling((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      }),
    );
  };

  const sell = (row: StageHolding) => {
    if (!row.holding || selling.has(row.key)) return; // only blocks a double click while the request is on its way
    setSelling((prev) => new Set(prev).add(row.key));
    void Promise.resolve(sellNow(row.holding)).finally(() =>
      setSelling((prev) => {
        const next = new Set(prev);
        next.delete(row.key);
        return next;
      }),
    );
  };

  // "Confirm sells" (Settings, off by default) puts a dialog in front of the sell; off = the request goes out at once.
  const requestSell = (row: StageHolding) => {
    if (!row.holding || selling.has(row.key)) return;
    if (confirmSells) setPending({ kind: "one", row });
    else sell(row);
  };

  // "Sell all" = the strategy stages only; the one-tap shares have their own "Sell all tap" button
  const sellableRows = rows.filter((row) => row.holding && !row.tap);
  const tapRows = rows.filter((row) => row.holding && row.tap);
  const sellingAll = selling.has("__all__");
  const sellingTap = selling.has("__tap__");
  const sellGroup = (key: "__all__" | "__tap__", scope: "stages" | "tap") => {
    if (!marketId || selling.has(key)) return;
    setSelling((prev) => new Set(prev).add(key));
    void Promise.resolve(sellAll(marketId, scope)).finally(() =>
      setSelling((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      }),
    );
  };
  const sellEverything = () => sellGroup("__all__", "stages");
  const sellAllTap = () => sellGroup("__tap__", "tap");

  const requestSellAll = () => {
    if (!marketId || sellableRows.length === 0 || sellingAll) return;
    if (confirmSells) setPending({ kind: "all" });
    else sellEverything();
  };

  const requestSellAllTap = () => {
    if (!marketId || tapRows.length === 0 || sellingTap) return;
    if (confirmSells) setPending({ kind: "tap" });
    else sellAllTap();
  };

  const runPending = () => {
    const action = pending;
    setPending(null);
    if (action?.kind === "one") sell(action.row);
    else if (action?.kind === "all") sellEverything();
    else if (action?.kind === "tap") sellAllTap();
  };

  const live = snapshot.bot.mode === "live";
  const strategy = snapshot.strategy;

  return (
    <>
      <Card
        title="Positions"
        className={className}
        left={
          <>
            <button
              type="button"
              onClick={requestSellAll}
              disabled={sellingAll || sellableRows.length === 0}
              title={`Sell every strategy stage that holds shares (${sellableRows.length}) now at the market. One-tap shares are not included.`}
              className="btn btn-danger !px-3 !py-1 !text-[12px]"
            >
              {sellingAll ? "Selling…" : "Sell all"}
            </button>
            <button
              type="button"
              onClick={requestSellAllTap}
              disabled={sellingTap || tapRows.length === 0}
              title={`Sell every one-tap position (${tapRows.length}) now at the market. The strategy stages are not touched.`}
              className="btn !px-3 !py-1 !text-[12px]"
              style={{ color: TAP_COLOR, borderColor: "color-mix(in srgb, var(--tap) 45%, transparent)" }}
            >
              {sellingTap ? "Selling…" : "Sell all tap"}
            </button>
          </>
        }
        right={
          <>
            <LimitOrderButton />
          </>
        }
      >
        <div className={cx("scrollbar min-h-0 flex-1 overflow-y-auto pr-1 pt-3", rows.length === 0 && pendingBuys.length === 0 && "flex flex-col")}>
        {rows.length === 0 && pendingBuys.length === 0 ? (
          <EmptyState
            className="ov-empty flex-1"
            icon={<IconLayers size={22} />}
            title="No open position"
          />
        ) : (
          <ul className="pos-list divide-y divide-white/[0.06]">
            {rows.map((row) => {
              const stage = row.tap || row.stage === null ? null : (Math.min(3, Math.max(0, row.stage)) as StageId);
              const tp = stage === null ? null : takeProfitPrice(stage, row.entry, strategy);
              const sl = stage === null ? null : stopLossPrice(stage, row.entry, strategy, remaining);
              const color = row.tap ? TAP_COLOR : stage === null ? "var(--muted)" : STAGE_VAR[stage];
              return (
                <li key={row.key} className="py-3 first:pt-0">
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex min-w-0 items-center gap-2.5 text-[13.5px]">
                      <span
                        className={cx(
                          "chip !px-2 !py-0.5 !text-[11.5px] font-bold",
                          row.side === "UP" ? "border-up/30 bg-up/12 text-up" : "border-down/30 bg-down/12 text-down",
                        )}
                      >
                        {row.side === "UP" ? "UP" : "DOWN"}
                      </span>
                      <span className="truncate text-slate-200">
                        {fmtNum(row.size, 1)} shares at avg {fmtPx(row.entry)}
                      </span>
                    </div>
                    <div className="flex shrink-0 items-center gap-2.5">
                      <span className={cx("text-[13.5px] font-semibold", row.pnl === null ? "text-slate-500" : row.pnl >= 0 ? "text-up" : "text-down")}>
                        {row.pnl === null ? "—" : fmtSignedUsd(row.pnl)}
                      </span>
                      {row.holding ? (
                        <LimitButton side={row.side} stage={row.holding.stage} />
                      ) : null}
                      {row.holding ? (
                        <button
                          type="button"
                          onClick={() => requestSell(row)}
                          disabled={selling.has(row.key)}
                          title={`Sell ${fmtNum(row.holding.size, 2)} shares now at the market`}
                          className="btn btn-danger !px-2.5 !py-1 !text-[12px]"
                        >
                          {selling.has(row.key) ? "Selling…" : "Sell"}
                        </button>
                      ) : null}
                    </div>
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3 text-[12px] text-slate-500">
                    <span className="flex shrink-0 items-center gap-2">
                      <span className="inline-block h-2.5 w-2.5 rounded-[3px]" style={{ background: color }} aria-hidden="true" />
                      {row.tap ? "One-tap" : stage === null ? "Whole position" : `Stage ${stage}`} · bid {fmtPx(row.bid)}
                    </span>
                    {row.tap ? (
                      <span className="truncate" title="One-tap shares have no take profit or stop loss">
                        pays ${fmtNum(row.size, 2)} if {row.side} wins · held to settlement
                      </span>
                    ) : stage === null ? (
                      <span className="truncate">No exit levels recorded</span>
                    ) : (
                      <ExitMeter inline entry={row.entry} bid={row.bid} stop={sl} takeProfit={tp} />
                    )}
                  </div>
                </li>
              );
            })}
            {pendingBuys.map((l) => {
              const ask = (l.side === "UP" ? snapshot.books.up : snapshot.books.down)?.best_ask ?? null;
              const cents = Math.round(l.price * 1000) / 10;
              const usd = `$${l.amount.toFixed(2).replace(/\.00$/, "")}`;
              const key = `buy:${l.side}`;
              return (
                <li key={key} className="py-3 first:pt-0">
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex min-w-0 items-center gap-2.5 text-[13.5px]">
                      <span
                        className={cx(
                          "chip !px-2 !py-0.5 !text-[11.5px] font-bold",
                          l.side === "UP" ? "border-up/30 bg-up/12 text-up" : "border-down/30 bg-down/12 text-down",
                        )}
                      >
                        {l.side}
                      </span>
                      <span className="truncate text-slate-200">
                        Limit buy {usd} at {cents}¢ or lower
                      </span>
                    </div>
                    <div className="flex shrink-0 items-center gap-2.5">
                      <span className="chip !px-2 !py-0.5 !text-[11.5px] font-bold" style={{ color: "var(--amber)" }}>
                        Pending
                      </span>
                      <button
                        type="button"
                        onClick={() => cancelPendingBuy(l.side)}
                        disabled={selling.has(key)}
                        title="Cancel this limit buy: nothing has been bought yet"
                        className="btn !px-2.5 !py-1 !text-[12px]"
                      >
                        {selling.has(key) ? "Cancelling…" : "Cancel"}
                      </button>
                    </div>
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3 text-[12px] text-slate-500">
                    <span className="flex shrink-0 items-center gap-2">
                      <span className="inline-block h-2.5 w-2.5 rounded-[3px]" style={{ background: TAP_COLOR }} aria-hidden="true" />
                      One-tap · ask {fmtPx(ask)}
                    </span>
                    <span className="truncate">Waiting to fill · buys once, never above {cents}¢</span>
                  </div>
                </li>
              );
            })}
          </ul>
        )}

        </div>
      </Card>

      <Modal
        open={pending !== null}
        onClose={() => setPending(null)}
        eyebrow={live ? "Live sell" : "Paper sell"}
        title={
          pending?.kind === "all"
            ? "Sell every stage at the market?"
            : pending?.kind === "tap"
              ? "Sell all one-tap positions at the market?"
              : "Sell this position at the market?"
        }
        subtitle={
          pending?.kind === "one"
            ? `${pending.row.side} · ${pending.row.tap ? "one-tap" : pending.row.stage === null ? "position" : `stage ${pending.row.stage}`} · ${fmtNum(pending.row.size, 2)} shares`
            : pending?.kind === "tap"
              ? `${tapRows.length} one-tap ${tapRows.length === 1 ? "position" : "positions"} in this market`
              : `${sellableRows.length} ${sellableRows.length === 1 ? "stage holds" : "stages hold"} shares in this market`
        }
        danger={live}
        maxWidth="max-w-md"
        footer={
          <>
            <button type="button" className="btn" onClick={() => setPending(null)}>
              Cancel
            </button>
            <button type="button" className="btn btn-danger" onClick={runPending}>
              {pending?.kind === "all" ? "Sell all" : pending?.kind === "tap" ? "Sell all tap" : "Sell"}
            </button>
          </>
        }
      >
        <p className="text-[12px] leading-relaxed text-slate-400">
          {live
            ? "This sends a real market sell order on Polymarket."
            : "This closes the shares in the paper account at the current bid."}{" "}
          You can turn this question off in Settings under Execution.
        </p>
      </Modal>
    </>
  );
}
