"""Limit buy: the operator's price and amount, kept by the bot in code (never an exchange order). When the best ask
falls to the limit the bot makes ONE one-tap buy that never pays more than the limit, and the limit is gone."""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.database.models import Order, Position
from app.database.session import session_scope
from app.order_manager.manager import OrderResult
from app.services.bot import LIMIT_MAX_ATTEMPTS, BotError

from .helpers import books_for, build_stack, make_config, make_market, stage_books
from .test_limit_sell import TAP, _run_limits, _sell_orders, _set_bid, _settle_limits, _setup


def _set_ask(bot, books, market, side: str, ask: float) -> None:
    token = market.up_token_id if side == "UP" else market.down_token_id
    books[token].asks = {ask: 1000.0}
    bot.data._books = dict(books)


def _buy_orders(bot) -> list[Order]:
    with session_scope() as session:
        orders = session.execute(select(Order).order_by(Order.id)).scalars().all()
        session.expunge_all()
    return [o for o in orders if bot.orders._key_action(o.order_key) == "BUY"]


# ---------------------------------------------------------------- the order manager: the price cap
async def test_tap_buy_with_a_cap_sends_nothing_while_the_ask_is_above_it():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.45, down_ask=0.55)
    manager = stack["manager"]
    stage_books(stack, market, books)
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0, None, 0.0, 0.40)
    assert (result.status, result.reason) == ("BLOCKED", "above_limit")
    with session_scope() as session:
        assert session.execute(select(Order)).scalars().all() == []
    assert manager._tap_last_s == 0 or manager._tap_last_s < 1  # a refused try does not start the tap cooldown


async def test_tap_buy_with_a_cap_buys_at_the_ask_when_it_is_at_or_below_the_cap():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = stack["manager"]
    stage_books(stack, market, books)
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0, None, 0.0, 0.40)
    assert result.status == "FILLED"
    assert result.avg_price is not None and result.avg_price <= 0.40 + 1e-9


async def test_tap_buy_without_a_cap_is_unchanged():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.45, down_ask=0.55)
    manager = stack["manager"]
    stage_books(stack, market, books)
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0, None, 0.0)
    assert result.status == "FILLED"


# ---------------------------------------------------------------- the bot: set, fire, cancel
async def test_bot_limit_buy_waits_above_the_limit_then_buys_once_and_disappears():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)
    snapshot = await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    assert snapshot["buy_limits"] == [
        {
            "market_id": market.market_id,
            "side": "UP",
            "price": 0.4,
            "amount": 5.0,
            "set_at": snapshot["buy_limits"][0]["set_at"],
        }
    ]
    assert bot._buy_limit_due(market.market_id) is False
    await _run_limits(bot)
    assert _buy_orders(bot) == []  # the ask is still above the limit: nothing bought, the limit stays
    assert (market.market_id, "UP") in bot._buy_limits

    _set_ask(bot, books, market, "UP", 0.40)
    assert bot._buy_limit_due(market.market_id) is True
    await _run_limits(bot)
    buys = _buy_orders(bot)
    assert [(o.status, o.reason) for o in buys] == [("FILLED", "one-tap buy")]
    assert buys[0].avg_fill_price <= 0.40 + 1e-9
    assert bot._buy_limits == {}  # one buy, then it is done
    with session_scope() as session:
        position = session.execute(select(Position)).scalar_one()
    assert (position.side, position.tag, position.status) == ("UP", "tap", "open")

    await _run_limits(bot)  # a later book event finds nothing to do
    assert len(_buy_orders(bot)) == 1


async def test_bot_limit_buy_fires_at_once_when_the_ask_is_already_at_or_below_the_limit():
    market, bot, books = _setup(up_ask=0.35, down_ask=0.65)
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    await _settle_limits(bot)
    assert [(o.status, o.reason) for o in _buy_orders(bot)] == [("FILLED", "one-tap buy")]
    assert bot._buy_limits == {}


async def test_bot_limit_buy_never_buys_above_its_limit_even_if_the_ask_moves_after_the_due_check():
    market, bot, books = _setup(up_ask=0.35, down_ask=0.65)
    await bot.set_buy_limit(market.market_id, "UP", 0.30, 5.0)  # ask 0.35 is above 0.30: waiting
    key = (market.market_id, "UP")
    entry = bot._buy_limits[key]
    assert await bot._fire_buy_limit(market, key, entry) is False  # forced attempt with the ask above the limit
    assert _buy_orders(bot) == []
    assert bot._buy_limits[key] is entry


async def test_bot_limit_buy_cancel_removes_it_and_removing_none_is_fine():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    snapshot = await bot.cancel_buy_limit(market.market_id, "UP")
    assert snapshot["buy_limits"] == [] and bot._buy_limits == {}
    snapshot = await bot.cancel_buy_limit(market.market_id, "UP")  # none left: still fine
    assert snapshot["buy_limits"] == []


async def test_bot_limit_buy_one_per_side_a_new_one_replaces_the_old():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.65)
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    await bot.set_buy_limit(market.market_id, "UP", 0.30, 3.0)
    await bot.set_buy_limit(market.market_id, "DOWN", 0.35, 2.0)
    assert {k: (e["price"], e["amount"]) for k, e in bot._buy_limits.items()} == {
        (market.market_id, "UP"): (0.3, 3.0),
        (market.market_id, "DOWN"): (0.35, 2.0),
    }


