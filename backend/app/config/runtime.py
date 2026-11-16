"""Runtime-tunable settings with database persistence.

Environment values are defaults. The dashboard can override a small, validated
subset; overrides live in the bot_settings table so they survive restarts.
Secrets are never overridable from the API.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, model_validator
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.config.settings import Settings

OVERRIDABLE_KEYS = (
    "stage1_threshold",
    "stage1_max_ask",
    "stage1_seconds",
    "stage1_enabled",
    "stage2_enabled",
    "stage3_enabled",
    "stage2_seconds",
    "stage2_threshold",
    "stage3_seconds",
    "stage3_threshold",
    "max_execution_price",
    "trade_amount_usdc",
    "max_daily_loss",
    "daily_profit_target",
    "stale_data_max_age_ms",
    "duplicate_policy",
    "conflict_policy",
    "paper_slippage_bps",
    "paper_fee_rate",
    "max_trades_per_day",
    "max_open_positions",
    "min_price_gap_usd",
    "stage2_min_price_gap_usd",
    "stage3_min_price_gap_usd",
    "stage0_enabled",
    "stage0_start_seconds",
    "stage0_end_seconds",
    "stage0_min_ask",
    "stage0_max_ask",
    "stage0_early_max_ask",
    "stage0_min_price_gap_usd",
    "stage0_mid_from_ask",
    "stage0_high_from_ask",
    "stage0_take_profit_pct_low",
    "stage0_take_profit_pct_mid",
    "stage0_take_profit_pct_high",
    "stage1_sell_enabled",
    "stage1_sell_max_buy",
    "stage1_take_profit_pct",
    "stage1_sell_price_high",
    "stage2_sell_enabled",
    "stage2_sell_price",
    "stage0_stop_loss_pct",
    "stage1_stop_loss_pct",
    "stage2_stop_loss_pct",
    "stage3_stop_loss_pct",
    "stage0_high_buy_price",
    "stage0_high_buy_stop_loss_pct",
    "stage0_high_buy_after_pct",
    "stage3_mid_seconds",
    "stage3_mid_stop_loss_pct",
    "stage3_last_seconds",
    "stage3_last_seconds_stop_loss_pct",
)


# highest bid a stage 0 percent take-profit waits for (a share pays at most 1.00)
STAGE0_MAX_TAKE_PROFIT_PRICE = 0.99


class RuntimeSettings(BaseModel):
    stage1_threshold: float = Field(ge=0.01, le=0.999)
    stage1_seconds: int = Field(ge=1, le=300)
    # Stage 1 does not buy when the best ask is above this (default 0.97). Never pays more than this either.
    stage1_max_ask: float = Field(default=0.97, ge=0.01, le=1.0)
    # Master switch per entry stage (stage 0: stage0_enabled). Off = no buys, no take-profit, no stop-loss.
    stage1_enabled: bool = True
    stage2_enabled: bool = True
    stage3_enabled: bool = True
    stage2_seconds: int = Field(default=50, ge=1, le=300)
    stage2_threshold: float = Field(default=0.90, ge=0.01, le=0.999)
    stage3_seconds: int = Field(default=30, ge=1, le=300)
    stage3_threshold: float = Field(default=0.90, ge=0.01, le=0.999)
    max_execution_price: float = Field(ge=0.01, le=1.0)
    trade_amount_usdc: float = Field(gt=0.0, le=100000.0)
    max_daily_loss: float = Field(ge=0.0)
    daily_profit_target: float = Field(ge=0.0)
    stale_data_max_age_ms: int = Field(ge=100, le=60000)
    duplicate_policy: str
    conflict_policy: str
    paper_slippage_bps: float = Field(ge=0.0, le=5000.0)
    paper_fee_rate: float = Field(ge=0.0, le=1.0)
    max_trades_per_day: int = Field(ge=1, le=10000)
    max_open_positions: int = Field(ge=0, le=100)
    min_price_gap_usd: float = Field(default=0.0, ge=0.0, le=1_000_000.0)
    stage2_min_price_gap_usd: float = Field(default=0.0, ge=0.0, le=1_000_000.0)
    stage3_min_price_gap_usd: float = Field(default=0.0, ge=0.0, le=1_000_000.0)
    # Stage 0 (early entry + take-profit sell). Off by default when a RuntimeSettings is built
    # directly; the app's Settings (.env) turns it on.
    stage0_enabled: bool = False
    stage0_start_seconds: int = Field(default=200, ge=1, le=300)
    stage0_end_seconds: int = Field(default=100, ge=0, le=299)
    stage0_min_ask: float = Field(default=0.69, ge=0.01, le=0.999)
    # Max ask by buy price: a buy below stage0_high_buy_price (the earlier, cheaper buys) goes up to
    # stage0_early_max_ask; an expensive buy (ask >= stage0_high_buy_price, only after
    # stage0_high_buy_after_pct of the window) goes up to stage0_max_ask. Asks between
    # stage0_early_max_ask and stage0_high_buy_price do not buy. An early value above stage0_max_ask has no
    # effect: the lower of the two applies. (The app's real values come from Settings/.env; these are fallbacks.)
    stage0_max_ask: float = Field(default=0.94, ge=0.01, le=0.999)
    stage0_early_max_ask: float = Field(default=0.85, ge=0.01, le=0.999)
    stage0_min_price_gap_usd: float = Field(default=70.0, ge=0.0, le=1_000_000.0)
    # Stage 0 take-profit in percent above the average buy price, by what stage 0 paid per share:
    #   cost <  stage0_mid_from_ask   -> stage0_take_profit_pct_low  (default 0.60-0.70 buy -> +15 %)
    #   cost <  stage0_high_from_ask  -> stage0_take_profit_pct_mid  (default 0.70-0.80 buy -> +10 %)
    #   otherwise                     -> stage0_take_profit_pct_high (default 0.80-0.85 buy -> +5 %)
    # 0 = no take-profit for that tier (held to settlement).
    stage0_mid_from_ask: float = Field(default=0.70, ge=0.01, le=0.999)
    stage0_high_from_ask: float = Field(default=0.80, ge=0.01, le=0.999)
    stage0_take_profit_pct_low: float = Field(default=15.0, ge=0.0, le=500.0)
    stage0_take_profit_pct_mid: float = Field(default=10.0, ge=0.0, le=500.0)
    stage0_take_profit_pct_high: float = Field(default=5.0, ge=0.0, le=500.0)
    stage1_sell_enabled: bool = False
    stage1_sell_max_buy: float = Field(default=0.86, ge=0.01, le=0.999)
    # Stage 1 take-profit in percent above the average buy price for shares that cost up to
    # stage1_sell_max_buy (0 = no take-profit for those shares: held to settlement / stop-loss).
    stage1_take_profit_pct: float = Field(default=8.0, ge=0.0, le=500.0)
    stage1_sell_price_high: float = Field(default=0.99, ge=0.02, le=0.999)
    stage2_sell_enabled: bool = False
    stage2_sell_price: float = Field(default=0.99, ge=0.02, le=0.999)
    # Stop-loss in percent below the average buy price (0 = off). Off when a RuntimeSettings is built
    # directly; the app's Settings (.env) sets 50 / 15 / 9 / 5 for stages 0 / 1 / 2 / 3.
    stage0_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    stage1_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    stage2_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    stage3_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    # Stage 0 shares that cost stage0_high_buy_price or more use stage0_high_buy_stop_loss_pct instead of
    # stage0_stop_loss_pct (0 = off: no separate percentage for expensive buys).
    stage0_high_buy_price: float = Field(default=0.90, ge=0.01, le=0.999)
    stage0_high_buy_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    # Buys at stage0_high_buy_price or more wait until this percent of the stage 0 window (start -> end)
    # has passed (0 = the whole window).
    stage0_high_buy_after_pct: float = Field(default=50.0, ge=0.0, le=100.0)
    # Last-seconds stop-loss for stage 3: with `stage3_last_seconds` or fewer seconds left, stage 3 shares
    # use stage3_last_seconds_stop_loss_pct (percent below cost) instead of stage3_stop_loss_pct. 0 = off.
    # Middle tier for stage 3: with `stage3_mid_seconds` or fewer seconds left (and more than
    # `stage3_last_seconds`), stage 3 shares use stage3_mid_stop_loss_pct. 0 = off.
    stage3_mid_seconds: int = Field(default=20, ge=0, le=300)
    stage3_mid_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    stage3_last_seconds: int = Field(default=10, ge=0, le=300)
    stage3_last_seconds_stop_loss_pct: float = Field(default=0.0, ge=0.0, le=99.0)
    paper_fill_ratio: float = Field(gt=0.0, le=1.0)
    paper_fee_exponent: float = Field(ge=0.0, le=5.0)
    paper_starting_balance: float = Field(gt=0.0)
    live_trading_enabled: bool
    live_credentials_present: bool
    geo_verified: bool

    @model_validator(mode="after")
    def _check_ranges(self) -> "RuntimeSettings":
        if self.duplicate_policy not in {"one_per_market", "one_per_side", "allow"}:
            raise ValueError("duplicate_policy must be one_per_market, one_per_side or allow")
        if self.conflict_policy not in {"up_first", "down_first", "highest_ask", "block_both"}:
            raise ValueError("conflict_policy must be up_first, down_first, highest_ask or block_both")
        if self.stage1_max_ask < self.stage1_threshold:
            raise ValueError("stage1_max_ask must be >= stage1_threshold")
        if self.max_execution_price < self.stage1_threshold:
            raise ValueError("max_execution_price must be >= stage1_threshold")
        if self.max_execution_price < max(self.stage2_threshold, self.stage3_threshold):
            raise ValueError("max_execution_price must be >= the stage 2/3 thresholds")
        # Stage windows follow each other: stage 1 starts at stage1_seconds and ends where stage 2
        # starts, stage 2 ends where stage 3 starts. So the start times of the enabled stages must fall.
        starts = [
            (stage, label, float(value))
            for stage, label, value in (
                (1, "stage1_seconds", self.stage1_seconds),
                (2, "stage2_seconds", self.stage2_seconds),
                (3, "stage3_seconds", self.stage3_seconds),
            )
            if self.stage_enabled(stage)
        ]
        for (_, prev_label, prev_value), (_, label, value) in zip(starts, starts[1:]):
            if value >= prev_value:
                raise ValueError(f"{label} ({value:g}) must be lower than {prev_label} ({prev_value:g}): each stage starts after the one before")
        if self.stage0_end_seconds >= self.stage0_start_seconds:
            raise ValueError("stage0_end_seconds must be less than stage0_start_seconds")
        if self.stage0_min_ask > self.stage0_max_ask:
            raise ValueError("stage0_min_ask must be <= stage0_max_ask")
        if self.stage0_mid_from_ask > self.stage0_high_from_ask:
            raise ValueError("stage0_mid_from_ask must be <= stage0_high_from_ask")
        if self.stage1_sell_price_high <= self.stage1_sell_max_buy:
            raise ValueError("stage1_sell_price_high must be greater than stage1_sell_max_buy")
        if self.stage2_sell_price <= self.stage2_threshold:
            raise ValueError("stage2_sell_price must be greater than stage2_threshold")
        return self

    def stage_end_seconds(self, stage: int) -> float:
        """Seconds left at which `stage` stops buying: where the next enabled stage starts.

        Stage 1 ends where stage 2 starts (stage2_seconds, default 50), stage 2 ends where stage 3 starts
        (stage3_seconds, default 30). A switched-off stage is skipped; 0 = until the market ends.
        """
        if stage == 0:
            return float(self.stage0_end_seconds)
        starts = {2: self.stage2_seconds, 3: self.stage3_seconds}
        for nxt in range(stage + 1, 4):
            if self.stage_enabled(nxt):
                return float(starts[nxt])
        return 0.0

    def stage_enabled(self, stage: int) -> bool:
        """True when `stage` (0-3) is switched on. A switched-off stage buys and sells nothing."""
        return {
            0: self.stage0_enabled,
            1: self.stage1_enabled,
            2: self.stage2_enabled,
            3: self.stage3_enabled,
        }.get(stage, True)

    def stage1_sell_price_for(self, cost_per_share: float | None) -> float | None:
        """Take-profit price for stage 1 shares bought at `cost_per_share`; None = take-profit is off.

        Paid up to stage1_sell_max_buy -> cost * (1 + stage1_take_profit_pct / 100), capped at
        STAGE0_MAX_TAKE_PROFIT_PRICE (a percentage of 0 means no take-profit for these shares: None);
        paid more -> stage1_sell_price_high (0.99). Unknown cost uses the high price, so it never
        sells too early.
        """
        if not self.stage1_sell_enabled:
            return None
        if cost_per_share is None:
            return self.stage1_sell_price_high
        if cost_per_share <= self.stage1_sell_max_buy + 1e-6:
            if self.stage1_take_profit_pct <= 0 or cost_per_share <= 0:
                return None
            return min(
                round(cost_per_share * (1.0 + self.stage1_take_profit_pct / 100.0), 6),
                STAGE0_MAX_TAKE_PROFIT_PRICE,
            )
        return self.stage1_sell_price_high

    def stage0_high_buy_opens_at(self) -> float:
        """Seconds left at or below which stage 0 may buy at stage0_high_buy_price or more."""
        span = max(self.stage0_start_seconds - self.stage0_end_seconds, 0)
        return self.stage0_start_seconds - span * self.stage0_high_buy_after_pct / 100.0

    def max_buy_price_for_stage(self, stage: int) -> float:
        """Highest ask a buy of `stage` may pay (stage 0 has its own tiered limit, see stage0_max_ask_for)."""
        if stage == 1:
            return min(self.stage1_max_ask, self.max_execution_price)
        return self.max_execution_price

    def stage0_max_ask_for(self, ask: float) -> float:
        """Highest ask stage 0 may buy at: stage0_max_ask for an expensive buy (ask >= stage0_high_buy_price),
        otherwise the earlier-buy limit stage0_early_max_ask."""
        if ask + 1e-9 >= self.stage0_high_buy_price:
            return self.stage0_max_ask
        return min(self.stage0_early_max_ask, self.stage0_max_ask)

    def stage0_high_buy_too_early(self, ask: float, remaining_seconds: float) -> bool:
        """True when `ask` is a high buy (>= stage0_high_buy_price) and its part of the window has not begun."""
        return ask + 1e-9 >= self.stage0_high_buy_price and remaining_seconds > self.stage0_high_buy_opens_at() + 1e-9

    def stage2_sell_price_for(self, cost_per_share: float | None) -> float | None:
        """Take-profit price for stage 2 shares (same price whatever they cost); None = off."""
        return self.stage2_sell_price if self.stage2_sell_enabled else None

    def stop_loss_pct_for(
        self,
        stage: int,
        cost_per_share: float | None = None,
        remaining_seconds: float | None = None,
    ) -> float:
        """Stop-loss percentage configured for `stage` (0 = off).

        Stage 3 steps down as the market ends: stage3_stop_loss_pct (5), then stage3_mid_stop_loss_pct
        with `stage3_mid_seconds` or fewer seconds left (3), then stage3_last_seconds_stop_loss_pct
        with `stage3_last_seconds` or fewer seconds left (2). A tier set to 0 is skipped.

        Stage 0 shares that cost stage0_high_buy_price or more use stage0_high_buy_stop_loss_pct
        (when it is above 0) instead of stage0_stop_loss_pct.
        """
        if (
            stage == 3
            and remaining_seconds is not None
            and self.stage3_last_seconds_stop_loss_pct > 0
            and 0 < self.stage3_last_seconds
            and remaining_seconds <= self.stage3_last_seconds
        ):
            return self.stage3_last_seconds_stop_loss_pct
        if (
            stage == 3
            and remaining_seconds is not None
            and self.stage3_mid_stop_loss_pct > 0
            and 0 < self.stage3_mid_seconds
            and remaining_seconds <= self.stage3_mid_seconds
        ):
            return self.stage3_mid_stop_loss_pct
        if (
            stage == 0
            and cost_per_share is not None
            and self.stage0_high_buy_stop_loss_pct > 0
            and cost_per_share + 1e-9 >= self.stage0_high_buy_price
        ):
            return self.stage0_high_buy_stop_loss_pct
        return {
            0: self.stage0_stop_loss_pct,
            1: self.stage1_stop_loss_pct,
            2: self.stage2_stop_loss_pct,
            3: self.stage3_stop_loss_pct,
        }.get(stage, 0.0)

    def stop_loss_price_for(
        self,
        stage: int,
        cost_per_share: float | None,
        remaining_seconds: float | None = None,
    ) -> float | None:
        """Best-bid level at or below which `stage`'s shares are sold; None = no stop-loss.

        cost * (1 - pct / 100) with the average price paid per share. An unknown cost has no
        reference price, so there is no stop for those shares.
        """
        pct = self.stop_loss_pct_for(stage, cost_per_share, remaining_seconds)
        if pct <= 0 or cost_per_share is None or cost_per_share <= 0:
            return None
        return cost_per_share * (1.0 - pct / 100.0)

    def stage0_take_profit_pct_for(self, cost_per_share: float | None) -> float:
        """Take-profit percentage for shares stage 0 bought at `cost_per_share` (0 = none).

        Below stage0_mid_from_ask -> low tier, below stage0_high_from_ask -> mid tier, else high tier.
        An unknown cost has no tier and no reference price, so it gets no take-profit.
        """
        if cost_per_share is None or cost_per_share <= 0:
            return 0.0
        if cost_per_share + 1e-9 < self.stage0_mid_from_ask:
            return self.stage0_take_profit_pct_low
        if cost_per_share + 1e-9 < self.stage0_high_from_ask:
            return self.stage0_take_profit_pct_mid
        return self.stage0_take_profit_pct_high

    def stage0_sell_price_for(self, cost_per_share: float | None) -> float | None:
        """Take-profit sell price for shares stage 0 bought at `cost_per_share`; None = no take-profit.

        cost * (1 + tier percentage / 100), capped at STAGE0_MAX_TAKE_PROFIT_PRICE (a share cannot be
        sold above 1.00). A tier percentage of 0, or an unknown cost, means the shares are held to settlement.
        """
        pct = self.stage0_take_profit_pct_for(cost_per_share)
        if pct <= 0 or cost_per_share is None:
            return None
        return min(cost_per_share * (1.0 + pct / 100.0), STAGE0_MAX_TAKE_PROFIT_PRICE)

    def to_public_dict(self) -> dict[str, Any]:
        data = self.model_dump()
        data.pop("live_credentials_present", None)
        return data


# Override keys renamed over time: old DB key -> current key.
LEGACY_KEYS = {"final_window_seconds": "stage1_seconds", "trigger_threshold": "stage1_threshold"}


class SettingsService:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._overrides: dict[str, Any] = {}

    def load(self, session: Session) -> None:
        from app.database.models import BotSetting

        self._rename_legacy_keys(session)
        rows = session.execute(select(BotSetting)).scalars().all()
        self._overrides = {}
        for row in rows:
            if row.key in OVERRIDABLE_KEYS:
                self._overrides[row.key] = row.value

    @staticmethod
    def _rename_legacy_keys(session: Session) -> None:
        """Move a saved override from its old key to the new one (an existing new key wins)."""
        from app.database.models import BotSetting

        changed = False
        for old, new in LEGACY_KEYS.items():
            row = session.get(BotSetting, old)
            if row is None:
                continue
            if session.get(BotSetting, new) is None:
                session.add(BotSetting(key=new, value=row.value))
            session.delete(row)
            changed = True
        if changed:
            session.commit()

    def get(self) -> RuntimeSettings:
        merged: dict[str, Any] = {}
        for key in OVERRIDABLE_KEYS:
            merged[key] = getattr(self._settings, key)
        merged.update(self._overrides)
        merged.update(
            paper_fill_ratio=self._settings.paper_fill_ratio,
            paper_fee_exponent=self._settings.paper_fee_exponent,
            paper_starting_balance=self._settings.paper_starting_balance,
            live_trading_enabled=self._settings.live_trading_enabled,
            live_credentials_present=self._settings.live_credentials_present,
            geo_verified=self._settings.polymarket_geo_verified,
        )
        return RuntimeSettings(**merged)

    def update(self, session: Session, changes: dict[str, Any]) -> RuntimeSettings:
        from app.database.models import BotSetting

        clean = {k: v for k, v in changes.items() if k in OVERRIDABLE_KEYS and v is not None}
        if not clean:
            raise ValueError("no supported settings supplied")
        candidate = self.get().model_dump()
        candidate.update(clean)
        RuntimeSettings(**candidate)  # validate before persisting

        for key, value in clean.items():
            row = session.get(BotSetting, key)
            if row is None:
                session.add(BotSetting(key=key, value=value))
            else:
                row.value = value
            self._overrides[key] = value
        session.commit()
        return self.get()

    def reset(self, session: Session) -> RuntimeSettings:
        """Drop every dashboard override so the .env values apply again."""
        from app.database.models import BotSetting

        session.execute(delete(BotSetting).where(BotSetting.key.in_(OVERRIDABLE_KEYS)))
        session.commit()
        self._overrides = {}
        return self.get()
