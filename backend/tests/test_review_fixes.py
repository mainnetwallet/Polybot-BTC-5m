"""Regression tests for the findings of the code review (security + logic)."""

from __future__ import annotations

import json

import pytest

from app.api.origin import build_host_checker, build_origin_checker, host_name
from app.config.settings import Settings, get_settings
from app.database.models import Position
from app.database.session import session_scope
from app.market_discovery.service import MarketDiscovery
from app.portfolio.service import Portfolio
from tests.helpers import make_config, make_market


# --- DNS rebinding: Host must be a name the owner would use ------------------------------------------


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("localhost:8000", "localhost"),
        ("LOCALHOST", "localhost"),
        ("127.0.0.1:8000", "127.0.0.1"),
        ("[::1]:8000", "::1"),
        ("::1", "::1"),
        ("abc.trycloudflare.com", "abc.trycloudflare.com"),
        (None, ""),
    ],
)
def test_host_name_strips_port_and_brackets(raw, expected):
    assert host_name(raw) == expected


def test_rebinding_host_is_not_same_origin_when_no_login_is_set():
    allowed = build_origin_checker(["http://localhost:5173"], trusted_hosts=[])
    # the attacker's own name, rebound to 127.0.0.1: Origin and Host match each other, but are not ours
    assert allowed("http://rebind.attacker.example:8000", "rebind.attacker.example:8000") is False
    # the owner's usual ways in
    assert allowed("http://localhost:8000", "localhost:8000") is True
    assert allowed("http://127.0.0.1:8000", "127.0.0.1:8000") is True
    assert allowed("http://192.168.1.20:8000", "192.168.1.20:8000") is True
    assert allowed("https://quiet-fox.trycloudflare.com", "quiet-fox.trycloudflare.com") is True
    # a CORS_ORIGINS entry keeps working, and no Origin (Android app, scripts) still passes
    assert allowed("http://localhost:5173", "anything") is True
    assert allowed(None, "rebind.attacker.example") is True
    # a lookalike must not slip through the tunnel suffix
    assert allowed("http://evil-trycloudflare.com", "evil-trycloudflare.com") is False


def test_trusted_hosts_entries_exact_and_wildcard():
    is_trusted = build_host_checker([], ["bot.example.com", ".tunnel.example.org"])
    assert is_trusted("bot.example.com:443") is True
    assert is_trusted("a.tunnel.example.org") is True
    assert is_trusted("example.com") is False
    assert is_trusted("other.example.com") is False


def test_host_check_is_off_when_a_login_or_token_is_set():
    assert build_origin_checker([], trusted_hosts=None)("http://my.domain", "my.domain") is True
    assert Settings(api_token="secret", admin_password="").host_allowlist is None
    assert Settings(api_token="", admin_password="pw").host_allowlist is None
    assert Settings(api_token="", admin_password="", trusted_hosts="a.example, .b.example").host_allowlist == [
        "a.example",
        ".b.example",
    ]


# --- take-profit must sit above the buy price ---------------------------------------------------------


def test_stage0_take_profit_cap_below_the_buy_limit_is_refused():
    make_config(stage0_enabled=True, stage0_max_ask=0.94, stage0_sell_cap_price=0.98)  # the default shape: fine
    with pytest.raises(ValueError, match="stage0_sell_cap_price"):
        make_config(stage0_enabled=True, stage0_max_ask=0.94, stage0_sell_cap_price=0.50)
    with pytest.raises(ValueError, match="stage0_sell_cap_price"):
        make_config(stage0_enabled=True, stage0_max_ask=0.94, stage0_sell_cap_price=0.94)


def test_stage1_take_profit_price_below_the_buy_limit_is_refused():
    make_config(stage1_sell_enabled=True, stage1_max_ask=0.97, stage1_sell_price_high=0.98)
    with pytest.raises(ValueError, match="stage1_sell_price_high"):
        make_config(stage1_sell_enabled=True, stage1_max_ask=0.97, stage1_sell_price_high=0.90)
    # nothing to protect while the stage-1 take-profit is switched off
    make_config(stage1_sell_enabled=False, stage1_max_ask=0.97, stage1_sell_price_high=0.90)


def test_shipped_defaults_pass_the_take_profit_checks():
    fields = Settings.model_fields
    assert fields["stage0_sell_cap_price"].default > fields["stage0_max_ask"].default
    assert fields["stage1_sell_price_high"].default > fields["stage1_max_ask"].default
    assert fields["stage2_sell_price"].default > fields["stage2_max_ask"].default


# --- daily realized PnL includes partial sells of still-open positions ----------------------------------


def test_realized_today_counts_losses_banked_by_partial_sells():
    settings = get_settings()
    portfolio = Portfolio(settings)
    mode = "paper"
    with session_scope() as session:
        portfolio.ensure_accounts(session)
        before = portfolio.realized_today(session, mode)
        # stage 1 stopped out for -3 while the stage 2 shares are still held: the row stays open
        session.add(
            Position(
                mode=mode, market_id="partial-market", side="UP", size=5.0, cost_basis=4.5, avg_price=0.9,
                realized_pnl=-3.0, unrealized_pnl=-1.0, status="open",
            )
        )
        session.flush()
        after = portfolio.realized_today(session, mode)
        assert round(after - before, 6) == -3.0  # the unrealized -1.0 is not part of it
        session.rollback()


# --- a closed market is only settled on a terminal price (or UMA's resolved flag) -------------------------


def _discovery_with_gamma(monkeypatch, market, prices, *, uma_status=None):
    discovery = MarketDiscovery(get_settings())

    async def fake_get(path, params=None):
        return [
            {
                "closed": True,
                "markets": [
                    {
                        "conditionId": market.market_id,
                        "closed": True,
                        "outcomes": json.dumps(["Up", "Down"]),
                        "outcomePrices": json.dumps([str(p) for p in prices]),
                        "umaResolutionStatus": uma_status,
                    }
                ],
            }
        ]

    monkeypatch.setattr(discovery, "_get", fake_get)
    return discovery


@pytest.mark.asyncio
async def test_closed_market_with_a_0995_price_is_not_settled(monkeypatch):
    market = make_market(remaining=-30.0)
    info = await _discovery_with_gamma(monkeypatch, market, [0.995, 0.005])._gamma_resolution(market)
    assert info.closed is True and info.outcome is None


@pytest.mark.asyncio
async def test_closed_market_with_terminal_prices_settles(monkeypatch):
    market = make_market(remaining=-30.0)
    info = await _discovery_with_gamma(monkeypatch, market, [0.0, 1.0])._gamma_resolution(market)
    assert info.outcome == "DOWN"


@pytest.mark.asyncio
async def test_uma_resolved_market_settles_at_099(monkeypatch):
    market = make_market(remaining=-30.0)
    discovery = _discovery_with_gamma(monkeypatch, market, [0.995, 0.005], uma_status="resolved")
    assert (await discovery._gamma_resolution(market)).outcome == "UP"
