from app.market_data.book import OrderBook


def _book() -> OrderBook:
    return OrderBook(
        token_id="t",
        market_id="m",
        side="UP",
        bids={0.91: 940, 0.90: 690, 0.89: 520, 0.88: 330, 0.87: 100, 0.86: 0},
        asks={0.93: 412, 0.94: 605, 0.95: 780, 0.96: 360, 0.97: 50, 0.98: 0},
        updated_at_ms=1,
    )


def test_snapshot_carries_nearest_four_levels_per_side():
    snap = _book().snapshot()
    assert snap["asks"] == [[0.93, 412], [0.94, 605], [0.95, 780], [0.96, 360]]
    assert snap["bids"] == [[0.91, 940], [0.90, 690], [0.89, 520], [0.88, 330]]


def test_snapshot_top_of_book_fields_unchanged():
    snap = _book().snapshot()
    assert snap["best_ask"] == 0.93 and snap["ask_size"] == 412
    assert snap["best_bid"] == 0.91 and snap["bid_size"] == 940


def test_empty_book_has_empty_depth():
    snap = OrderBook(token_id="t", market_id="m", side="DOWN").snapshot()
    assert snap["asks"] == [] and snap["bids"] == []
