"""Orders record how they were sent (proxy / direct) so the user can see it."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import httpx
import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text
from sqlalchemy import select

from app.config.settings import Settings
from app.database.models import Order
from app.database.session import session_scope
from app.execution.base import ExecutionReport
from app.live_trading.engine import LiveExecutionEngine, _make_client_class, order_relay_url
from app.paper_trading.engine import PaperExecutionEngine

from .helpers import books_for, build_stack, make_market
from .test_order_flow import _orders, _submit
from .test_order_relay import HOST, RELAY, _buy_request, _sell_request, http  # noqa: F401


def _live(relay_on: bool):
    engine = LiveExecutionEngine(Settings(_env_file=None, order_proxy_enabled=relay_on, order_proxy_url=RELAY))
    engine._ensure_conditional_allowance = lambda client, token_id: None
    base = _make_client_class(order_relay_url(engine._settings))

    class FakeSigningClient(base):
        def get_tick_size(self, token_id):
            return "0.01"

        def create_and_post_market_order(self, args, options, order_type, defer_exec):
            return self._post(f"{HOST}/order", headers={}, data="{}")

    return engine, FakeSigningClient(host=HOST, chain_id=137)


def test_live_buy_report_says_proxy_when_relay_is_on(http):  # noqa: F811
    engine, client = _live(relay_on=True)
    assert engine._place_sync(client, _buy_request()).route == "proxy"


def test_live_buy_report_says_direct_when_relay_is_off(http):  # noqa: F811
    engine, client = _live(relay_on=False)
    assert engine._place_sync(client, _buy_request()).route == "direct"


def test_relay_down_buy_is_still_tagged_proxy(http):  # noqa: F811
    _, state = http
    state["fail_relay"] = httpx.ConnectError("refused")
    engine, client = _live(relay_on=True)
    report = engine._place_sync(client, _buy_request())
    assert report.status == "REJECTED"
    assert report.route == "proxy"  # it was sent to the proxy, which could not be reached


def test_live_sell_is_always_direct(http):  # noqa: F811
    import asyncio

    engine, client = _live(relay_on=True)
    engine._orders_allowed = True
    engine._client = client
    report = asyncio.run(engine.place_sell(_sell_request()))
    assert report.route == "direct"


async def test_paper_orders_have_no_route():
    stack = build_stack()
    market = make_market(remaining=55)
    await _submit(stack, market, books_for(market, up_ask=0.90, down_ask=0.10))
    assert _orders()[0].route is None


async def test_route_is_saved_on_the_order(monkeypatch):
    original = PaperExecutionEngine.place_buy

    async def tagged(self, request):
        report = await original(self, request)
        report.route = "proxy"
        return report

    monkeypatch.setattr(PaperExecutionEngine, "place_buy", tagged)
    stack = build_stack()
    market = make_market(remaining=55)
    await _submit(stack, market, books_for(market, up_ask=0.90, down_ask=0.10))
    assert _orders()[0].route == "proxy"


def test_api_order_rows_carry_route():
    import inspect as pyinspect

    from app.services import bot

    source = pyinspect.getsource(bot)
    assert source.count('"route": o.route') == 2  # open orders + recent orders


def _load_migration():
    path = Path(__file__).resolve().parents[1] / "migrations" / "versions" / "0002_order_route.py"
    spec = importlib.util.spec_from_file_location("mig0002", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_adds_route_to_an_existing_database_and_is_idempotent():
    migration = _load_migration()
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE orders (id INTEGER PRIMARY KEY, latency_ms FLOAT)"))
        conn.execute(text("INSERT INTO orders (id, latency_ms) VALUES (1, 12.0)"))
        with Operations.context(MigrationContext.configure(conn)):
            migration.upgrade()
            migration.upgrade()  # a fresh DB already has the column: must not fail
        columns = [c["name"] for c in inspect(conn).get_columns("orders")]
        assert "route" in columns
        assert conn.execute(text("SELECT latency_ms, route FROM orders")).one() == (12.0, None)


def test_ensure_schema_upgrades_an_already_migrated_database(monkeypatch):
    from app.database import migrate

    calls = []
    monkeypatch.setattr(migrate, "current_revision", lambda: "0001_initial")
    monkeypatch.setattr(migrate, "upgrade_database", lambda: calls.append("upgrade"))
    migrate.ensure_schema()
    assert calls == ["upgrade"]
