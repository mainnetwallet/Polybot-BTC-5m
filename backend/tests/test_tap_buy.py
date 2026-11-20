"""One-tap buy: a manual market buy that must never change what the strategy stages do."""

from __future__ import annotations

import asyncio

import pytest
from sqlalchemy import select

from app.database.models import Order, Position
from app.database.session import session_scope

from .helpers import books_for, build_stack, make_config, make_market, stage_books
from .test_stage0 import STAGE0, _event, _running_bot, _stage0_buy


def _tap_ready(stack, market, books):
    stage_books(stack, market, books)
    return stack["manager"]


async def _tap(stack, market, books, side="UP", amount=2.0, request_id=None):
    manager = _tap_ready(stack, market, books)
    return await manager.tap_buy(market, books, "paper", stack["config"], side, amount, request_id)


def _cooldown_over(manager) -> None:
    manager._tap_last_s -= 5.0  # pretend the last tap was 5 s ago


# ---------------------------------------------------------------- the buy itself
async def test_tap_buy_fills_at_the_ask_and_creates_a_tagged_position():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    result = await _tap(stack, market, books, "UP", 2.0)
    assert result.status == "FILLED"
    assert result.filled_size == pytest.approx(5.0, rel=0.02)  # $2 at 0.40
    with session_scope() as session:
        order = session.execute(select(Order)).scalar_one()
        position = session.execute(select(Position)).scalar_one()
    assert ":tap:" in order.order_key and order.order_key.endswith(":s9")
    assert stack["manager"]._key_stage(order.order_key) == 9
    assert order.reason == "one-tap buy"
    assert (position.side, position.tag, position.status) == ("UP", "tap", "open")
    with session_scope() as session:
        assert stack["portfolio"].balance(session, "paper") < 20.0  # the $2 left the paper account


async def test_tap_buy_has_no_take_profit_and_no_stop_loss():
    stack = build_stack(make_config(paper_starting_balance=20.0, stage0_stop_loss_pct=30.0, stage1_stop_loss_pct=30.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    assert (await _tap(stack, market, books)).status == "FILLED"
    manager = stack["manager"]
    for bid in (0.99, 0.01):  # far above any take-profit and far below any stop-loss
        books[market.up_token_id].bids = {bid: 1000.0}
        assert await manager.handle_exits(market, books, "paper", stack["config"]) == []
    assert manager.exit_check_needed("paper", market.market_id) is False  # the exit watcher ignores tap shares
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1  # only the buy: nothing was sold
        assert session.execute(select(Position)).scalar_one().status == "open"


# ---------------------------------------------------------------- double tap guard
async def test_second_tap_within_one_second_is_ignored_even_on_the_other_side():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    first = await _tap(stack, market, books, "UP")
    second = await stack["manager"].tap_buy(market, books, "paper", stack["config"], "DOWN", 2.0)
    assert first.status == "FILLED"
    assert (second.status, second.reason) == ("BLOCKED", "tap_cooldown")
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1
    _cooldown_over(stack["manager"])
    third = await stack["manager"].tap_buy(market, books, "paper", stack["config"], "DOWN", 2.0)
    assert third.status == "FILLED"  # a tap after the cooldown is a new buy


async def test_two_taps_at_the_same_moment_buy_exactly_once():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)
    results = await asyncio.gather(
        *(manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0) for _ in range(5))
    )
    assert sorted(r.status for r in results) == ["BLOCKED"] * 4 + ["FILLED"]
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1


async def test_a_retried_request_id_never_buys_twice():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    assert (await _tap(stack, market, books, request_id="tap-1")).status == "FILLED"
    _cooldown_over(stack["manager"])  # even after the cooldown, the same request id is refused
    again = await stack["manager"].tap_buy(market, books, "paper", stack["config"], "UP", 2.0, "tap-1")
    assert (again.status, again.reason) == ("BLOCKED", "duplicate_request")
    with session_scope() as session:
        assert len(session.execute(select(Order)).scalars().all()) == 1


