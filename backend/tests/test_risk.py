from __future__ import annotations

from app.risk_manager.manager import RiskManager
from app.strategy.types import Signal

from .helpers import make_config, make_ctx


def signal(ask: float = 0.85, age_ms: float = 50, size: float | None = 100.0) -> Signal:
    return Signal(
        market_id="0xmarket",
        side="UP",
        token_id="up-token",
        best_ask=ask,
        ask_size=size,
        remaining_seconds=30.0,
        threshold=0.80,
        observed_at_ms=1,
        book_age_ms=age_ms,
    )


def test_risk_approves_valid_signal():
    decision = RiskManager().check(signal(), make_ctx(), make_config())
    assert decision.approved is True


def test_stale_data_blocks():
    decision = RiskManager().check(signal(age_ms=5000), make_ctx(), make_config())
    assert decision.approved is False
    assert decision.reason == "stale_data"


def test_expired_market_blocks():
    decision = RiskManager().check(
        signal(), make_ctx(remaining=0), make_config()
    )
    assert decision.reason == "market_expired"


def test_max_execution_price_blocks():
    decision = RiskManager().check(
        signal(ask=0.995), make_ctx(), make_config(max_execution_price=0.99, stage1_max_ask=1.0)
    )
    assert decision.approved is False
    assert decision.reason == "max_execution_price_exceeded"


def test_stage1_max_ask_blocks_above_97():
    decision = RiskManager().check(signal(ask=0.98), make_ctx(), make_config())
    assert decision.approved is False
    assert decision.reason == "stage1_max_ask_exceeded"


def test_stage1_max_ask_allows_97_exactly():
    decision = RiskManager().check(signal(ask=0.97), make_ctx(), make_config())
    assert decision.approved is True


def test_no_liquidity_blocks():
    decision = RiskManager().check(signal(size=0), make_ctx(), make_config())
    assert decision.reason == "no_liquidity"


def test_daily_loss_limit_blocks():
    decision = RiskManager().check(signal(), make_ctx(daily_pnl=-5.0), make_config())
    assert decision.reason == "daily_loss_limit"


def test_daily_profit_target_blocks():
    decision = RiskManager().check(signal(), make_ctx(daily_pnl=10.0), make_config())
    assert decision.reason == "daily_profit_target"


def test_insufficient_balance_blocks():
    decision = RiskManager().check(signal(), make_ctx(balance=2.0), make_config())
    assert decision.reason == "insufficient_balance"


def test_kill_switch_blocks():
    decision = RiskManager().check(signal(), make_ctx(kill_switch=True), make_config())
    assert decision.reason == "kill_switch"


def test_max_trades_per_day_blocks():
    decision = RiskManager().check(signal(), make_ctx(trades_today=60), make_config())
    assert decision.reason == "max_trades_per_day"


def staged(stage: int, side: str = "UP") -> Signal:
    base = signal(ask=0.93)
    return Signal(**{**base.__dict__, "stage": stage, "side": side, "threshold": 0.90})


def test_add_on_stage_may_add_to_existing_position_even_at_position_cap():
    config = make_config(max_open_positions=1)
    ctx = make_ctx(open_positions=1, exposed_sides=frozenset({"UP"}), stage1_bought=True)
    assert RiskManager().check(staged(2), ctx, config).approved is True
    assert RiskManager().check(staged(3), ctx, config).approved is True


def test_stage1_and_opposite_side_still_respect_position_cap():
    config = make_config(max_open_positions=1)
    ctx = make_ctx(open_positions=1, exposed_sides=frozenset({"UP"}), stage1_bought=True)
    assert RiskManager().check(staged(1), ctx, config).reason == "max_open_positions"
    assert RiskManager().check(staged(2, side="DOWN"), ctx, config).reason == "max_open_positions"


# ---------------------------------------------------------------------------
# Price-to-beat gap rule
# ---------------------------------------------------------------------------
def _side(side: str, stage: int = 1, ask: float = 0.85) -> Signal:
    base = signal(ask=ask)
    return Signal(**{**base.__dict__, "side": side, "stage": stage})


