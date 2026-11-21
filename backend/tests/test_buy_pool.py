"""A live buy must run on its own thread pool, never on the shared default pool."""
import asyncio
import threading

from app.config.settings import Settings
from app.live_trading import engine as eng
from app.execution.base import ExecutionReport


def test_place_buy_runs_on_dedicated_buy_pool():
    seen = []

    async def run():
        engine = eng.LiveExecutionEngine(Settings())
        engine._orders_allowed = True

        async def fake_client():
            return object()

        def fake_place_sync(client, request):
            seen.append(threading.current_thread().name)
            return ExecutionReport(status="REJECTED", error="test")

        engine._get_client = fake_client
        engine._place_sync = fake_place_sync
        request = type("R", (), {"observed_at_ms": 0})()
        report = await engine.place_buy(request)
        await engine.close()
        return report

    report = asyncio.run(run())
    assert report.status == "REJECTED"
    assert seen and seen[0].startswith("live-buy")


def _engine_with_fake_exchange():
    engine = eng.LiveExecutionEngine(Settings())
    engine._orders_allowed = True

    async def fake_client():
        return object()

    def fake_place_sync(client, request):
        return ExecutionReport(status="REJECTED", error="test")

    engine._get_client = fake_client
    engine._place_sync = fake_place_sync
    return engine


def test_a_one_tap_buy_does_not_wait_for_the_order_lock():
    """A strategy buy holding the order lock must not hold a one-tap buy back; a strategy buy still waits."""

    async def run():
        engine = _engine_with_fake_exchange()
        tap = type("R", (), {"observed_at_ms": 0, "exclusive": False})()
        strategy = type("R", (), {"observed_at_ms": 0, "exclusive": True})()
        async with engine._order_lock:  # a strategy buy is "on its way"
            tap_report = await asyncio.wait_for(engine.place_buy(tap), timeout=1.0)  # goes straight through
            blocked = asyncio.ensure_future(engine.place_buy(strategy))
            await asyncio.sleep(0.05)
            still_waiting = not blocked.done()  # the strategy buy keeps queueing behind the lock
        strategy_report = await asyncio.wait_for(blocked, timeout=1.0)
        await engine.close()
        return tap_report, still_waiting, strategy_report

    tap_report, still_waiting, strategy_report = asyncio.run(run())
    assert tap_report.status == "REJECTED" and still_waiting and strategy_report.status == "REJECTED"


def test_a_one_tap_buy_is_still_blocked_when_live_orders_are_not_allowed():
    async def run():
        engine = _engine_with_fake_exchange()
        engine._orders_allowed = False
        tap = type("R", (), {"observed_at_ms": 0, "exclusive": False})()
        report = await engine.place_buy(tap)
        await engine.close()
        return report

    assert "blocked" in asyncio.run(run()).error
