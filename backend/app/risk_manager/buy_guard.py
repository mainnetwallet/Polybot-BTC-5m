"""Volatility circuit breakers that switch new buys off.

Two independent guards, both measured on the official Chainlink BTC/USD TWAP60 series the markets settle on:

* gap guard   - BTC is ``limit`` USD (or more) above OR below the current window's price to beat.
* trend guard - BTC moved ``limit`` USD (or more) up OR down over the last ``markets`` 5-minute windows,
                measured from the price to beat of the oldest of them to the current price.

The functions are pure (no I/O, no state) so they are easy to test; the bot owns the on/off state.
"""

from __future__ import annotations

from dataclasses import dataclass

GAP_GUARD = "gap_guard"
TREND_GUARD = "trend_guard"


@dataclass(frozen=True)
class GuardTrip:
    code: str
    value: float  # signed USD move (positive = up)
    limit: float
    message: str  # one plain sentence: logs, the risk context and the dashboard
    details: tuple[tuple[str, str], ...] = ()  # (label, value) rows for the Telegram table


def _direction(value: float) -> str:
    return "above" if value >= 0 else "below"


def check_gap(btc_price: float | None, price_to_beat: float | None, limit: float) -> GuardTrip | None:
    """Trip when |BTC - price to beat| >= limit. Missing data never trips (nothing to judge)."""
    if limit <= 0 or btc_price is None or price_to_beat is None:
        return None
    move = btc_price - price_to_beat
    if abs(move) + 1e-9 < limit:
        return None
    return GuardTrip(
        GAP_GUARD,
        move,
        limit,
        f"BTC {btc_price:,.2f} is {abs(move):,.2f} USD {_direction(move)} the price to beat "
        f"{price_to_beat:,.2f} (limit {limit:,.0f} USD)",
        (
            ("BTC", f"{btc_price:,.2f}"),
            ("Price to beat", f"{price_to_beat:,.2f}"),
            ("Gap", f"{move:+,.2f} USD ({_direction(move)})"),
            ("Limit", f"{limit:,.0f} USD"),
        ),
    )


def check_trend(
    btc_price: float | None, anchor_price: float | None, limit: float, markets: int
) -> GuardTrip | None:
    """Trip when |BTC - anchor| >= limit, anchor being the price to beat `markets` windows ago."""
    if limit <= 0 or btc_price is None or anchor_price is None:
        return None
    move = btc_price - anchor_price
    if abs(move) + 1e-9 < limit:
        return None
    minutes = markets * 5
    return GuardTrip(
        TREND_GUARD,
        move,
        limit,
        f"BTC moved {move:+,.2f} USD over the last {markets} markets (~{minutes} min): "
        f"{anchor_price:,.2f} -> {btc_price:,.2f} (limit {limit:,.0f} USD)",
        (
            ("Window", f"last {markets} markets (~{minutes} min)"),
            ("From", f"{anchor_price:,.2f}"),
            ("Now", f"{btc_price:,.2f}"),
            ("Move", f"{move:+,.2f} USD"),
            ("Limit", f"{limit:,.0f} USD"),
        ),
    )
