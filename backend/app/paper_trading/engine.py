"""Paper execution: simulates marketable BUY against the live order book.

Never imports or calls the live CLOB client. Fills walk real ask levels, so
spread and slippage are represented; depth caps produce partial fills.

With ORDER_PROXY_ENABLED a paper BUY goes through the order relay like a live one: no order is
sent, but the buy makes the same round trip to the relay (GET /time) first, so its latency
includes the relay hop, and it is refused when the relay is down (no direct fallback).
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Callable

import httpx

from app import clock
from app.config.settings import Settings
from app.execution.base import ExecutionEngine, ExecutionReport, FillPart, OrderRequest, SellRequest
from app.execution.relay import order_relay_url
from app.market_data.book import OrderBook

logger = logging.getLogger(__name__)

RELAY_TIMEOUT_SECONDS = 3.0

BookProvider = Callable[[str], OrderBook | None]


class PaperExecutionEngine(ExecutionEngine):
    mode = "paper"

    def __init__(
        self,
        settings: Settings,
        book_provider: BookProvider,
        relay_client: httpx.AsyncClient | None = None,
    ) -> None:
        self._settings = settings
        self._book_provider = book_provider
        self._relay_url = order_relay_url(settings)
        self._relay_client = relay_client  # created on first use (tests pass a mock-transport client)

    async def _relay_round_trip(self) -> str | None:
        """One GET /time through the order relay. Returns None when it answered, else an error text."""
        if self._relay_client is None:
            self._relay_client = httpx.AsyncClient(
                timeout=RELAY_TIMEOUT_SECONDS, headers={"User-Agent": "py_clob_client_v2"}
            )
        try:
            resp = await self._relay_client.get(f"{self._relay_url}/time")
        except httpx.RequestError as exc:
            logger.error("paper order relay unreachable (%s)", type(exc).__name__)
            return f"order relay unreachable ({type(exc).__name__})"
        if resp.status_code >= 500:
            logger.error("paper order relay returned HTTP %s", resp.status_code)
            return f"order relay returned HTTP {resp.status_code}"
        return None

    async def close(self) -> None:
        client, self._relay_client = self._relay_client, None
        if client is not None:
            await client.aclose()

    def _fee(self, shares: float, price: float) -> float:
        base = max(0.0, price * (1.0 - price))
        rate = self._settings.paper_fee_rate * (base ** self._settings.paper_fee_exponent)
        return round(shares * rate, 8)

    async def place_buy(self, request: OrderRequest) -> ExecutionReport:
        if not self._relay_url:
            return self._simulate_buy(request)
        # Same path as a live buy: through the relay, never direct. The book is read after the round
        # trip, as a real order would reach the exchange after the relay hop.
        error = await self._relay_round_trip()
        if error is not None:
            return ExecutionReport(
                status="REJECTED",
                latency_ms=float(max(0, clock.now_ms() - request.observed_at_ms)),
                error=error,
                route="proxy",
            )
        report = self._simulate_buy(request)
        report.route = "proxy"
        return report

    def _simulate_buy(self, request: OrderRequest) -> ExecutionReport:
        started_ms = clock.now_ms()
        book = self._book_provider(request.side)
        if book is None or book.best_ask is None:
            return ExecutionReport(
                status="REJECTED",
                latency_ms=float(started_ms - request.observed_at_ms),
                error="no order book available",
            )
        best_ask = book.best_ask
        if best_ask > request.max_price + 1e-9:
            return ExecutionReport(
                status="REJECTED",
                submitted_price=best_ask,
                latency_ms=float(started_ms - request.observed_at_ms),
                error=f"best ask {best_ask:.4f} above max execution price {request.max_price:.4f}",
            )

        slip = self._settings.paper_slippage_bps / 10_000.0
        ratio = self._settings.paper_fill_ratio
        remaining = request.notional
        fills: list[FillPart] = []

        for level_price, level_size in book.level_prices(up_to=request.max_price):
            if remaining <= 1e-9:
                break
            exec_price = min(request.max_price, level_price * (1.0 + slip))
            exec_price = round(exec_price, 6)
            available_shares = max(0.0, level_size * ratio)
            if available_shares <= 0:
                continue
            spend = min(remaining, available_shares * exec_price)
            shares = spend / exec_price
            if shares <= 0:
                continue
            fills.append(
                FillPart(
                    price=exec_price,
                    size=round(shares, 8),
                    fee=self._fee(shares, exec_price),
                    exchange_trade_id=f"paper-{uuid.uuid4().hex[:12]}",
                )
            )
            remaining -= shares * exec_price

        filled_size = round(sum(f.size for f in fills), 8)
        cost = sum(f.price * f.size for f in fills)
        fee = round(sum(f.fee for f in fills), 8)
        avg_price = round(cost / filled_size, 6) if filled_size > 0 else None
        latency_ms = float(clock.now_ms() - request.observed_at_ms)

        if not fills:
            return ExecutionReport(
                status="REJECTED",
                submitted_price=best_ask,
                latency_ms=latency_ms,
                error="no available liquidity within max execution price",
            )

        status = "FILLED" if remaining <= 0.001 else "PARTIALLY_FILLED"
        return ExecutionReport(
            status=status,
            filled_size=filled_size,
            avg_price=avg_price,
            fee=fee,
            submitted_price=best_ask,
            exchange_order_id=f"paper-{uuid.uuid4().hex[:16]}",
            fills=fills,
            latency_ms=latency_ms,
        )

    async def place_sell(self, request: SellRequest) -> ExecutionReport:
        """Immediate-or-cancel SELL: walks the real bid levels at or above ``min_price``."""
        started_ms = clock.now_ms()
        book = self._book_provider(request.side)
        if book is None or book.best_bid is None:
            return ExecutionReport(
                status="REJECTED",
                latency_ms=float(started_ms - request.observed_at_ms),
                error="no order book available",
            )
        best_bid = book.best_bid
        if best_bid + 1e-9 < request.min_price:
            return ExecutionReport(
                status="CANCELED",
                submitted_price=best_bid,
                latency_ms=float(started_ms - request.observed_at_ms),
                error=f"best bid {best_bid:.4f} below minimum sell price {request.min_price:.4f}",
            )

        slip = self._settings.paper_slippage_bps / 10_000.0
        ratio = self._settings.paper_fill_ratio
        remaining = request.size
        fills: list[FillPart] = []
        for level_price, level_size in book.bid_levels(down_to=request.min_price):
            if remaining <= 1e-9:
                break
            exec_price = round(max(request.min_price, level_price * (1.0 - slip)), 6)
            available = max(0.0, level_size * ratio)
            shares = min(remaining, available)
            if shares <= 0:
                continue
            fills.append(
                FillPart(
                    price=exec_price,
                    size=round(shares, 8),
                    fee=self._fee(shares, exec_price),
                    exchange_trade_id=f"paper-{uuid.uuid4().hex[:12]}",
                )
            )
            remaining -= shares

        latency_ms = float(clock.now_ms() - request.observed_at_ms)
        if not fills:
            return ExecutionReport(
                status="CANCELED",
                submitted_price=best_bid,
                latency_ms=latency_ms,
                error="no bid liquidity at or above the minimum sell price",
            )
        filled_size = round(sum(f.size for f in fills), 8)
        proceeds = sum(f.price * f.size for f in fills)
        return ExecutionReport(
            status="FILLED" if remaining <= 1e-6 else "CANCELED",  # IOC: the unfilled rest is dropped
            filled_size=filled_size,
            avg_price=round(proceeds / filled_size, 6),
            fee=round(sum(f.fee for f in fills), 8),
            submitted_price=best_bid,
            exchange_order_id=f"paper-{uuid.uuid4().hex[:16]}",
            fills=fills,
            latency_ms=latency_ms,
            error=None if remaining <= 1e-6 else "partially filled; the rest stays held",
        )

    async def get_balance(self) -> float | None:
        return None
