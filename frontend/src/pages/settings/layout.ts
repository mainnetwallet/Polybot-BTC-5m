import type { FieldSpec } from "./fields";
import { FIELDS } from "./fields";

/** Pseudo key: a read-only row showing where the low take-profit tier starts (it is stage 0's min ask). */
export const LOW_TIER_FROM = "@stage0_low_from";

export type TabId = "stage0" | "stage1" | "stage2" | "stage3" | "risk" | "execution" | "maintenance";

export interface FieldGroup {
  title: string;
  description?: string;
  keys: string[];
}

export interface TabSpec {
  id: TabId;
  label: string;
  title: string;
  description: string;
  groups: FieldGroup[];
  /** Stage tabs only: the on/off setting that switches the whole stage. */
  enabledKey?: string;
}

// Every field is placed by key, so adding a field to FIELDS never lands in the wrong card.
// Each stage tab holds everything for that stage: when it is on, its entry, take-profit and stop-loss.
export const TABS: TabSpec[] = [
  {
    id: "stage0",
    label: "Stage 0",
    title: "Stage 0",
    description: "Early buy with its own time window, a tiered take-profit and stop-loss.",
    enabledKey: "stage0_enabled",
    groups: [
      { title: "Activation", description: "Seconds left before expiry in which stage 0 may buy.", keys: ["stage0_enabled", "stage0_start_seconds", "stage0_end_seconds"] },
      {
        title: "Entry",
        description: "Ask range and BTC gap in which stage 0 may buy.",
        keys: [
          "stage0_min_ask",
          "stage0_early_max_ask",
          "stage0_high_buy_price",
          "stage0_max_ask",
          "stage0_block_ask",
          "stage0_high_buy_after_pct",
          "stage0_min_price_gap_usd",
          "stage0_high_min_price_gap_usd",
        ],
      },
      {
        title: "Exits: take-profit",
        description: "Buy price decides the tier. 0 % means hold to settlement.",
        keys: [
          LOW_TIER_FROM,
          "stage0_mid_from_ask",
          "stage0_high_from_ask",
          "stage0_take_profit_pct_low",
          "stage0_take_profit_pct_mid",
          "stage0_take_profit_pct_high",
          "stage0_sell_cap_price",
        ],
      },
      {
        title: "Exits: stop-loss",
        description: "Market sell once the best bid is this many percent below the average buy. 0 = off.",
        keys: ["stage0_stop_loss_pct", "stage0_high_buy_stop_loss_pct"],
      },
    ],
  },
  {
    id: "stage1",
    label: "Stage 1",
    title: "Stage 1",
    description: "First final-window buy when the ask reaches its trigger.",
    enabledKey: "stage1_enabled",
    groups: [
      {
        title: "Activation",
        description: "A stage that is off does nothing: no buys, no take-profit and no stop-loss for it.",
        keys: ["stage1_enabled", "stage1_seconds"],
      },
      {
        title: "Entry",
        description: "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
        keys: ["stage1_threshold", "stage1_max_ask", "stage1_block_ask", "min_price_gap_usd"],
      },
      {
        title: "Exits: take-profit",
        keys: ["stage1_sell_enabled", "stage1_sell_max_buy", "stage1_take_profit_pct", "stage1_sell_price_high"],
      },
      {
        title: "Exits: stop-loss",
        description: "Market sell once the best bid is this many percent below the average buy. 0 = off.",
        keys: ["stage1_stop_loss_pct"],
      },
    ],
  },
  {
    id: "stage2",
    label: "Stage 2",
    title: "Stage 2",
    description: "Adds one more buy, only if stage 1 already bought before this stage starts.",
    enabledKey: "stage2_enabled",
    groups: [
      {
        title: "Activation",
        description: "A stage that is off does nothing: no buys, no take-profit and no stop-loss for it.",
        keys: ["stage2_enabled", "stage2_seconds"],
      },
      {
        title: "Entry",
        description: "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
        keys: ["stage2_threshold", "stage2_block_ask", "stage2_min_price_gap_usd"],
      },
      { title: "Exits: take-profit", keys: ["stage2_sell_enabled", "stage2_sell_price"] },
      {
        title: "Exits: stop-loss",
        description: "Market sell once the best bid is this many percent below the average buy. 0 = off.",
        keys: ["stage2_stop_loss_pct"],
      },
    ],
  },
  {
    id: "stage3",
    label: "Stage 3",
    title: "Stage 3",
    description: "Last buy on its own rules. It has a stop-loss but no take-profit.",
    enabledKey: "stage3_enabled",
    groups: [
      {
        title: "Activation",
        description: "A stage that is off does nothing: no buys and no stop-loss for it.",
        keys: ["stage3_enabled", "stage3_seconds"],
      },
      {
        title: "Entry",
        description: "Best ask at or above the trigger fires a marketable BUY. BTC gap 0 turns the filter off.",
        keys: ["stage3_threshold", "stage3_block_ask", "stage3_min_price_gap_usd"],
      },
      {
        title: "Exits: stop-loss",
        description: "Market sell once the best bid is this many percent below the average buy. 0 = off.",
        keys: ["stage3_stop_loss_pct"],
      },
    ],
  },
  {
    id: "risk",
    label: "Amount & Limits",
    title: "Amount & limits",
    description: "Trade amount, max price, order handling, daily limits and data-quality protection.",
    groups: [
      { title: "Sizing", keys: ["trade_amount_usdc", "max_execution_price"] },
      { title: "Order handling", keys: ["duplicate_policy", "conflict_policy"] },
      {
        title: "Daily limits",
        keys: ["max_daily_loss", "daily_profit_target", "max_trades_per_day", "max_open_positions"],
      },
      {
        title: "Buy guards (switch all buying off)",
        description: "Volatility circuit breakers. When one trips, new buys stop and Telegram sends an alert. Buying switches itself back on after the auto-resume wait (default 1 hour) unless you act: send \"Buy\" on Telegram (or press Buy ON) to resume now, or \"Off\" to keep it off until the next day.",
        keys: ["buy_guard_gap_usd", "buy_guard_trend_usd", "buy_guard_trend_markets", "buy_guard_resume_minutes"],
      },
      { title: "Data quality", keys: ["stale_data_max_age_ms"] },
    ],
  },
  {
    id: "execution",
    label: "Execution",
    title: "Execution & live safety",
    description: "Paper fill model, live readiness gates and connection check.",
    groups: [{ title: "Paper fill model", keys: ["paper_slippage_bps", "paper_fee_rate"] }],
  },
  {
    id: "maintenance",
    label: "Maintenance",
    title: "Maintenance & resets",
    description: "Reset counters, the paper ledger or the whole database.",
    groups: [],
  },
];

const BY_KEY = new Map<string, FieldSpec>(FIELDS.map((field) => [field.key, field]));

export function fieldsOf(keys: string[]): FieldSpec[] {
  return keys.map((key) => BY_KEY.get(key)).filter((field): field is FieldSpec => !!field);
}

export function tabOfField(key: string): TabId | null {
  for (const tab of TABS) {
    if (tab.groups.some((group) => group.keys.includes(key))) return tab.id;
  }
  return null;
}
