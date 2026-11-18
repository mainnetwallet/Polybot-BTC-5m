import { useMemo, useState } from "react";
import { api } from "../../api/client";
import type { OrderRow, PositionRow, SellableHolding, TriggerRow } from "../../api/types";
import { Card } from "../Card";
import { EmptyState } from "../EmptyState";
import { ExitMeter } from "../ExitMeter";
import { IconLayers } from "../Icons";
import { LoadingPanel } from "../LoadingPanel";
import { Modal } from "../Modal";
import { useBot } from "../../hooks/BotContext";
import { useRemainingSeconds } from "../../hooks/useCountdown";
import { useLiveRefresh } from "../../hooks/useLiveRefresh";
import { cx, fmtNum, fmtPx, fmtSignedUsd, fmtTime, sentence } from "../../lib/format";
import { useConfirmSells } from "../../lib/prefs";
import { STAGE_VAR, stopLossPrice, takeProfitPrice, type StageId } from "../../lib/stages";

type StageHolding = {
  key: string;
  side: "UP" | "DOWN";
  stage: number | null;
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

interface LogLine {
  key: string;
  t: number;
  text: string;
  bold?: string;
  tone?: "up" | "down" | "amber";
}

function sideWord(side: string): string {
  return side === "UP" ? "Up" : "Down";
}

export function PositionsCard({ orders, className }: { orders: OrderRow[]; className?: string }) {
  const { snapshot, sellNow, sellAll, dataVersion, logs } = useBot();
  const remaining = useRemainingSeconds();
  const [selling, setSelling] = useState<Set<string>>(new Set());
  const [confirmSells] = useConfirmSells();
  const [pending, setPending] = useState<{ kind: "one"; row: StageHolding } | { kind: "all" } | null>(null);
  const mode = snapshot?.bot.mode;
  const triggers = useLiveRefresh<TriggerRow[]>(
    () => (mode ? api.triggers(60) : Promise.resolve([])),
    [mode],
    dataVersion,
    mode !== undefined,
  );

  const marketId = snapshot?.market?.market_id ?? null;

  const lines = useMemo<LogLine[]>(() => {
    const out: LogLine[] = [];
    for (const row of triggers.data ?? []) {
      if (marketId && row.market_id !== marketId) continue;
      const t = Date.parse(row.created_at);
      if (!Number.isFinite(t)) continue;
      out.push({
        key: `t${row.id}`,
        t,
        bold: `${sideWord(row.side)} ask ${fmtPx(row.best_ask)}`,
        text: `${sentence(row.decision)}${row.reason ? `, ${row.reason.replace(/_/g, " ")}` : ""}`,
        tone: row.triggered ? "up" : undefined,
      });
    }
    for (const o of orders) {
      if (marketId && o.market_id !== marketId) continue;
      if (!(o.filled_size > 0)) continue;
      const t = Date.parse(o.created_at);
      if (!Number.isFinite(t)) continue;
      const price = o.avg_fill_price ?? o.submitted_price ?? o.requested_price;
      const sell = o.action === "SELL";
      out.push({
        key: `o${o.id}`,
        t,
        bold: `${sell ? "Sold" : "Filled"} ${fmtNum(o.filled_size, 1)} at ${fmtPx(price)}`,
        text: `stage ${o.stage ?? 1} ${sideWord(o.side)}`,
        tone: sell ? "amber" : "up",
      });
    }
    for (const log of logs) {
      const warn = log.level === "WARNING" || log.level === "ERROR" || log.level === "CRITICAL";
      if (log.category !== "settlement" && !warn) continue;
      const t = Date.parse(log.created_at);
      if (!Number.isFinite(t)) continue;
      out.push({
        key: `l${log.id ?? `${log.created_at}${log.message}`}`,
        t,
        text: log.message,
        tone: log.level === "INFO" ? undefined : log.level === "WARNING" ? "amber" : "down",
      });
    }
    return out.sort((a, b) => b.t - a.t).slice(0, 8);
  }, [triggers.data, orders, logs, marketId]);

  if (!snapshot) return <LoadingPanel className={className} />;

  const sellable = snapshot.sellable ?? [];
  const rows: StageHolding[] = [];
  const open: PositionRow[] = marketId
    ? snapshot.positions.filter((p) => p.market_id === marketId && p.status === "open" && p.size > 0)
    : [];
  for (const p of open) {
    const bid = (p.side === "UP" ? snapshot.books.up : snapshot.books.down)?.best_bid ?? null;
    const stages = sellable.filter((h) => h.market_id === p.market_id && h.side === p.side);
    if (stages.length === 0) {
      // held, but nothing sellable right now (sell in flight, kill switch, ...): show the whole position
      rows.push({
        key: `${p.market_id}:${p.side}`,
        side: p.side,
        stage: null,
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
        size: h.size,
        entry,
        bid,
        pnl: entry !== null && bid !== null ? (bid - entry) * h.size : null,
        holding: h,
      });
    }
  }
  rows.sort((x, y) => (x.stage ?? 99) - (y.stage ?? 99) || x.side.localeCompare(y.side));

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

  const sellableRows = rows.filter((row) => row.holding);
  const sellingAll = selling.has("__all__");
  const sellEverything = () => {
    if (!marketId || sellableRows.length === 0 || sellingAll) return;
    setSelling((prev) => new Set(prev).add("__all__"));
    void Promise.resolve(sellAll(marketId)).finally(() =>
      setSelling((prev) => {
        const next = new Set(prev);
        next.delete("__all__");
        return next;
      }),
    );
  };

  const requestSellAll = () => {
    if (!marketId || sellableRows.length === 0 || sellingAll) return;
    if (confirmSells) setPending({ kind: "all" });
    else sellEverything();
  };

  const runPending = () => {
    const action = pending;
    setPending(null);
    if (action?.kind === "one") sell(action.row);
    else if (action?.kind === "all") sellEverything();
  };

  const live = snapshot.bot.mode === "live";
  const strategy = snapshot.strategy;

  return (
    <>
      <Card
        title="Positions and triggers"
        className={className}
        right={
          <>
            {sellableRows.length > 0 ? (
              <button
                type="button"
                onClick={requestSellAll}
                disabled={sellingAll}
                title={`Sell every stage that holds shares (${sellableRows.length}) now at the market`}
                className="btn btn-danger !px-3 !py-1 !text-[12px]"
              >
                {sellingAll ? "Selling…" : "Sell all"}
              </button>
            ) : null}
            <span>This round</span>
          </>
        }
      >
        {rows.length === 0 ? (
          <EmptyState
            icon={<IconLayers size={22} />}
            title="No open position"
            hint="Filled buys of the live market appear here, one row per stage, with their take profit and stop loss."
          />
        ) : (
          <ul className="scrollbar max-h-[26rem] divide-y divide-white/[0.06] overflow-y-auto pr-1">
            {rows.map((row) => {
              const stage = row.stage === null ? null : (Math.min(3, Math.max(0, row.stage)) as StageId);
              const tp = stage === null ? null : takeProfitPrice(stage, row.entry, strategy);
              const sl = stage === null ? null : stopLossPrice(stage, row.entry, strategy, remaining);
              const color = stage === null ? "var(--muted)" : STAGE_VAR[stage];
              return (
                <li key={row.key} className="py-3.5 first:pt-0">
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex min-w-0 items-center gap-2.5 text-[14px]">
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
                      <span className={cx("text-[14px] font-semibold", row.pnl === null ? "text-slate-500" : row.pnl >= 0 ? "text-up" : "text-down")}>
                        {row.pnl === null ? "—" : fmtSignedUsd(row.pnl)}
                      </span>
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
                  <div className="mt-1.5 flex items-center gap-2 text-[12.5px] text-slate-500">
                    <span className="inline-block h-2.5 w-2.5 rounded-[3px]" style={{ background: color }} aria-hidden="true" />
                    {stage === null ? "Whole position" : `Stage ${stage}`} · bid {fmtPx(row.bid)}
                  </div>
                  <div className="mt-2.5">
                    {stage === null ? (
                      <div className="text-[12.5px] text-slate-500">Exit levels are tracked per stage; none is recorded for this position</div>
                    ) : (
                      <ExitMeter entry={row.entry} bid={row.bid} stop={sl} takeProfit={tp} />
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}

        <div className="mt-5 border-t hairline pt-4">
          <div className="mb-2.5 text-[13px] font-medium text-slate-400">Trigger log</div>
          {lines.length === 0 ? (
            <div className="text-[12.5px] text-slate-500">
              {triggers.data ? "No trigger, fill or settlement yet for this round." : "Loading the trigger log"}
            </div>
          ) : (
            <ul className="space-y-2">
              {lines.map((line) => (
                <li key={line.key} className="grid grid-cols-[4.2rem_1fr] gap-2 text-[13px] leading-snug">
                  <span className="text-slate-500">{fmtTime(new Date(line.t).toISOString())}</span>
                  <span className={cx("min-w-0 break-words text-slate-300", line.tone === "down" && "text-down", line.tone === "amber" && "text-amber")}>
                    {line.bold ? <b className={cx("font-semibold", line.tone === "up" ? "text-up" : "text-slate-100")}>{line.bold}</b> : null}
                    {line.bold ? " " : ""}
                    {line.text}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </Card>

      <Modal
        open={pending !== null}
        onClose={() => setPending(null)}
        eyebrow={live ? "Live sell" : "Paper sell"}
        title={pending?.kind === "all" ? "Sell every stage at the market?" : "Sell this position at the market?"}
        subtitle={
          pending?.kind === "one"
            ? `${pending.row.side} · ${pending.row.stage === null ? "position" : `stage ${pending.row.stage}`} · ${fmtNum(pending.row.size, 2)} shares`
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
              {pending?.kind === "all" ? "Sell all" : "Sell"}
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
