"""STAGE1_STAGED_MARKET_BUY.

First part of the market: no trading.
Stage 1, last N seconds (default 90): monitor UP and DOWN best asks.

Entries are staged. Each stage may place ONE buy per market:

* stage 0 (optional, independent): while more than stage0_end_seconds and at most stage0_start_seconds remain
  (independent of the stage 1), stage0_min_ask <= best_ask <= the stage 0 limit: stage0_early_max_ask for a cheaper buy,
  stage0_max_ask for an expensive one (asks between the two do not buy). Asks of stage0_high_buy_price or more
  only buy once stage0_high_buy_after_pct of the window has passed. The risk manager also
  demands the BTC price gap (stage0_min_price_gap_usd) and the order manager sells the shares again once the
  best bid is the tier's take-profit percentage above the buy price (by buy price, capped at 0.99).
  Values: see Settings / .env. Once either side's best ask reaches stage0_block_ask (default 0.98) inside the stage 0 window, stage 0 does not
  buy again in that market (even if the ask drops back).

* stage 1: inside the stage 1 window (stage1_seconds left, default 90, until stage 2 starts at
  stage2_seconds, default 50), best_ask >= stage1_threshold (default 0.85).
  If the ask is not there yet the strategy keeps watching and fires the moment it
  gets there, as long as the stage 1 window is still open (RiskManager._check_stage_chain: "stage1_missed"). Once either side's best ask reaches stage1_block_ask
  (default 0.99) inside the stage 1 window without a stage 1 buy, stage 1 does not buy in that market any more (even if the
  ask drops back and the rules are met again). Stages 0, 2 and 3 are unaffected.
  Stage 2 and stage 3 work the same way with stage2_block_ask / stage3_block_ask (default 0.99), each inside its
  own window and only for its own stage.
* stage 2: stage3_seconds (default 30) < remaining <= stage2_seconds (default 50) and
  best_ask >= stage2_threshold (default 0.88). From stage3_seconds on stage 3 takes over.
* stage 3: remaining <= stage3_seconds (default 30) and best_ask >= stage3_threshold
  (default 0.90).

The windows follow each other: stage 1 ends where stage 2 starts, stage 2 ends where stage 3 starts
(RuntimeSettings.stage_end_seconds). Stages are independent: none needs another to have bought.
This module only produces the signals; see RiskManager._check_stage_chain for the stage 1 cutoff.

The thresholds are triggers, never limit prices. Signals carry the live ask so the
executor buys at the market instead of posting a fixed-price order.
"""

from __future__ import annotations

from datetime import datetime, timezone

from app.config.runtime import RuntimeSettings
from app.market_data.book import OrderBook
from app.market_discovery.types import MarketInfo
from app.strategy.types import Signal, StrategyEvaluation


def datetime_to_epoch_ms(value: datetime) -> int:
    aware = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value
    return int(aware.timestamp() * 1000)


def stage_plan(config: RuntimeSettings) -> list[tuple[int, float, float]]:
    """(stage, max remaining seconds, ask threshold) for every entry stage that is switched on."""
    plan = [
        (1, float(config.stage1_seconds), config.stage1_threshold),
        (2, float(config.stage2_seconds), config.stage2_threshold),
        (3, float(config.stage3_seconds), config.stage3_threshold),
    ]
    return [entry for entry in plan if config.stage_enabled(entry[0])]


def stage0_active(config: RuntimeSettings, remaining: float) -> bool:
    """Stage 0 buys between `stage0_start_seconds` left and `stage0_end_seconds` left (independent of the stage 1)."""
    return bool(
        config.stage0_enabled
        and config.stage0_end_seconds < remaining <= config.stage0_start_seconds
    )


# Default of the stage0_block_ask setting: once either side's best ask reaches it during the stage 0 window,
# stage 0 stops buying in that market.
STAGE0_BLOCK_ASK = 0.98
_STAGE0_BLOCKED_KEEP = 64  # remembered markets; older ones are long expired

# Default of the stage1_block_ask setting: once either side's best ask reaches it inside the stage 1 window,
# stage 1 stops buying in that market.
STAGE1_BLOCK_ASK = 0.99
_STAGE1_BLOCKED_KEEP = 64

# Same for stages 2 and 3 (defaults of stage2_block_ask / stage3_block_ask).
STAGE2_BLOCK_ASK = 0.99
STAGE3_BLOCK_ASK = 0.99
_STAGE_BLOCKED_KEEP = 64


