"""Limit sell: the operator's price, kept by the bot in code (never an exchange order), sold at the market once the
best bid reaches it and never below it. It can be set before the shares exist (one-tap) and removed at any time."""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.database.models import Order, Position
from app.database.session import session_scope
from app.services.bot import BotError

from .helpers import books_for, build_stack, make_config, make_market, stage_books
from .test_stage0 import _running_bot

TAP = 9


async def _run_limits(bot) -> None:
    """Wake the limit worker the way a book event does and wait for it to finish."""
    bot._dispatch_limits()
    task = bot._limit_task
    if task is not None:
        await task


def _set_bid(bot, books, market, side: str, bid: float) -> None:
    token = market.up_token_id if side == "UP" else market.down_token_id
    books[token].bids = {bid: 1000.0}
    bot.data._books = dict(books)


def _sell_orders(bot) -> list[Order]:
    with session_scope() as session:
        orders = session.execute(select(Order).order_by(Order.id)).scalars().all()
        session.expunge_all()
    return [o for o in orders if bot.orders._key_action(o.order_key) == "SELL"]


def _setup(up_ask: float = 0.40, down_ask: float = 0.60):
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    books = books_for(market, up_ask=up_ask, down_ask=down_ask)
    bot.data._books = dict(books)
    return market, bot, books


# ---------------------------------------------------------------- the order manager's limit sell
async def test_limit_sell_waits_below_the_limit_then_sells_at_the_bid_with_a_limit_reason():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = stack["manager"]
    stage_books(stack, market, books)
    bought = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0, None)
    assert bought.status == "FILLED"

    books[market.up_token_id].bids = {0.70: 1000.0}
    blocked = await manager.limit_sell(market, books, "paper", stack["config"], "UP", TAP, 0.80)
    assert (blocked.status, blocked.reason) == ("BLOCKED", "below_limit")  # not due: nothing was sent
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1  # only the buy

    books[market.up_token_id].bids = {0.82: 1000.0}
    sold = await manager.limit_sell(market, books, "paper", stack["config"], "UP", TAP, 0.80)
    assert sold.status == "FILLED"
    assert sold.reason == "stage9_limit"
    assert sold.avg_price == pytest.approx(0.82)  # at the market, which is at or above the limit
    with session_scope() as session:
        position = session.execute(select(Position)).scalar_one()
    assert position.status == "closed" and position.size == pytest.approx(0.0)


