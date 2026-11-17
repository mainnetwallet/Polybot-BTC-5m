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
