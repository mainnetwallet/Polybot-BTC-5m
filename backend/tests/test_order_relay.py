"""Only the live order POST (/order) goes through the relay Worker; everything else stays direct."""

from __future__ import annotations

import httpx
import pytest
from py_clob_client_v2.exceptions import PolyApiException
from py_clob_client_v2.http_helpers import helpers

from app.config.settings import Settings
from app.live_trading.engine import _make_client_class, order_relay_url

HOST = "https://clob.polymarket.com"
RELAY = "https://polybot.example.workers.dev"


def _settings(**kw) -> Settings:
    return Settings(_env_file=None, **kw)


def _client(relay_url: str = "", fallback: bool = True):
    return _make_client_class(relay_url, fallback)(host=HOST, chain_id=137)


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
    assert order_relay_url(_settings(order_proxy_url=RELAY)) == f"{RELAY}/{HOST}"
    assert order_relay_url(_settings(order_proxy_url=RELAY + "/")) == f"{RELAY}/{HOST}"
    assert order_relay_url(_settings(order_proxy_url="")) == ""
    # CLOB_BASE_URL already points at the relay: nothing extra to do
    assert order_relay_url(_settings(order_proxy_url=RELAY, clob_base_url=f"{RELAY}/{HOST}")) == ""


def test_order_post_goes_through_relay_and_other_posts_stay_direct(http):
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    assert client._post(f"{HOST}/order", headers={}, data="{}") == {"ok": True}
    client._post(f"{HOST}/auth/api-key", headers={})
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/auth/api-key"]


def test_gets_and_deletes_stay_direct(http):
    seen, _ = http
    client = _client(f"{RELAY}/{HOST}")
    client._get(f"{HOST}/time")
    client._delete(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{HOST}/time", f"{HOST}/order"]


def test_no_relay_means_direct(http):
    seen, _ = http
    _client("")._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{HOST}/order"]


def test_unreachable_relay_falls_back_to_direct(http):
    seen, state = http
    state["fail_relay"] = httpx.ConnectError("refused")
    client = _client(f"{RELAY}/{HOST}", fallback=True)
    assert client._post(f"{HOST}/order", headers={}, data="{}") == {"ok": True}
    assert seen == [f"{RELAY}/{HOST}/order", f"{HOST}/order"]


def test_unreachable_relay_without_fallback_raises(http):
    seen, state = http
    state["fail_relay"] = httpx.ConnectTimeout("slow")
    client = _client(f"{RELAY}/{HOST}", fallback=False)
    with pytest.raises(PolyApiException):
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{RELAY}/{HOST}/order"]


def test_read_timeout_never_falls_back(http):
    """The order may already be on the exchange: no direct re-post (it could double-submit)."""
    seen, state = http
    state["fail_relay"] = httpx.ReadTimeout("no answer")
    client = _client(f"{RELAY}/{HOST}", fallback=True)
    with pytest.raises(PolyApiException):
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert seen == [f"{RELAY}/{HOST}/order"]


def test_relay_http_error_surfaces_status(http, monkeypatch):
    def handler(request):
        return httpx.Response(400, json={"error": "not enough balance"})

    monkeypatch.setattr(helpers, "_http_client", httpx.Client(transport=httpx.MockTransport(handler)))
    client = _client(f"{RELAY}/{HOST}")
    with pytest.raises(PolyApiException) as info:
        client._post(f"{HOST}/order", headers={}, data="{}")
    assert info.value.status_code == 400


def test_ping_relay_is_a_get_time_through_the_relay(http):
    seen, _ = http
    _client(f"{RELAY}/{HOST}").ping_relay()
    assert seen == [f"{RELAY}/{HOST}/time"]