async def test_limit_sell_is_not_due_one_tick_under_the_limit_so_nothing_is_ever_sent_below_it():
    stack = build_stack(make_config(paper_starting_balance=20.0, sell_floor_tolerance_ticks=0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = stack["manager"]
    stage_books(stack, market, books)
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0, None)
    books[market.up_token_id].bids = {0.79: 1000.0}
    result = await manager.limit_sell(market, books, "paper", stack["config"], "UP", TAP, 0.80)
    assert (result.status, result.reason) == ("BLOCKED", "below_limit")
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1  # only the buy, no sell was sent


# ---------------------------------------------------------------- the bot: set, fire, cancel
async def test_bot_limit_fires_once_when_the_bid_reaches_it_and_then_disappears():
    market, bot, books = _setup()
    await bot.tap_buy(market.market_id, "UP", 5.0)
    snapshot = await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    assert snapshot["limits"] == [
        {"market_id": market.market_id, "side": "UP", "stage": TAP, "price": 0.8, "set_at": snapshot["limits"][0]["set_at"]}
    ]

    _set_bid(bot, books, market, "UP", 0.70)
    assert bot._limit_due(market.market_id) is False
    await _run_limits(bot)
    assert _sell_orders(bot) == []  # still below the limit: nothing sold, the limit stays
    assert (market.market_id, "UP", TAP) in bot._limits

    _set_bid(bot, books, market, "UP", 0.83)
    assert bot._limit_due(market.market_id) is True
    await _run_limits(bot)
    sells = _sell_orders(bot)
    assert [(o.status, o.reason) for o in sells] == [("FILLED", "stage9_limit")]
    assert bot._limits == {}  # fully sold: the limit is done
    with session_scope() as session:
        position = session.execute(select(Position)).scalar_one()
    assert position.status == "closed"

    await _run_limits(bot)  # a later book event finds nothing to do
    assert len(_sell_orders(bot)) == 1


async def test_bot_limit_set_before_the_buy_waits_for_the_shares_then_sells():
    market, bot, books = _setup()
    _set_bid(bot, books, market, "UP", 0.85)  # the bid is already above the limit, but nothing is held yet
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    await _run_limits(bot)
    assert _sell_orders(bot) == []
    assert (market.market_id, "UP", TAP) in bot._limits  # armed ahead of the buy

    await bot.tap_buy(market.market_id, "UP", 5.0)
    bot._limits[(market.market_id, "UP", TAP)]["next_try_ms"] = 0  # skip the short wait between looks
    await _run_limits(bot)
    assert [(o.status, o.reason) for o in _sell_orders(bot)] == [("FILLED", "stage9_limit")]
    assert bot._limits == {}


async def test_bot_limit_set_while_the_bid_is_already_above_it_sells_right_away():
    market, bot, books = _setup()
    await bot.tap_buy(market.market_id, "UP", 5.0)
    _set_bid(bot, books, market, "UP", 0.90)
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    if bot._limit_task is not None:
        await bot._limit_task
    assert [(o.status, o.reason) for o in _sell_orders(bot)] == [("FILLED", "stage9_limit")]


async def test_bot_cancelled_limit_never_fires_and_needs_no_exchange_cancel():
    market, bot, books = _setup()
    await bot.tap_buy(market.market_id, "UP", 5.0)
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    snapshot = await bot.cancel_limit(market.market_id, "UP", TAP)
    assert snapshot["limits"] == []
    _set_bid(bot, books, market, "UP", 0.90)
    await _run_limits(bot)
    assert _sell_orders(bot) == []
    again = await bot.cancel_limit(market.market_id, "UP", TAP)  # removing none is fine
    assert again["limits"] == []


async def test_bot_manual_sell_works_at_once_with_a_limit_set_and_a_stage_limit_goes_with_its_shares():
    market, bot, books = _setup()
    await bot.tap_buy(market.market_id, "UP", 5.0)
    await bot.set_limit(market.market_id, "UP", TAP, 0.95)
    await bot.set_limit(market.market_id, "DOWN", 1, 0.95)
    sold = await bot.manual_sell(market.market_id, "UP", TAP)  # no exchange order to cancel first
    assert sold["manual_sell"]["status"] == "FILLED"
    assert (market.market_id, "UP", TAP) in bot._limits  # a one-tap limit stays: it is set ahead of buys

    bot.orders._tap_last_s -= 5
    bot._limits[(market.market_id, "UP", 1)] = {
        "price": 0.9, "set_at": "x", "next_try_ms": 0, "attempts": 0, "fired": False,
    }
    with pytest.raises(BotError):
        await bot.manual_sell(market.market_id, "UP", 1)  # nothing held in stage 1: refused ...
    assert (market.market_id, "UP", 1) in bot._limits  # ... and the limit is left alone


# ---------------------------------------------------------------- safety
async def test_bot_limit_is_refused_when_stopped_killed_ended_or_out_of_range():
    market, bot, books = _setup()
    with pytest.raises(BotError, match="between"):
        await bot.set_limit(market.market_id, "UP", TAP, 1.5)
    with pytest.raises(BotError, match="between"):
        await bot.set_limit(market.market_id, "UP", TAP, 0.0)
    with pytest.raises(BotError, match="ended"):
        await bot.set_limit("other-market", "UP", TAP, 0.8)
    with pytest.raises(BotError, match="stage"):
        await bot.set_limit(market.market_id, "UP", 5, 0.8)
    bot._kill_switch = True
    with pytest.raises(BotError, match="running"):
        await bot.set_limit(market.market_id, "UP", TAP, 0.8)
    bot._kill_switch = False
    bot._status = "STOPPED"
    with pytest.raises(BotError, match="running"):
        await bot.set_limit(market.market_id, "UP", TAP, 0.8)


async def test_bot_stopping_drops_every_limit_so_nothing_fires_later():
    market, bot, books = _setup()
    await bot.tap_buy(market.market_id, "UP", 5.0)
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    await bot.stop_bot("test")
    assert bot._limits == {}
    bot._status = "RUNNING"
    _set_bid(bot, books, market, "UP", 0.90)
    await _run_limits(bot)
    assert _sell_orders(bot) == []


async def test_bot_limits_belong_to_one_market_and_are_not_shown_for_another():
    market, bot, books = _setup()
    await bot.set_limit(market.market_id, "UP", TAP, 0.80)
    other = make_market(remaining=250, market_id="0xnext", slug="btc-updown-5m-next")
    assert bot._limits_payload(other) == []  # the snapshot of another market shows none
    assert len(bot._limits_payload(market)) == 1
    await bot._on_market(other, "opened")  # a new round starts without any limit
    assert bot._limits == {}