def _gap_config(gap: float = 20.0, **overrides):
    overrides.setdefault("stage2_min_price_gap_usd", 25.0)
    overrides.setdefault("stage3_min_price_gap_usd", 30.0)
    return make_config(min_price_gap_usd=gap, **overrides)


def test_gap_rule_is_off_by_default_and_ignores_missing_price_data():
    config = make_config()
    assert config.min_price_gap_usd == 0
    assert RiskManager().check(signal(), make_ctx(), config).approved is True


def test_up_needs_price_at_least_gap_above_price_to_beat():
    ctx = lambda price: make_ctx(btc_price=price, price_to_beat=100_000.0)  # noqa: E731
    risk = RiskManager()
    assert risk.check(_side("UP"), ctx(100_020.0), _gap_config()).approved is True  # exactly the gap
    assert risk.check(_side("UP"), ctx(100_150.0), _gap_config()).approved is True
    too_small = risk.check(_side("UP"), ctx(100_019.99), _gap_config())
    assert too_small.reason == "price_gap_too_small"
    assert risk.check(_side("UP"), ctx(100_000.0), _gap_config()).reason == "price_gap_too_small"
    # price on the wrong side of the price to beat
    assert risk.check(_side("UP"), ctx(99_900.0), _gap_config()).reason == "price_gap_too_small"


def test_down_needs_price_at_least_gap_below_price_to_beat():
    ctx = lambda price: make_ctx(btc_price=price, price_to_beat=100_000.0)  # noqa: E731
    risk = RiskManager()
    assert risk.check(_side("DOWN"), ctx(99_980.0), _gap_config()).approved is True  # exactly the gap
    assert risk.check(_side("DOWN"), ctx(99_800.0), _gap_config()).approved is True
    assert risk.check(_side("DOWN"), ctx(99_980.01), _gap_config()).reason == "price_gap_too_small"
    assert risk.check(_side("DOWN"), ctx(100_100.0), _gap_config()).reason == "price_gap_too_small"


def test_gap_rule_applies_to_add_on_stages_too():
    config = _gap_config(max_open_positions=1)
    exposed = frozenset({"UP"})
    weak = make_ctx(
        open_positions=1, exposed_sides=exposed, btc_price=100_010.0, price_to_beat=100_000.0,
        stage1_bought=True,
    )
    strong = make_ctx(
        open_positions=1, exposed_sides=exposed, btc_price=100_030.0, price_to_beat=100_000.0,
        stage1_bought=True,
    )
    for stage in (2, 3):
        assert RiskManager().check(_side("UP", stage=stage, ask=0.93), weak, config).reason == "price_gap_too_small"
        assert RiskManager().check(_side("UP", stage=stage, ask=0.93), strong, config).approved is True


def test_each_stage_has_its_own_gap_20_25_30():
    config = _gap_config(max_open_positions=1)
    exposed = frozenset({"UP"})

    def ctx_for(stage: int, move: float):
        # stage 1 is the first buy (no position yet); stage 2/3 add to the held UP position
        return make_ctx(
            open_positions=0 if stage == 1 else 1,
            exposed_sides=frozenset() if stage == 1 else exposed,
            btc_price=100_000.0 + move, price_to_beat=100_000.0,
            stage1_bought=stage != 1,
        )

    def verdict(stage: int, move: float) -> str:
        return RiskManager().check(_side("UP", stage=stage, ask=0.93), ctx_for(stage, move), config).reason

    assert verdict(1, 20.0) == "approved"
    assert verdict(1, 19.99) == "price_gap_too_small"
    assert verdict(2, 24.99) == "price_gap_too_small"
    assert verdict(2, 25.0) == "approved"
    assert verdict(3, 29.99) == "price_gap_too_small"
    assert verdict(3, 30.0) == "approved"