async def test_a_refused_buy_still_starts_the_cooldown():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    books[market.up_token_id].asks = {}  # no ask: nothing to buy, nothing is counted
    blocked = await _tap(stack, market, books, "UP")
    assert (blocked.status, blocked.reason) == ("BLOCKED", "no_ask")
    books[market.up_token_id].asks = {0.40: 1000.0}
    assert (await stack["manager"].tap_buy(market, books, "paper", stack["config"], "UP", 2.0)).status == "FILLED"


# ---------------------------------------------------------------- the strategy is not affected
async def test_tap_shares_and_stage_shares_of_one_side_stay_separate():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.10)
    assert [r.status for r in await _stage0_buy(stack, market, books)] == ["FILLED"]  # stage 0 holds UP
    manager = stack["manager"]
    stage0_before = manager.load_stage0_holding("paper", market.market_id, 0).size
    _cooldown_over(manager)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 3.0)).status == "FILLED"

    with session_scope() as session:
        rows = session.execute(select(Position).order_by(Position.id)).scalars().all()
    assert [(p.side, p.tag) for p in rows] == [("UP", ""), ("UP", "tap")]
    assert manager.load_stage0_holding("paper", market.market_id, 0).size == pytest.approx(stage0_before)
    tap_held = manager.load_stage0_holding("paper", market.market_id, 9)
    assert tap_held is not None and tap_held.size == pytest.approx(3.0 / 0.80, rel=0.02)

    # selling the tap shares leaves stage 0 untouched, and the other way round
    books[market.up_token_id].bids = {0.78: 1000.0}
    sold = await manager.manual_sell(market, books, "paper", stack["config"], "UP", 9)
    assert sold.status == "FILLED"
    assert manager.load_stage0_holding("paper", market.market_id, 9) is None
    assert manager.load_stage0_holding("paper", market.market_id, 0).size == pytest.approx(stage0_before)
    with session_scope() as session:
        rows = session.execute(select(Position).order_by(Position.id)).scalars().all()
        sell = [o for o in session.execute(select(Order)).scalars() if manager._key_action(o.order_key) == "SELL"]
    assert [(p.tag, p.status) for p in rows] == [("", "open"), ("tap", "closed")]
    assert [o.reason for o in sell] == ["stage9_manual"]


async def test_sell_all_sells_the_tap_shares_too():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.10)
    await _stage0_buy(stack, market, books)
    manager = stack["manager"]
    _cooldown_over(manager)
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 3.0)
    books[market.up_token_id].bids = {0.78: 1000.0}
    results = await manager.manual_sell_all(market, books, "paper", stack["config"])
    assert len(results) == 2 and all(r.status == "FILLED" for r in results)
    with session_scope() as session:
        assert {p.status for p in session.execute(select(Position)).scalars()} == {"closed"}


async def test_a_working_tap_order_does_not_block_a_strategy_entry_under_policy_allow():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0, duplicate_policy="allow"))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.10)
    stage_books(stack, market, books)
    with session_scope() as session:  # a tap order that is still working on the exchange
        session.add(
            Order(
                order_key=f"paper:{market.market_id}:UP:tap:abc:s9", client_order_id="x", mode="paper",
                market_id=market.market_id, side="UP", status="SUBMITTED", requested_notional=2.0, requested_price=0.8,
            )
        )
    assert [r.status for r in await _stage0_buy(stack, market, books)] == ["FILLED"]


async def test_tap_orders_are_never_topped_up_like_a_strategy_entry():
    from app.order_manager.manager import OrderManager

    with session_scope() as session:
        session.add(
            Order(
                order_key="paper:mkt-partial:UP:tap:abc:s9", client_order_id="y", mode="paper", market_id="mkt-partial",
                side="UP", status="PARTIALLY_FILLED", requested_notional=5.0, requested_price=0.5, filled_size=2.0,
                avg_fill_price=0.5,
            )
        )
        session.add(
            Order(
                order_key="paper:mkt-partial", client_order_id="z", mode="paper", market_id="mkt-partial",
                side="DOWN", status="PARTIALLY_FILLED", requested_notional=5.0, requested_price=0.5, filled_size=2.0,
                avg_fill_price=0.5,
            )
        )
    found = OrderManager._partial_order_candidates("mkt-partial", "paper")
    assert [row[1] for row in found] == ["DOWN"]  # only the strategy order is a top-up candidate