async def test_bot_limit_buy_checks_price_amount_balance_market_and_state():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)
    with pytest.raises(BotError):
        await bot.set_buy_limit(market.market_id, "UP", 1.5, 5.0)
    with pytest.raises(BotError):
        await bot.set_buy_limit(market.market_id, "UP", 0.40, 0.5)  # under the $1 minimum
    with pytest.raises(BotError):
        await bot.set_buy_limit(market.market_id, "UP", 0.40, 10_000_000.0)  # over the cap / the balance
    with pytest.raises(BotError):
        await bot.set_buy_limit("another-market", "UP", 0.40, 5.0)
    with pytest.raises(BotError):
        await bot.set_buy_limit(market.market_id, "SIDEWAYS", 0.40, 5.0)
    bot._kill_switch = True
    with pytest.raises(BotError):
        await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    assert bot._buy_limits == {}


async def test_bot_limit_buy_is_dropped_with_the_market_and_when_the_bot_stops():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    await bot.stop_bot("test")
    assert bot._buy_limits == {}


async def test_bot_limit_buy_is_not_repeated_when_the_outcome_is_not_known():
    """A live order whose result is unknown must never be sent a second time: it could double the position."""
    market, bot, books = _setup(up_ask=0.35, down_ask=0.65)
    calls: list[int] = []

    async def unknown(*_args, **_kwargs):
        calls.append(1)
        return OrderResult(None, "UP", "SUBMITTED", "one-tap buy", error="outcome unknown")

    bot.orders.tap_buy = unknown
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    await _settle_limits(bot)
    assert calls == [1]
    assert bot._buy_limits == {}  # dropped, not retried
    await _run_limits(bot)
    assert calls == [1]


async def test_bot_limit_buy_retries_a_clean_refusal_then_gives_up():
    market, bot, books = _setup(up_ask=0.35, down_ask=0.65)
    calls: list[int] = []

    async def refused(*_args, **_kwargs):
        calls.append(1)
        return OrderResult(None, "UP", "REJECTED", "one-tap buy", error="refused")

    bot.orders.tap_buy = refused
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    key = (market.market_id, "UP")
    for _ in range(LIMIT_MAX_ATTEMPTS + 5):
        entry = bot._buy_limits.get(key)
        if entry is None:
            break
        entry["next_try_ms"] = 0
        await _run_limits(bot)
    assert bot._buy_limits == {}
    assert len(calls) == LIMIT_MAX_ATTEMPTS


async def test_limit_buy_shares_are_one_tap_shares_so_a_limit_sell_set_before_picks_them_up():
    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)
    _set_bid(bot, books, market, "UP", 0.85)
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)  # sell limit armed ahead of any shares
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    _set_ask(bot, books, market, "UP", 0.40)
    await _run_limits(bot)  # the buy fires
    assert len(_buy_orders(bot)) == 1
    bot._limits[(market.market_id, "UP", TAP)]["next_try_ms"] = 0
    await _run_limits(bot)  # now the shares exist and the bid is above the sell limit
    assert [(o.status, o.reason) for o in _sell_orders(bot)] == [("FILLED", "stage9_limit")]


# ---------------------------------------------------------------- audit fixes
async def test_bot_held_back_limit_buy_is_retried_by_a_timer_on_a_quiet_book():
    import asyncio
    import time

    market, bot, books = _setup(up_ask=0.40, down_ask=0.60)
    bot.orders._tap_last_s = time.monotonic()  # a tap just happened: the buy is held back by the cooldown
    await bot.set_buy_limit(market.market_id, "UP", 0.40, 5.0)
    await _settle_limits(bot)
    assert _buy_orders(bot) == []
    await asyncio.sleep(1.6)  # no book event arrives: only the timer can wake the limit
    assert [o.status for o in _buy_orders(bot)] == ["FILLED"]
    assert bot._buy_limits == {}


async def test_bot_slow_limit_sell_does_not_hold_back_a_due_limit_buy():
    import asyncio
    import time

    market, bot, books = _setup(up_ask=0.55, down_ask=0.45)

    async def slow_limit_sell(*args, **kwargs):
        await asyncio.sleep(0.8)
        return OrderResult(None, "UP", "CANCELED", "stage9_limit", 0.0, None, 800.0, None, "no fill")

    bot.orders.limit_sell = slow_limit_sell
    await bot.set_limit(market.market_id, "UP", TAP, 0.50)
    await bot.set_buy_limit(market.market_id, "DOWN", 0.45, 5.0)
    _set_bid(bot, books, market, "UP", 0.52)
    _set_ask(bot, books, market, "DOWN", 0.45)
    started = time.monotonic()
    bot._dispatch_limits()
    while not _buy_orders(bot) and time.monotonic() - started < 3:
        await asyncio.sleep(0.01)
    assert _buy_orders(bot), "the buy limit never fired"
    assert time.monotonic() - started < 0.5  # it did not wait for the 0.8 s sell
    await asyncio.gather(*list(bot._limit_jobs))


async def test_bot_limit_buy_fires_with_the_bot_stopped():
    market, bot, books = _setup(up_ask=0.60, down_ask=0.40)
    await bot.set_buy_limit(market.market_id, "UP", 0.50, 2.0)
    await bot.stop_bot("operator")
    assert bot._status == "STOPPED" and len(bot._buy_limits) == 1  # a plain stop keeps the operator's limit buy
    _set_ask(bot, books, market, "UP", 0.48)
    await _run_limits(bot)
    assert len(_buy_orders(bot)) == 1
    assert bot._buy_limits == {}


async def test_bot_kill_switch_drops_the_limit_buys_and_sells():
    market, bot, books = _setup(up_ask=0.60, down_ask=0.40)
    await bot.set_buy_limit(market.market_id, "UP", 0.50, 2.0)
    await bot.set_kill_switch(True)
    assert bot._buy_limits == {} and bot._limits == {}
    _set_ask(bot, books, market, "UP", 0.48)
    await _run_limits(bot)
    assert _buy_orders(bot) == []
