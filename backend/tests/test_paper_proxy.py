"""A paper BUY goes through the order relay like a live one (no order is sent, but the relay is used)."""

from __future__ import annotations

import httpx

from app import clock
from app.config.settings import Settings
from app.execution.base import OrderRequest
from app.paper_trading.engine import PaperExecutionEngine

from .helpers import make_book
from .test_order_relay import HOST, RELAY


def _engine(enabled: bool, handler=None):
    settings = Settings(_env_file=None, order_proxy_enabled=enabled, order_proxy_url=RELAY)
    book = make_book("123", "UP", asks=[(0.55, 100.0)])
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler)) if handler else None
    return PaperExecutionEngine(settings, lambda side: book, relay_client=client)


def _request() -> OrderRequest:
    return OrderRequest("k", "c", "m", "UP", "123", 5.0, 0.60, clock.now_ms())


async def test_paper_buy_goes_through_the_relay():
    seen: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, str(request.url)))
        return httpx.Response(200, json={"time": 1})

    report = await _engine(True, handler).place_buy(_request())
    assert seen == [("GET", f"{RELAY}/{HOST}/time")]  # the relay hop, nothing else
    assert report.route == "proxy"
    assert report.status == "FILLED"
    assert report.fills


async def test_paper_buy_is_refused_when_the_relay_is_down():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    report = await _engine(True, handler).place_buy(_request())
    assert report.status == "REJECTED"
    assert report.route == "proxy"
    assert not report.fills  # no direct fallback: nothing is filled


async def test_paper_buy_is_refused_on_a_relay_5xx():
    report = await _engine(True, lambda request: httpx.Response(502)).place_buy(_request())
    assert report.status == "REJECTED"
    assert report.route == "proxy"
    assert not report.fills


async def test_paper_buy_makes_no_relay_call_when_the_proxy_is_off():
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("the relay must not be called")

    report = await _engine(False, handler).place_buy(_request())
    assert report.route is None
    assert report.status == "FILLED"


async def test_paper_sell_never_uses_the_relay():
    from app.execution.base import SellRequest

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("sells are always direct")

    engine = _engine(True, handler)
    report = await engine.place_sell(SellRequest("k", "c", "m", "UP", "123", 5.0, 0.40, clock.now_ms()))
    assert report.route is None
