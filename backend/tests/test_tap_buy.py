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


async def test_sell_all_leaves_the_tap_shares_and_sell_all_tap_leaves_the_stages():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.10)
    await _stage0_buy(stack, market, books)
    manager = stack["manager"]
    _cooldown_over(manager)
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 3.0)
    books[market.up_token_id].bids = {0.78: 1000.0}

    results = await manager.manual_sell_all(market, books, "paper", stack["config"])  # "Sell all"
    assert len(results) == 1 and results[0].status == "FILLED"
    assert manager.load_stage0_holding("paper", market.market_id, 0) is None
    assert manager.load_stage0_holding("paper", market.market_id, 9) is not None  # tap shares still held
    with session_scope() as session:
        rows = session.execute(select(Position).order_by(Position.id)).scalars().all()
    assert [(p.tag, p.status) for p in rows] == [("", "closed"), ("tap", "open")]

    results = await manager.manual_sell_all(market, books, "paper", stack["config"], "tap")  # "Sell all tap"
    assert len(results) == 1 and results[0].status == "FILLED"
    assert manager.load_stage0_holding("paper", market.market_id, 9) is None
    assert await manager.manual_sell_all(market, books, "paper", stack["config"], "tap") == []  # nothing left


async def test_sell_all_tap_does_not_touch_the_stages():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.10)
    await _stage0_buy(stack, market, books)
    manager = stack["manager"]
    books[market.up_token_id].bids = {0.78: 1000.0}
    assert await manager.manual_sell_all(market, books, "paper", stack["config"], "tap") == []
    assert manager.load_stage0_holding("paper", market.market_id, 0) is not None


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
    with pytest.raises(BotError, match="kill switch"):
        await bot.tap_buy(market.market_id, "UP", 2.0)
    bot._kill_switch = False
    with session_scope() as session:
        assert session.execute(select(Order)).scalars().all() == []  # none of them reached the exchange path


async def test_bot_tap_buy_works_with_the_bot_stopped_in_paper_mode():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot._status = "STOPPED"  # the strategy is off: a one-tap buy does not need it
    bot.data._books = dict(books_for(market, up_ask=0.80, down_ask=0.40))
    snapshot = await bot.tap_buy(market.market_id, "UP", 2.0)
    assert snapshot["tap_buy"]["status"] == "FILLED"
    assert snapshot["bot"]["status"] == "STOPPED"
    assert snapshot["bot"]["manual_ready"] is True


async def test_bot_tap_buy_in_live_mode_needs_live_enabled_in_env_not_a_bot_start(monkeypatch):
    from app.services.bot import BotError

    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot._status = "STOPPED"
    bot._mode = "live"  # e.g. restored from the database after a restart
    cls = type(bot.settings)
    monkeypatch.setattr(bot.settings, "live_trading_enabled", False)  # .env does not allow live trading
    monkeypatch.setattr(bot.settings, "polymarket_geo_verified", True)
    monkeypatch.setattr(cls, "live_credentials_present", property(lambda self: True))
    with pytest.raises(BotError, match="not enabled"):
        await bot.tap_buy(market.market_id, "UP", 2.0)
    monkeypatch.setattr(bot.settings, "live_trading_enabled", True)
    assert bot._manual_ready() is True  # no bot start, no confirmation phrase needed
    bot._kill_switch = True  # the kill switch beats everything
    assert bot._manual_ready() is False


async def test_bot_tap_buy_ignores_the_buy_guard_switch_because_it_is_a_manual_buy():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot.data._books = dict(books_for(market, up_ask=0.80, down_ask=0.40))
    bot._buys_off = True  # the strategy's buys are switched off (buy guard / operator) ...
    bot._buys_off_by = "manual"
    snapshot = await bot.tap_buy(market.market_id, "UP", 2.0)
    assert snapshot["tap_buy"]["status"] == "FILLED"


