"""Buy guards (BTC gap / BTC trend), the buy on/off switch, the Telegram control and the API toggle."""

from __future__ import annotations

import time
from dataclasses import replace
from datetime import timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.config.settings import Settings, get_settings
from app.database.models import BotSetting, Order, utcnow
from app.database.session import session_scope
from app.main import create_app
from app.market_data.btc_price import BtcPriceFeed
from app.risk_manager import buy_guard
from app.risk_manager.manager import RiskManager
from app.services import bot as bot_module
from app.services.telegram import TelegramService, parse_command, parse_message, parse_minutes

from .helpers import books_for, build_stack, make_config, make_ctx, make_market, stage_books
from .test_strategy import evaluate


# --------------------------------------------------------------------------- pure guards
def test_gap_guard_trips_on_either_side_at_the_limit():
    assert buy_guard.check_gap(100_500.0, 100_000.0, 500.0).code == buy_guard.GAP_GUARD
    assert buy_guard.check_gap(99_500.0, 100_000.0, 500.0).code == buy_guard.GAP_GUARD
    up = buy_guard.check_gap(100_732.5, 100_000.0, 500.0)
    assert up.value == pytest.approx(732.5)
    assert "above" in up.message
    assert "below" in buy_guard.check_gap(99_000.0, 100_000.0, 500.0).message


def test_gap_guard_stays_quiet_below_the_limit_without_data_or_when_off():
    assert buy_guard.check_gap(100_499.99, 100_000.0, 500.0) is None
    assert buy_guard.check_gap(99_500.01, 100_000.0, 500.0) is None
    assert buy_guard.check_gap(None, 100_000.0, 500.0) is None  # no price: nothing to judge
    assert buy_guard.check_gap(100_900.0, None, 500.0) is None
    assert buy_guard.check_gap(105_000.0, 100_000.0, 0.0) is None  # 0 = guard off


def test_trend_guard_trips_on_a_1000_move_up_or_down():
    up = buy_guard.check_trend(101_000.0, 100_000.0, 1000.0, 3)
    down = buy_guard.check_trend(99_000.0, 100_000.0, 1000.0, 3)
    assert up.code == down.code == buy_guard.TREND_GUARD
    assert up.value == pytest.approx(1000.0) and down.value == pytest.approx(-1000.0)
    assert "3 markets" in up.message
    assert buy_guard.check_trend(100_999.0, 100_000.0, 1000.0, 3) is None
    assert buy_guard.check_trend(101_000.0, None, 1000.0, 3) is None
    assert buy_guard.check_trend(105_000.0, 100_000.0, 0.0, 3) is None


def test_defaults_are_500_gap_1000_trend_over_3_markets():
    fields = Settings.model_fields
    assert fields["buy_guard_gap_usd"].default == 500.0
    assert fields["buy_guard_trend_usd"].default == 1000.0
    assert fields["buy_guard_trend_markets"].default == 3
    assert fields["buy_guard_resume_minutes"].default == 60  # buying comes back by itself after 1 hour
    assert get_settings().telegram_enabled is False  # nothing configured in tests


# --------------------------------------------------------------------------- risk manager / order manager
@pytest.mark.parametrize("remaining,stage", [(55, 1), (40, 2), (20, 3)])
def test_risk_manager_refuses_every_stage_while_buys_are_off(remaining, stage):
    signal = next(s for s in evaluate(remaining, up=0.95, down=0.05).signals if s.stage == stage)
    ctx = make_ctx(remaining=remaining, btc_price=100_150.0, price_to_beat=100_000.0, stage1_bought=True)
    config = make_config()
    assert RiskManager().check(signal, ctx, config).reason != "buys_off"
    decision = RiskManager().check(signal, replace(ctx, buys_blocked="BTC gap too large: x"), config)
    assert (decision.approved, decision.reason) == (False, "buys_off")
    assert "BTC gap too large" in decision.detail


async def test_order_manager_places_no_order_while_buys_are_off():
    stack = build_stack()
    market = make_market(remaining=55)
    books = books_for(market, up_ask=0.93, down_ask=0.07)
    stage_books(stack, market, books)
    evaluation = stack["strategy"].evaluate(market, books, int(time.time() * 1000), stack["config"])
    assert evaluation.signals  # without buys_blocked this would buy
    with session_scope() as session:
        before = len(session.execute(select(Order)).scalars().all())
    blocked = replace(make_ctx(remaining=55.0), buys_blocked="buying turned off by telegram")
    results = await stack["manager"].handle_event(market, evaluation, books, "paper", blocked)
    assert results and all(r.status == "BLOCKED" and r.reason == "buys_off" for r in results)
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == before