def test_gap_rule_fails_closed_without_price_data():
    risk = RiskManager()
    no_price = risk.check(_side("UP"), make_ctx(price_gap_issue="BTC price is stale (12s old)"), _gap_config())
    assert no_price.approved is False
    assert no_price.reason == "price_gap_unavailable"
    assert "stale" in no_price.detail
    no_beat = risk.check(_side("UP"), make_ctx(btc_price=100_050.0), _gap_config())
    assert no_beat.reason == "price_gap_unavailable"


def test_gap_threshold_is_configurable():
    ctx = make_ctx(btc_price=100_005.0, price_to_beat=100_000.0)
    assert RiskManager().check(_side("UP"), ctx, _gap_config(gap=5.0)).approved is True
    assert RiskManager().check(_side("UP"), ctx, _gap_config(gap=5.01)).reason == "price_gap_too_small"


# ---------------------------------------------------------------------------
# Stage chain
# ---------------------------------------------------------------------------
def _chain_config(**overrides):
    return make_config(max_open_positions=1, **overrides)


def _chain_ctx(remaining, bought=False):
    return make_ctx(
        remaining=remaining,
        open_positions=1 if bought else 0,
        exposed_sides=frozenset({"UP"}) if bought else frozenset(),
        stage1_bought=bought,
    )


def test_stage1_can_buy_until_50_seconds_left_then_only_stage1_stays_off():
    risk, config = RiskManager(), _chain_config()
    assert risk.check(_side("UP"), _chain_ctx(60), config).approved is True
    assert risk.check(_side("UP"), _chain_ctx(51), config).approved is True  # last second of the stage 1 window
    assert risk.check(_side("UP"), _chain_ctx(50), config).reason == "stage1_missed"
    assert risk.check(_side("UP"), _chain_ctx(35), config).reason == "stage1_missed"
    # the cutoff is where stage 2 starts: with stage 2 starting at 70, stage 1 ends at 70
    later = _chain_config().model_copy(update={"stage1_seconds": 90, "stage2_seconds": 70})
    assert risk.check(_side("UP"), _chain_ctx(71), later).approved is True
    assert risk.check(_side("UP"), _chain_ctx(70), later).reason == "stage1_missed"
    # stages 2 and 3 do not need stage 1: their own rules decide
    assert risk.check(_side("UP", stage=2, ask=0.93), _chain_ctx(25), config).approved is True
    assert risk.check(_side("UP", stage=3, ask=0.93), _chain_ctx(25), config).approved is True


def test_stage2_does_not_need_stage1():
    risk, config = RiskManager(), _chain_config()
    s2 = _side("UP", stage=2, ask=0.93)
    assert risk.check(s2, _chain_ctx(45), config).approved is True  # stage 1 never bought
    assert risk.check(s2, _chain_ctx(45, bought=True), config).approved is True
    assert risk.check(s2, _chain_ctx(35, bought=True), config).approved is True


def test_stage3_is_independent_of_stage1_and_stage2():
    risk, config = RiskManager(), _chain_config()
    s3 = _side("UP", stage=3, ask=0.93)
    assert risk.check(s3, _chain_ctx(25), config).approved is True  # stage 1 never bought
    assert risk.check(s3, _chain_ctx(25, bought=True), config).approved is True


def _stage_signal(stage: int, ask: float) -> Signal:
    return Signal(
        market_id="0xmarket",
        side="UP",
        token_id="up-token",
        best_ask=ask,
        ask_size=100.0,
        remaining_seconds=25.0,
        threshold=0.75,
        observed_at_ms=1,
        book_age_ms=50,
        stage=stage,
    )


def test_stage2_and_3_use_their_own_threshold_below_trigger():
    # stage 2/3 thresholds below stage1_threshold must not be overridden by the trigger threshold
    config = make_config(stage1_threshold=0.80, stage2_threshold=0.75, stage3_threshold=0.75)
    for stage in (2, 3):
        decision = RiskManager().check(_stage_signal(stage, 0.77), make_ctx(remaining=25.0), config)
        assert decision.approved is True, (stage, decision)


