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
    result = evaluate(30, up=0.70, down=0.10, trigger_threshold=0.70)
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
