from __future__ import annotations

from app.strategy.engine import Stage1Strategy, datetime_to_epoch_ms

from .helpers import books_for, make_config, make_market


def evaluate(remaining_before_end: float, up: float, down: float, **config):
    market = make_market(remaining=remaining_before_end + 1000)
    end_ms = datetime_to_epoch_ms(market.window_end)
    now_ms = end_ms - int(remaining_before_end * 1000)
    books = books_for(market, up, down)
    return Stage1Strategy().evaluate(market, books, now_ms, make_config(**config))


def test_first_four_minutes_never_trade():
    result = evaluate(240, up=0.95, down=0.95)
    assert result.in_window is False
    assert result.signals == []
    assert result.reason == "outside_stage1"


def test_final_60_seconds_activates_strategy():
    result = evaluate(60, up=0.50, down=0.50)
    assert result.in_window is True
    assert result.reason == "monitoring"


def test_up_at_079_does_not_trigger():
    result = evaluate(30, up=0.79, down=0.20)
    assert result.in_window is True
    assert all(side.side != "UP" for side in result.signals)
    assert result.up_armed is False


def test_down_at_079_does_not_trigger():
    result = evaluate(30, up=0.20, down=0.79)
    assert all(side.side != "DOWN" for side in result.signals)
    assert result.down_armed is False


def test_up_at_080_triggers():
    result = evaluate(30, up=0.80, down=0.20)
    assert [s.side for s in result.signals] == ["UP"]
    assert result.signals[0].best_ask == 0.80
    assert result.up_armed is True


def test_down_at_080_triggers():
    result = evaluate(30, up=0.20, down=0.80)
    assert [s.side for s in result.signals] == ["DOWN"]
    assert result.signals[0].best_ask == 0.80


def test_up_above_080_triggers():
    result = evaluate(30, up=0.93, down=0.07)
    assert result.signals[0].side == "UP"
    assert result.signals[0].best_ask == 0.93


def test_down_above_080_triggers():
    result = evaluate(30, up=0.07, down=0.91)
    assert result.signals[0].side == "DOWN"
    assert result.signals[0].best_ask == 0.91


def test_both_sides_can_trigger():
    result = evaluate(20, up=0.88, down=0.84)
    assert sorted(s.side for s in result.signals) == ["DOWN", "UP"]


def test_asks_visible_outside_window_for_dashboard():
    result = evaluate(180, up=0.42, down=0.61)
    assert result.in_window is False
    assert result.up_ask == 0.42
    assert result.down_ask == 0.61


def test_expired_market_never_triggers():
    result = evaluate(-1, up=0.95, down=0.95)
    assert result.in_window is False
    assert result.signals == []
    assert result.reason == "market_expired"


def test_threshold_is_configurable():
    result = evaluate(30, up=0.70, down=0.10, stage1_threshold=0.70)
    assert result.signals[0].side == "UP"


def test_stage1_window_is_configurable():
    result = evaluate(90, up=0.95, down=0.95, stage1_seconds=120)
    assert result.in_window is True
    assert len(result.signals) == 2


def _stages(result):
    return sorted({sig.stage for sig in result.signals})


def test_stage1_waits_then_fires_late_in_window():
    assert evaluate(55, up=0.60, down=0.40).signals == []
    late = evaluate(8, up=0.82, down=0.18)
    assert _stages(late) == [1]


def test_stage2_needs_50s_left_and_090():
    assert _stages(evaluate(55, up=0.95, down=0.05)) == [1]
    assert _stages(evaluate(45, up=0.85, down=0.15)) == [1]
    assert _stages(evaluate(45, up=0.95, down=0.05)) == [1, 2]


def test_stage3_needs_30s_left_and_090():
    assert _stages(evaluate(35, up=0.95, down=0.05)) == [1, 2]
    assert _stages(evaluate(25, up=0.85, down=0.15)) == [1]
    # stage 2 ends at 30s, so only stages 1 and 3 are left at 25s
    assert _stages(evaluate(25, up=0.95, down=0.05)) == [1, 3]


def test_stage_settings_are_configurable():
    result = evaluate(
        40, up=0.95, down=0.05, stage2_seconds=45, stage2_threshold=0.95, stage3_seconds=20
    )
    assert _stages(result) == [1, 2]


def test_stage2_window_is_50s_to_30s_then_stage3_takes_over():
    assert 2 in _stages(evaluate(50, up=0.95, down=0.05))
    assert 2 in _stages(evaluate(35, up=0.95, down=0.05))
    # at 30s and below stage 2 is over, stage 3 is the one that buys
    assert 2 not in _stages(evaluate(30, up=0.95, down=0.05))
    assert 3 in _stages(evaluate(30, up=0.95, down=0.05))
    assert 2 not in _stages(evaluate(10, up=0.95, down=0.05))