# ---------------------------------------------------------------- UP and DOWN one-tap at the same time
async def test_tap_up_and_tap_down_are_each_sellable_by_their_own_side():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    manager = _tap_ready(stack, market, books)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 3.0)).status == "FILLED"
    _cooldown_over(manager)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 3.0)).status == "FILLED"

    # each side has its own holding (the DOWN one used to be hidden behind the first UP buy)
    up = manager.load_stage0_holding("paper", market.market_id, 9, "UP")
    down = manager.load_stage0_holding("paper", market.market_id, 9, "DOWN")
    assert up is not None and up.side == "UP" and up.size == pytest.approx(3.0 / 0.80, rel=0.02)
    assert down is not None and down.side == "DOWN" and down.size == pytest.approx(3.0 / 0.40, rel=0.02)
    assert down.cost == pytest.approx(0.40, abs=0.01)

    # selling DOWN by hand leaves the UP tap shares alone
    books[market.down_token_id].bids = {0.60: 1000.0}
    sold = await manager.manual_sell(market, books, "paper", stack["config"], "DOWN", 9)
    assert sold.status == "FILLED"
    assert manager.load_stage0_holding("paper", market.market_id, 9, "DOWN") is None
    assert manager.load_stage0_holding("paper", market.market_id, 9, "UP") is not None
    with session_scope() as session:
        rows = session.execute(select(Position).order_by(Position.id)).scalars().all()
    assert [(p.side, p.tag, p.status) for p in rows] == [("UP", "tap", "open"), ("DOWN", "tap", "closed")]


async def test_entry_of_a_tap_holding_ignores_shares_already_sold_in_the_same_market():
    """Cheap tap buy, sold; a dearer tap buy after it: the shares held cost the dearer price, not a blend."""
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    manager = _tap_ready(stack, market, books)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 4.0)).status == "FILLED"
    books[market.up_token_id].bids = {0.85: 1000.0}
    assert (await manager.manual_sell(market, books, "paper", stack["config"], "UP", 9)).status == "FILLED"
    assert manager.load_stage0_holding("paper", market.market_id, 9, "UP") is None

    _cooldown_over(manager)
    books[market.up_token_id].asks = {0.99: 1000.0}
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 4.0)).status == "FILLED"

    held = manager.load_stage0_holding("paper", market.market_id, 9, "UP")
    assert held is not None
    assert held.cost == pytest.approx(0.99, abs=0.01)  # used to show the blend of 0.80 and 0.99
    with session_scope() as session:
        position = session.execute(select(Position)).scalar_one()
        basis = manager.stage0_basis(session, "paper", market.market_id, "UP", 9)
    assert position.avg_price == pytest.approx(0.99, abs=0.01)
    assert basis is not None and basis[0] == pytest.approx(0.99, abs=0.01)


async def test_a_partial_sell_keeps_the_average_cost_of_the_rest():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    manager = _tap_ready(stack, market, books)
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 4.0)
    _cooldown_over(manager)
    books[market.up_token_id].asks = {0.90: 1000.0}
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 4.0)
    held = manager.load_stage0_holding("paper", market.market_id, 9, "UP")
    with session_scope() as session:
        position = session.execute(select(Position)).scalar_one()
    assert held is not None and held.cost == pytest.approx(position.avg_price, abs=0.001)


async def test_sell_all_tap_sells_both_up_and_down_tap_shares():
    stack = build_stack(make_config(**STAGE0, trade_amount_usdc=4.0, paper_starting_balance=40.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.40)
    manager = _tap_ready(stack, market, books)
    await manager.tap_buy(market, books, "paper", stack["config"], "UP", 3.0)
    _cooldown_over(manager)
    await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 3.0)
    books[market.up_token_id].bids = {0.78: 1000.0}
    books[market.down_token_id].bids = {0.60: 1000.0}

    results = await manager.manual_sell_all(market, books, "paper", stack["config"], "tap")
    assert sorted(r.side for r in results) == ["DOWN", "UP"]
    assert all(r.status == "FILLED" for r in results)
    assert manager.load_stage0_holding("paper", market.market_id, 9, "UP") is None
    assert manager.load_stage0_holding("paper", market.market_id, 9, "DOWN") is None
    with session_scope() as session:
        rows = session.execute(select(Position)).scalars().all()
    assert {(p.side, p.status) for p in rows} == {("UP", "closed"), ("DOWN", "closed")}


