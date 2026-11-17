"""The CLOB HTTP connection must stay open so a stop-loss sell does not pay a new TLS handshake."""
import asyncio

from app.config.settings import Settings
from app.live_trading import engine as eng


def test_sdk_http_pool_gets_long_keepalive_once():
    from py_clob_client_v2.http_helpers import helpers

    eng.configure_sdk_http_pool()
    pool = helpers._http_client
    assert getattr(pool, "_polybot_keepalive", False)
    assert pool._transport._pool._keepalive_expiry == eng.KEEPALIVE_EXPIRY_SECONDS
    assert eng.configure_sdk_http_pool() is False  # idempotent
    assert helpers._http_client is pool


def test_keep_connection_warm_pings_without_blocking_and_throttles():
    calls = []

    class FakeClient:
        def get_server_time(self):
            calls.append(1)
            return 1

    async def run():
        engine = eng.LiveExecutionEngine(Settings())
        engine._client = FakeClient()
        engine._orders_allowed = True
        await engine.keep_connection_warm()
        await engine.keep_connection_warm()  # throttled: no second ping
        await engine._ping_task
        await engine.close()

    asyncio.run(run())
    assert len(calls) == 1


def test_keep_connection_warm_skips_when_orders_blocked():
    class FakeClient:
        def get_server_time(self):
            raise AssertionError("must not ping")

    async def run():
        engine = eng.LiveExecutionEngine(Settings())
        engine._client = FakeClient()
        await engine.keep_connection_warm()
        await engine.close()

    asyncio.run(run())