class Stage1Strategy:
    def __init__(self) -> None:
        # markets where an ask hit stage0_block_ask inside the stage 0 window (stage 0 buys stay off for them)
        self._stage0_blocked: dict[str, None] = {}
        # markets where an ask hit stage1_block_ask inside the stage 1 window (stage 1 buys stay off for them)
        self._stage1_blocked: dict[str, None] = {}
        # same for stage 2 and stage 3
        self._stage2_blocked: dict[str, None] = {}
        self._stage3_blocked: dict[str, None] = {}

    def stage0_blocked(self, market_id: str) -> bool:
        return market_id in self._stage0_blocked

    def _block_stage0(self, market_id: str) -> None:
        self._stage0_blocked[market_id] = None
        while len(self._stage0_blocked) > _STAGE0_BLOCKED_KEEP:
            self._stage0_blocked.pop(next(iter(self._stage0_blocked)))

    def stage1_blocked(self, market_id: str) -> bool:
        return market_id in self._stage1_blocked

    def _block_stage1(self, market_id: str) -> None:
        self._stage1_blocked[market_id] = None
        while len(self._stage1_blocked) > _STAGE1_BLOCKED_KEEP:
            self._stage1_blocked.pop(next(iter(self._stage1_blocked)))

    def stage2_blocked(self, market_id: str) -> bool:
        return market_id in self._stage2_blocked

    def stage3_blocked(self, market_id: str) -> bool:
        return market_id in self._stage3_blocked

    def _block_stage(self, blocked: dict[str, None], market_id: str) -> None:
        blocked[market_id] = None
        while len(blocked) > _STAGE_BLOCKED_KEEP:
            blocked.pop(next(iter(blocked)))

    def evaluate(
        self,
        market: MarketInfo,
        books: dict[str, OrderBook],
        now_ms: int,
        config: RuntimeSettings,
    ) -> StrategyEvaluation:
        end_ms = datetime_to_epoch_ms(market.window_end)
        start_ms = datetime_to_epoch_ms(market.window_start)
        remaining = (end_ms - now_ms) / 1000.0

        up = books.get(market.up_token_id)
        down = books.get(market.down_token_id)
        up_ask = up.best_ask if up else None
        down_ask = down.best_ask if down else None
        up_armed = up_ask is not None and up_ask + 1e-9 >= config.stage1_threshold
        down_armed = down_ask is not None and down_ask + 1e-9 >= config.stage1_threshold

        if now_ms < start_ms:
            return StrategyEvaluation(
                False, remaining, up_ask, down_ask, up_armed, down_armed, reason="market_not_started"
            )
        if remaining <= 0:
            return StrategyEvaluation(
                False, remaining, up_ask, down_ask, up_armed, down_armed, reason="market_expired"
            )
        in_stage0 = stage0_active(config, remaining)
        if remaining > config.entry_window_seconds() and not in_stage0:
            return StrategyEvaluation(
                False, remaining, up_ask, down_ask, up_armed, down_armed, reason="outside_stage1"
            )

        signals: list[Signal] = []
        if in_stage0 and not self.stage0_blocked(market.market_id):
            if any(a is not None and a + 1e-9 >= config.stage0_block_ask for a in (up_ask, down_ask)):
                self._block_stage0(market.market_id)  # an ask touched stage0_block_ask: no stage 0 buy in this market any more
        if in_stage0 and not self.stage0_blocked(market.market_id):
            for side, market_side_token, book, ask in (
                ("UP", market.up_token_id, up, up_ask),
                ("DOWN", market.down_token_id, down, down_ask),
            ):
                if book is None or ask is None:
                    continue
                if ask > config.stage0_max_ask_for(ask) + 1e-9 or ask + 1e-9 < config.stage0_min_ask:
                    continue
                if config.stage0_high_buy_too_early(ask, remaining):
                    continue  # a 0.90+ ask only buys in the later part of the stage 0 window
                signals.append(
                    Signal(
                        market_id=market.market_id,
                        side=side,
                        token_id=market_side_token,
                        best_ask=round(ask, 6),
                        ask_size=book.ask_size,
                        remaining_seconds=round(remaining, 3),
                        threshold=config.stage0_max_ask_for(ask),
                        observed_at_ms=now_ms,
                        book_age_ms=book.age_ms(now_ms),
                        stage=0,
                    )
                )
        in_stage1 = (
            config.stage_enabled(1)
            and config.stage_end_seconds(1) < remaining <= config.stage1_seconds
        )
        if in_stage1 and not self.stage1_blocked(market.market_id):
            if any(a is not None and a + 1e-9 >= config.stage1_block_ask for a in (up_ask, down_ask)):
                self._block_stage1(market.market_id)  # an ask touched stage1_block_ask: no stage 1 buy in this market any more
        for stage, blocked, block_ask in (
            (2, self._stage2_blocked, config.stage2_block_ask),
            (3, self._stage3_blocked, config.stage3_block_ask),
        ):
            in_stage = (
                config.stage_enabled(stage)
                and config.stage_end_seconds(stage) < remaining <= (config.stage2_seconds if stage == 2 else config.stage3_seconds)
            )
            if in_stage and market.market_id not in blocked:
                if any(a is not None and a + 1e-9 >= block_ask for a in (up_ask, down_ask)):
                    self._block_stage(blocked, market.market_id)  # an ask touched the block ask: this stage stays off here
        for stage, max_remaining, threshold in stage_plan(config):
            if remaining > max_remaining:
                continue
            if stage == 1 and self.stage1_blocked(market.market_id):
                continue
            if stage == 2 and self.stage2_blocked(market.market_id):
                continue
            if stage == 3 and self.stage3_blocked(market.market_id):
                continue
            if stage == 2 and remaining <= config.stage_end_seconds(2):
                continue  # stage 2 ends where stage 3 begins
            for side, market_side_token, book, ask in (
                ("UP", market.up_token_id, up, up_ask),
                ("DOWN", market.down_token_id, down, down_ask),
            ):
                if book is None or ask is None:
                    continue
                if ask + 1e-9 < threshold:
                    continue
                signals.append(
                    Signal(
                        market_id=market.market_id,
                        side=side,
                        token_id=market_side_token,
                        best_ask=round(ask, 6),
                        ask_size=book.ask_size,
                        remaining_seconds=round(remaining, 3),
                        threshold=threshold,
                        observed_at_ms=now_ms,
                        book_age_ms=book.age_ms(now_ms),
                        stage=stage,
                    )
                )

        reason = "trigger" if signals else "monitoring"
        return StrategyEvaluation(
            True, remaining, up_ask, down_ask, up_armed, down_armed, signals, reason
        )


__all__ = ["STAGE0_BLOCK_ASK", "STAGE1_BLOCK_ASK", "STAGE2_BLOCK_ASK", "STAGE3_BLOCK_ASK", "Stage1Strategy", "datetime_to_epoch_ms", "stage0_active", "stage_plan"]