def test_stage2_and_3_still_blocked_below_their_own_threshold():
    config = make_config(stage1_threshold=0.80, stage2_threshold=0.90, stage3_threshold=0.90)
    for stage in (2, 3):
        decision = RiskManager().check(_stage_signal(stage, 0.85), make_ctx(remaining=25.0), config)
        assert decision.approved is False
        assert decision.reason == "below_threshold"


def test_stage1_still_uses_stage1_threshold():
    config = make_config(stage1_threshold=0.80, stage2_threshold=0.75)
    decision = RiskManager().check(_stage_signal(1, 0.77), make_ctx(remaining=25.0), config)
    assert decision.reason == "below_threshold"


# ---------------------------------------------------------------------------
# max_execution_price is a hard cap for stage 0 too
# ---------------------------------------------------------------------------
def _stage0_signal(ask: float) -> Signal:
    return Signal(
        market_id="0xmarket",
        side="UP",
        token_id="up-token",
        best_ask=ask,
        ask_size=100.0,
        remaining_seconds=130.0,
        threshold=0.96,
        observed_at_ms=1,
        book_age_ms=50,
        stage=0,
    )


def test_stage0_limit_never_exceeds_max_execution_price():
    config = make_config(max_execution_price=0.90, stage0_max_ask=0.96, stage0_early_max_ask=0.95)
    assert config.stage0_max_ask_for(0.95) == 0.90  # expensive buy: stage0_max_ask is clamped
    assert config.stage0_max_ask_for(0.80) == 0.90  # cheap buy: stage0_early_max_ask is clamped too
    uncapped = make_config(max_execution_price=0.99, stage0_max_ask=0.96)
    assert uncapped.stage0_max_ask_for(0.95) == 0.96  # a cap above the stage 0 limit changes nothing


def test_risk_blocks_stage0_buy_above_max_execution_price():
    config = make_config(max_execution_price=0.90, stage0_enabled=True, stage0_max_ask=0.96, stage0_high_buy_after_pct=0.0)
    ctx = make_ctx(remaining=130, btc_price=100_200.0, price_to_beat=100_000.0)
    blocked = RiskManager().check(_stage0_signal(0.95), ctx, config)
    assert blocked.approved is False and blocked.reason == "stage0_ask_too_high"
    assert RiskManager().check(_stage0_signal(0.90), ctx, config).approved is True


def test_stage0_min_ask_above_max_execution_price_is_rejected():
    import pytest

    with pytest.raises(ValueError, match="stage0_min_ask"):
        make_config(max_execution_price=0.90, stage0_enabled=True, stage0_min_ask=0.92, stage0_max_ask=0.96)


def test_stage2_is_inside_the_window_when_stage1_is_off_and_stage1_seconds_is_small():
    config = make_config(
        stage0_enabled=False,
        stage1_enabled=False,
        stage1_seconds=40,
        stage2_seconds=50,
        stage3_seconds=30,
        stage2_min_price_gap_usd=0,
        min_price_gap_usd=0,
    )
    stage2 = Signal(
        market_id="0xmarket",
        side="UP",
        token_id="up-token",
        best_ask=0.95,
        ask_size=100.0,
        remaining_seconds=45.0,
        threshold=0.88,
        observed_at_ms=1,
        book_age_ms=50,
        stage=2,
    )
    assert RiskManager().check(stage2, make_ctx(remaining=45), config).approved is True
    too_early = RiskManager().check(stage2, make_ctx(remaining=55), config)
    assert too_early.approved is False
    assert too_early.reason == "outside_stage1"


# ---------------------------------------------------------------------------
# Stage 2: max ask, spread guard, opposite side, take-profit vs buy price
# ---------------------------------------------------------------------------
def _s2(ask: float, bid: float | None = 0.93, side: str = "UP") -> Signal:
    return Signal(
        market_id="0xmarket",
        side=side,
        token_id="up-token" if side == "UP" else "down-token",
        best_ask=ask,
        ask_size=100.0,
        remaining_seconds=40.0,
        threshold=0.88,
        observed_at_ms=1,
        book_age_ms=50,
        stage=2,
        best_bid=bid,
    )


