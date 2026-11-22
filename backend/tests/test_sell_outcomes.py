from types import SimpleNamespace as NS

from app.services.order_outcomes import sell_outcomes


def _key_stage(k):
    import re
    m = re.search(r":s(\d+)", k or "")
    return int(m.group(1)) if m else 1


def _key_action(k):
    return "SELL" if ":sell:" in (k or "") else "BUY"


def _o(id, key, size, price, fee=0.0):
    return NS(id=id, order_key=key, market_id="m", side="DOWN", filled_size=size, avg_fill_price=price, fee=fee)


def test_stop_loss_sell_shows_loss():
    buy = _o(1, "paper:m:s0", 1.30, 0.77, 0.016)
    sell = _o(2, "paper:m:DOWN:s0:sell:ab", 1.29, 0.62, 0.021)
    res = sell_outcomes([buy, sell], _key_stage, _key_action)
    assert res[2][0] == "LOSS" and res[2][1] < 0


def test_take_profit_sell_shows_win():
    buy = _o(1, "paper:m", 1.10, 0.91, 0.006)
    sell = _o(2, "paper:m:DOWN:s1:sell:ab", 1.10, 0.99, 0.001)
    assert sell_outcomes([buy, sell], _key_stage, _key_action)[2][0] == "WIN"


def test_unfilled_sell_has_no_result():
    buy = _o(1, "paper:m", 1.0, 0.9)
    sell = _o(2, "paper:m:DOWN:s1:sell:ab", 0.0, None)
    assert sell_outcomes([buy, sell], _key_stage, _key_action) == {}


def test_sold_and_rebought_tap_does_not_blend_old_buy_into_the_cost():
    # one-tap: buy 2.46 at 20c, sell at 30c, buy 2.46 again at 97c, sell at 97c.
    # The second sale made nothing (it used to show about +$0.95 from a blended 58.5c cost).
    tap = ":s9"
    buy1 = _o(1, f"paper:m:DOWN:tap:a{tap}", 2.46, 0.20)
    sell1 = _o(2, "paper:m:DOWN:s9:sell:x", 2.46, 0.30)
    buy2 = _o(3, f"paper:m:DOWN:tap:b{tap}", 2.46, 0.97)
    sell2 = _o(4, "paper:m:DOWN:s9:sell:y", 2.46, 0.97, 0.004)
    res = sell_outcomes([sell2, buy2, sell1, buy1], _key_stage, _key_action)
    assert res[2][0] == "WIN" and abs(res[2][1] - 0.246) < 1e-6
    assert res[4][0] == "LOSS" and abs(res[4][1] + 0.004) < 1e-6  # only the sell fee is lost


def test_partial_sells_use_the_running_average_cost():
    buy = _o(1, "paper:m:DOWN:tap:a:s9", 4.0, 0.50)
    sell1 = _o(2, "paper:m:DOWN:s9:sell:x", 1.0, 0.60)
    sell2 = _o(3, "paper:m:DOWN:s9:sell:y", 3.0, 0.40)
    res = sell_outcomes([buy, sell1, sell2], _key_stage, _key_action)
    assert abs(res[2][1] - 0.10) < 1e-6
    assert abs(res[3][1] + 0.30) < 1e-6
