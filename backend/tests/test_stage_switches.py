"""Per-stage on/off switches: a stage that is off does nothing (no buys, no take-profit, no stop-loss)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config.settings import Settings
from app.main import create_app
from app.risk_manager.manager import RiskManager

from .helpers import books_for, build_stack, make_config, make_ctx, make_market
from .test_stage0 import STAGE0, _stage_buy
from .test_strategy import evaluate

ALL_STAGES = (1, 2, 3)


def _stages(result) -> set[int]:
    return {signal.stage for signal in result.signals}


def test_all_stages_are_on_by_default():
    fields = Settings.model_fields
    for stage in ALL_STAGES:
        assert fields[f"stage{stage}_enabled"].default is True
    config = make_config()
    assert all(config.stage_enabled(stage) for stage in (0, 1, 2, 3)) is False  # stage 0 is off when built directly
    assert all(config.stage_enabled(stage) for stage in ALL_STAGES)


@pytest.mark.parametrize("stage", ALL_STAGES)
def test_switched_off_stage_produces_no_signal_and_the_others_still_do(stage):
    on = evaluate(25, up=0.95, down=0.05)
    assert _stages(on) == {1, 2, 3}
    off = evaluate(25, up=0.95, down=0.05, **{f"stage{stage}_enabled": False})
    assert _stages(off) == {1, 2, 3} - {stage}


@pytest.mark.parametrize("stage", ALL_STAGES)
def test_risk_manager_rejects_a_switched_off_stage_even_if_a_signal_arrives(stage):
    signal = next(s for s in evaluate(25, up=0.95, down=0.05).signals if s.stage == stage)
    ctx = make_ctx(remaining=25, btc_price=100_150.0, price_to_beat=100_000.0, stage1_bought=True)
    on = make_config(**{f"stage{stage}_enabled": True})
    off = make_config(**{f"stage{stage}_enabled": False})
    assert RiskManager().check(signal, ctx, on).reason != f"stage{stage}_disabled"
    decision = RiskManager().check(signal, ctx, off)
    assert (decision.approved, decision.reason) == (False, f"stage{stage}_disabled")


def test_stage_switches_can_be_patched_through_the_api():
    with TestClient(create_app()) as client:
        ok = client.patch("/api/settings", json={"stage2_enabled": False, "stage3_enabled": False})
        assert ok.status_code == 200
        body = ok.json()
        assert (body["stage1_enabled"], body["stage2_enabled"], body["stage3_enabled"]) == (True, False, False)
        assert client.get("/api/settings").json()["stage2_enabled"] is False


async def test_switched_off_stage_sells_nothing_but_manual_sell_still_works():
    stops = dict(stage1_stop_loss_pct=15.0, stage1_sell_enabled=True)
    stack = build_stack(make_config(**STAGE0, **stops, trade_amount_usdc=4.0))
    market = make_market(remaining=45)
    books = books_for(market, up_ask=0.88, down_ask=0.10)
    results = await _stage_buy(stack, market, books, 1)
    assert [r.status for r in results] == ["FILLED"]
    manager = stack["manager"]
    up = books[market.up_token_id]
    up.bids = {0.50: 100.0}  # far below the 15 % stop-loss

    off = make_config(**STAGE0, **stops, stage1_enabled=False, trade_amount_usdc=4.0)
    assert await manager.handle_exits(market, books, "paper", off) == []  # stage 1 is off: no stop-loss

    fired = await manager.handle_exits(market, books, "paper", stack["config"])
    assert [(r.status, r.reason) for r in fired] == [("FILLED", "stage1_stop_loss")]