# ---------------------------------------------------------------- a side under 40 cents cannot be tapped
@pytest.mark.parametrize("ask", [0.01, 0.10, 0.39])
async def test_tap_buy_is_blocked_when_the_ask_is_under_40_cents(ask):
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=ask)
    result = await _tap(stack, market, books, "DOWN", 1.0)
    assert (result.status, result.reason) == ("BLOCKED", "ask_too_low")
    with session_scope() as session:
        assert session.execute(select(Order)).first() is None  # nothing was even reserved
        assert stack["portfolio"].balance(session, "paper") == pytest.approx(20.0)


async def test_a_refused_cheap_tap_does_not_start_the_cooldown_and_the_other_side_still_buys():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.01)
    manager = _tap_ready(stack, market, books)
    first = await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 1.0)
    assert first.reason == "ask_too_low"
    second = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 1.0)  # right away: no cooldown
    assert second.status == "FILLED"


async def test_tap_buy_at_exactly_40_cents_is_allowed():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.60, down_ask=0.40)
    assert (await _tap(stack, market, books, "DOWN", 1.0)).status == "FILLED"


async def test_the_owner_can_lower_or_remove_the_floor_with_min_ask():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.80, down_ask=0.30)
    manager = _tap_ready(stack, market, books)
    # no min_ask sent: the 40 cent default applies
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 1.0)).reason == "ask_too_low"
    # the owner set the floor to 25 cents: 30 cents is fine now
    ok = await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 1.0, None, 0.25)
    assert ok.status == "FILLED"
    _cooldown_over(manager)
    # ... and 20 cents is still under his floor
    books[market.down_token_id].asks = {0.20: 1000.0}
    low = await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 1.0, None, 0.25)
    assert low.reason == "ask_too_low"
    # a floor of 0 = no floor at all
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "DOWN", 1.0, None, 0.0)).status == "FILLED"


async def test_min_ask_can_also_raise_the_floor():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.45, down_ask=0.55)
    manager = _tap_ready(stack, market, books)
    blocked = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 1.0, None, 0.50)
    assert blocked.reason == "ask_too_low"


def test_tap_buy_request_validates_min_ask():
    from pydantic import ValidationError

    from app.api.schemas import TapBuyRequest

    base = {"market_id": "m", "side": "UP", "amount": 1}
    assert TapBuyRequest(**base).min_ask is None
    assert TapBuyRequest(**base, min_ask=0).min_ask == 0
    assert TapBuyRequest(**base, min_ask=0.4).min_ask == 0.4
    for bad in (-0.1, 1.0, 5):
        with pytest.raises(ValidationError):
            TapBuyRequest(**base, min_ask=bad)


# ---------------------------------------------------------------- latency: nothing slow sits before the order
async def test_the_order_row_is_written_while_the_buy_is_on_its_way_not_before_it():
    """A slow database write must not delay the exchange call: both start together, the row still ends up saved."""
    import time

    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)

    events: list[str] = []
    real_reserve = manager._reserve_tap_order

    def slow_reserve(*args, **kwargs):
        events.append("reserve_start")
        time.sleep(0.2)  # a slow database
        result = real_reserve(*args, **kwargs)
        events.append("reserve_done")
        return result

    real_provider = manager._executor_provider

    def spy_provider(mode):
        executor = real_provider(mode)
        real_place_buy = executor.place_buy

        async def spy_place_buy(request):
            events.append("exchange_call")
            return await real_place_buy(request)

        executor.place_buy = spy_place_buy  # type: ignore[method-assign]
        return executor

    manager._reserve_tap_order = slow_reserve  # type: ignore[method-assign]
    manager._executor_provider = spy_provider
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)

    assert result.status == "FILLED"
    assert events.index("exchange_call") < events.index("reserve_done")  # the order did not wait for the row
    with session_scope() as session:
        order = session.execute(select(Order)).scalar_one()
        assert order.id == result.order_id and order.filled_size > 0  # and the row holds the fill