def test_stage_windows_follow_the_start_times_90_70_40():
    # stage 1 starts at 90, stage 2 at 70, stage 3 at 40: each ends where the next starts
    cfg = dict(stage1_seconds=90, stage2_seconds=70, stage3_seconds=40, stage2_threshold=0.88)
    assert 2 not in _stages(evaluate(75, up=0.95, down=0.05, **cfg))
    assert 2 in _stages(evaluate(70, up=0.95, down=0.05, **cfg))
    assert 2 in _stages(evaluate(41, up=0.95, down=0.05, **cfg))
    assert 2 not in _stages(evaluate(40, up=0.95, down=0.05, **cfg))
    assert 3 in _stages(evaluate(40, up=0.95, down=0.05, **cfg))
    assert 3 not in _stages(evaluate(41, up=0.95, down=0.05, **cfg))


def test_stage_end_seconds_is_where_the_next_enabled_stage_starts():
    cfg = make_config(stage1_seconds=90, stage2_seconds=70, stage3_seconds=40)
    assert (cfg.stage_end_seconds(1), cfg.stage_end_seconds(2), cfg.stage_end_seconds(3)) == (70, 40, 0)
    # a switched-off stage is skipped: stage 1 then runs until stage 3 starts
    no2 = make_config(stage1_seconds=90, stage2_seconds=70, stage3_seconds=40, stage2_enabled=False)
    assert no2.stage_end_seconds(1) == 40
    # no later stage on: until the market ends
    only1 = make_config(stage2_enabled=False, stage3_enabled=False)
    assert only1.stage_end_seconds(1) == 0


def test_stage_start_times_must_fall():
    import pytest

    with pytest.raises(Exception, match="stage2_seconds"):
        make_config(stage1_seconds=60, stage2_seconds=70)
    with pytest.raises(Exception, match="stage3_seconds"):
        make_config(stage2_seconds=40, stage3_seconds=40)


# ------------------------- an ask of .99 inside the stage 1 window stops stage 1 buys in that market
def _stage1_run(strategy, market, config, remaining, up, down):
    end_ms = datetime_to_epoch_ms(market.window_end)
    result = strategy.evaluate(market, books_for(market, up, down), end_ms - int(remaining * 1000), config)
    return [s for s in result.signals if s.stage == 1]


def test_ask_touching_99_in_stage1_blocks_stage1_for_the_rest_of_the_market():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    # 0.79 -> no buy yet; then .99 touched without any stage 1 buy; afterwards the rules are met but stage 1 stays off
    assert _stage1_run(strategy, market, config, 58, 0.79, 0.21) == []
    assert _stage1_run(strategy, market, config, 56, 0.99, 0.01) == []  # the .99 tick itself does not buy either
    assert strategy.stage1_blocked(market.market_id)
    assert _stage1_run(strategy, market, config, 55, 0.85, 0.15) == []  # back to .85: still blocked
    assert _stage1_run(strategy, market, config, 54, 0.15, 0.85) == []  # other side too


def test_stage1_buys_normally_below_99_and_block_is_per_market():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    assert [s.side for s in _stage1_run(strategy, market, config, 58, 0.85, 0.15)] == ["UP"]
    assert not strategy.stage1_blocked(market.market_id)
    _stage1_run(strategy, market, config, 56, 0.99, 0.01)
    assert strategy.stage1_blocked(market.market_id)
    assert not strategy.stage1_blocked("another-market")


def test_99_before_the_stage1_window_does_not_block_stage1():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    end_ms = datetime_to_epoch_ms(market.window_end)
    strategy.evaluate(market, books_for(market, 0.99, 0.01), end_ms - 100_000, config)  # remaining 100 > 60
    assert not strategy.stage1_blocked(market.market_id)
    assert [s.side for s in _stage1_run(strategy, market, config, 58, 0.85, 0.15)] == ["UP"]


def test_stage1_block_does_not_touch_stage2_and_stage3():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    end_ms = datetime_to_epoch_ms(market.window_end)
    strategy.evaluate(market, books_for(market, 0.99, 0.01), end_ms - 58_000, config)
    result = strategy.evaluate(market, books_for(market, 0.95, 0.05), end_ms - 20_000, config)
    assert 3 in [s.stage for s in result.signals]


def test_stage1_block_ask_is_a_setting_with_default_098():
    from app.config.settings import Settings

    assert Settings.model_fields["stage1_block_ask"].default == 0.98
    assert make_config().stage1_block_ask == 0.98
    assert "stage1_block_ask" in make_config().model_dump()


def test_stage1_ask_of_097_does_not_block_but_098_does():
    market = make_market(remaining=1000)
    strategy = Stage1Strategy()
    config = make_config()
    assert [s.side for s in _stage1_run(strategy, market, config, 58, 0.97, 0.03)] == ["UP"]
    assert not strategy.stage1_blocked(market.market_id)
    assert _stage1_run(strategy, market, config, 56, 0.98, 0.02) == []
    assert strategy.stage1_blocked(market.market_id)


def test_stage1_block_ask_setting_changes_when_the_market_is_blocked():
    market = make_market(remaining=1000)
    strategy = Stage1Strategy()
    config = make_config(stage1_block_ask=0.92)
    assert _stage1_run(strategy, market, config, 58, 0.93, 0.07) == []  # 0.93 >= 0.92: blocked
    assert strategy.stage1_blocked(market.market_id)
    assert _stage1_run(strategy, market, config, 56, 0.85, 0.15) == []
    # a higher block ask keeps the market open
    fresh = Stage1Strategy()
    relaxed = make_config(stage1_block_ask=0.99)
    assert [s.side for s in _stage1_run(fresh, market, relaxed, 58, 0.93, 0.07)] == ["UP"]


