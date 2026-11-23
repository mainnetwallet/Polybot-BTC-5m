"""WIN / LOSS and PnL of filled orders.

A filled SELL row carries the result of that exit (take-profit or stop-loss). A BUY row stays blank while
its outcome is the exit that followed, which the SELL row already shows (showing it on both rows
duplicated the same PnL).

The one exception is a BUY whose shares were held to the end of the market: there is no SELL row, so once
the market has resolved the BUY row itself carries the settlement result (see `settlement_outcomes`).
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Callable, Iterable, Mapping


def _replay(
    orders: Iterable,
    key_stage: Callable[[str | None], int],
    key_action: Callable[[str | None], str],
) -> tuple[dict[int, tuple[str, float]], dict[tuple[str, str, int], tuple[int, float, float, float]]]:
    """Replay buys and sells per market / side / stage, oldest first, with the average-cost method.

    Returns ({sell order id: (result, pnl)}, {group: (last buy id, shares still held, their cost, their buy fee)}).
    The second dict only holds groups that still hold shares after the last sale.
    """
    groups: dict[tuple[str, str, int], list[tuple[int, int, object]]] = defaultdict(list)
    for o in orders:
        if (o.filled_size or 0) <= 0:
            continue
        is_sell = 1 if key_action(o.order_key) == "SELL" else 0
        groups[(o.market_id, o.side, key_stage(o.order_key))].append((o.id or 0, is_sell, o))

    out: dict[int, tuple[str, float]] = {}
    leftovers: dict[tuple[str, str, int], tuple[int, float, float, float]] = {}
    for group, events in groups.items():
        events.sort(key=lambda e: (e[0], e[1]))  # oldest first; a buy before a sell of the same id
        held = cost = fees = 0.0  # shares still held, their total cost and total buy fee
        last_buy_id = 0
        for _, is_sell, o in events:
            if not is_sell:
                held += o.filled_size
                cost += o.filled_size * (o.avg_fill_price or 0.0)
                fees += o.fee or 0.0
                last_buy_id = o.id
                continue
            if held <= 1e-9:
                continue  # nothing bought before this sale: no basis, no result
            sold = min(o.filled_size, held)
            fraction = sold / held
            basis = (cost + fees) * fraction  # cost and buy fee of exactly the shares sold
            proceeds = o.filled_size * (o.avg_fill_price or 0.0) * (sold / o.filled_size)
            pnl = proceeds - (o.fee or 0.0) - basis
            cost -= cost * fraction
            fees -= fees * fraction
            held -= sold
            out[o.id] = ("WIN" if pnl > 0 else "LOSS", round(pnl, 6))
        if held > 1e-9 and last_buy_id:
            leftovers[group] = (last_buy_id, held, cost, fees)
    return out, leftovers


def sell_outcomes(
    orders: Iterable,
    key_stage: Callable[[str | None], int],
    key_action: Callable[[str | None], str],
) -> dict[int, tuple[str, float]]:
    """{order id: (result, pnl)} for every filled SELL (take-profit or stop-loss exit).

    A SELL row used to show no result at all, so a stop-loss that lost money looked like an empty
    row. Its own P&L is what that exit realised against the cost of the shares it sold: proceeds -
    sell fee - sold shares * (cost + buy fee per share).

    The buys and sells of one market / side / stage are replayed oldest first with the average-cost
    method (as the position row does), so shares bought and sold earlier no longer weigh on the cost
    of shares bought later. A one-tap buy that was sold and bought again at a higher price used to be
    blended with the first buy, which showed a win for a sale that made almost nothing.
    """
    return _replay(orders, key_stage, key_action)[0]


def settlement_outcomes(
    orders: Iterable,
    key_stage: Callable[[str | None], int],
    key_action: Callable[[str | None], str],
    resolutions: Mapping[str, str | None],
) -> dict[int, tuple[str, float]]:
    """{buy order id: (result, pnl)} for buys whose shares were held until the market resolved.

    Without a SELL row such a buy never showed WIN / LOSS. Once the market has a winner (`resolutions`
    maps market id to "UP" / "DOWN"), the shares still held after all earlier sales pay 1 per share if
    the buy's side won and nothing otherwise. The P&L is that payout minus the cost and buy fee of those
    shares. It sits on the last filled BUY of the market / side / stage, so a position built from several
    buys shows one result, not one per buy. A market that has not resolved yet has no entry.
    """
    _, leftovers = _replay(orders, key_stage, key_action)
    out: dict[int, tuple[str, float]] = {}
    for (market_id, side, _stage), (buy_id, held, cost, fees) in leftovers.items():
        winner = resolutions.get(market_id)
        if not winner:
            continue
        payout = held if side == winner else 0.0
        pnl = payout - cost - fees
        out[buy_id] = ("WIN" if pnl > 0 else "LOSS", round(pnl, 6))
    return out