# ---------------------------------------------------------------- bot level: risk limits, snapshot
async def test_bot_tap_buy_is_invisible_to_the_strategy_limits_and_shows_up_as_its_own_row():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))  # BTC +$150: stage 0 would buy UP
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    bot.data._books = dict(books)
    stage_cfg = bot.runtime.get()
    assert stage_cfg.max_open_positions == 1  # the strict default: one position per market

    snapshot = await bot.tap_buy(market.market_id, "DOWN", 2.0, "t-1")  # a tap on the OTHER side first
    assert snapshot["tap_buy"]["status"] == "FILLED"
    assert [(p["side"], p["tag"]) for p in snapshot["positions"]] == [("DOWN", "tap")]
    assert [(h["side"], h["stage"]) for h in snapshot["sellable"]] == [("DOWN", 9)]
    assert snapshot["account"]["trades_today"] == 0  # a tap is not one of the strategy's trades

    await _event(bot, market)  # the strategy still buys UP although a tap position is open
    with session_scope() as session:
        orders = session.execute(select(Order).order_by(Order.id)).scalars().all()
    assert [(o.side, bot.orders._key_stage(o.order_key), o.status) for o in orders] == [
        ("DOWN", 9, "FILLED"),
        ("UP", 0, "FILLED"),
    ]
    ctx = bot._risk_context(market, bot.runtime.get(), 0, (100_150.0, 100_000.0, ""))
    assert ctx.open_positions == 1 and ctx.exposed_sides == frozenset({"UP"})  # the tap position is not counted


async def test_bot_tap_buy_loss_does_not_count_towards_the_daily_loss_limit():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    bot.data._books = dict(books)
    await bot.tap_buy(market.market_id, "UP", 5.0)
    books[market.up_token_id].bids = {0.10: 1000.0}  # a terrible exit
    bot.data._books = dict(books)
    bot.orders._tap_last_s -= 5
    sold = await bot.manual_sell(market.market_id, "UP", 9)
    assert sold["manual_sell"]["status"] == "FILLED"
    assert sold["account"]["realized_pnl"] < -1.0  # the account shows the loss ...
    assert bot._risk_context(market, bot.runtime.get(), 0).daily_pnl == 0.0  # ... the strategy limits do not
    assert sold["bot"]["risk_halt"] is None


async def test_bot_tap_buy_refuses_a_stopped_bot_a_bad_amount_too_little_balance_and_the_kill_switch():
    from app.services.bot import BotError

    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot.data._books = dict(books_for(market, up_ask=0.80, down_ask=0.40))

    with pytest.raises(BotError, match="between"):
        await bot.tap_buy(market.market_id, "UP", 0.5)
    with pytest.raises(BotError, match="balance"):
        await bot.tap_buy(market.market_id, "UP", 50.0)  # the test account holds $10
    with pytest.raises(BotError, match="ended"):
        await bot.tap_buy("another-market", "UP", 2.0)
    bot._kill_switch = True
    with pytest.raises(BotError, match="running"):
        await bot.tap_buy(market.market_id, "UP", 2.0)
    bot._kill_switch = False
    bot._status = "STOPPED"
    with pytest.raises(BotError, match="running"):
        await bot.tap_buy(market.market_id, "UP", 2.0)
    with session_scope() as session:
        assert session.execute(select(Order)).scalars().all() == []  # none of them reached the exchange path


async def test_bot_tap_buy_ignores_the_buy_guard_switch_because_it_is_a_manual_buy():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot.data._books = dict(books_for(market, up_ask=0.80, down_ask=0.40))
    bot._buys_off = True  # the strategy's buys are switched off (buy guard / operator) ...
    bot._buys_off_by = "manual"
    snapshot = await bot.tap_buy(market.market_id, "UP", 2.0)
    assert snapshot["tap_buy"]["status"] == "FILLED"