def test_stage1_block_ask_must_not_be_below_the_trigger():
    import pytest

    with pytest.raises(ValueError):
        make_config(stage1_threshold=0.85, stage1_block_ask=0.80)


# ------------------------- stage 2 and stage 3: an ask at the block ask stops that stage in that market
def _stage_run(strategy, market, config, stage, remaining, up, down):
    end_ms = datetime_to_epoch_ms(market.window_end)
    result = strategy.evaluate(market, books_for(market, up, down), end_ms - int(remaining * 1000), config)
    return [s for s in result.signals if s.stage == stage]


def test_stage2_and_stage3_block_ask_are_settings_with_default_098():
    from app.config.settings import Settings

    for n in (2, 3):
        assert Settings.model_fields[f"stage{n}_block_ask"].default == 0.98
        assert getattr(make_config(), f"stage{n}_block_ask") == 0.98
        assert f"stage{n}_block_ask" in make_config().model_dump()


def test_stage2_ask_touching_block_ask_blocks_stage2_for_the_rest_of_the_market():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    # remaining 45 is inside the stage 2 window (50 > r > 30)
    assert _stage_run(strategy, market, config, 2, 48, 0.80, 0.20) == []
    assert _stage_run(strategy, market, config, 2, 46, 0.98, 0.02) == []  # the touching tick does not buy
    assert strategy.stage2_blocked(market.market_id)
    assert _stage_run(strategy, market, config, 2, 44, 0.92, 0.08) == []  # rules met again: still blocked
    assert _stage_run(strategy, market, config, 2, 42, 0.08, 0.92) == []  # other side too
    assert not strategy.stage3_blocked(market.market_id)


def test_stage2_ask_097_does_not_block_but_098_does():
    market = make_market(remaining=1000)
    strategy = Stage1Strategy()
    config = make_config()
    assert [s.side for s in _stage_run(strategy, market, config, 2, 48, 0.97, 0.03)] == ["UP"]
    assert not strategy.stage2_blocked(market.market_id)
    assert _stage_run(strategy, market, config, 2, 46, 0.98, 0.02) == []
    assert strategy.stage2_blocked(market.market_id)


def test_stage3_ask_touching_block_ask_blocks_stage3_for_the_rest_of_the_market():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    assert _stage_run(strategy, market, config, 3, 28, 0.80, 0.20) == []
    assert _stage_run(strategy, market, config, 3, 25, 0.99, 0.01) == []
    assert strategy.stage3_blocked(market.market_id)
    assert _stage_run(strategy, market, config, 3, 20, 0.93, 0.07) == []
    assert _stage_run(strategy, market, config, 3, 10, 0.07, 0.93) == []
    assert not strategy.stage2_blocked(market.market_id)


def test_stage3_buys_normally_when_ask_stays_below_block_and_block_is_per_market():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    assert [s.side for s in _stage_run(strategy, market, config, 3, 25, 0.95, 0.05)] == ["UP"]
    assert not strategy.stage3_blocked(market.market_id)
    _stage_run(strategy, market, config, 3, 20, 0.99, 0.01)
    assert strategy.stage3_blocked(market.market_id)
    assert not strategy.stage3_blocked("another-market")


def test_block_ask_before_the_window_does_not_block_stage2_or_stage3():
    market = make_market(remaining=1000)
    config = make_config()
    strategy = Stage1Strategy()
    end_ms = datetime_to_epoch_ms(market.window_end)
    strategy.evaluate(market, books_for(market, 0.99, 0.01), end_ms - 100_000, config)
    strategy.evaluate(market, books_for(market, 0.99, 0.01), end_ms - 40_000, config)  # stage 2 window, not stage 3's
    assert strategy.stage2_blocked(market.market_id)
    assert not strategy.stage3_blocked(market.market_id)
    assert [s.side for s in _stage_run(strategy, market, config, 3, 25, 0.95, 0.05)] == ["UP"]


def test_stage2_and_stage3_block_ask_setting_changes_when_blocked():
    market = make_market(remaining=1000)
    strategy = Stage1Strategy()
    config = make_config(stage2_block_ask=0.93)
    assert _stage_run(strategy, market, config, 2, 46, 0.94, 0.06) == []
    assert strategy.stage2_blocked(market.market_id)
    fresh = Stage1Strategy()
    relaxed = make_config(stage2_block_ask=0.99)
    assert [s.side for s in _stage_run(fresh, market, relaxed, 2, 46, 0.94, 0.06)] == ["UP"]


def test_stage2_and_stage3_block_ask_must_not_be_below_the_trigger():
    import pytest

    with pytest.raises(ValueError):
        make_config(stage2_threshold=0.90, stage2_block_ask=0.85)
    with pytest.raises(ValueError):
        make_config(stage3_threshold=0.90, stage3_block_ask=0.85)