# --------------------------------------------------------------------------- BTC feed anchor for the trend guard
def _feed_with_ticks(market, per_window: dict[int, float]):
    """Ticks stamped exactly at the start of the window `k` windows before `market` (k -> price)."""
    feed = BtcPriceFeed(get_settings())
    start_ms = int(market.window_start.replace(tzinfo=timezone.utc).timestamp() * 1000)
    for windows_ago, price in per_window.items():
        feed._ticks.append((start_ms - windows_ago * 300_000, price))
    return feed


def test_trend_anchor_is_the_price_at_the_start_of_the_oldest_of_the_last_n_windows():
    market = make_market(remaining=100)
    feed = _feed_with_ticks(market, {0: 100_000.0, 1: 99_800.0, 2: 99_000.0})
    assert feed.trend_anchor(market, 3) == 99_000.0  # this window + the 2 before it
    assert feed.trend_anchor(market, 2) == 99_800.0


def test_trend_anchor_is_none_without_real_history_and_never_guessed():
    market = make_market(remaining=100)
    feed = _feed_with_ticks(market, {0: 100_000.0})  # the bot only just started: no older ticks
    assert feed.trend_anchor(market, 3) is None
    # a neighbouring tick far from the window start is not used as a stand-in
    start_ms = int(market.window_start.replace(tzinfo=timezone.utc).timestamp() * 1000)
    feed._ticks.append((start_ms - 2 * 300_000 + 60_000, 99_500.0))
    feed._trend_anchor_retry_at.clear()
    assert feed.trend_anchor(market, 3) is None


def test_trend_anchor_for_one_window_is_the_price_to_beat():
    market = make_market(remaining=100)
    feed = _feed_with_ticks(market, {0: 100_000.0})
    assert feed.trend_anchor(market, 1) == 100_000.0


# --------------------------------------------------------------------------- bot: trip, resume, re-arm
class FakeTelegram:
    enabled = True

    def __init__(self):
        self.sent: list[str] = []

    async def send(self, text: str) -> bool:
        self.sent.append(text)
        return True

    async def start(self): ...
    async def stop(self): ...


def _new_bot(monkeypatch, gap=(100_000.0, 100_000.0), anchor=None):
    """A bot that is 'running' on a test market with controllable BTC readings. Nothing touches the network."""
    bot = create_app().state.bot
    bot.telegram = FakeTelegram()
    state = {"price": gap[0], "beat": gap[1], "anchor": anchor}
    bot._status = "RUNNING"
    bot._market = make_market(remaining=120)
    bot._mode = "paper"
    bot._buys_off = False
    bot._buys_off_by = bot._buys_off_reason = ""
    bot._buys_off_since = None
    bot._guard_disarmed = set()
    monkeypatch.setattr(bot.btc, "gap_inputs", lambda market: (state["price"], state["beat"], ""))
    monkeypatch.setattr(bot.btc, "trend_anchor", lambda market, markets: state["anchor"])
    return bot, state, make_config(buy_guard_gap_usd=500.0, buy_guard_trend_usd=1000.0, buy_guard_trend_markets=3)


async def _drain(bot):
    import asyncio

    for _ in range(3):
        await asyncio.sleep(0)
    if bot._background_tasks:
        await asyncio.gather(*list(bot._background_tasks), return_exceptions=True)


