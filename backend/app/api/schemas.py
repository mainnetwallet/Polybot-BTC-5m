from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class StartRequest(BaseModel):
    confirm_live: bool = False
    confirmation_phrase: str = ""


class ModeRequest(BaseModel):
    mode: Literal["paper", "live"]
    confirm_live: bool = False
    confirmation_phrase: str = ""


class KillSwitchRequest(BaseModel):
    active: bool


class BuysRequest(BaseModel):
    enabled: bool


class ResetPaperRequest(BaseModel):
    balance: float = Field(gt=0, le=100000)
    confirm: bool = False


class ManualSellRequest(BaseModel):
    market_id: str
    side: Literal["UP", "DOWN"]
    # 0-3 = a strategy stage, 9 = the one-tap buys
    stage: Literal[0, 1, 2, 3, 9]


class TapBuyRequest(BaseModel):
    market_id: str
    side: Literal["UP", "DOWN"]
    amount: float = Field(ge=1, le=100000)
    # one id per tap: the same id sent twice (a retry over a bad connection) buys only once
    request_id: str | None = Field(default=None, max_length=64)


class ManualSellAllRequest(BaseModel):
    market_id: str


class ResetDailyRequest(BaseModel):
    confirm: bool = False


class ClearHistoryRequest(BaseModel):
    confirm: bool = False


class ResetServerRequest(BaseModel):
    confirm: bool = False
    confirmation_phrase: str = ""


class SettingsPatch(BaseModel):
    stage1_threshold: float | None = Field(default=None, ge=0.01, le=0.999)
    stage1_seconds: int | None = Field(default=None, ge=1, le=300)
    stage1_max_ask: float | None = Field(default=None, ge=0.01, le=1.0)
    stage2_max_ask: float | None = Field(default=None, ge=0.01, le=1.0)
    stage2_max_spread: float | None = Field(default=None, ge=0.0, le=1.0)
    stage3_max_ask: float | None = Field(default=None, ge=0.01, le=1.0)
    stage3_max_spread: float | None = Field(default=None, ge=0.0, le=1.0)
    stage3_min_stop_headroom: float | None = Field(default=None, ge=0.0, le=1.0)
    sell_floor_tolerance_ticks: int | None = Field(default=None, ge=0, le=5)
    stage1_enabled: bool | None = None
    stage2_enabled: bool | None = None
    stage3_enabled: bool | None = None
    stage2_seconds: int | None = Field(default=None, ge=1, le=300)
    stage2_threshold: float | None = Field(default=None, ge=0.01, le=0.999)
    stage3_seconds: int | None = Field(default=None, ge=1, le=300)
    stage3_threshold: float | None = Field(default=None, ge=0.01, le=0.999)
    max_execution_price: float | None = Field(default=None, ge=0.01, le=1.0)
    trade_amount_usdc: float | None = Field(default=None, gt=0, le=100000)
    max_daily_loss: float | None = Field(default=None, ge=0)
    daily_profit_target: float | None = Field(default=None, ge=0)
    stale_data_max_age_ms: int | None = Field(default=None, ge=100, le=60000)
    duplicate_policy: Literal["one_per_market", "one_per_side", "allow"] | None = None
    conflict_policy: Literal["up_first", "down_first", "highest_ask", "block_both"] | None = None
    paper_slippage_bps: float | None = Field(default=None, ge=0, le=5000)
    paper_fee_rate: float | None = Field(default=None, ge=0, le=1)
    max_trades_per_day: int | None = Field(default=None, ge=1, le=10000)
    max_open_positions: int | None = Field(default=None, ge=0, le=100)
    min_price_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    stage2_min_price_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    stage3_min_price_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    stage0_enabled: bool | None = None
    stage0_start_seconds: int | None = Field(default=None, ge=1, le=300)
    stage0_end_seconds: int | None = Field(default=None, ge=0, le=299)
    stage0_min_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_max_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_early_max_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_block_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage1_block_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage2_block_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage3_block_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_min_price_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    stage0_high_min_price_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    stage0_mid_from_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_high_from_ask: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_take_profit_pct_low: float | None = Field(default=None, ge=0, le=500)
    stage0_take_profit_pct_mid: float | None = Field(default=None, ge=0, le=500)
    stage0_take_profit_pct_high: float | None = Field(default=None, ge=0, le=500)
    stage0_sell_cap_price: float | None = Field(default=None, ge=0.02, le=0.999)
    stage1_sell_enabled: bool | None = None
    stage1_sell_max_buy: float | None = Field(default=None, ge=0.01, le=0.999)
    stage1_take_profit_pct: float | None = Field(default=None, ge=0.0, le=500.0)
    stage1_sell_price_high: float | None = Field(default=None, ge=0.02, le=0.999)
    stage2_sell_enabled: bool | None = None
    stage2_sell_price: float | None = Field(default=None, ge=0.02, le=0.999)
    stage0_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage1_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage2_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage3_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage0_high_buy_price: float | None = Field(default=None, ge=0.01, le=0.999)
    stage0_high_buy_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage0_high_buy_after_pct: float | None = Field(default=None, ge=0, le=100)
    stage3_mid_seconds: int | None = Field(default=None, ge=0, le=300)
    stage3_mid_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    stage3_last_seconds: int | None = Field(default=None, ge=0, le=300)
    stage3_last_seconds_stop_loss_pct: float | None = Field(default=None, ge=0, le=99)
    buy_guard_gap_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    buy_guard_trend_usd: float | None = Field(default=None, ge=0, le=1_000_000)
    buy_guard_trend_markets: int | None = Field(default=None, ge=1, le=12)
    buy_guard_resume_minutes: int | None = Field(default=None, ge=0, le=1440)

    def changes(self) -> dict:
        return {k: v for k, v in self.model_dump().items() if v is not None}
