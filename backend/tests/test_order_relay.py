"""Only the live BUY order POST (/order) goes through the relay Worker; sells and everything else stay direct."""

from __future__ import annotations

import httpx
import pytest
from py_clob_client_v2.exceptions import PolyApiException, PolyException
from py_clob_client_v2.http_helpers import helpers

from app.config.settings import Settings
from app.live_trading.engine import _make_client_class, classify_post_exception, order_relay_url

HOST = "https://clob.polymarket.com"
RELAY = "https://polybot.example.workers.dev"


def _settings(**kw) -> Settings:
    return Settings(_env_file=None, **kw)


def _client(relay_url: str = ""):
    return _make_client_class(relay_url)(host=HOST, chain_id=137)


@pytest.fixture
def http(monkeypatch):
    seen: list[str] = []
    state = {"fail_relay": None}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        if state["fail_relay"] and request.url.host == "polybot.example.workers.dev":
            raise state["fail_relay"]
        return httpx.Response(200, json={"ok": True})

    monkeypatch.setattr(helpers, "_http_client", httpx.Client(transport=httpx.MockTransport(handler)))
    return seen, state


def test_relay_url_building():
    on = dict(order_proxy_enabled=True)
    assert order_relay_url(_settings(order_proxy_url=RELAY, **on)) == f"{RELAY}/{HOST}"
    assert order_relay_url(_settings(order_proxy_url=RELAY + "/", **on)) == f"{RELAY}/{HOST}"
    assert order_relay_url(_settings(order_proxy_url="", **on)) == ""
    # CLOB_BASE_URL already points at the relay: nothing extra to do
    assert order_relay_url(_settings(order_proxy_url=RELAY, clob_base_url=f"{RELAY}/{HOST}", **on)) == ""


def test_switch_off_means_direct_whatever_the_url():
    assert order_relay_url(_settings(order_proxy_enabled=False, order_proxy_url=RELAY)) == ""


def test_proxy_is_off_by_default():
    assert Settings.model_fields["order_proxy_enabled"].default is False


def test_buy_order_post_goes_through_relay_and_other_posts_stay_direct(http):
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    with client.relay_for_buy():
        assert client._post(f"{HOST}/order", headers={}, data="{}") == {"ok": True}
        client._post(f"{HOST}/auth/api-key", headers={})
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/auth/api-key"]


def test_sell_order_post_stays_direct_even_with_relay_on(http):
    """Take-profit / stop-loss sells are posted outside relay_for_buy(): always direct."""
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    client._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{HOST}/order"]


def test_relay_flag_resets_after_the_buy(http):
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    with client.relay_for_buy():
        client._post(f"{HOST}/order", headers={}, data="{}")
    client._post(f"{HOST}/order", headers={}, data="{}")  # a sell right after the buy
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/order"]


def test_gets_and_deletes_stay_direct(http):
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    client._get(f"{HOST}/time")
    client._delete(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{HOST}/time", f"{HOST}/order"]


def test_no_relay_means_direct(http):
    seen, _ = http
    client = _client("")
    with client.relay_for_buy():
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{HOST}/order"]


@pytest.mark.parametrize("exc", [httpx.ConnectError("refused"), httpx.ConnectTimeout("slow")])
def test_unreachable_relay_never_goes_direct(http, exc):
    """No fallback: with the relay on, a buy only ever leaves through it."""
    seen, state = http
    state["fail_relay"] = exc
    client = _client(f"{RELAY}/{HOST}")
    with pytest.raises(PolyException) as info, client.relay_for_buy():
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert not isinstance(info.value, PolyApiException)
    assert seen == [f"{RELAY}/{HOST}/order"]  # nothing was posted direct
    # ...and it is a definite "not sent", so the order is retried instead of waiting for reconciliation
    report = classify_post_exception(info.value)
    assert report.status == "REJECTED"


def test_read_timeout_is_an_unknown_outcome(http):
    """The order may already be on the exchange: unknown outcome (reconciled), never re-posted."""
    seen, state = http
    state["fail_relay"] = httpx.ReadTimeout("no answer")
    client = _client(f"{RELAY}/{HOST}")
    with pytest.raises(PolyApiException) as info, client.relay_for_buy():
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{RELAY}/{HOST}/order"]
    assert classify_post_exception(info.value).status == "SUBMITTED"


def test_relay_http_error_surfaces_status(http, monkeypatch):
    def handler(request):
        return httpx.Response(400, json={"error": "not enough balance"})

    monkeypatch.setattr(helpers, "_http_client", httpx.Client(transport=httpx.MockTransport(handler)))
    client = _client(f"{RELAY}/{HOST}")
    with pytest.raises(PolyApiException) as info, client.relay_for_buy():
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert info.value.status_code == 400


def test_ping_relay_is_a_get_time_through_the_relay(http):
    seen, _ = http
    _client(f"{RELAY}/{HOST}").ping_relay()
    assert seen == [f"{RELAY}/{HOST}/time"]


def _engine_and_client(relay_on: bool):
    """Real LiveExecutionEngine buy/sell code paths; only the SDK signing step is faked."""
    from app.live_trading.engine import LiveExecutionEngine

    engine = LiveExecutionEngine(
        _settings(order_proxy_enabled=relay_on, order_proxy_url=RELAY)
    )
    engine._ensure_conditional_allowance = lambda client, token_id: None
    base = _make_client_class(order_relay_url(engine._settings))

    class FakeSigningClient(base):
        def get_tick_size(self, token_id):
            return "0.01"

        def create_and_post_market_order(self, args, options, order_type, defer_exec):
            return self._post(f"{HOST}/order", headers={}, data="{}")

    return engine, FakeSigningClient(host=HOST, chain_id=137)


def _buy_request():
    from app.execution.base import OrderRequest

    return OrderRequest("k", "c", "m", "BUY", "123", 5.0, 0.6, 0)


def _sell_request():
    from app.execution.base import SellRequest

    return SellRequest("k", "c", "m", "SELL", "123", 5.0, 0.4, 0)


def test_engine_buy_is_relayed_and_sell_is_direct(http):
    seen, _ = http
    engine, client = _engine_and_client(relay_on=True)
    engine._place_sync(client, _buy_request())
    engine._place_sell_sync(client, _sell_request())
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/order"]


def test_engine_everything_direct_when_switch_off(http):
    seen, _ = http
    engine, client = _engine_and_client(relay_on=False)
    engine._place_sync(client, _buy_request())
    engine._place_sell_sync(client, _sell_request())
    assert seen == [f"{HOST}/order", f"{HOST}/order"]


def test_engine_relay_down_refuses_buy_but_sell_still_goes_direct(http):
    seen, state = http
    state["fail_relay"] = httpx.ConnectError("refused")
    engine, client = _engine_and_client(relay_on=True)
    assert engine._place_sync(client, _buy_request()).status == "REJECTED"
    engine._place_sell_sync(client, _sell_request())
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/order"]


def test_concurrent_buy_and_sell_do_not_mix_routes(http):
    """A buy and sells running at the same time on different threads keep their own route."""
    from concurrent.futures import ThreadPoolExecutor

    seen, _ = http
    engine, client = _engine_and_client(relay_on=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(engine._place_sync, client, _buy_request()) for _ in range(20)]
        futures += [pool.submit(engine._place_sell_sync, client, _sell_request()) for _ in range(20)]
        for f in futures:
            f.result()
    assert seen.count(f"{RELAY}/{HOST}/order") == 20
    assert seen.count(f"{HOST}/order") == 20