async def test_gap_of_500_switches_buying_off_and_sends_one_telegram_alert(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_480.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_block_reason() == ""  # 480 is inside the limit

    state["price"] = 100_520.0
    bot._check_buy_guards(bot._market, config)
    await _drain(bot)
    assert bot._buys_off and bot._buys_off_by == buy_guard.GAP_GUARD
    assert "BTC gap too large" in bot._buys_block_reason()
    assert len(bot.telegram.sent) == 1
    alert = bot.telegram.sent[0]
    assert "BUYING STOPPED" in alert and "520.00 USD above" in alert and '"Buy"' in alert

    bot._check_buy_guards(bot._market, config)  # still crossed: no second alert, no flapping
    await _drain(bot)
    assert len(bot.telegram.sent) == 1


async def test_a_downward_gap_trips_too(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 99_400.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_off and "below" in bot._buys_off_reason


async def test_three_market_move_of_1000_switches_buying_off(monkeypatch):
    bot, state, config = _new_bot(monkeypatch, gap=(100_100.0, 100_000.0), anchor=99_000.0)
    bot._check_buy_guards(bot._market, config)
    await _drain(bot)
    assert bot._buys_off and bot._buys_off_by == buy_guard.TREND_GUARD
    assert "last 3 markets" in bot.telegram.sent[0] and "BTC trend too large" in bot.telegram.sent[0]


async def test_trend_guard_waits_for_history_instead_of_guessing(monkeypatch):
    bot, state, config = _new_bot(monkeypatch, gap=(100_100.0, 100_000.0), anchor=None)
    bot._check_buy_guards(bot._market, config)
    assert not bot._buys_off


async def test_stopped_bot_does_not_trip(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    bot._status = "STOPPED"
    state["price"] = 101_000.0
    bot._check_buy_guards(bot._market, config)
    assert not bot._buys_off


async def test_buy_command_resumes_and_the_guard_stays_quiet_until_the_condition_clears(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_off

    reply = await bot._on_telegram_command("buy")
    assert "Buying is ON" in reply
    assert not bot._buys_off and bot._buys_block_reason() == ""

    for _ in range(3):  # the gap is still 700, but the operator decided: no re-trip
        bot._check_buy_guards(bot._market, config)
    assert not bot._buys_off

    state["price"] = 100_100.0  # back inside the limit: the guard re-arms
    bot._check_buy_guards(bot._market, config)
    assert buy_guard.GAP_GUARD not in bot._guard_disarmed
    state["price"] = 100_650.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_off


async def test_stop_and_status_commands(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    state["price"] = 100_200.0

    status = await bot._on_telegram_command("status")
    assert "Buying: ON" in status and "+200.00 USD" in status

    assert "Buying is OFF" in await bot._on_telegram_command("stop")
    assert bot._buys_off and bot._buys_off_by == "manual"
    assert "already OFF" in await bot._on_telegram_command("stop")
    assert "Buying: OFF" in await bot._on_telegram_command("status")

    assert "Buying is ON" in await bot._on_telegram_command("buy")
    assert "already ON" in await bot._on_telegram_command("buy")


async def test_buy_command_warns_when_the_bot_itself_is_stopped(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    await bot._change_buys(False, "dashboard")
    bot._status = "STOPPED"
    reply = await bot._on_telegram_command("buy")
    assert "STOPPED" in reply


async def test_switch_survives_a_restart(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_900.0
    bot._check_buy_guards(bot._market, config)
    with session_scope() as session:
        row = session.get(BotSetting, bot_module.BUYS_STATE_KEY)
        assert row.value["off"] is True and row.value["by"] == buy_guard.GAP_GUARD

    restarted = create_app().state.bot
    with session_scope() as session:
        restarted._load_buys_state(session)
    assert restarted._buys_off and restarted._buys_off_by == buy_guard.GAP_GUARD
    assert "BTC gap too large" in restarted._buys_block_reason()

    await bot._change_buys(True, "dashboard")
    with session_scope() as session:
        restarted._load_buys_state(session)
    assert not restarted._buys_off


async def test_turning_buys_on_or_off_elsewhere_notifies_telegram(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    await bot._change_buys(False, "dashboard")
    await bot._change_buys(True, "dashboard")
    await _drain(bot)
    assert [("OFF" in m, "ON" in m) for m in bot.telegram.sent] == [(True, False), (False, True)]
    # a change made from Telegram is answered in the chat itself, not announced a second time
    bot.telegram.sent.clear()
    await bot._on_telegram_command("stop")
    await _drain(bot)
    assert bot.telegram.sent == []


# --------------------------------------------------------------------------- auto-resume, delay, permanent off
@pytest.mark.parametrize(
    "arg,expected",
    [
        ("30", 30), ("30m", 30), ("45 min", 45), ("2h", 120), ("1.5 hours", 90), ("1,5h", 90), ("0", 0),
        ("off", 0), ("never", 0), ("1440", 1440), ("24h", 1440),
        ("", None), ("abc", None), ("-5", None), ("1441", None), ("25h", None), ("10x", None), ("1.2.3", None),
    ],
)
def test_parse_minutes(arg, expected):
    assert parse_minutes(arg) == expected


def test_parse_message_keeps_the_argument():
    assert parse_message("Delay 30") == ("delay", "30")
    assert parse_message("/delay@my_bot 2h") == ("delay", "2h")
    assert parse_message("buy") == ("buy", "")
    assert parse_message("hello 30") is None


async def test_trip_alert_tells_when_buying_comes_back_by_itself(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    await _drain(bot)
    alert = bot.telegram.sent[0]
    assert "turns back on by itself in 60 min" in alert and "re-armed" in alert and '"Delay 30"' in alert

    bot2, state2, config2 = _new_bot(monkeypatch)
    config2 = make_config(buy_guard_gap_usd=500.0, buy_guard_resume_minutes=0)
    state2["price"] = 100_700.0
    bot2._check_buy_guards(bot2._market, config2)
    await _drain(bot2)
    assert "Auto-resume is off" in bot2.telegram.sent[0]


async def test_buying_switches_itself_back_on_after_the_delay_and_the_guards_reset(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_off and bot._buys_resume_at(config) == bot._buys_off_since + timedelta(minutes=60)

    bot._buys_off_since = utcnow() - timedelta(minutes=59)  # not yet
    assert bot._maybe_auto_resume(config) is None and bot._buys_off

    # operator pressed Buy earlier while BTC was far away: that guard was disarmed; the reset re-arms it
    bot._guard_disarmed = {buy_guard.GAP_GUARD}
    bot._buys_off_since = utcnow() - timedelta(minutes=61)
    text = bot._maybe_auto_resume(config)
    assert text is not None and "back ON by itself" in text and "60 min" in text
    assert not bot._buys_off and bot._buys_block_reason() == "" and bot._guard_disarmed == set()
    with session_scope() as session:
        assert session.get(BotSetting, bot_module.BUYS_STATE_KEY).value["off"] is False


async def test_after_the_auto_resume_the_guard_trips_again_with_a_new_alert_if_btc_is_still_past_the_limit(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    await _drain(bot)
    first_since = bot._buys_off_since
    assert len(bot.telegram.sent) == 1

    bot._buys_off_since = utcnow() - timedelta(minutes=61)
    assert bot._maybe_auto_resume(config) is not None
    bot._check_buy_guards(bot._market, config)  # BTC is still 700 away
    await _drain(bot)
    assert bot._buys_off and bot._buys_off_by == buy_guard.GAP_GUARD
    assert bot._buys_off_since > first_since  # a new wait starts
    assert len(bot.telegram.sent) == 2 and "BUYING STOPPED" in bot.telegram.sent[1]

    # BTC calmed down meanwhile: resume stays on and no alert
    bot._buys_off_since = utcnow() - timedelta(minutes=61)
    state["price"] = 100_100.0
    assert bot._maybe_auto_resume(config) is not None
    bot._check_buy_guards(bot._market, config)
    await _drain(bot)
    assert not bot._buys_off and len(bot.telegram.sent) == 2


async def test_no_auto_resume_when_the_delay_is_off(monkeypatch):
    bot, state, _config = _new_bot(monkeypatch)
    config = make_config(buy_guard_gap_usd=500.0, buy_guard_resume_minutes=0)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    bot._buys_off_since = utcnow() - timedelta(days=3)
    assert bot._buys_resume_at(config) is None
    assert bot._maybe_auto_resume(config) is None and bot._buys_off


async def test_changing_the_delay_applies_to_the_running_countdown(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    bot._buys_off_since = utcnow() - timedelta(minutes=50)
    try:
        assert "Auto-resume wait after a guard trip: 60 min" in await bot._on_telegram_command("delay")
        reply = await bot._on_telegram_command("delay", "2h")
        assert "120 min" in reply and "comes back at" in reply
        assert bot.runtime.get().buy_guard_resume_minutes == 120
        assert bot._buys_resume_at(bot.runtime.get()) == bot._buys_off_since + timedelta(minutes=120)

        reply = await bot._on_telegram_command("delay", "30")  # 50 min already passed: due right away
        assert "already passed" in reply
        assert bot._maybe_auto_resume(bot.runtime.get()) is not None and not bot._buys_off

        assert "could not read" in await bot._on_telegram_command("delay", "soon")
        assert bot.runtime.get().buy_guard_resume_minutes == 30
        assert "OFF" in await bot._on_telegram_command("delay", "off")
        assert bot.runtime.get().buy_guard_resume_minutes == 0
    finally:
        with session_scope() as session:
            bot.runtime.reset(session)


async def test_off_is_permanent_for_the_day_and_comes_back_the_next_day(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    await bot._change_buys(False, "dashboard")
    assert bot._buys_off_by == "manual"

    # hours later: still off, no hourly timer for the operator's Off
    bot._buys_off_since = utcnow().replace(hour=0, minute=0, second=1, microsecond=0)
    resume_at = bot._buys_resume_at(config)
    assert resume_at == bot._buys_off_since.replace(hour=0, minute=0, second=0) + timedelta(days=1)
    assert bot._maybe_auto_resume(config) is None and bot._buys_off

    # the next day starts: buying is back and every guard is re-armed
    bot._buys_off_since = utcnow() - timedelta(days=1, minutes=1)
    bot._guard_disarmed = {buy_guard.GAP_GUARD}
    text = bot._maybe_auto_resume(config)
    assert text is not None and "New day" in text
    assert not bot._buys_off and bot._guard_disarmed == set()


async def test_off_while_a_guard_has_buying_off_turns_the_hourly_timer_into_off_for_the_day(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    assert bot._buys_off_by == buy_guard.GAP_GUARD
    reply = await bot._on_telegram_command("stop")
    assert "rest of the day" in reply and bot._buys_off_by == "manual"
    bot._buys_off_since = utcnow() - timedelta(minutes=90)  # the hour is over, but the operator said Off
    assert bot._maybe_auto_resume(config) is None and bot._buys_off


async def test_status_and_snapshot_show_when_buying_comes_back(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    assert "Auto-resume wait after a guard trip: 60 min" in await bot._on_telegram_command("status")
    state["price"] = 100_700.0
    bot._check_buy_guards(bot._market, config)
    status = bot.buy_guard_status(bot._market, config)
    assert status["resume_minutes"] == 60 and status["resume_at"].endswith("Z")
    assert "comes back by itself" in await bot._on_telegram_command("status")
    await bot._change_buys(False, "dashboard")
    assert "next day" in await bot._on_telegram_command("status")


async def test_the_once_a_second_tick_resumes_buying_and_trips_the_guard_again_in_one_go(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    state["price"] = 100_700.0
    bot._buy_switch_tick()  # the guard trips
    await _drain(bot)
    assert bot._buys_off and len(bot.telegram.sent) == 1

    bot._buy_switch_tick()  # still inside the hour: nothing changes, no second alert
    await _drain(bot)
    assert bot._buys_off and len(bot.telegram.sent) == 1

    bot._buys_off_since = utcnow() - timedelta(minutes=61)
    bot._buy_switch_tick()  # hour over, BTC still 700 away: resumed and tripped again within one tick
    await _drain(bot)
    assert bot._buys_off and bot._buys_off_by == buy_guard.GAP_GUARD
    assert len(bot.telegram.sent) == 2 and "BUYING STOPPED" in bot.telegram.sent[1]
    assert not any("back ON by itself" in m for m in bot.telegram.sent)  # one message, not two

    bot._buys_off_since = utcnow() - timedelta(minutes=61)
    state["price"] = 100_050.0  # calm now
    bot._buy_switch_tick()
    await _drain(bot)
    assert not bot._buys_off and "back ON by itself" in bot.telegram.sent[-1]


async def test_a_stopped_bot_still_switches_buying_back_on_when_the_wait_is_over(monkeypatch):
    bot, state, config = _new_bot(monkeypatch)
    monkeypatch.setattr(bot.runtime, "get", lambda: config)
    await bot._change_buys(False, "dashboard")
    bot._status = "STOPPED"
    bot._buys_off_since = utcnow() - timedelta(days=1, minutes=5)
    bot._buy_switch_tick()
    assert not bot._buys_off


# --------------------------------------------------------------------------- API
def test_api_toggle_and_snapshot():
    with TestClient(create_app()) as client:
        state = client.get("/api/state").json()
        assert state["buy_guard"]["buys_enabled"] is True
        assert state["buy_guard"]["gap_limit_usd"] == 500.0
        assert state["buy_guard"]["trend_limit_usd"] == 1000.0
        assert state["buy_guard"]["trend_markets"] == 3
        assert state["buy_guard"]["telegram_enabled"] is False

        off = client.post("/api/buys", json={"enabled": False})
        assert off.status_code == 200
        assert off.json()["buy_guard"]["buys_enabled"] is False
        assert off.json()["buy_guard"]["off_by"] == "manual"
        assert client.get("/api/state").json()["buy_guard"]["buys_enabled"] is False

        on = client.post("/api/buys", json={"enabled": True})
        assert on.json()["buy_guard"]["buys_enabled"] is True
        assert client.post("/api/buys", json={}).status_code == 422


def test_guard_limits_can_be_changed_from_the_dashboard():
    with TestClient(create_app()) as client:
        ok = client.patch(
            "/api/settings",
            json={"buy_guard_gap_usd": 650, "buy_guard_trend_usd": 1500, "buy_guard_trend_markets": 4},
        )
        assert ok.status_code == 200
        body = client.get("/api/settings").json()
        assert (body["buy_guard_gap_usd"], body["buy_guard_trend_usd"], body["buy_guard_trend_markets"]) == (650, 1500, 4)
        assert client.patch("/api/settings", json={"buy_guard_trend_markets": 0}).status_code == 422
        assert client.patch("/api/settings", json={"buy_guard_resume_minutes": 120}).status_code == 200
        assert client.get("/api/settings").json()["buy_guard_resume_minutes"] == 120
        assert client.patch("/api/settings", json={"buy_guard_resume_minutes": 0}).status_code == 200  # 0 = never
        assert client.patch("/api/settings", json={"buy_guard_resume_minutes": 1441}).status_code == 422
        assert client.patch("/api/settings", json={"buy_guard_resume_minutes": -1}).status_code == 422
        client.post("/api/settings/reset")
        assert client.get("/api/settings").json()["buy_guard_gap_usd"] == 500.0
        assert client.get("/api/settings").json()["buy_guard_resume_minutes"] == 60


# --------------------------------------------------------------------------- Telegram service
@pytest.mark.parametrize(
    "text,expected",
    [
        ("Buy", "buy"), ("buy", "buy"), (" BUY ", "buy"), ("/buy", "buy"), ("/buy@my_bot", "buy"), ("buy now", "buy"),
        ("Stop", "stop"), ("off", "stop"), ("Permanent", "stop"), ("status", "status"),
        ("Delay 30", "delay"), ("/delay@my_bot 2h", "delay"), ("wait", "delay"),
        ("hello", None), ("", None), (None, None), ("buyer", None),
    ],
)
def test_parse_command(text, expected):
    assert parse_command(text) == expected


def _telegram(handler, on_command=None):
    settings = get_settings().model_copy(update={"telegram_bot_token": "123:SECRET", "telegram_chat_id": "42"})
    service = TelegramService(settings, on_command)
    service._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return service


async def test_send_posts_to_the_configured_chat():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.read().decode()
        return httpx.Response(200, json={"ok": True})

    service = _telegram(handler)
    assert await service.send("hello") is True
    assert seen["url"] == "https://api.telegram.org/bot123:SECRET/sendMessage"
    assert '"chat_id":"42"' in seen["body"].replace(" ", "") and "hello" in seen["body"]


async def test_send_failure_never_raises_and_hides_the_token():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom https://api.telegram.org/bot123:SECRET/sendMessage")

    service = _telegram(handler)
    assert await service.send("hello") is False
    assert "SECRET" not in (service.last_error or "")


async def test_disabled_without_token_or_chat():
    service = TelegramService(get_settings())  # nothing configured
    assert service.enabled is False
    assert await service.send("x") is False


async def test_only_the_owner_chat_can_send_commands():
    received = []

    async def on_command(command, arg=""):
        received.append(command)
        return f"done {command}"

    sent = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(request.read().decode())
        return httpx.Response(200, json={"ok": True})

    service = _telegram(handler, on_command)
    await service._handle_update({"update_id": 1, "message": {"chat": {"id": 999}, "text": "Buy"}})  # a stranger
    assert received == [] and sent == []
    await service._handle_update({"update_id": 2, "message": {"chat": {"id": 42}, "text": "Buy"}})
    await service._handle_update({"update_id": 3, "message": {"chat": {"id": 42}, "text": "just chatting"}})
    assert received == ["buy"]
    assert len(sent) == 1 and "done buy" in sent[0]


async def test_commands_sent_while_the_backend_was_down_are_dropped():
    received = []

    async def on_command(command, arg=""):
        received.append(command)

    def handler(request: httpx.Request) -> httpx.Response:
        params = dict(request.url.params)
        if params.get("offset") == "-1":  # the start-up look at the newest update
            return httpx.Response(200, json={"ok": True, "result": [{"update_id": 77, "message": {"chat": {"id": 42}, "text": "Buy"}}]})
        return httpx.Response(200, json={"ok": True, "result": []})

    service = _telegram(handler, on_command)
    await service._skip_backlog()
    assert service._offset == 78 and received == []