async def test_a_sell_right_after_a_full_tap_fill_reads_no_database_before_it_sends():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)).status == "FILLED"
    await asyncio.gather(*manager._background_tasks)  # the background read right after the fill
    assert ("paper", market.market_id, "UP") in manager._tap_ready

    calls: list[str] = []
    real_load = manager.load_stage0_holding

    def spy_load(*args, **kwargs):
        calls.append("load")
        return real_load(*args, **kwargs)

    manager.load_stage0_holding = spy_load  # type: ignore[method-assign]
    books[market.up_token_id].bids = {0.38: 1000.0}
    sold = await manager.manual_sell(market, books, "paper", stack["config"], "UP", 9)
    assert sold.status in {"FILLED", "PARTIALLY_FILLED"} and sold.filled_size > 0
    assert calls == ["load"]  # only the reload AFTER the sell: nothing was read before the order left
    assert ("paper", market.market_id, "UP") not in manager._tap_ready  # used once


async def test_a_new_tap_buy_or_sell_voids_the_ready_holding():
    stack = build_stack(make_config(paper_starting_balance=50.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)
    key = ("paper", market.market_id, "UP")
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)).status == "FILLED"
    await asyncio.gather(*manager._background_tasks)
    first_size = manager._tap_ready[key].size

    _cooldown_over(manager)
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)).status == "FILLED"
    await asyncio.gather(*manager._background_tasks)
    assert manager._tap_ready[key].size == pytest.approx(first_size * 2, rel=0.05)  # re-read after the 2nd buy

    # the sell sells everything that is really held (both buys), then nothing is left
    books[market.up_token_id].bids = {0.38: 1000.0}
    sold = await manager.manual_sell(market, books, "paper", stack["config"], "UP", 9)
    assert sold.filled_size == pytest.approx(first_size * 2, rel=0.05)
    again = await manager.manual_sell(market, books, "paper", stack["config"], "UP", 9)
    assert (again.status, again.reason) == ("BLOCKED", "no_holding")


async def test_a_failed_order_row_write_never_loses_the_fill():
    """The row is written in parallel with the buy: if that write fails once, it is retried and the fill is saved."""
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)
    real_reserve = manager._reserve_tap_order
    calls = {"n": 0}

    def flaky_reserve(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("database busy")
        return real_reserve(*args, **kwargs)

    manager._reserve_tap_order = flaky_reserve  # type: ignore[method-assign]
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)
    assert result.status == "FILLED" and result.order_id is not None
    with session_scope() as session:
        order = session.execute(select(Order)).scalar_one()
        position = session.execute(select(Position)).scalar_one()
    assert order.id == result.order_id and order.filled_size > 0
    assert position.side == "UP" and position.tag == "tap"


async def test_if_the_order_row_cannot_be_saved_at_all_the_tap_still_answers_with_the_fill():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)

    def broken_reserve(*args, **kwargs):
        raise RuntimeError("database down")

    manager._reserve_tap_order = broken_reserve  # type: ignore[method-assign]
    result = await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)  # must not raise
    assert result.reason == "order_row_missing" and result.filled_size > 0 and result.order_id is None
    assert result.error and "exchange" in result.error


async def test_a_tap_buy_request_is_not_serialised_behind_the_order_lock():
    stack = build_stack(make_config(paper_starting_balance=20.0))
    market = make_market(remaining=150)
    books = books_for(market, up_ask=0.40, down_ask=0.60)
    manager = _tap_ready(stack, market, books)
    seen = []
    real_provider = manager._executor_provider

    def spy_provider(mode):
        executor = real_provider(mode)
        real_place_buy = executor.place_buy

        async def spy_place_buy(request):
            seen.append(request.exclusive)
            return await real_place_buy(request)

        executor.place_buy = spy_place_buy  # type: ignore[method-assign]
        return executor

    manager._executor_provider = spy_provider
    assert (await manager.tap_buy(market, books, "paper", stack["config"], "UP", 2.0)).status == "FILLED"
    assert seen == [False]  # a tap asks for no lock; strategy buys keep the default (True)


async def test_bot_tap_buy_ignores_the_strategy_risk_halt():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_150.0, 100_000.0, ""))
    bot.data._books = dict(books_for(market, up_ask=0.80, down_ask=0.40))
    bot._risk_halt_reason = "daily loss limit reached"  # the strategy is halted ...
    snapshot = await bot.tap_buy(market.market_id, "UP", 2.0)  # ... the operator's own buy is not
    assert snapshot["tap_buy"]["status"] == "FILLED"