def test_stage2_does_not_buy_at_097_or_above():
    config = make_config(stage2_min_price_gap_usd=0)
    risk = RiskManager()
    assert risk.check(_s2(0.96, bid=0.95), make_ctx(remaining=40), config).approved is True
    for ask in (0.97, 0.98):
        decision = risk.check(_s2(ask, bid=ask - 0.01), make_ctx(remaining=40), config)
        assert decision.approved is False
        assert decision.reason == "stage2_max_ask_exceeded"


def test_stage2_max_ask_also_caps_the_order_price():
    config = make_config(max_execution_price=0.99)
    assert config.max_buy_price_for_stage(2) == 0.96
    assert make_config(max_execution_price=0.90, stage2_threshold=0.88).max_buy_price_for_stage(2) == 0.90


def test_stage2_blocks_a_wide_spread():
    config = make_config(stage2_max_spread=0.05, stage2_min_price_gap_usd=0)
    risk = RiskManager()
    assert risk.check(_s2(0.92, bid=0.87), make_ctx(remaining=40), config).approved is True  # 0.05 exactly
    wide = risk.check(_s2(0.92, bid=0.80), make_ctx(remaining=40), config)
    assert wide.approved is False
    assert wide.reason == "stage2_spread_too_wide"


def test_stage2_blocks_when_there_is_no_bid_and_spread_guard_is_on():
    config = make_config(stage2_max_spread=0.05, stage2_min_price_gap_usd=0)
    decision = RiskManager().check(_s2(0.92, bid=None), make_ctx(remaining=40), config)
    assert decision.reason == "stage2_no_bid"


def test_stage2_spread_guard_can_be_switched_off():
    config = make_config(stage2_max_spread=0.0, stage2_min_price_gap_usd=0)
    assert RiskManager().check(_s2(0.92, bid=0.40), make_ctx(remaining=40), config).approved is True


def test_stage2_spread_stays_inside_the_stop_loss_distance():
    # a spread at the limit must never put the bid at or below the 10 % stop-loss level right after the buy
    config = make_config(stage2_max_spread=0.05, stage2_stop_loss_pct=10.0)
    for ask in (0.88, 0.92, 0.96):
        worst_bid = ask - config.stage2_max_spread
        assert worst_bid > config.stop_loss_price_for(2, ask)


def test_stage2_sell_price_must_stay_above_the_max_buy_price():
    import pytest

    with pytest.raises(ValueError):
        make_config(stage2_sell_enabled=True, stage2_sell_price=0.96, stage2_max_ask=0.96)
    with pytest.raises(ValueError):
        make_config(stage2_sell_enabled=True, stage2_sell_price=0.97, stage2_max_ask=0.97)
    assert make_config(stage2_sell_enabled=True, stage2_sell_price=0.98, stage2_max_ask=0.96).stage2_max_ask == 0.96
    with pytest.raises(ValueError):
        make_config(stage2_max_ask=0.80, stage2_threshold=0.88)


def test_later_stages_do_not_buy_against_a_stage0_holding_on_the_other_side():
    config = make_config(stage2_min_price_gap_usd=0, max_open_positions=1)
    ctx = make_ctx(
        remaining=40,
        open_positions=1,
        exposed_sides=frozenset({"DOWN"}),
        stage0_pure_sides=frozenset({"DOWN"}),
    )
    risk = RiskManager()
    blocked = risk.check(_s2(0.92, bid=0.91, side="UP"), ctx, config)
    assert blocked.approved is False
    assert blocked.reason == "opposite_side_held"
    # same side as the Stage 0 holding is still allowed
    assert risk.check(_s2(0.92, bid=0.91, side="DOWN"), ctx, config).approved is True
