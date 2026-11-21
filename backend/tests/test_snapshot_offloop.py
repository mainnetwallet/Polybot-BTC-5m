"""The dashboard snapshot must never keep the event loop away from a one-tap click.

`snapshot()` is built twice a second for an open dashboard and again for every tap / sell reply. Its database
reads used to run on the event loop itself: with a hosted database (one network round trip per query) the loop
stood still for 100-400 ms each time, so a click that arrived meanwhile was not even read.
"""

from __future__ import annotations

import asyncio
import threading
import time

import pytest

from app.database.session import session_scope
from app.order_manager.manager import _floor_shares
from app.portfolio.service import TAP_STAGE

from .helpers import books_for, make_market
from .test_stage0 import _running_bot


def _bot_with_two_tap_positions():
    market = make_market(remaining=150)
    bot = _running_bot(market, btc=(100_000.0, 100_000.0, ""))
    bot.data._books = dict(books_for(market, up_ask=0.50, down_ask=0.50))
    return market, bot


async def _tap_both_sides(bot, market) -> None:
    for side in ("UP", "DOWN"):
        bot.orders._tap_last_s -= 5.0  # the one-second tap cooldown is not what is tested here
        await bot.tap_buy(market.market_id, side, 2.0)


async def test_snapshot_reads_the_database_in_a_worker_thread():
    market, bot = _bot_with_two_tap_positions()
    seen: list[int] = []
    original = bot._snapshot_db

    def spy(*args, **kwargs):
        seen.append(threading.get_ident())
        return original(*args, **kwargs)

    bot._snapshot_db = spy
    await bot.snapshot()
    assert seen, "the snapshot did not read the database"
    assert seen[0] != threading.get_ident(), "the snapshot read the database on the event loop"


async def test_a_slow_database_does_not_stall_the_event_loop_during_a_snapshot():
    market, bot = _bot_with_two_tap_positions()
    original = bot.portfolio.pnl_summary

    def slow_pnl_summary(*args, **kwargs):
        time.sleep(0.3)  # a database that answers slowly (hosted Postgres): a blocking wait inside the query
        return original(*args, **kwargs)

    bot.portfolio.pnl_summary = slow_pnl_summary
    gaps: list[float] = []
    stop = False

    async def heartbeat() -> None:  # stands in for "a tap click arrives and has to be served"
        last = time.perf_counter()
        while not stop:
            await asyncio.sleep(0.005)
            now = time.perf_counter()
            gaps.append(now - last)
            last = now

    beat = asyncio.create_task(heartbeat())
    await bot.snapshot()  # takes >= 0.6 s here (two slow pnl summaries), all of it in the worker thread
    stop = True
    await beat
    assert max(gaps) < 0.1, f"the event loop stood still for {max(gaps) * 1000:.0f} ms during the snapshot"


async def test_snapshot_sellable_rows_are_the_same_as_the_direct_holding_lookup():
    market, bot = _bot_with_two_tap_positions()
    await _tap_both_sides(bot, market)
    snapshot = await bot.snapshot()
    rows = {(row["side"], row["stage"]): row for row in snapshot["sellable"]}
    assert set(rows) == {("UP", TAP_STAGE), ("DOWN", TAP_STAGE)}
    for side in ("UP", "DOWN"):
        direct = bot.orders.load_stage0_holding("paper", market.market_id, TAP_STAGE, side)
        assert direct is not None
        assert rows[(side, TAP_STAGE)]["size"] == _floor_shares(direct.size)
        assert rows[(side, TAP_STAGE)]["entry_price"] == pytest.approx(direct.cost)


async def test_holding_lookup_with_rows_already_read_gives_the_same_answer():
    from sqlalchemy import select

    from app.database.models import Order, Position

    market, bot = _bot_with_two_tap_positions()
    await _tap_both_sides(bot, market)
    manager = bot.orders
    with session_scope() as session:
        orders = session.execute(select(Order).where(Order.mode == "paper", Order.market_id == market.market_id)).scalars().all()
        positions = {
            (p.side, p.tag or ""): p
            for p in session.execute(select(Position).where(Position.mode == "paper", Position.status == "open")).scalars().all()
        }
        for side in ("UP", "DOWN"):
            direct = manager.stage0_holding_in_session(session, "paper", market.market_id, TAP_STAGE, side)
            reused = manager.stage0_holding_in_session(
                session, "paper", market.market_id, TAP_STAGE, side, orders=orders, positions=positions
            )
            assert reused == direct and reused is not None
        # a stage that holds nothing: still None with the rows handed in
        assert manager.stage0_holding_in_session(session, "paper", market.market_id, 0, None, orders=orders, positions=positions) is None
