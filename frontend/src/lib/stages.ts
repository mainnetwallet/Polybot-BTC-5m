import type { OrderRow, Snapshot, StrategyState } from "../api/types";
import { fmtClock, fmtGapUsd, fmtPx, isNum } from "./format";

/**
 * Stage model for the dashboard: when each entry stage is open, which of its entry conditions pass right
 * now, and where its take profit / stop loss sit. The windows and exit prices mirror the backend rules in
 * app/config/runtime.py (stage_end_seconds, stop_loss_price_for, stage*_sell_price_for) for display only;
 * the backend stays the single source of truth for what actually trades.
 */

export type StageId = 0 | 1 | 2 | 3;
export const STAGES: StageId[] = [0, 1, 2, 3];

export const STAGE_VAR: Record<StageId, string> = {
  0: "var(--stage-0)",
  1: "var(--stage-1)",
  2: "var(--stage-2)",
  3: "var(--stage-3)",
};

export const STAGE_NAME: Record<StageId, string> = {
  0: "Early buy",
  1: "Stage 1",
  2: "Stage 2",
  3: "Stage 3",
};

const STAGE0_MAX_TAKE_PROFIT_PRICE = 0.99;

type Config = Snapshot["config"];

function cfgNum(config: Config, key: string): number | null {
  const value = config[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export function stageEnabled(s: StrategyState, stage: StageId): boolean {
  switch (stage) {
    case 0:
      return s.stage0_enabled;
    case 1:
      return s.stage1_enabled;
    case 2:
      return s.stage2_enabled;
    default:
      return s.stage3_enabled;
  }
}

/** A stage window in seconds left on the market clock: it buys while `end < remaining <= start`. */
export interface StageWindow {
  stage: StageId;
  enabled: boolean;
  start: number;
  end: number;
}

export function stageWindows(s: StrategyState): StageWindow[] {
  const starts: Record<1 | 2 | 3, number> = { 1: s.stage1_seconds, 2: s.stage2_seconds, 3: s.stage3_seconds };
  const endOf = (stage: 1 | 2 | 3): number => {
    // a stage ends where the next enabled stage starts; 0 = until the market ends
    for (let next = stage + 1; next <= 3; next += 1) {
      if (stageEnabled(s, next as StageId)) return starts[next as 2 | 3];
    }
    return 0;
  };
  return STAGES.map((stage) =>
    stage === 0
      ? { stage, enabled: s.stage0_enabled, start: s.stage0_start_seconds, end: s.stage0_end_seconds }
      : { stage, enabled: stageEnabled(s, stage), start: starts[stage], end: endOf(stage) },
  );
}

export type StageStatus =
  | { kind: "off" }
  | { kind: "waiting" } // no active market
  | { kind: "opens"; inSeconds: number }
  | { kind: "watching" }
  | { kind: "done" };

export function stageStatus(window: StageWindow, remaining: number | null): StageStatus {
  if (!window.enabled) return { kind: "off" };
  if (remaining === null) return { kind: "waiting" };
  if (remaining > window.start) return { kind: "opens", inSeconds: remaining - window.start };
  if (remaining > window.end) return { kind: "watching" };
  return { kind: "done" };
}

export function statusLabel(status: StageStatus): string {
  switch (status.kind) {
    case "off":
      return "off";
    case "waiting":
      return "waiting";
    case "opens":
      return `in ${fmtClock(status.inSeconds)}`;
    case "watching":
      return "watching";
    default:
      return "done";
  }
}

export function windowText(window: StageWindow): string {
  return window.end <= 0 ? `${window.start} s or less` : `${window.start} to ${window.end} s left`;
}

/** Evaluation inputs: the side whose ask is higher is the one a stage would buy. */
export interface CheckContext {
  side: "UP" | "DOWN" | null;
  ask: number | null;
  bid: number | null;
  /** BTC move from the price to beat in the direction of `side` (negative = against it) */
  gap: number | null;
}

export function checkContext(snapshot: Snapshot): CheckContext {
  const upAsk = snapshot.books.up?.best_ask ?? null;
  const downAsk = snapshot.books.down?.best_ask ?? null;
  const diff = snapshot.btc?.diff ?? null;
  let side: "UP" | "DOWN" | null = null;
  if (isNum(upAsk) && isNum(downAsk)) side = upAsk >= downAsk ? "UP" : "DOWN";
  else if (isNum(upAsk)) side = "UP";
  else if (isNum(downAsk)) side = "DOWN";
  else if (isNum(diff)) side = diff >= 0 ? "UP" : "DOWN";
  const book = side === "UP" ? snapshot.books.up : side === "DOWN" ? snapshot.books.down : null;
  return {
    side,
    ask: book?.best_ask ?? null,
    bid: book?.best_bid ?? null,
    gap: isNum(diff) && side ? (side === "UP" ? diff : -diff) : null,
  };
}

/** The BTC gap (USD, in the bought direction) a stage needs; 0 = no gap rule. */
export function requiredGap(stage: StageId, s: StrategyState, config: Config, ask: number | null): number {
  if (stage === 0) {
    const high = isNum(ask) && ask + 1e-9 >= s.stage0_high_buy_price;
    return high ? s.stage0_high_min_price_gap_usd : s.stage0_min_price_gap_usd;
  }
  const key = stage === 1 ? "min_price_gap_usd" : stage === 2 ? "stage2_min_price_gap_usd" : "stage3_min_price_gap_usd";
  return cfgNum(config, key) ?? 0;
}

export type CheckTone = "pass" | "fail" | "neutral";
export interface StageCheck {
  key: string;
  label: string;
  tone: CheckTone;
}

function signedGap(value: number): string {
  return `${value < 0 ? "-" : "+"}${fmtGapUsd(value)}`;
}

/** Per-stage entry limits: [lowest ask that buys, highest ask that buys]. */
function askRange(stage: StageId, s: StrategyState): [number, number] {
  switch (stage) {
    case 0:
      return [s.stage0_min_ask, s.stage0_early_max_ask];
    case 1:
      return [s.stage1_threshold, s.stage1_max_ask];
    case 2:
      return [s.stage2_threshold, s.stage2_max_ask ?? 1];
    default:
      return [s.stage3_threshold, s.stage3_max_ask ?? 1];
  }
}

export function stopLossPct(stage: StageId, entry: number | null, s: StrategyState, remaining: number | null): number {
  if (stage === 3 && remaining !== null) {
    const last = s.stage3_last_seconds ?? 0;
    const lastPct = s.stage3_last_seconds_stop_loss_pct ?? 0;
    if (lastPct > 0 && last > 0 && remaining <= last) return lastPct;
    const mid = s.stage3_mid_seconds ?? 0;
    const midPct = s.stage3_mid_stop_loss_pct ?? 0;
    if (midPct > 0 && mid > 0 && remaining <= mid) return midPct;
  }
  if (stage === 0 && entry !== null && s.stage0_high_buy_stop_loss_pct > 0 && entry + 1e-9 >= s.stage0_high_buy_price) {
    return s.stage0_high_buy_stop_loss_pct;
  }
  return [s.stage0_stop_loss_pct, s.stage1_stop_loss_pct, s.stage2_stop_loss_pct, s.stage3_stop_loss_pct][stage] ?? 0;
}

export function stopLossPrice(stage: StageId, entry: number | null, s: StrategyState, remaining: number | null): number | null {
  const pct = stopLossPct(stage, entry, s, remaining);
  if (pct <= 0 || entry === null || entry <= 0) return null;
  return entry * (1 - pct / 100);
}

export function takeProfitPrice(stage: StageId, entry: number | null, s: StrategyState): number | null {
  if (stage === 0) {
    if (entry === null || entry <= 0) return null;
    const pct =
      entry + 1e-9 < s.stage0_mid_from_ask
        ? s.stage0_take_profit_pct_low
        : entry + 1e-9 < s.stage0_high_from_ask
          ? s.stage0_take_profit_pct_mid
          : s.stage0_take_profit_pct_high;
    return pct > 0 ? Math.min(entry * (1 + pct / 100), STAGE0_MAX_TAKE_PROFIT_PRICE) : null;
  }
  if (stage === 1) {
    if (!s.stage1_sell_enabled) return null;
    if (entry === null) return s.stage1_sell_price_high;
    if (entry <= s.stage1_sell_max_buy + 1e-6) {
      if (s.stage1_take_profit_pct <= 0 || entry <= 0) return null;
      return Math.min(entry * (1 + s.stage1_take_profit_pct / 100), STAGE0_MAX_TAKE_PROFIT_PRICE);
    }
    return s.stage1_sell_price_high;
  }
  if (stage === 2) return s.stage2_sell_enabled ? s.stage2_sell_price : null;
  return null; // stage 3 has no take profit: it is held to settlement or its stop loss
}

/** The condition chips of one stage row (ask range, gap, spread, exits), with live pass/fail while it is watching. */
export function stageChecks(
  window: StageWindow,
  status: StageStatus,
  s: StrategyState,
  config: Config,
  ctx: CheckContext,
  remaining: number | null = null,
): StageCheck[] {
  const stage = window.stage;
  const live = status.kind === "watching";
  const verdict = (ok: boolean | null): CheckTone => (!live || ok === null ? "neutral" : ok ? "pass" : "fail");
  const chips: StageCheck[] = [];

  const [lo, hi] = askRange(stage, s);
  // stage 0 has two buy tiers: the cheaper early buy and the expensive buy (ask >= stage0_high_buy_price)
  const expHi = Math.min(s.stage0_max_ask, cfgNum(config, "max_execution_price") ?? 1);
  const expensive = stage === 0 && isNum(ctx.ask) && ctx.ask + 1e-9 >= s.stage0_high_buy_price;
  let askLabel: string;
  let askOk: boolean | null;
  if (stage === 0 && expensive && isNum(ctx.ask)) {
    askOk = ctx.ask <= expHi + 1e-9;
    askLabel = live ? `Ask ${fmtPx(ctx.ask)} in ${fmtPx(s.stage0_high_buy_price)} to ${fmtPx(expHi)}` : `Ask ${fmtPx(s.stage0_high_buy_price)} to ${fmtPx(expHi)}`;
  } else {
    askOk = isNum(ctx.ask) ? ctx.ask + 1e-9 >= lo && ctx.ask <= hi + 1e-9 : null;
    if (live && isNum(ctx.ask)) askLabel = `Ask ${fmtPx(ctx.ask)} in ${fmtPx(lo)} to ${fmtPx(hi)}`;
    else if (stage === 0) askLabel = `Ask ${fmtPx(lo)} to ${fmtPx(hi)}, ${fmtPx(s.stage0_high_buy_price)} to ${fmtPx(expHi)}`;
    else askLabel = `Ask ${fmtPx(lo)} to ${fmtPx(hi)}`;
  }
  chips.push({ key: "ask", label: askLabel, tone: verdict(askOk) });

  if (stage === 0) {
    // expensive buys only start once stage0_high_buy_after_pct of the window has passed (mirrors stage0_high_buy_opens_at)
    const span = Math.max(s.stage0_start_seconds - s.stage0_end_seconds, 0);
    const opensAt = s.stage0_start_seconds - (span * s.stage0_high_buy_after_pct) / 100;
    const timeOk = remaining !== null ? remaining <= opensAt + 1e-9 : null;
    chips.push({
      key: "expensive",
      label:
        live && expensive && timeOk
          ? "Expensive open"
          : `Expensive from ${Math.round(opensAt)} s left`,
      tone: verdict(expensive ? timeOk : null),
    });
  }

  const need = requiredGap(stage, s, config, ctx.ask);
  if (need > 0) {
    chips.push({
      key: "gap",
      label: live && isNum(ctx.gap) ? `Gap ${signedGap(ctx.gap)} of ${fmtGapUsd(need)}` : `Gap ${fmtGapUsd(need)}`,
      tone: verdict(isNum(ctx.gap) ? ctx.gap + 1e-9 >= need : null),
    });
  }

  const maxSpread = stage === 2 ? s.stage2_max_spread : stage === 3 ? s.stage3_max_spread : undefined;
  if (isNum(maxSpread) && maxSpread > 0) {
    const spread = isNum(ctx.ask) && isNum(ctx.bid) ? ctx.ask - ctx.bid : null;
    chips.push({
      key: "spread",
      label: live && spread !== null ? `Spread ${fmtPx(spread)} max ${fmtPx(maxSpread)}` : `Spread ${fmtPx(maxSpread)} max`,
      tone: verdict(spread !== null ? spread <= maxSpread + 1e-9 : null),
    });
  }

  if (stage === 0) {
    const tiers = [s.stage0_take_profit_pct_low, s.stage0_take_profit_pct_mid, s.stage0_take_profit_pct_high];
    if (Math.max(...tiers) > 0) {
      chips.push({
        key: "tp",
        label: expensive ? `Take profit +${tiers[2]}%` : `Take profit +${tiers[0]}/+${tiers[1]}/+${tiers[2]}%`,
        tone: "neutral",
      });
    }
  } else if (stage === 1 && s.stage1_sell_enabled) {
    if (s.stage1_take_profit_pct > 0) chips.push({ key: "tp", label: `Take profit +${s.stage1_take_profit_pct}%`, tone: "neutral" });
    chips.push({ key: "tp-high", label: `Above ${fmtPx(s.stage1_sell_max_buy)} sell ${fmtPx(s.stage1_sell_price_high)}`, tone: "neutral" });
  } else if (stage === 2 && s.stage2_sell_enabled) {
    chips.push({ key: "tp", label: `Take profit ${fmtPx(s.stage2_sell_price)}`, tone: "neutral" });
  }

  // the stop loss level of an open position is shown in Positions and triggers, so no chip here
  return chips;
}

export interface StageFill {
  shares: number;
  avg: number | null;
  /** a BUY order of this stage is still open (submitted, not filled yet) */
  pending: boolean;
}

/** What each stage bought in this market: filled BUY shares at their volume-weighted price, plus open BUY orders. */
export function stageFills(orders: OrderRow[], openOrders: OrderRow[], marketId: string | null): Record<StageId, StageFill> {
  const out: Record<StageId, StageFill> = {
    0: { shares: 0, avg: null, pending: false },
    1: { shares: 0, avg: null, pending: false },
    2: { shares: 0, avg: null, pending: false },
    3: { shares: 0, avg: null, pending: false },
  };
  if (!marketId) return out;
  const cost: Record<StageId, number> = { 0: 0, 1: 0, 2: 0, 3: 0 };
  for (const order of orders) {
    if (order.market_id !== marketId || order.action !== "BUY" || !(order.filled_size > 0)) continue;
    const stage = Math.min(3, Math.max(0, order.stage ?? 1)) as StageId;
    const price = order.avg_fill_price ?? order.submitted_price ?? order.requested_price;
    out[stage].shares += order.filled_size;
    cost[stage] += price * order.filled_size;
  }
  for (const stage of STAGES) {
    if (out[stage].shares > 0) out[stage].avg = cost[stage] / out[stage].shares;
  }
  for (const order of openOrders) {
    if (order.market_id !== marketId || order.action !== "BUY") continue;
    const stage = Math.min(3, Math.max(0, order.stage ?? 1)) as StageId;
    out[stage].pending = true;
  }
  return out;
}

/** Which stage needs how much BTC gap right now: the open stage, else the next one to open. */
export function gapTarget(
  windows: StageWindow[],
  remaining: number | null,
  s: StrategyState,
  config: Config,
  ask: number | null,
): { stage: StageId; need: number } | null {
  const open = windows.filter((w) => stageStatus(w, remaining).kind === "watching");
  const upcoming = windows.filter((w) => stageStatus(w, remaining).kind === "opens").sort((a, b) => b.start - a.start);
  const pick = open[0] ?? upcoming[0] ?? null;
  if (!pick) return null;
  return { stage: pick.stage, need: requiredGap(pick.stage, s, config, ask) };
}
