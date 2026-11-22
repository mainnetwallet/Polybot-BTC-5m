"""WIN / LOSS and PnL of a filled SELL order (take-profit or stop-loss exit).

Only SELL rows carry a result. A BUY row stays blank: its outcome is the exit that followed, which the
SELL row already shows, so showing it on both rows duplicated the same PnL.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Callable, Iterable


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
    groups: dict[tuple[str, str, int], list[tuple[int, int, object]]] = defaultdict(list)
    for o in orders:
        if (o.filled_size or 0) <= 0:
            continue
        is_sell = 1 if key_action(o.order_key) == "SELL" else 0
        groups[(o.market_id, o.side, key_stage(o.order_key))].append((o.id or 0, is_sell, o))

    out: dict[int, tuple[str, float]] = {}
    for events in groups.values():
        events.sort(key=lambda e: (e[0], e[1]))  # oldest first; a buy before a sell of the same id
        held = cost = fees = 0.0  # shares still held, their total cost and total buy fee
        for _, is_sell, o in events:
            if not is_sell:
                held += o.filled_size
                cost += o.filled_size * (o.avg_fill_price or 0.0)
                fees += o.fee or 0.0
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
    return out
