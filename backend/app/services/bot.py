"""Trading bot orchestrator: lifecycle, safety gates, snapshots and settlement."""

from __future__ import annotations

import asyncio
import logging
import re
import secrets
import time
from collections import deque
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app import clock
from app.config.runtime import SettingsService
from app.config.settings import Settings
from app.database.models import AppState, BotSetting, Market, Order, Position, TriggerEvent, utcnow
from app.database.session import session_scope
from app.execution.base import ExecutionEngine
from app.live_trading.engine import LiveExecutionEngine, LiveTradingUnavailable
from app.market_data.btc_price import BtcPriceFeed
from app.market_data.client import ClobMarketDataClient
from app.market_discovery.service import MarketDiscovery, MarketMonitor
from app.market_discovery.types import MarketInfo, ResolutionInfo
from app.monitoring.logs import LogService, Publisher
from app.order_manager.manager import (
    EXIT_STAGES,
    SELL_DUST,
    SELLABLE_STAGES,
    TAP_COOLDOWN_S,
    TAP_MIN_ASK,
    OrderManager,
    _floor_shares,
)
from app.portfolio.service import TAP_STAGE, TAP_TAG
from app.paper_trading.engine import PaperExecutionEngine
from app.portfolio.analytics import AnalyticsService
from app.portfolio.service import Portfolio, start_of_day_utc
from app.risk_manager import buy_guard
from app.risk_manager.manager import RiskContext, RiskManager
from app.services.order_outcomes import sell_outcomes
from app.services import telegram_format as tg
from app.services.telegram import TelegramService, parse_minutes
from app.strategy.engine import Stage1Strategy
from app.strategy.types import StrategyEvaluation

logger = logging.getLogger(__name__)


RESET_SERVER_PHRASE = "RESET ALL"
BUYS_STATE_KEY = "buys_state"  # bot_settings row holding the persisted buy on/off switch
MANUAL_OFF = "manual"  # the operator's "Off": no auto-resume timer, buying comes back at the next day's start
# Limit sell (kept by the bot, not an exchange order): a due sell that is refused / not filled is tried this many
# times, then the limit is dropped and the operator is told, so a bad exchange state is not hammered.
LIMIT_MAX_ATTEMPTS = 5
LIMIT_MIN_PRICE = 0.01
LIMIT_MAX_PRICE = 0.99
GUARD_TITLES = {
    buy_guard.GAP_GUARD: "BTC gap too large",
    buy_guard.TREND_GUARD: "BTC trend too large",
}

def evaluation_market_id(evaluation: StrategyEvaluation) -> str:
    return evaluation.signals[0].market_id if evaluation.signals else ""


TAP_MIN_USDC = 1.0  # Polymarket does not take a marketable buy below one dollar


class BotError(RuntimeError):
    pass


class ModeSwitchError(BotError):
    pass


class LiveGateError(BotError):
    pass


def iso_z(value: datetime | None) -> str | None:
    if value is None:
        return None
    aware = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value
    return aware.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


class TradingBot:
    def __init__(
        self,
        settings: Settings,
        runtime: SettingsService,
        portfolio: Portfolio,
        analytics: AnalyticsService,
        hub: Publisher,
    ) -> None:
        self.settings = settings
        self.runtime = runtime
        self.portfolio = portfolio
        self.analytics = analytics
        self.hub = hub

        self.logs = LogService(publisher=hub)
        self.strategy = Stage1Strategy()
        self.risk = RiskManager()

        self.discovery = MarketDiscovery(settings)
        self.monitor = MarketMonitor(self.discovery, settings, self._on_market)
        self.data = ClobMarketDataClient(settings, self._on_book)
        self.data.on_tick_size_change = self._on_tick_size_change
        self.btc = BtcPriceFeed(settings)

        self.paper_engine = PaperExecutionEngine(settings, self.data.book_for)
        self.live_engine = LiveExecutionEngine(settings, balance_hint=lambda: self._live_balance)
        self.orders = OrderManager(
            settings,
            runtime,
            portfolio,
            self.risk,
            self._executor_for,
            self.logs,
            submission_allowed=self._can_submit,
        )

        self._status = "STOPPED"
        self._mode = "paper"
        self._kill_switch = False
        self._started_at: datetime | None = None
        self._lock = asyncio.Lock()
        self._stop_generation = 0
        self._market: MarketInfo | None = None
        self._evaluation: StrategyEvaluation | None = None
        self._last_book_event_ms = 0
        self._last_order: dict | None = None
        self._latencies: deque[float] = deque(maxlen=50)
        self._background_pushes: set[asyncio.Task] = set()  # state broadcasts running off the request path
        self._live_balance: float | None = None
        self._live_balance_at = 0.0
        self._last_price_persist_ms: dict[str, int] = {}
        self._tasks: list[asyncio.Task] = []
        self._background_tasks: set[asyncio.Task] = set()
        self._shutdown = False
        self._market_finalized: set[str] = set()
        self._last_marks: dict[tuple[str, str], float] = {}
        self._resolution_pending_logged: set[str] = set()
        self._stuck_logged: set[str] = set()
        self._last_resolution_poll = 0.0
        self._last_live_reconcile = 0.0
        self._risk_halt_reason: str | None = None
        self._tick_pending = False
        self._trade_task: asyncio.Task | None = None
        self._trade_dirty = False
        self._exit_dirty = False
        self._exit_task: asyncio.Task | None = None
        # Operator limit sells, kept here in code (never as an exchange order): (market_id, side, stage) ->
        # {"price", "set_at", "next_try_ms", "attempts", "fired"}. Only for the live market; dropped when the
        # market changes and when the bot stops (so a kill switch or a mode switch can never fire one later).
        self._limits: dict[tuple[str, str, int], dict] = {}
        self._limit_dirty = False
        self._limit_task: asyncio.Task | None = None
        # market_id -> {"attempts": int, "next_at": monotonic seconds}
        self._redeem_queue: dict[str, dict] = {}
        self._redeem_notice_logged: set[str] = set()
        # Buy switch. Off = no NEW buys in any stage; exits, settlement and market data keep running.
        # Tripped by a buy guard (gap / trend) or turned off by the operator. The operator turns it on again with
        # "Buy"; a guard trip also switches itself back on after BUY_GUARD_RESUME_MINUTES with no action (see
        # _maybe_auto_resume), and the operator's "Off" lasts until the next day starts.
        self._buys_off = False
        self._buys_off_by = ""  # buy_guard.GAP_GUARD | TREND_GUARD | "manual"
        self._buys_off_reason = ""
        self._buys_off_since: datetime | None = None
        # Guards the operator switched buying back on over: they stay quiet until their condition clears
        # (then they re-arm), so "Buy" is not undone a moment later by the very condition the operator accepted.
        self._guard_disarmed: set[str] = set()
        self.telegram = TelegramService(settings, self._on_telegram_command)

    @property
    def status(self) -> str:
        return self._status

    @property
    def mode(self) -> str:
        return self._mode

    # ------------------------------------------------------------------
    def _executor_for(self, mode: str) -> ExecutionEngine:
        if mode == "live":
            return self.live_engine
        return self.paper_engine

    def _can_submit(self, mode: str) -> bool:
        return (
            mode == self._mode
            and self._status == "RUNNING"
            and not self._kill_switch
            and self._risk_halt_reason is None
        )

    def _schedule_background(self, fn, *args) -> None:
        task = asyncio.create_task(asyncio.to_thread(fn, *args))
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    def _on_tick_size_change(self, message: dict) -> None:
        """Re-read tick size for the market (the SDK caches it) without blocking the WS loop."""
        condition_id = str(message.get("market") or "")
        if not condition_id:
            return
        task = asyncio.create_task(self.live_engine.refresh_market_params(condition_id))
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    async def initialize(self) -> None:
        self._shutdown = False
        with session_scope() as session:
            self.portfolio.ensure_accounts(session)
            self.runtime.load(session)
            state = session.get(AppState, 1)
            if state is None:
                state = AppState(id=1, status="STOPPED", mode="paper", kill_switch=False)
                session.add(state)
            self._mode = state.mode if state.mode in {"paper", "live"} else "paper"
            self._kill_switch = bool(state.kill_switch)
            self._load_buys_state(session)
            self._status = "STOPPED"
            self._started_at = None
            self._market = None
            self._evaluation = None
            self._last_order = None
            state.status = "STOPPED"
            state.started_at = None
            session.flush()
            recovered = session.execute(
                select(Order).where(
                    Order.mode == "paper",
                    Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                )
            ).scalars().all()
            for order in recovered:
                order.status = "EXPIRED"
                order.reason = "recovered after restart; open remainder cancelled"
            recovered_count = len(recovered)
            unresolved_live_count = session.execute(
                select(Order.id).where(
                    Order.mode == "live",
                    Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                )
            ).scalars().all()
            paper_open = session.execute(
                select(Position).where(Position.mode == "paper", Position.status == "open")
            ).scalars().all()
            live_open = session.execute(
                select(Position).where(Position.mode == "live", Position.status == "open")
            ).scalars().all()
            paper_open_count, live_open_count = len(paper_open), len(live_open)

        self._clock_task = asyncio.create_task(clock.run(self.settings.clob_base_url), name="clock-sync")
        await self.discovery.start()
        await self.data.start()
        await self.btc.start()
        await self.monitor.start()
        self.logs.start()
        await self.telegram.start()
        self._tasks = [
            asyncio.create_task(self._state_broadcast_loop(), name="state-broadcast"),
            asyncio.create_task(self._maintenance_loop(), name="maintenance"),
            asyncio.create_task(self._retry_loop(), name="entry-retry"),
        ]
        self.logs.log(
            "INFO",
            "system",
            f"backend started; mode={self._mode} status=STOPPED",
            payload={
                "recovered_orders": recovered_count,
                "unresolved_live_orders": len(unresolved_live_count),
                "paper_open_positions": paper_open_count,
                "live_open_positions": live_open_count,
            },
            mode=self._mode,
        )
        if live_open_count and self._mode == "live":
            await self._reconcile_live_positions()

    async def shutdown(self) -> None:
        if self._status == "RUNNING":
            try:
                await self.stop_bot("shutdown")
            except Exception as exc:
                logger.warning("shutdown stop failed: %s", exc)
        self._shutdown = True
        for worker in (self._trade_task, self._exit_task):
            if worker is None:
                continue
            worker.cancel()
            try:
                await worker
            except asyncio.CancelledError:
                pass
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            try:
                await task
            except asyncio.CancelledError:
                pass
        await self.telegram.stop()
        await self.monitor.stop()
        await self.data.stop()
        await self.btc.stop()
        clock_task = getattr(self, "_clock_task", None)
        if clock_task is not None:
            clock_task.cancel()
            try:
                await clock_task
            except asyncio.CancelledError:
                pass
        if self._background_tasks:
            await asyncio.gather(*self._background_tasks, return_exceptions=True)
        await self.orders.close()
        await self.discovery.close()
        await self.live_engine.close()
        await self.paper_engine.close()
        await self.logs.stop()

    # ------------------------------------------------------------------
    async def _on_market(self, market: MarketInfo | None, event: str) -> None:
        if market is not None:
            previous = self._market
            self._market = market
            # a limit belongs to one market: a new round starts without any
            for stale in [key for key in self._limits if key[0] != market.market_id]:
                self._limits.pop(stale, None)
            if previous is not None and previous.market_id != market.market_id:
                self._snapshot_unrealized(previous.market_id)
                await self.orders.finalize_market(previous.market_id, self._mode, "market changed")
            self._persist_market(market)
            self.monitor.track(market)
            self.data.set_market(market)
            self.btc.set_market(market)
            if self._mode == "live":
                await self.live_engine.prewarm(
                    [market.up_token_id, market.down_token_id], market.market_id
                )
            self.orders.reset_trigger_cache()
            self._evaluation = None
            self._market_finalized.discard(market.market_id)
            self.logs.log(
                "INFO",
                "market",
                f"market opened {market.slug} ({iso_z(market.window_start)} -> {iso_z(market.window_end)})",
                payload={
                    "market_id": market.market_id,
                    "slug": market.slug,
                    "up_token_id": market.up_token_id,
                    "down_token_id": market.down_token_id,
                    "window_start": iso_z(market.window_start),
                    "window_end": iso_z(market.window_end),
                },
                mode=self._mode,
            )
            await self._broadcast_state()
        else:
            previous = self._market
            if previous is not None:
                self._marks()
                self._snapshot_unrealized(previous.market_id)
                self._limits.clear()
                self._market = None
                self.data.set_market(None)
                self.btc.set_market(None)
                self._evaluation = None
                if previous.market_id not in self._market_finalized:
                    await self.orders.finalize_market(previous.market_id, self._mode, "market closed")
                    self._market_finalized.add(previous.market_id)
                self.logs.log(
                    "INFO",
                    "market",
                    f"market closed {previous.slug}; awaiting resolution",
                    payload={"market_id": previous.market_id},
                    mode=self._mode,
                )
                await self._broadcast_state()

    def _persist_market(self, market: MarketInfo) -> None:
        with session_scope() as session:
            row = session.execute(
                select(Market).where(Market.market_id == market.market_id)
            ).scalar_one_or_none()
            if row is None:
                row = Market(market_id=market.market_id)
                session.add(row)
            row.slug = market.slug
            row.event_slug = market.event_slug
            row.series_id = market.series_id
            row.window_start = market.window_start
            row.window_end = market.window_end
            row.up_token_id = market.up_token_id
            row.down_token_id = market.down_token_id
            row.tick_size = market.tick_size
            row.min_order_size = market.min_order_size
            row.status = "active"
            session.flush()

    async def _on_book(self, market_id: str, side: str, book) -> None:
        market = self._market
        if market is None or market.market_id != market_id:
            return
        config = self.runtime.get()
        now_ms = int(time.time() * 1000)
        self._last_book_event_ms = now_ms

        # Exits first, straight from the book event: a stop-loss / take-profit never waits for the
        # strategy evaluation or for the trading worker to finish an entry.
        if self._status == "RUNNING" and self.orders.exit_check_needed(self._mode, market_id):
            self._dispatch_exits()

        # The operator's limit sells are watched right here too, on the same book event.
        if self._limits and self._status == "RUNNING" and not self._kill_switch and self._limit_due(market_id):
            self._dispatch_limits()

        evaluation = self.strategy.evaluate(market, self.data.books, now_ms, config)
        self._evaluation = evaluation
        self._schedule_tick()
        try:
            if self._status == "RUNNING":
                # Trading runs in its own task. It used to be awaited inside the websocket reader:
                # while a (live) order was in flight no book update was read, so the stop-loss judged
                # a stale bid and sold far below its level. Now the books keep updating and the
                # worker always re-reads them.
                self._dispatch_trading()
        finally:
            # Price history is written after the trade path so a database write never
            # sits between a book event and the order.
            last_persist = self._last_price_persist_ms.get(side, 0)
            if now_ms - last_persist >= self.settings.ws_price_persist_interval_ms:
                self._last_price_persist_ms[side] = now_ms
                self._schedule_background(self._persist_price, market_id, side, book.snapshot())

    def _dispatch_exits(self) -> None:
        """Wake the exit worker; book events that arrive while it is busy are coalesced into one more pass."""
        self._exit_dirty = True
        task = self._exit_task
        if task is None or task.done():
            self._exit_task = asyncio.create_task(self._exit_worker(), name="exit-worker")

    async def _exit_worker(self) -> None:
        try:
            while self._exit_dirty:
                self._exit_dirty = False
                market = self._market
                if market is None or self._status != "RUNNING":
                    return
                if not self.orders.exit_check_needed(self._mode, market.market_id):
                    return
                results = await self.orders.handle_exits(
                    market, self.data.books, self._mode, self.runtime.get()
                )
                acted = False
                for result in results:
                    if result.status not in {"SKIPPED", "BLOCKED"}:
                        self._last_order = result.as_dict()
                        acted = True
                if acted:
                    # a stop-loss / take-profit just went through: tell the dashboards now so the
                    # closed position leaves the screen at once, not on the next periodic push
                    await self._broadcast_state()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("exit worker failed")
            self.logs.log("ERROR", "error", f"exit worker failed: {exc}", mode=self._mode)

    # ------------------------------------------------------------------
    # Limit sell: the operator's price, watched by the bot. It is never an exchange order, so there is nothing
    # to cancel before the shares can be sold by hand, and it can be set before the shares are even bought.
    # ------------------------------------------------------------------
    @staticmethod
    def _limit_label(stage: int) -> str:
        return "one-tap" if stage == TAP_STAGE else f"stage {stage}"

    def _limit_due(self, market_id: str) -> bool:
        """True when a limit of this market has its best bid at or above the limit and may be tried now."""
        now_ms = int(time.time() * 1000)
        for (limit_market, side, _stage), entry in self._limits.items():
            if limit_market != market_id or entry["next_try_ms"] > now_ms:
                continue
            book = self.data.book_for(side)
            bid = book.best_bid if book is not None else None
            if bid is not None and bid + 1e-9 >= entry["price"]:
                return True
        return False

    def _dispatch_limits(self) -> None:
        """Wake the limit worker; book events that arrive while it is busy are coalesced into one more pass."""
        self._limit_dirty = True
        task = self._limit_task
        if task is None or task.done():
            self._limit_task = asyncio.create_task(self._limit_worker(), name="limit-worker")

    async def _limit_worker(self) -> None:
        try:
            while self._limit_dirty:
                self._limit_dirty = False
                market = self._market
                if market is None or self._status != "RUNNING" or self._kill_switch:
                    return
                changed = False
                for key, entry in list(self._limits.items()):
                    if self._limits.get(key) is not entry or key[0] != market.market_id:
                        continue
                    if entry["next_try_ms"] > int(time.time() * 1000):
                        continue
                    book = self.data.book_for(key[1])
                    bid = book.best_bid if book is not None else None
                    if bid is None or bid + 1e-9 < entry["price"]:
                        continue
                    if await self._fire_limit(market, key, entry):
                        changed = True
                if changed:
                    await self._broadcast_state()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("limit worker failed")
            self.logs.log("ERROR", "error", f"limit worker failed: {exc}", mode=self._mode)

    async def _fire_limit(self, market: MarketInfo, key: tuple[str, str, int], entry: dict) -> bool:
        """Try one due limit sell. True = something changed that the dashboards should show now."""
        _market_id, side, stage = key
        label = self._limit_label(stage)
        price = entry["price"]
        entry["next_try_ms"] = int(time.time() * 1000) + 250  # no second try while this one is on its way
        result = await self.orders.limit_sell(
            market, self.data.books, self._mode, self.runtime.get(), side, stage, price
        )
        now_ms = int(time.time() * 1000)
        # the limit may have been removed (or replaced) while the sell was on its way
        present = self._limits.get(key) is entry

        if result.status == "BLOCKED":
            if not present:
                return False
            reason = result.reason
            if reason == "no_holding":
                if entry["fired"] or stage != TAP_STAGE:
                    # this limit already sold everything (or the stage holds nothing any more): it is done.
                    # Only a one-tap limit may wait for shares that are bought later.
                    self._limits.pop(key, None)
                    return True
                entry["next_try_ms"] = now_ms + 1000
            elif reason == "sell_in_flight":
                entry["next_try_ms"] = now_ms + 200
            elif reason in {"no_bid", "below_limit"}:
                entry["next_try_ms"] = now_ms + 100
            elif reason in {"market_ended", "unknown_stage"}:
                self._limits.pop(key, None)
                return True
            else:
                entry["next_try_ms"] = now_ms + 1000
            return False

        self._last_order = result.as_dict()
        self._last_order["ts"] = iso_z(utcnow())
        if result.latency_ms is not None:
            self._latencies.append(result.latency_ms)

        if result.filled_size > 0:
            if present:
                entry["fired"] = True
            if result.status == "FILLED":
                if present:
                    self._limits.pop(key, None)
                avg = result.avg_price or 0.0
                self.logs.log(
                    "INFO",
                    "system",
                    f"limit SELL filled: {label} {side} {result.filled_size:.2f} shares @ {avg:.3f} (limit {price:.3f})",
                    payload={"market_id": market.market_id, "side": side, "stage": stage, "limit": price, "avg_price": avg},
                    mode=self._mode,
                )
                self._notify_telegram(
                    f"Limit sell filled: {label} {side} {result.filled_size:.2f} shares at {avg * 100:.1f}c "
                    f"(limit {price * 100:g}c)"
                )
            elif present:
                entry["next_try_ms"] = now_ms + 250  # sold in part: the rest follows
            return True

        # nothing filled: refused, or cancelled because the bid fell back under the limit before the order reached
        # the book (or a live outcome that is not known yet). Try again a few times, then give up and say so.
        if present:
            entry["attempts"] += 1
            if entry["attempts"] >= LIMIT_MAX_ATTEMPTS:
                self._limits.pop(key, None)
                self.logs.log(
                    "ERROR",
                    "error",
                    f"limit SELL gave up: {label} {side} limit {price:.3f} not filled after {LIMIT_MAX_ATTEMPTS} tries "
                    f"({result.status}: {result.error or 'no fill'})",
                    payload={"market_id": market.market_id, "side": side, "stage": stage, "limit": price},
                    mode=self._mode,
                )
                self._notify_telegram(
                    f"Limit sell NOT filled: {label} {side} at {price * 100:g}c. The limit was removed after "
                    f"{LIMIT_MAX_ATTEMPTS} tries ({result.error or result.status}). The shares are still held."
                )
                return True
            entry["next_try_ms"] = now_ms + 500
        return False

    def _limits_payload(self, market: MarketInfo | None) -> list[dict]:
        if market is None:
            return []
        return [
            {"market_id": m, "side": s, "stage": st, "price": e["price"], "set_at": e["set_at"]}
            for (m, s, st), e in self._limits.items()
            if m == market.market_id
        ]

    async def set_limit(self, market_id: str, side: str, stage: int, price: float) -> dict:
        """Set (or change) the limit sell of one side / stage in the live market.

        Once the best bid reaches `price` the bot sells what that stage holds at the market, never below the limit
        (see `OrderManager.limit_sell`). It lives in the bot, not on the exchange, so it can be set before the
        shares exist (one-tap), changed or removed at any time, and a manual "Sell" still works at once.
        """
        market = self._market
        if self._status != "RUNNING" or self._kill_switch:
            raise BotError("the bot must be running to set a limit")
        if market is None or market.market_id != market_id or market.remaining_seconds() <= 0:
            raise BotError("this market has ended: wait for the next one")
        if side not in {"UP", "DOWN"}:
            raise BotError("side must be UP or DOWN")
        if stage not in SELLABLE_STAGES:
            raise BotError("unknown stage")
        if not (LIMIT_MIN_PRICE <= price <= LIMIT_MAX_PRICE):
            raise BotError(f"the limit must be between {LIMIT_MIN_PRICE * 100:g}c and {LIMIT_MAX_PRICE * 100:g}c")
        price = round(price, 3)
        self._limits[(market_id, side, stage)] = {
            "price": price,
            "set_at": iso_z(utcnow()),
            "next_try_ms": 0,
            "attempts": 0,
            "fired": False,
        }
        self.logs.log(
            "INFO",
            "system",
            f"limit SELL set: {self._limit_label(stage)} {side} at {price * 100:g}c",
            payload={"market_id": market_id, "side": side, "stage": stage, "limit": price, "mode": self._mode},
            mode=self._mode,
        )
        if self._limit_due(market_id):
            # the bid may already be at or above it: look now, not at the next book event
            self._dispatch_limits()
        return await self._snapshot_and_publish()

    async def cancel_limit(self, market_id: str, side: str, stage: int) -> dict:
        """Remove the limit sell of one side / stage (nothing to cancel on the exchange). Removing none is fine."""
        removed = self._limits.pop((market_id, side, stage), None)
        if removed is not None:
            self.logs.log(
                "INFO",
                "system",
                f"limit SELL removed: {self._limit_label(stage)} {side} (was {removed['price'] * 100:g}c)",
                payload={"market_id": market_id, "side": side, "stage": stage, "limit": removed["price"]},
                mode=self._mode,
            )
        return await self._snapshot_and_publish()

    def _dispatch_trading(self) -> None:
        """Wake the trading worker; events that arrive while it is busy are coalesced into one more pass."""
        self._trade_dirty = True
        task = self._trade_task
        if task is None or task.done():
            self._trade_task = asyncio.create_task(self._trade_worker(), name="trade-worker")

    async def _trade_worker(self) -> None:
        try:
            while self._trade_dirty:
                self._trade_dirty = False
                market = self._market
                if market is None or self._status != "RUNNING":
                    return
                config = self.runtime.get()
                now_ms = int(time.time() * 1000)
                evaluation = self.strategy.evaluate(market, self.data.books, now_ms, config)
                self._evaluation = evaluation
                await self._handle_trading(market, evaluation, config, now_ms)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("trade worker failed")
            self.logs.log("ERROR", "error", f"trade worker failed: {exc}", mode=self._mode)

    async def _handle_trading(
        self, market: MarketInfo, evaluation: StrategyEvaluation, config, now_ms: int
    ) -> None:
        # Exits first: a stop-loss must not wait behind the entry path (risk-context DB read,
        # order submission).
        exit_results: list = []
        if self.orders.exit_check_needed(self._mode, market.market_id):
            try:
                exit_results = await self.orders.handle_exits(
                    market, self.data.books, self._mode, config
                )
            except Exception as exc:
                logger.exception("exit handling failed")
                self.logs.log("ERROR", "error", f"exit handling failed: {exc}", mode=self._mode)

        # The risk context hits the database; only build it when a signal needs it.
        ctx = None
        gap_inputs = None
        # A partly filled paper buy is topped up by the order manager; that is a buy too, so it gets the
        # same risk context (BTC gap, kill switch, daily limits) even when no new signal fires.
        topup_pending = self.orders.has_partial_pending(self._mode, market.market_id)
        if evaluation.signals or topup_pending:
            # BTC price / price to beat are read here on the event loop: the feed's tick buffer is
            # written by the websocket task, so it must not be iterated from the database thread.
            gap_inputs = self.btc.gap_inputs(market)
            # A buy guard must trip BEFORE this pass can buy: the risk context below carries the switch.
            self._check_buy_guards(market, config, gap_inputs)
        if evaluation.signals:
            evaluation = self._prefilter_stage0(evaluation, gap_inputs, config)
        if evaluation.signals or topup_pending:
            try:
                ctx = await asyncio.to_thread(self._risk_context, market, config, now_ms, gap_inputs)
            except Exception as exc:
                logger.exception("risk context failed")
                self.logs.log("ERROR", "error", f"risk context failed: {exc}", mode=self._mode)
                return

        try:
            results = await self.orders.handle_event(
                market, evaluation, self.data.books, self._mode, ctx
            )
        except Exception as exc:
            logger.exception("order handling failed")
            self.logs.log("ERROR", "error", f"order handling failed: {exc}", mode=self._mode)
            results = []
        entered = any(r.status not in {"SKIPPED", "BLOCKED"} for r in results)
        results = list(exit_results) + list(results)
        if entered and self.orders.exit_check_needed(self._mode, market.market_id):
            # freshly bought shares are watched for their exits right away
            try:
                results += await self.orders.handle_exits(market, self.data.books, self._mode, config)
            except Exception as exc:
                logger.exception("exit handling failed")
                self.logs.log("ERROR", "error", f"exit handling failed: {exc}", mode=self._mode)
        for result in results:
            if result.status not in {"SKIPPED", "BLOCKED"}:
                self._last_order = result.as_dict()
                self._last_order["ts"] = iso_z(utcnow())
                if result.latency_ms is not None:
                    self._latencies.append(result.latency_ms)
        if results:
            await self._broadcast_state()

    def _prefilter_stage0(self, evaluation: StrategyEvaluation, gap_inputs, config) -> StrategyEvaluation:
        """Drop stage 0 signals that cannot buy, without touching the database.

        Stage 0 watches both sides for ~2 minutes and "ask <= limit" is nearly always true for the
        cheap side, so only the side the BTC price gap points to may go on to the risk manager (this
        also keeps `conflict_policy` from picking the wrong side). It buys once per market.
        """
        if not any(signal.stage == 0 for signal in evaluation.signals):
            return evaluation
        btc_price, price_to_beat, _issue = gap_inputs
        done = self.orders.stage0_bought(self._mode, evaluation_market_id(evaluation))
        keep = []
        for signal in evaluation.signals:
            if signal.stage != 0:
                keep.append(signal)
                continue
            if done:
                continue
            min_gap = config.stage0_min_gap_for(signal.best_ask)  # cheaper buys 70, expensive (0.89+) buys 60
            if min_gap > 0:
                if btc_price is None or price_to_beat is None:
                    continue  # no BTC data: fail closed
                move = btc_price - price_to_beat
                directional = move if signal.side == "UP" else -move
                if directional + 1e-9 < min_gap:
                    continue
            keep.append(signal)
        if len(keep) == len(evaluation.signals):
            return evaluation
        return replace(evaluation, signals=keep)

    # ------------------------------------------------------------------
    def _schedule_tick(self) -> None:
        """Push books/countdown to the dashboard on every book event (coalesced, no timer)."""
        if self._tick_pending or not getattr(self.hub, "client_count", 0):
            return
        self._tick_pending = True
        # keep a reference: the event loop only holds weak references to tasks, so a bare create_task
        # could be garbage-collected before it finishes (shutdown also waits for these)
        task = asyncio.get_running_loop().create_task(self._send_tick())
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    async def _send_tick(self) -> None:
        self._tick_pending = False
        try:
            market = self._market
            evaluation = self._evaluation
            up_book = self.data.book_for("UP")
            down_book = self.data.book_for("DOWN")
            await self.hub.broadcast(
                {
                    "type": "tick",
                    "data": {
                        "server_time_ms": clock.now_ms(),
                        "remaining_seconds": round(market.remaining_seconds(), 3) if market else None,
                        "books": {
                            "up": up_book.snapshot() if up_book else None,
                            "down": down_book.snapshot() if down_book else None,
                        },
                        "strategy": evaluation.summary() if evaluation else None,
                    },
                }
            )
        except Exception as exc:
            logger.debug("tick broadcast failed: %s", exc)

    def _persist_price(self, market_id: str, side: str, book: dict) -> None:
        from app.database.models import PriceUpdate

        try:
            with session_scope() as session:
                session.add(
                    PriceUpdate(
                        market_id=market_id,
                        side=side,
                        best_bid=book["best_bid"],
                        best_ask=book["best_ask"],
                        bid_size=book["bid_size"],
                        ask_size=book["ask_size"],
                        source=book["source"],
                        ts=book["updated_at_ms"],
                    )
                )
        except Exception as exc:  # price history must never break trading
            logger.warning("price persist failed: %s", exc)

    def _risk_context(
        self, market: MarketInfo, config, now_ms: int, gap_inputs: tuple | None = None
    ) -> RiskContext:
        marks = self._marks()
        with session_scope() as session:
            # One-tap trades are manual: the strategy's risk limits (daily loss / profit, max open positions,
            # trades per day) never count them.
            unrealized = self.portfolio.unrealized_pnl(
                session, self._mode, marks, market.market_id, include_tap=False
            )
            summary = self.portfolio.pnl_summary(session, self._mode, unrealized, include_tap=False)
            # daily loss / profit limits: realized pnl only unless DAILY_LIMIT_INCLUDE_UNREALIZED=true
            pnl = summary["daily_pnl"] if self.settings.daily_limit_include_unrealized else summary["realized_pnl"]
            trades_today = self.portfolio.trades_today(session, self._mode)
            # max_open_positions is a per-market cap: a position from an earlier 5-minute
            # window that is still waiting to settle must not block the current market.
            position_sizes = {
                position.side: position.size
                for position in self.portfolio.open_positions(session, self._mode, include_tap=False)
                if position.market_id == market.market_id
            }
            exposure_keys = {(market.market_id, side) for side in position_sizes}
            stage0 = self.orders.stage0_holding_in_session(session, self._mode, market.market_id)
            stage0_pure_sides = (
                frozenset({stage0.side})
                if stage0 is not None
                and stage0.size >= SELL_DUST
                and position_sizes.get(stage0.side, 0.0) <= stage0.size + SELL_DUST
                else frozenset()
            )
            active_orders = session.execute(
                select(Order).where(
                    Order.mode == self._mode,
                    Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                )
            ).scalars().all()
            exposure_keys.update(
                (order.market_id, order.side)
                for order in active_orders
                if order.market_id == market.market_id and self.orders._key_stage(order.order_key) != TAP_STAGE
            )
            market_orders = session.execute(
                select(Order).where(Order.mode == self._mode, Order.market_id == market.market_id)
            ).scalars().all()
            stage1_bought = any(
                self.orders._key_stage(order.order_key) == 1
                and self.orders._key_action(order.order_key) == "BUY"
                and (
                    order.status in {"PENDING", "SUBMITTED", "PARTIALLY_FILLED", "FILLED"}
                    or order.filled_size > 0
                )
                for order in market_orders
            )
            open_positions = len(exposure_keys)
            exposed_sides = frozenset(side for _, side in exposure_keys)
            balance = self._balance(session)
        remaining = market.remaining_seconds()
        btc_price, price_to_beat, gap_issue = gap_inputs if gap_inputs is not None else self.btc.gap_inputs(market)
        return RiskContext(
            now_ms=now_ms,
            kill_switch=self._kill_switch,
            account_balance=balance,
            daily_pnl=pnl,
            trades_today=trades_today,
            open_positions=open_positions,
            market_active=True,
            market_expired=remaining <= 0,
            remaining_seconds=remaining,
            exposed_sides=exposed_sides,
            btc_price=btc_price,
            price_to_beat=price_to_beat,
            price_gap_issue=gap_issue,
            stage1_bought=stage1_bought,
            stage0_pure_sides=stage0_pure_sides,
            buys_blocked=self._buys_block_reason(),
        )

    def _balance(self, session) -> float:
        if self._mode == "live" and self._live_balance is not None:
            return self._live_balance
        return self.portfolio.balance(session, self._mode)

    async def _balance_fast(self) -> float:
        """Balance for the one-tap pre-check without touching the database on the event loop."""
        if self._mode == "live" and self._live_balance is not None:
            return self._live_balance
        mode = self._mode

        def _read() -> float:
            with session_scope() as session:
                return self.portfolio.balance(session, mode)

        return await asyncio.to_thread(_read)

    def _marks(self) -> dict[tuple[str, str], float]:
        market = self._market
        if market is None:
            return {}
        marks: dict[tuple[str, str], float] = {}
        for side in ("UP", "DOWN"):
            book = self.data.book_for(side)
            if book is not None and book.best_bid is not None:
                marks[(market.market_id, side)] = book.best_bid
        # remember the latest bids so positions can still be valued after the window ends
        self._last_marks.update(marks)
        return marks

    def _snapshot_unrealized(self, market_id: str) -> None:
        """Freeze the last known bid-based PnL on positions whose window just ended.

        Once the market is no longer tracked there are no live marks, so a position
        waiting for settlement would otherwise show $0.00 unrealized.
        """
        marks = {key: value for key, value in self._last_marks.items() if key[0] == market_id}
        if not marks:
            return
        try:
            with session_scope() as session:
                self.portfolio.snapshot_unrealized(session, self._mode, market_id, marks)
        except Exception as exc:  # display-only; must never break the market lifecycle
            logger.warning("unrealized snapshot failed: %s", exc)

    # ------------------------------------------------------------------
    async def _state_broadcast_loop(self) -> None:
        while not self._shutdown:
            await asyncio.sleep(0.5)
            try:
                if getattr(self.hub, "client_count", 0):
                    await self._broadcast_state()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.debug("state broadcast failed: %s", exc)

    async def _broadcast_state(self) -> None:
        try:
            snapshot = await self.snapshot()
            await self.hub.broadcast({"type": "state", "data": snapshot})
        except Exception as exc:
            logger.debug("snapshot failed: %s", exc)

    async def _snapshot_and_publish(self) -> dict:
        """Build the dashboard state once: push it to the open dashboards in the background and hand the
        same snapshot back, so a reply (e.g. to a manual sell) never waits for a second snapshot or for
        the websocket fan-out."""
        snapshot = await self.snapshot()

        async def _push(data: dict) -> None:
            try:
                await self.hub.broadcast({"type": "state", "data": data})
            except Exception as exc:
                logger.debug("state broadcast failed: %s", exc)

        task = asyncio.ensure_future(_push(dict(snapshot)))
        self._background_pushes.add(task)
        task.add_done_callback(self._background_pushes.discard)
        return snapshot

    # ------------------------------------------------------------------
    # Buy switch and buy guards
    # ------------------------------------------------------------------
    def _load_buys_state(self, session) -> None:
        row = session.get(BotSetting, BUYS_STATE_KEY)
        value = row.value if row is not None and isinstance(row.value, dict) else {}
        self._buys_off = bool(value.get("off", False))
        self._buys_off_by = str(value.get("by") or "") if self._buys_off else ""
        self._buys_off_reason = str(value.get("reason") or "") if self._buys_off else ""
        since = value.get("since")
        try:
            self._buys_off_since = datetime.fromisoformat(str(since).replace("Z", "+00:00")).replace(tzinfo=None) if (self._buys_off and since) else None
        except ValueError:
            self._buys_off_since = None
        self._guard_disarmed = set()

    def _persist_buys_state(self) -> None:
        """Save the switch so a restart keeps buying off (a restart must never silently switch buys back on)."""
        value = {
            "off": self._buys_off,
            "by": self._buys_off_by,
            "reason": self._buys_off_reason,
            "since": iso_z(self._buys_off_since),
        }
        try:
            with session_scope() as session:
                row = session.get(BotSetting, BUYS_STATE_KEY)
                if row is None:
                    session.add(BotSetting(key=BUYS_STATE_KEY, value=value))
                else:
                    row.value = value
        except Exception as exc:  # the in-memory switch already applies; never break trading over a failed save
            logger.warning("buy switch not persisted: %s", exc)

    def _buys_block_reason(self) -> str:
        """Why new buys are off right now ('' = buying is on). Goes into the risk context."""
        if not self._buys_off:
            return ""
        title = GUARD_TITLES.get(self._buys_off_by, "Buying turned off")
        return f"{title}: {self._buys_off_reason}" if self._buys_off_reason else title

    def _guard_readings(self, market: MarketInfo, config, gap_inputs: tuple | None = None):
        """(tripped guards by code, BTC gap now, BTC move over the trend windows). None = no data."""
        btc_price, price_to_beat, _issue = gap_inputs if gap_inputs is not None else self.btc.gap_inputs(market)
        gap_now = btc_price - price_to_beat if btc_price is not None and price_to_beat is not None else None
        trend_now = None
        anchor = None
        if config.buy_guard_trend_usd > 0 and btc_price is not None:
            anchor = self.btc.trend_anchor(market, config.buy_guard_trend_markets)
            if anchor is not None:
                trend_now = btc_price - anchor
        trips: dict[str, buy_guard.GuardTrip] = {}
        gap = buy_guard.check_gap(btc_price, price_to_beat, config.buy_guard_gap_usd)
        if gap is not None:
            trips[gap.code] = gap
        trend = buy_guard.check_trend(
            btc_price, anchor, config.buy_guard_trend_usd, config.buy_guard_trend_markets
        )
        if trend is not None:
            trips[trend.code] = trend
        return trips, gap_now, trend_now

    def _check_buy_guards(self, market: MarketInfo | None, config, gap_inputs: tuple | None = None) -> None:
        """Switch buying off when a guard's limit is crossed. Only while running, only when buying is still on."""
        if market is None or self._status != "RUNNING":
            return
        try:
            trips, gap_now, trend_now = self._guard_readings(market, config, gap_inputs)
        except Exception as exc:  # a guard bug must never stop the trading loop
            logger.exception("buy guard check failed")
            self.logs.log("ERROR", "error", f"buy guard check failed: {exc}", mode=self._mode)
            return
        # a guard the operator switched on over re-arms once its condition is gone (and only when it can be measured)
        if buy_guard.GAP_GUARD not in trips and gap_now is not None:
            self._guard_disarmed.discard(buy_guard.GAP_GUARD)
        if buy_guard.TREND_GUARD not in trips and trend_now is not None:
            self._guard_disarmed.discard(buy_guard.TREND_GUARD)
        if self._buys_off:
            return
        for code in (buy_guard.GAP_GUARD, buy_guard.TREND_GUARD):
            trip = trips.get(code)
            if trip is not None and code not in self._guard_disarmed:
                self._trip_buy_guard(trip, config)
                return

    def _trip_buy_guard(self, trip: buy_guard.GuardTrip, config) -> None:
        self._buys_off = True
        self._buys_off_by = trip.code
        self._buys_off_reason = trip.message
        self._buys_off_since = utcnow()
        self._persist_buys_state()
        title = GUARD_TITLES.get(trip.code, trip.code)
        self.logs.log(
            "CRITICAL",
            "risk",
            f"BUYING OFF: {title}. {trip.message}",
            payload={"guard": trip.code, "move_usd": round(trip.value, 2), "limit_usd": trip.limit},
            mode=self._mode,
        )
        resume_at = self._buys_resume_at(config)
        self._notify_telegram(
            tg.guard_alert(
                title,
                trip.details or (("Detail", trip.message),),
                int(config.buy_guard_resume_minutes) if resume_at is not None else None,
                f"{resume_at:%H:%M}" if resume_at is not None else None,
            ),
            tg.MAIN_KEYBOARD,
        )
        self._schedule_broadcast()

    def _buys_resume_at(self, config) -> datetime | None:
        """When buying switches itself back on (naive UTC), or None while it is on / never switches back on.

        Computed from the moment buying went off, so changing the delay (Telegram, dashboard, app) applies to a
        countdown that is already running. A guard trip waits ``buy_guard_resume_minutes`` (0 = never); the
        operator's "Off" waits for the next day's start (00:00 UTC, the daily-limit boundary).
        """
        if not self._buys_off or self._buys_off_since is None:
            return None
        if self._buys_off_by == MANUAL_OFF:
            return start_of_day_utc(self._buys_off_since) + timedelta(days=1)
        minutes = int(config.buy_guard_resume_minutes)
        if minutes <= 0:
            return None
        return self._buys_off_since + timedelta(minutes=minutes)

    def _maybe_auto_resume(self, config) -> str | None:
        """Switch buying back on when its resume time has come. Returns the Telegram text, or None if nothing changed.

        This resets every guard to zero: the ones the operator had switched back on over are re-armed too, so
        if BTC is still past a limit the very next check trips the guard again (and Telegram is told again).
        """
        resume_at = self._buys_resume_at(config)
        if resume_at is None or utcnow() < resume_at:
            return None
        by = self._buys_off_by
        title = GUARD_TITLES.get(by, "Buying turned off")
        self._buys_off = False
        self._buys_off_by = ""
        self._buys_off_reason = ""
        self._buys_off_since = None
        self._guard_disarmed = set()
        self._persist_buys_state()
        self.orders.reset_trigger_cache()
        if by == MANUAL_OFF:
            text = tg.auto_resumed(new_day=True, minutes=0, title=title)
            log = "buying turned ON automatically: the day it was switched off has ended"
        else:
            minutes = int(config.buy_guard_resume_minutes)
            text = tg.auto_resumed(new_day=False, minutes=minutes, title=title)
            log = f"buying turned ON automatically {minutes} min after: {title}"
        self.logs.log("WARNING", "risk", log, payload={"enabled": True, "source": "auto_resume", "was": by}, mode=self._mode)
        self._schedule_broadcast()
        return text

    def _buy_switch_tick(self) -> None:
        """Once a second: switch buying back on when its wait is over, then judge the guards.

        Auto-resume goes first and the guards right after, in the same tick: if BTC is still past a limit the
        guard trips again at once (new Telegram alert, new wait) and no buy can slip in between.
        """
        running = self._status == "RUNNING" and self._market is not None
        if not self._buys_off and not running:
            return
        config = self.runtime.get()
        resumed = self._maybe_auto_resume(config)
        if running:
            self._check_buy_guards(self._market, config)
        if resumed and not self._buys_off:
            self._notify_telegram(resumed, tg.MAIN_KEYBOARD)

    def _schedule_broadcast(self) -> None:
        """Push the new state to open dashboards from sync code (a guard trip runs inside the trading path)."""
        try:
            task = asyncio.get_running_loop().create_task(self._broadcast_state())
        except RuntimeError:
            return
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    def _notify_telegram(self, text: str, keyboard: dict | None = None) -> None:
        if not self.telegram.enabled:
            return
        try:
            task = asyncio.get_running_loop().create_task(self.telegram.send(text, keyboard))
        except RuntimeError:
            return
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)

    async def _change_buys(self, enabled: bool, source: str) -> bool:
        """Turn buying on/off. Returns True when the state actually changed."""
        changed = False
        if enabled:
            if self._buys_off:
                market = self._market
                if market is not None:
                    try:
                        trips, _gap, _trend = self._guard_readings(market, self.runtime.get())
                        # the operator accepts today's conditions: these guards stay quiet until they clear
                        self._guard_disarmed.update(trips)
                    except Exception:
                        logger.exception("buy guard disarm failed")
                self._buys_off = False
                self._buys_off_by = ""
                self._buys_off_reason = ""
                self._buys_off_since = None
                changed = True
        elif not self._buys_off or self._buys_off_by != MANUAL_OFF:
            # The operator's "Off" (also pressed while a guard has buying off): no auto-resume timer, buying
            # stays off for the rest of the day and comes back at the next day's start.
            self._buys_off = True
            self._buys_off_by = MANUAL_OFF
            self._buys_off_reason = f"turned off by {source}"
            self._buys_off_since = utcnow()
            self._guard_disarmed = set()
            changed = True
        if changed:
            self._persist_buys_state()
            self.orders.reset_trigger_cache()
            self.logs.log(
                "WARNING",
                "risk",
                f"buying turned {'ON' if enabled else 'OFF'} by {source}",
                payload={"enabled": enabled, "source": source},
                mode=self._mode,
            )
            if source != "telegram":
                self._notify_telegram(tg.switched(enabled, source), tg.MAIN_KEYBOARD)
            await self._broadcast_state()
        return changed

    async def set_buys(self, enabled: bool, source: str = "dashboard") -> dict:
        await self._change_buys(enabled, source)
        return await self.snapshot()

    def buy_guard_status(self, market: MarketInfo | None, config) -> dict:
        gap_now = trend_now = None
        if market is not None:
            try:
                _trips, gap_now, trend_now = self._guard_readings(market, config)
            except Exception:
                logger.debug("buy guard readings failed", exc_info=True)
        resume_at = self._buys_resume_at(config)
        return {
            "buys_enabled": not self._buys_off,
            "off_by": self._buys_off_by or None,
            "reason": self._buys_off_reason or None,
            "since": iso_z(self._buys_off_since),
            "resume_at": iso_z(resume_at),
            "resume_minutes": config.buy_guard_resume_minutes,
            "gap_limit_usd": config.buy_guard_gap_usd,
            "gap_now_usd": None if gap_now is None else round(gap_now, 2),
            "trend_limit_usd": config.buy_guard_trend_usd,
            "trend_markets": config.buy_guard_trend_markets,
            "trend_now_usd": None if trend_now is None else round(trend_now, 2),
            "disarmed": sorted(self._guard_disarmed),
            "telegram_enabled": self.telegram.enabled,
        }

    async def _on_telegram_command(self, command: str, arg: str = "") -> str | None:
        """Commands from the owner's Telegram chat (typed or tapped): Buy / Off / Status / Delay / Help.

        Returns the reply as Telegram HTML (see telegram_format.py).
        """
        if command == "buy":
            changed = await self._change_buys(True, "telegram")
            notes: list[str] = []
            if self._kill_switch:
                notes.append("⚠️ The kill switch is active, so nothing buys until it is cleared.")
            elif self._status != "RUNNING":
                notes.append("⚠️ The bot itself is STOPPED: start it from the dashboard / app.")
            if changed:
                notes.append("The guard that stopped buying stays quiet until BTC is back inside its limit.")
            return tg.buy_reply(changed, notes)
        if command == "stop":
            changed = await self._change_buys(False, "telegram")
            return tg.stop_reply(changed, self._buys_block_reason())
        if command == "status":
            return self._status_text()
        if command == "help":
            return tg.help_reply(self._status_text())
        if command == "delay":
            return await self._telegram_delay(arg)
        return None

    def _status_text(self) -> str:
        status = self.buy_guard_status(self._market, self.runtime.get())
        gap = trend = None
        if status["gap_now_usd"] is not None:
            gap = f"{status['gap_now_usd']:+,.2f} USD (limit {status['gap_limit_usd']:,.0f})"
        if status["trend_now_usd"] is not None:
            trend = (
                f"{status['trend_now_usd']:+,.2f} USD / {status['trend_markets']} markets "
                f"(limit {status['trend_limit_usd']:,.0f})"
            )
        return tg.status_reply(
            bot=f"{self._status} ({self._mode})",
            buying_on=status["buys_enabled"],
            gap=gap,
            trend=trend,
            off_reason=self._buys_block_reason(),
            resume_line=self._resume_line(status),
        )

    def _resume_line(self, status: dict) -> str:
        """One line about the auto-resume: when buying comes back (or how long the wait after a guard trip is)."""
        minutes = int(status["resume_minutes"])
        wait = f"{minutes} min" if minutes > 0 else "off"
        if status["buys_enabled"]:
            return f"Auto-resume wait after a guard trip: {wait}"
        resume_at = self._buys_resume_at(self.runtime.get())
        if resume_at is None:
            return 'Auto-resume: off (send "Buy" to turn buying on; "Delay 60" turns auto-resume on)'
        left = max(0, int((resume_at - utcnow()).total_seconds()))
        kind = "next day" if self._buys_off_by == MANUAL_OFF else f"after the {wait} wait"
        return f"Buying comes back by itself at ~{resume_at:%H:%M} UTC ({kind}, in {left // 60} min)"

    async def _telegram_delay(self, arg: str) -> str:
        """'Delay' shows the wait; 'Delay 30' / 'Delay 2h' / 'Delay off' changes it (the same setting as the dashboard)."""
        config = self.runtime.get()
        current = int(config.buy_guard_resume_minutes)
        if not arg.strip():
            return tg.delay_reply(current)
        minutes = parse_minutes(arg)
        if minutes is None:
            return (
                "⚠️ I could not read that. Examples: "
                "<code>Delay 30</code>, <code>Delay 90m</code>, <code>Delay 2h</code>, <code>Delay off</code> "
                "(max 24 h)."
            )
        if minutes != current:
            await self.update_settings({"buy_guard_resume_minutes": minutes})
        detail = ""
        if minutes > 0:
            resume_at = self._buys_resume_at(self.runtime.get())
            if resume_at is not None and self._buys_off_by != MANUAL_OFF:
                if resume_at <= utcnow():
                    detail = "The new wait has already passed, so buying turns back on within a second."
                else:
                    detail = (
                        f"Buying is off right now and comes back at ~{resume_at:%H:%M} UTC "
                        "(counted from when it went off)."
                    )
            else:
                detail = "It applies to the next guard trip."
        return tg.delay_set_reply(minutes, detail)

    # ------------------------------------------------------------------
    async def start_bot(self, confirm_live: bool = False, confirmation_phrase: str = "") -> dict:
        async with self._lock:
            start_generation = self._stop_generation
            if self._status == "RUNNING":
                return await self.snapshot()
            if self._kill_switch:
                raise BotError("kill switch is active; clear it before starting")
            gate = await self._live_gate(self._mode, confirm_live, confirmation_phrase, validate=True)
            if self._mode == "live":
                await self._reconcile_open_live_orders()
                with session_scope() as session:
                    unresolved = session.execute(
                        select(Order.id).where(
                            Order.mode == "live",
                            Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                        )
                    ).scalars().all()
                if unresolved:
                    raise LiveGateError(
                        f"{len(unresolved)} unresolved live order(s) remain; reconcile them before starting"
                    )
                if self._market is not None:
                    await self.live_engine.prewarm(
                        [self._market.up_token_id, self._market.down_token_id], self._market.market_id
                    )
            if self._stop_generation != start_generation or self._kill_switch:
                raise BotError("start request was canceled by a stop or kill switch")
            self._status = "RUNNING"
            self._started_at = utcnow()
            with session_scope() as session:
                state = session.get(AppState, 1)
                if state:
                    state.status = "RUNNING"
                    state.started_at = self._started_at
            self._risk_halt_reason = None
            self.orders.reset_trigger_cache()
            if self._mode == "live":
                self.live_engine.allow_new_orders()
            self.logs.log(
                "INFO",
                "system",
                f"bot started in {self._mode.upper()} mode",
                payload={"mode": self._mode, "live_balance": gate.get("balance")},
                mode=self._mode,
            )
        await self._broadcast_state()
        return await self.snapshot()

    async def reset_daily_stats(self) -> dict:
        """Reset today's trade count and daily profit/loss for the active mode.

        History (orders, fills, positions, analytics) is kept. The daily loss stop,
        profit target and max-trades-per-day limits start counting from zero again.
        """
        async with self._lock:
            with session_scope() as session:
                self.portfolio.reset_daily(session, self._mode)
            self._risk_halt_reason = None
            self.orders.reset_trigger_cache()
            self.logs.log(
                "WARNING",
                "system",
                f"daily trades and profit/loss reset ({self._mode})",
                mode=self._mode,
            )
        await self._broadcast_state()
        return await self.snapshot()

    async def clear_order_history(self) -> dict:
        """Clear the order-history list of the active mode (paper or live).

        This only hides the rows from the list (server-side marker). Nothing is deleted, so
        Daily PnL, trades today, analytics, positions and risk limits are not changed, and it
        works while the bot is running.
        """
        async with self._lock:
            mode = self._mode
            with session_scope() as session:
                hidden = self.portfolio.clear_orders_view(session, mode)
            self.logs.log(
                "INFO",
                "system",
                f"{mode} order history cleared from view ({hidden} orders hidden, nothing deleted)",
                mode=mode,
            )
        await self._broadcast_state()
        return await self.snapshot()

    async def reset_server(self) -> dict:
        """Wipe the whole database (paper + live records, logs, setting overrides) and
        return the app to a fresh STOPPED paper state. Requires the bot to be stopped."""
        if self._status == "RUNNING":
            raise BotError("stop the bot before resetting the server")
        async with self._lock:
            with session_scope() as session:
                live_open = self.portfolio.open_positions(session, "live")
                live_orders = session.execute(
                    select(Order.id).where(
                        Order.mode == "live",
                        Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                    )
                ).scalars().all()
                if live_open or live_orders:
                    raise BotError(
                        "live positions/orders are still open; settle or redeem them first "
                        "so real-money records are not lost"
                    )
                self.portfolio.reset_all(session)
            with session_scope() as session:
                self.runtime.load(session)
            self._mode = "paper"
            self._kill_switch = False
            self._status = "STOPPED"
            self._started_at = None
            self._evaluation = None
            self._last_order = None
            self._live_balance = None
            self._live_balance_at = 0.0
            self._latencies.clear()
            self._last_price_persist_ms.clear()
            self._market_finalized.clear()
            self._resolution_pending_logged.clear()
            self._stuck_logged.clear()
            self._redeem_queue.clear()
            self._redeem_notice_logged.clear()
            self._risk_halt_reason = None
            self._buys_off = False
            self._buys_off_by = ""
            self._buys_off_reason = ""
            self._buys_off_since = None
            self._guard_disarmed = set()
            self._persist_buys_state()
            self.orders.reset_trigger_cache()
            self.logs.log(
                "WARNING",
                "system",
                "full server reset: database wiped, settings back to .env defaults",
                mode="paper",
            )
        await self._broadcast_state()
        return await self.snapshot()

    async def stop_bot(self, reason: str = "operator") -> dict:
        self._stop_generation += 1
        # a stopped bot (stop button, kill switch, mode switch) fires nothing later on its own
        self._limits.clear()
        self.live_engine.block_new_orders()
        async with self._lock:
            was_running = self._status == "RUNNING"
            if was_running:
                self._status = "STOPPED"
                self._started_at = None
                with session_scope() as session:
                    state = session.get(AppState, 1)
                    if state:
                        state.status = "STOPPED"
                        state.started_at = None
            market = self._market
            if self._mode == "live":
                await self.orders.wait_for_submissions()
                await self.orders.cancel_live_orders()
            else:
                await self.orders.wait_for_submissions()
                if was_running and market is not None:
                    await self.orders.finalize_market(market.market_id, self._mode, f"bot stopped ({reason})")
            if was_running:
                self.logs.log(
                    "WARNING",
                    "system",
                    f"bot stopped ({reason})",
                    payload={"mode": self._mode},
                    mode=self._mode,
                )
        await self._broadcast_state()
        return await self.snapshot()

    async def switch_mode(
        self, mode: str, confirm_live: bool = False, confirmation_phrase: str = ""
    ) -> dict:
        if mode not in {"paper", "live"}:
            raise ModeSwitchError("mode must be 'paper' or 'live'")
        async with self._lock:
            if self._status == "RUNNING":
                raise ModeSwitchError("stop the bot before switching modes")
            with session_scope() as session:
                active = session.execute(
                    select(Order).where(
                        Order.mode == mode,
                        Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                    )
                ).scalars().all()
                if active:
                    raise ModeSwitchError(
                        f"{len(active)} outstanding order(s) must be resolved before switching"
                    )
            gate = await self._live_gate(mode, confirm_live, confirmation_phrase, validate=True)
            previous = self._mode
            self._mode = mode
            with session_scope() as session:
                state = session.get(AppState, 1)
                if state:
                    state.mode = mode
            self._evaluation = None
            self._last_order = None
            self._live_balance = gate.get("balance") if mode == "live" else None
            self.logs.log(
                "WARNING" if mode == "live" else "INFO",
                "system",
                f"execution mode switched {previous.upper()} -> {mode.upper()}",
                payload={"previous": previous, "mode": mode},
                mode=mode,
            )
            if mode == "live":
                await self._reconcile_live_positions()
        await self._broadcast_state()
        return await self.snapshot()

    async def _live_gate(
        self, mode: str, confirm_live: bool, phrase: str, validate: bool
    ) -> dict:
        if mode != "live":
            return {}
        if not confirm_live:
            raise LiveGateError("live trading requires explicit confirmation")
        expected = self.settings.live_confirmation_phrase
        if not phrase or not secrets.compare_digest(phrase.strip(), expected):
            raise LiveGateError("confirmation phrase does not match")
        if not self.settings.live_trading_enabled:
            raise LiveGateError("LIVE_TRADING_ENABLED is false in .env")
        if not self.settings.polymarket_geo_verified:
            raise LiveGateError(
                "POLYMARKET_GEO_VERIFIED is false; verify geo eligibility before live trading"
            )
        if not self.settings.live_credentials_present:
            raise LiveGateError("POLYMARKET_PRIVATE_KEY is not configured")
        if validate:
            try:
                result = await self.live_engine.validate()
            except LiveTradingUnavailable as exc:
                raise LiveGateError(str(exc)) from exc
            self._live_balance = float(result["balance"])
            self._live_balance_at = time.monotonic()
            with session_scope() as session:
                self.portfolio.set_cached_balance(session, "live", self._live_balance)
            return result
        return {}

    async def manual_sell(self, market_id: str, side: str, stage: int) -> dict:
        """Operator "sell now": sell the shares `stage` holds on `side` in the live market at the market.

        Instant and unconfirmed by design: it runs the moment it is called. It is not held back by a
        risk halt (selling only reduces exposure), but it needs the bot running with an open market.
        """
        market = self._market
        if self._status != "RUNNING" or self._kill_switch:
            raise BotError("the bot must be running to sell")
        if market is None or market.market_id != market_id or market.remaining_seconds() <= 0:
            raise BotError("this market has ended: its shares settle automatically")
        self.logs.log(
            "WARNING",
            "system",
            f"manual SELL requested: S{stage} {side}",
            payload={"market_id": market_id, "side": side, "stage": stage, "mode": self._mode},
            mode=self._mode,
        )
        result = await self.orders.manual_sell(
            market, self.data.books, self._mode, self.runtime.get(), side, stage
        )
        reasons = {
            "no_holding": "nothing left to sell for this order",
            "sell_in_flight": "a sell for this order is already in progress",
            "no_bid": "no bid in the order book right now",
            "market_ended": "this market has ended: its shares settle automatically",
            "unknown_stage": "unknown stage",
        }
        if result.status == "BLOCKED":
            raise BotError(reasons.get(result.reason, f"sell blocked: {result.reason}"))
        self._last_order = result.as_dict()
        self._last_order["ts"] = iso_z(utcnow())
        if result.latency_ms is not None:
            self._latencies.append(result.latency_ms)
        if result.status in {"REJECTED", "CANCELED"} and result.filled_size <= 0:
            # nothing was sold: the shares are still held, so the limit of this card stays
            raise BotError(f"sell {result.status.lower()}: {result.error or 'no fill'}")
        if stage != TAP_STAGE:
            # a stage's limit goes with the shares it was for (a one-tap limit stays: it is set ahead of buys)
            self._limits.pop((market_id, side, stage), None)
        snapshot = await self._snapshot_and_publish()
        snapshot["manual_sell"] = result.as_dict()
        return snapshot

    async def tap_buy(
        self,
        market_id: str,
        side: str,
        amount: float,
        request_id: str | None = None,
        min_ask: float | None = None,
    ) -> dict:
        """One-tap buy: buy `amount` USDC of `side` in the live market right now, at the market.

        Manual and separate from the strategy: no stage rules, no risk limits of the strategy, no buy guard
        and no take-profit / stop-loss. The shares are tagged "tap" and stay until the market settles or the
        operator sells them from the positions list. The kill switch, a stopped bot and a missing / stale
        order book still block it, and so does a second tap within one second. A side priced below
        the floor is blocked too: `min_ask` (the owner's setting, sent with the tap) or TAP_MIN_ASK (40 cents)
        when none is sent; a floor of 0 turns the check off.
        """
        market = self._market
        if self._status != "RUNNING" or self._kill_switch:
            raise BotError("the bot must be running to buy")
        if market is None or market.market_id != market_id or market.remaining_seconds() <= 0:
            raise BotError("this market has ended: wait for the next one")
        limit = float(getattr(self.settings, "tap_buy_max_usdc", 100.0))
        if not (TAP_MIN_USDC <= amount <= limit):
            raise BotError(f"one-tap amount must be between ${TAP_MIN_USDC:g} and ${limit:g}")
        # No database round trip between the tap and the exchange call: live uses the cached balance,
        # paper reads it in a worker thread so the event loop (order books, other taps) is never blocked.
        balance = await self._balance_fast()
        if amount > balance + 1e-9:
            raise BotError(f"not enough balance: ${balance:.2f} available")
        self.logs.log(
            "INFO",
            "system",
            f"one-tap BUY requested: {side} ${amount:.2f}",
            payload={"market_id": market_id, "side": side, "amount": amount, "mode": self._mode},
            mode=self._mode,
        )
        result = await self.orders.tap_buy(
            market, self.data.books, self._mode, self.runtime.get(), side, amount, request_id, min_ask
        )
        reasons = {
            "market_ended": "this market has ended: wait for the next one",
            "tap_cooldown": "second tap ignored: wait one second between taps",
            "tap_in_flight": "a one-tap buy is already in progress",
            "duplicate_request": "this tap was already received",
            "no_ask": "no ask in the order book right now",
            "ask_too_low": "this side is priced under your one-tap minimum: buy is off for it",
            "stale_book": "the order book is out of date: try again in a moment",
            "bot_not_accepting_orders": "the bot is not accepting orders right now",
            "bad_side": "side must be UP or DOWN",
        }
        if result.status == "BLOCKED":
            raise BotError(reasons.get(result.reason, f"buy blocked: {result.reason}"))
        self._last_order = result.as_dict()
        self._last_order["ts"] = iso_z(utcnow())
        if result.latency_ms is not None:
            self._latencies.append(result.latency_ms)
        if result.status in {"REJECTED", "CANCELED"} and result.filled_size <= 0:
            raise BotError(f"buy {result.status.lower()}: {result.error or 'no fill'}")
        snapshot = await self._snapshot_and_publish()
        snapshot["tap_buy"] = result.as_dict()
        return snapshot

    async def manual_sell_all(self, market_id: str, scope: str = "stages") -> dict:
        """Operator "sell all": sell the shares of every stage (0-3) that holds any in the live market, at the market.

        One-tap shares are NOT included: `scope="tap"` ("Sell all tap") sells those and only those.

        Same rules as `manual_sell` (instant, no confirmation, not held back by a risk halt, needs the bot
        running with an open market). Stages that hold nothing are skipped; if some stages cannot be sold
        (no bid, a sell already in progress) the others still go through and the rest is reported back.
        """
        market = self._market
        if self._status != "RUNNING" or self._kill_switch:
            raise BotError("the bot must be running to sell")
        if market is None or market.market_id != market_id or market.remaining_seconds() <= 0:
            raise BotError("this market has ended: its shares settle automatically")
        self.logs.log(
            "WARNING",
            "system",
            "manual SELL ALL TAP requested" if scope == "tap" else "manual SELL ALL requested",
            payload={"market_id": market_id, "mode": self._mode, "scope": scope},
            mode=self._mode,
        )
        results = await self.orders.manual_sell_all(
            market, self.data.books, self._mode, self.runtime.get(), scope
        )
        if not results:
            raise BotError("nothing left to sell in this market")
        reasons = {
            "no_holding": "nothing left to sell",
            "sell_in_flight": "a sell is already in progress",
            "no_bid": "no bid in the order book right now",
            "market_ended": "this market has ended: its shares settle automatically",
        }
        def _went_through(r) -> bool:
            return r.status not in {"BLOCKED", "REJECTED", "CANCELED"} or r.filled_size > 0

        sold = [r for r in results if _went_through(r)]
        failed = [r for r in results if not _went_through(r)]
        if not sold:
            first = failed[0]
            if first.status == "BLOCKED":
                raise BotError(reasons.get(first.reason, f"sell blocked: {first.reason}"))
            raise BotError(f"sell {first.status.lower()}: {first.error or 'no fill'}")
        if scope != "tap":
            # a limit belongs to its own card: only the stages that really sold lose theirs. A stage whose
            # sell was blocked or refused still holds its shares, so its limit stays.
            sold_stages = {
                int(m.group(1))
                for m in (re.match(r"stage(\d+)_", r.reason or "") for r in sold)
                if m is not None
            }
            for stage_key in [
                k for k in self._limits
                if k[0] == market_id and k[2] != TAP_STAGE and k[2] in sold_stages
            ]:
                self._limits.pop(stage_key, None)
        last = sold[-1]
        self._last_order = last.as_dict()
        self._last_order["ts"] = iso_z(utcnow())
        for result in sold:
            if result.latency_ms is not None:
                self._latencies.append(result.latency_ms)
        snapshot = await self._snapshot_and_publish()
        snapshot["manual_sell_all"] = {
            "sold": [r.as_dict() for r in sold],
            "failed": [
                {**r.as_dict(), "message": reasons.get(r.reason, r.error or r.status)} for r in failed
            ],
        }
        return snapshot

    async def set_kill_switch(self, active: bool) -> dict:
        if active:
            self._kill_switch = True
            self.live_engine.block_new_orders()
            with session_scope() as session:
                state = session.get(AppState, 1)
                if state:
                    state.kill_switch = True
            await self.stop_bot("kill switch")
            await self.orders.wait_for_submissions()
            await self.orders.cancel_live_orders()
        else:
            async with self._lock:
                if self._status == "RUNNING":
                    raise BotError("stop the bot before clearing the kill switch")
                self._kill_switch = False
                with session_scope() as session:
                    state = session.get(AppState, 1)
                    if state:
                        state.kill_switch = False
        self.logs.log(
            "CRITICAL" if active else "WARNING",
            "system",
            f"kill switch {'ENGAGED' if active else 'cleared'}",
            mode=self._mode,
        )
        await self._broadcast_state()
        return await self.snapshot()

    # ------------------------------------------------------------------
    # Safety-net loop timing: (seconds between looks, book silence needed before it acts). While
    # shares are held it wakes much faster, so a take-profit / stop-loss that is due, or whose last
    # FOK sell did not fill, never waits for the next book event or for a half-second tick.
    RETRY_TICK_S = 0.5
    RETRY_QUIET_MS = 500
    EXIT_RETRY_TICK_S = 0.01
    EXIT_RETRY_QUIET_MS = 10

    def _retry_timing(self) -> tuple[float, int]:
        market = self._market
        if market is not None and self.orders.has_stage0_holding(self._mode, market.market_id):
            return self.EXIT_RETRY_TICK_S, self.EXIT_RETRY_QUIET_MS
        return self.RETRY_TICK_S, self.RETRY_QUIET_MS

    async def _retry_loop(self) -> None:
        """Safety net for entries that were blocked or failed, and for exits that are still due.

        Trading is book-event driven. If an entry is blocked (stale book, balance, ...)
        or rejected and the book then goes quiet, no event would re-trigger it. While
        the market is in its stage 1 and the book has been silent for a moment,
        re-evaluate so every stage whose condition still holds is tried again. Duplicate
        protection in the order manager keeps this from double-buying. The same pass
        re-checks the exits (stop-loss / take-profit) of every stage that holds shares.
        """
        while not self._shutdown:
            await asyncio.sleep(self._retry_timing()[0])
            try:
                market = self._market
                if market is None or self._status != "RUNNING":
                    continue
                now_ms = int(time.time() * 1000)
                if now_ms - self._last_book_event_ms < self._retry_timing()[1]:
                    continue
                config = self.runtime.get()
                window = config.entry_window_seconds()
                if config.stage0_enabled:
                    window = max(window, config.stage0_start_seconds)
                has_exit = self.orders.has_stage0_holding(self._mode, market.market_id)
                if market.remaining_seconds() > window and not has_exit:
                    continue
                evaluation = self.strategy.evaluate(market, self.data.books, now_ms, config)
                if not evaluation.signals and not has_exit:
                    continue
                self._evaluation = evaluation
                await self._handle_trading(market, evaluation, config, now_ms)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("entry retry loop error: %s", exc)

    # ------------------------------------------------------------------
    async def _maintenance_loop(self) -> None:
        while not self._shutdown:
            # 1s tick, and live orders are reconciled on every tick: an order whose response did not
            # carry the fill (so the position is not known yet) must not wait several seconds.
            # Reconciling only costs a REST call per order that is still open.
            await asyncio.sleep(1.0)
            try:
                # Guards are also judged once a second with no signal needed, so the Telegram alert goes out
                # as soon as BTC crosses a limit, not only when a buy happens to be attempted.
                self._buy_switch_tick()
                await self._expire_market_if_due()
                now = time.monotonic()
                if now - self._last_resolution_poll >= self.settings.resolution_poll_seconds:
                    self._last_resolution_poll = now
                    await self._poll_resolutions()
                if (
                    self._mode == "live"
                    and self._status == "RUNNING"
                    and now - self._last_live_reconcile >= 1.0
                ):
                    self._last_live_reconcile = now
                    await self._reconcile_open_live_orders()
                if self._mode == "live" and self._status == "RUNNING":
                    # Keep the CLOB HTTP/2 connection open: a stop-loss must not pay a new
                    # TLS handshake because the socket idled out (default keep-alive is 5s).
                    await self.live_engine.keep_connection_warm()
                if self._mode == "live" and self._redeem_queue:
                    await self._process_redeem_queue()
                if self._mode == "live" and now - self._live_balance_at >= 15.0:
                    await self._refresh_live_balance()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("maintenance loop error: %s", exc)

    async def _expire_market_if_due(self) -> None:
        market = self._market
        if market is None or market.remaining_seconds() > 0:
            return
        if market.market_id in self._market_finalized:
            return
        self._marks()  # refresh last known bids before the market is dropped
        self._snapshot_unrealized(market.market_id)
        await self.orders.finalize_market(market.market_id, self._mode, "market expired")
        self._market_finalized.add(market.market_id)
        self.logs.log(
            "INFO",
            "market",
            f"stage 1 {self.runtime.get().stage1_seconds}s window closed for {market.slug}",
            payload={"market_id": market.market_id},
            mode=self._mode,
        )
        self._market = None
        self.data.set_market(None)
        await self._broadcast_state()

    async def _retrack_unsettled_markets(self) -> None:
        """Re-register markets that still hold open positions (e.g. after a restart).

        Resolution polling used to rely on in-memory tracking only, so a position whose
        market was no longer tracked stayed "Unrealized" forever.
        """
        with session_scope() as session:
            market_ids = set(
                session.execute(
                    select(Position.market_id).where(Position.status == "open")
                ).scalars().all()
            )
            if not market_ids:
                return
            rows = session.execute(select(Market).where(Market.market_id.in_(market_ids))).scalars().all()
            infos = [
                MarketInfo(
                    market_id=r.market_id,
                    slug=r.slug,
                    event_slug=r.event_slug or r.slug,
                    series_id=r.series_id or "",
                    window_start=r.window_start,
                    window_end=r.window_end,
                    up_token_id=r.up_token_id,
                    down_token_id=r.down_token_id,
                    tick_size=r.tick_size or 0.01,
                    min_order_size=r.min_order_size or 5.0,
                )
                for r in rows
            ]
        for info in infos:
            if info.slug and not self.monitor.is_tracked(info.slug):
                self.monitor.track(info)

    async def _poll_resolutions(self) -> None:
        await self._retrack_unsettled_markets()
        results = await self.monitor.poll_resolution()
        resolved_ids: set[str] = set()
        for info in results:
            await self._apply_resolution(info)
            if info.outcome:
                resolved_ids.add(info.market_id)
        if self._mode == "live":
            await self._settle_from_wallet(resolved_ids)
        await self._diagnose_stuck_markets(resolved_ids)

    STUCK_AFTER_SECONDS = 180.0

    async def _diagnose_stuck_markets(self, resolved_ids: set[str]) -> None:
        """Log once, with the raw CLOB/Gamma answers, why an ended market is still unsettled."""
        for market in self.monitor.open_markets.values():
            if market.market_id in resolved_ids or market.market_id in self._stuck_logged:
                continue
            if market.remaining_seconds() > -self.STUCK_AFTER_SECONDS:
                continue
            with session_scope() as session:
                has_open = session.execute(
                    select(Position.id).where(
                        Position.market_id == market.market_id, Position.status == "open"
                    )
                ).first()
            if not has_open:
                continue
            self._stuck_logged.add(market.market_id)
            try:
                detail = await self.discovery.describe_resolution(market)
            except Exception as exc:  # diagnostics only
                detail = {"error": type(exc).__name__}
            self.logs.log(
                "WARNING",
                "settlement",
                f"market {market.slug} ended >{int(self.STUCK_AFTER_SECONDS)}s ago but is still unsettled",
                payload={"market_id": market.market_id, **detail},
                mode=self._mode,
            )

    async def _apply_resolution(self, info) -> None:
        with session_scope() as session:
            row = session.execute(
                select(Market).where(Market.market_id == info.market_id)
            ).scalar_one_or_none()
            if row is not None:
                row.status = "resolved" if info.outcome else "closed"
            if info.outcome and row is not None:
                row.resolved_outcome = info.outcome
                row.resolved_at = utcnow()
        if info.outcome:
            for mode in ("paper", "live"):
                with session_scope() as session:
                    closed = self.portfolio.settle(session, mode, info.market_id, info.outcome)
                if closed and mode == "live" and any(p.resolution == p.side for p in closed):
                    self._queue_live_redeem(info.market_id)
                if closed:
                    pnl = round(sum(p.realized_pnl for p in closed), 6)
                    self.logs.log(
                        "INFO",
                        "settlement",
                        f"market resolved {info.outcome}; {len(closed)} position(s) settled, pnl {pnl:+.4f} USDC",
                        payload={
                            "market_id": info.market_id,
                            "outcome": info.outcome,
                            "positions": len(closed),
                            "pnl": pnl,
                            "mode": mode,
                        },
                        mode=mode,
                    )
            await self.orders.finalize_market(info.market_id, "paper", "market resolved")
            await self.orders.finalize_market(info.market_id, "live", "market resolved")
            await self._broadcast_state()
        elif info.market_id not in self._resolution_pending_logged:
            self._resolution_pending_logged.add(info.market_id)
            self.logs.log(
                "WARNING",
                "settlement",
                f"market {info.market_id[:10]}… closed but outcome not published yet",
                payload={"market_id": info.market_id},
                mode=self._mode,
            )

    async def _settle_from_wallet(self, already_resolved: set[str]) -> None:
        """Live fallback: settle ended positions from the wallet's redeemable tokens.

        Gamma / CLOB can lag (or never flag the winner) while Polymarket already lets the
        wallet redeem. Without this the UI stays on "settling…" after a manual redeem.
        """
        now = utcnow()
        with session_scope() as session:
            pending = {
                m.market_id.lower(): (m.market_id, m.slug)
                for m in session.execute(
                    select(Market).where(
                        Market.market_id.in_(
                            select(Position.market_id).where(
                                Position.mode == "live", Position.status == "open"
                            )
                        ),
                        Market.window_end <= now,
                    )
                ).scalars().all()
                if m.market_id not in already_resolved
            }
        if not pending:
            return
        wallet = await self.live_engine.wallet_resolutions()
        if not wallet:
            return
        for key, (market_id, slug) in pending.items():
            outcome = wallet.get(key)
            if outcome:
                self.monitor.untrack(slug)
                self.logs.log(
                    "INFO",
                    "settlement",
                    f"market {market_id[:10]}… resolved {outcome} per wallet (redeemable); settling",
                    payload={"market_id": market_id, "outcome": outcome},
                    mode="live",
                )
                await self._apply_resolution(ResolutionInfo(market_id, True, outcome))

    # ------------------------------------------------------------------
    def _queue_live_redeem(self, market_id: str) -> None:
        """A live win only becomes pUSD after redemption (official Polymarket docs)."""
        if not self.settings.live_auto_redeem:
            if market_id not in self._redeem_notice_logged:
                self._redeem_notice_logged.add(market_id)
                self.logs.log(
                    "WARNING",
                    "settlement",
                    "live win settled but winning tokens are not redeemed yet; pUSD balance "
                    "updates after you claim on polymarket.com (Portfolio) or set LIVE_AUTO_REDEEM=true",
                    payload={"market_id": market_id},
                    mode="live",
                )
            return
        self._redeem_queue.setdefault(market_id, {"attempts": 0, "next_at": 0.0})

    async def _process_redeem_queue(self) -> None:
        now = time.monotonic()
        for market_id, entry in list(self._redeem_queue.items()):
            if now < entry["next_at"]:
                continue
            entry["attempts"] += 1
            try:
                result = await self.live_engine.redeem_market(market_id)
            except Exception as exc:
                result = {"status": "error", "error": f"redemption failed ({type(exc).__name__})"}
            status = result.get("status")
            if status in ("redeemed", "already_redeemed"):
                self._redeem_queue.pop(market_id, None)
                self.logs.log(
                    "INFO",
                    "settlement",
                    "winning tokens redeemed into pUSD"
                    if status == "redeemed"
                    else "winning tokens were already redeemed (none left in the wallet)",
                    payload={"market_id": market_id, **result},
                    mode="live",
                )
                self._live_balance_at = 0.0  # force a fresh balance read
                await self._refresh_live_balance()
                await self._broadcast_state()
            elif entry["attempts"] >= 8:
                self._redeem_queue.pop(market_id, None)
                self.logs.log(
                    "ERROR",
                    "settlement",
                    f"auto-redeem gave up after {entry['attempts']} attempts; claim manually on polymarket.com",
                    payload={"market_id": market_id, **result},
                    mode="live",
                )
            else:
                entry["next_at"] = now + 15.0 * entry["attempts"]
                if status == "error":
                    self.logs.log(
                        "WARNING",
                        "settlement",
                        f"auto-redeem attempt {entry['attempts']} failed; will retry",
                        payload={"market_id": market_id, **result},
                        mode="live",
                    )

    async def _queue_unredeemed_live_wins(self) -> None:
        """On live start: queue redeemable wins on markets this bot traded."""
        if not self.settings.live_auto_redeem:
            return
        redeemable = await self.live_engine.redeemable_condition_ids()
        if not redeemable:
            return
        with session_scope() as session:
            known = {
                m.lower()
                for m in session.execute(
                    select(Position.market_id).where(Position.mode == "live")
                ).scalars().all()
            }
        for market_id in redeemable & known:
            self._redeem_queue.setdefault(market_id, {"attempts": 0, "next_at": 0.0})

    # A FOK order resolves within seconds. Anything still "active" after this long
    # that the exchange can no longer tell us about is stuck, not in flight.
    STALE_LIVE_ORDER_SECONDS = 300

    @staticmethod
    def _open_live_order_ids() -> list[int]:
        with session_scope() as session:
            return list(
                session.execute(
                    select(Order.id).where(
                        Order.mode == "live",
                        Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                        Order.exchange_order_id.is_not(None),
                    )
                ).scalars().all()
            )

    async def _reconcile_open_live_orders(self) -> None:
        # This runs every second: its database reads go to a worker thread so the event loop (and with it a
        # one-tap click that arrives just now) never waits for a database round trip.
        order_ids = await asyncio.to_thread(self._open_live_order_ids)
        for order_id in order_ids:
            try:
                await self.orders.reconcile_live_order(order_id)
            except Exception as exc:
                logger.warning("live reconciliation failed for order %s: %s", order_id, exc)
        expired = await asyncio.to_thread(self._close_stale_live_orders)
        self._log_stale_live_orders(expired)

    def _expire_stale_live_orders(self) -> int:
        """Close live orders that can never be reconciled by exchange id.

        Covers (a) orders with no exchange_order_id (submission outcome unknown, e.g.
        the executor raised) and (b) orders the exchange no longer returns. Only
        orders with zero recorded fills and older than STALE_LIVE_ORDER_SECONDS are
        touched, so nothing that could still be filling is cancelled. Any real fill
        that was missed will show up in the on-chain position reconciliation.
        """
        expired = self._close_stale_live_orders()
        self._log_stale_live_orders(expired)
        return len(expired)

    def _close_stale_live_orders(self) -> list[dict]:
        """The database part of `_expire_stale_live_orders` (safe for a worker thread: it logs nothing)."""
        cutoff = utcnow() - timedelta(seconds=self.STALE_LIVE_ORDER_SECONDS)
        expired: list[dict] = []
        with session_scope() as session:
            stale = session.execute(
                select(Order).where(
                    Order.mode == "live",
                    Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                    Order.filled_size <= 0,
                    Order.created_at < cutoff,
                )
            ).scalars().all()
            for order in stale:
                order.status = "CANCELED"
                order.reason = "stale live order closed at startup"
                order.error = (
                    (order.error + "; " if order.error else "")
                    + "exchange outcome unknown; verify against on-chain positions"
                )
                expired.append({"order_id": order.id, "exchange_order_id": order.exchange_order_id})
        return expired

    def _log_stale_live_orders(self, expired: list[dict]) -> None:
        if expired:
            self.logs.log(
                "WARNING",
                "reconciliation",
                f"{len(expired)} stale live order(s) closed (no fills recorded, outcome unconfirmed)",
                payload={"orders": expired},
                mode="live",
            )

    async def _refresh_live_balance(self) -> None:
        balance = await self.live_engine.get_balance()
        if balance is None:
            return
        changed = self._live_balance is None or abs(self._live_balance - balance) > 1e-9
        self._live_balance = balance
        self._live_balance_at = time.monotonic()
        def _cache() -> None:
            with session_scope() as session:
                self.portfolio.set_cached_balance(session, "live", balance)

        try:
            await asyncio.to_thread(_cache)
        except Exception as exc:
            logger.debug("live balance cache failed: %s", exc)
        if changed:
            await self._broadcast_state()

    async def _reconcile_live_positions(self) -> None:
        if self._mode != "live":
            return
        await self._queue_unredeemed_live_wins()
        positions = await self.live_engine.fetch_onchain_positions()
        if positions is None:
            self.logs.log(
                "WARNING",
                "reconciliation",
                "on-chain live positions could not be fetched (funder address or data API unavailable)",
                mode="live",
            )
            return
        onchain = {}
        for item in positions:
            market_id = item.get("condition_id") or item.get("conditionId")
            outcome = str(item.get("outcome") or "").upper()
            try:
                size = float(item.get("current_size") or item.get("size") or 0)
            except (TypeError, ValueError):
                size = 0.0
            if market_id and outcome in {"UP", "DOWN"}:
                onchain[(str(market_id).lower(), outcome)] = size
        with session_scope() as session:
            local = session.execute(
                select(Position).where(Position.mode == "live", Position.status == "open")
            ).scalars().all()
            local_map: dict[tuple[str, str], float] = {}
            for p in local:  # the stage row and the one-tap row of one side hold shares of the same wallet position
                local_map[(p.market_id.lower(), p.side)] = local_map.get((p.market_id.lower(), p.side), 0.0) + p.size
        mismatches = []
        for key, size in onchain.items():
            local_size = local_map.get(key, 0.0)
            if abs(local_size - size) > 0.01:
                mismatches.append({"market_id": key[0], "side": key[1], "local": local_size, "onchain": size})
        self.logs.log(
            "WARNING" if mismatches else "INFO",
            "reconciliation",
            f"live positions reconciled: {len(onchain)} on-chain, {len(local_map)} local, {len(mismatches)} mismatch(es)",
            payload={"mismatches": mismatches},
            mode="live",
        )

    # ------------------------------------------------------------------
    def live_readiness(self) -> dict:
        reasons = []
        if not self.settings.live_trading_enabled:
            reasons.append("LIVE_TRADING_ENABLED is false")
        if not self.settings.polymarket_geo_verified:
            reasons.append("geo eligibility not verified")
        if not self.settings.live_credentials_present:
            reasons.append("private key not configured")
        return {
            "live_enabled": self.settings.live_trading_enabled,
            "geo_verified": self.settings.polymarket_geo_verified,
            "credentials_present": self.settings.live_credentials_present,
            "funder_configured": bool(self.settings.polymarket_funder_address),
            "ready": not reasons,
            "reasons": reasons,
            "balance": self._live_balance,
        }

    def _snapshot_db(self, mode: str, marks: dict, sellable_market_id: str | None) -> dict:
        """Everything `snapshot()` reads from the database, in one go, for a WORKER THREAD.

        It used to run on the event loop: with a hosted database every query is a network round trip, so
        a snapshot (built twice a second for an open dashboard, and again for every tap / sell reply)
        kept the loop busy for 100-400 ms. A one-tap click that arrived meanwhile was not even read, and
        the order books, exits and other taps stood still. Nothing here touches loop-owned state:
        `marks` and `sellable_market_id` are worked out on the loop and handed in.
        """
        live_balance = self._live_balance
        with session_scope() as session:
            balance = (
                live_balance
                if mode == "live" and live_balance is not None
                else self.portfolio.balance(session, mode)
            )
            account = self.portfolio.account(session, mode)
            starting_balance = account.starting_balance
            positions = self.portfolio.open_positions(session, mode)
            unrealized = self.portfolio.unrealized_pnl(session, mode, marks)
            pnl = self.portfolio.pnl_summary(session, mode, unrealized)
            # the daily limits (and the "risk halt" shown from them) only look at the strategy stages
            unrealized_stages = self.portfolio.unrealized_pnl(session, mode, marks, include_tap=False)
            pnl_limits = self.portfolio.pnl_summary(session, mode, unrealized_stages, include_tap=False)
            trades_today = self.portfolio.trades_today(session, mode)
            open_orders = session.execute(
                select(Order).where(
                    Order.mode == mode,
                    Order.status.in_(["PENDING", "SUBMITTED", "PARTIALLY_FILLED"]),
                )
            ).scalars().all()
            ends = {
                m.market_id: m.window_end
                for m in session.execute(
                    select(Market).where(Market.market_id.in_({p.market_id for p in positions}))
                ).scalars().all()
            } if positions else {}
            now_naive = utcnow()
            position_rows = [
                {
                    "id": p.id,
                    "market_id": p.market_id,
                    "side": p.side,
                    # "" = strategy stages 0-3, "tap" = one-tap buy
                    "tag": p.tag or "",
                    "size": p.size,
                    "avg_price": p.avg_price,
                    "cost_basis": p.cost_basis,
                    "fees": p.fees,
                    "unrealized_pnl": p.unrealized_pnl,
                    "realized_pnl": p.realized_pnl,
                    "status": p.status,
                    "resolution": p.resolution,
                    # window over, waiting for the exchange to publish the winner
                    "awaiting_settlement": bool(
                        p.status == "open"
                        and ends.get(p.market_id) is not None
                        and ends[p.market_id] <= now_naive
                    ),
                    "opened_at": iso_z(p.opened_at),
                    "closed_at": iso_z(p.closed_at),
                }
                for p in positions
            ]
            # Shares the operator can sell right now: only the live market, only while the bot runs.
            sellable_rows: list[dict] = []
            if sellable_market_id is not None:
                # one read of this market's orders and of its open positions serves every holding below
                # (it used to be two queries per position and stage)
                market_orders = session.execute(
                    select(Order).where(Order.mode == mode, Order.market_id == sellable_market_id)
                ).scalars().all()
                market_positions = {
                    (p.side, p.tag or ""): p for p in positions if p.market_id == sellable_market_id
                }
                for p in positions:
                    if p.market_id != sellable_market_id:
                        continue
                    # a one-tap position is its own row: only the tap holding belongs to it, and the strategy's
                    # stage holdings only to the untagged row of the same side
                    for stage in ((TAP_STAGE,) if p.tag == TAP_TAG else EXIT_STAGES):
                        held = self.orders.stage0_holding_in_session(
                            session, mode, p.market_id, stage,
                            p.side if stage == TAP_STAGE else None,
                            orders=market_orders,
                            positions=market_positions,
                        )
                        if (
                            held is not None
                            and held.side == p.side
                            and not held.sell_in_flight
                            and _floor_shares(held.size) >= SELL_DUST
                        ):
                            sellable_rows.append(
                                {
                                    "market_id": p.market_id,
                                    "side": p.side,
                                    "stage": stage,
                                    "size": _floor_shares(held.size),
                                    # average price paid per share by this stage alone (None if unknown)
                                    "entry_price": held.cost,
                                }
                            )
            order_rows = [
                {
                    "id": o.id,
                    "market_id": o.market_id,
                    "side": o.side,
                    "status": o.status,
                    "stage": self.orders._key_stage(o.order_key),
                    "tap": self.orders._key_stage(o.order_key) == TAP_STAGE,
                    "action": self.orders._key_action(o.order_key),
                    "requested_notional": o.requested_notional,
                    "requested_price": o.requested_price,
                    "submitted_price": o.submitted_price,
                    "filled_size": o.filled_size,
                    "avg_fill_price": o.avg_fill_price,
                    "fee": o.fee,
                    "latency_ms": o.latency_ms,
                    "route": o.route,
                    "exchange_order_id": o.exchange_order_id,
                    "reason": o.reason,
                    "created_at": iso_z(o.created_at),
                }
                for o in open_orders
            ]

        return {
            "balance": balance,
            "starting_balance": starting_balance,
            "pnl": pnl,
            "pnl_limits": pnl_limits,
            "trades_today": trades_today,
            "position_rows": position_rows,
            "sellable_rows": sellable_rows,
            "order_rows": order_rows,
        }

    async def snapshot(self) -> dict:
        config = self.runtime.get()
        market = self._market
        now_ms = int(time.time() * 1000)
        up_book = self.data.book_for("UP")
        down_book = self.data.book_for("DOWN")
        evaluation = (
            self.strategy.evaluate(market, self.data.books, now_ms, config) if market else None
        )
        self._evaluation = evaluation

        mode = self._mode
        marks = self._marks()  # touches loop-owned state, so it is done here, not in the thread
        sellable_market_id = (
            market.market_id
            if market is not None
            and market.remaining_seconds() > 0
            and self._status == "RUNNING"
            and not self._kill_switch
            else None
        )
        db = await asyncio.to_thread(self._snapshot_db, mode, marks, sellable_market_id)
        balance = db["balance"]
        starting_balance = db["starting_balance"]
        pnl = db["pnl"]
        pnl_limits = db["pnl_limits"]
        trades_today = db["trades_today"]
        position_rows = db["position_rows"]
        sellable_rows = db["sellable_rows"]
        order_rows = db["order_rows"]

        latency_avg = round(sum(self._latencies) / len(self._latencies), 2) if self._latencies else None
        market_payload = None
        if market is not None:
            market_payload = {
                "market_id": market.market_id,
                "slug": market.slug,
                "event_slug": market.event_slug,
                "window_start": iso_z(market.window_start),
                "window_end": iso_z(market.window_end),
                "remaining_seconds": round(market.remaining_seconds(), 3),
                "duration_seconds": market.duration_seconds,
                "up_token_id": market.up_token_id,
                "down_token_id": market.down_token_id,
                "status": "active" if market.remaining_seconds() > 0 else "expired",
            }

        limit_pnl = (
            pnl_limits["daily_pnl"] if self.settings.daily_limit_include_unrealized else pnl_limits["realized_pnl"]
        )
        risk_halt = None
        if self._risk_halt_reason:
            risk_halt = self._risk_halt_reason
        elif limit_pnl <= -config.max_daily_loss and config.max_daily_loss > 0:
            risk_halt = "daily_loss_limit"
        elif config.daily_profit_target > 0 and limit_pnl >= config.daily_profit_target:
            risk_halt = "daily_profit_target"

        return {
            "server_time": iso_z(clock.now_utc()),
            "bot": {
                "status": self._status,
                "mode": self._mode,
                "kill_switch": self._kill_switch,
                "started_at": iso_z(self._started_at),
                "risk_halt": risk_halt,
            },
            "market": market_payload,
            "books": {
                "up": up_book.snapshot() if up_book else None,
                "down": down_book.snapshot() if down_book else None,
            },
            "strategy": {
                "name": "FINAL_60_SECONDS_MARKET_BUY",
                **(
                    evaluation.summary()
                    if evaluation
                    else {
                        "in_window": False,
                        "remaining_seconds": None,
                        "up_ask": None,
                        "down_ask": None,
                        "up_armed": False,
                        "down_armed": False,
                        "reason": "no_market",
                    }
                ),
                "stage1_threshold": config.stage1_threshold,
                "stage1_seconds": config.stage1_seconds,
                "stage1_max_ask": config.stage1_max_ask,
                "stage2_max_ask": config.stage2_max_ask,
                "stage2_max_spread": config.stage2_max_spread,
                "stage3_max_ask": config.stage3_max_ask,
                "stage3_max_spread": config.stage3_max_spread,
                "stage3_min_stop_headroom": config.stage3_min_stop_headroom,
                "sell_floor_tolerance_ticks": config.sell_floor_tolerance_ticks,
                "stage1_enabled": config.stage1_enabled,
                "stage2_enabled": config.stage2_enabled,
                "stage3_enabled": config.stage3_enabled,
                "stage2_seconds": config.stage2_seconds,
                "stage2_threshold": config.stage2_threshold,
                "stage3_seconds": config.stage3_seconds,
                "stage3_threshold": config.stage3_threshold,
                "stage0_enabled": config.stage0_enabled,
                "stage0_start_seconds": config.stage0_start_seconds,
                "stage0_end_seconds": config.stage0_end_seconds,
                "stage0_min_ask": config.stage0_min_ask,
                "stage0_max_ask": config.stage0_max_ask,
                "stage0_early_max_ask": config.stage0_early_max_ask,
                "stage0_block_ask": config.stage0_block_ask,
                "stage1_block_ask": config.stage1_block_ask,
                "stage2_block_ask": config.stage2_block_ask,
                "stage3_block_ask": config.stage3_block_ask,
                "stage0_min_price_gap_usd": config.stage0_min_price_gap_usd,
                "stage0_high_min_price_gap_usd": config.stage0_high_min_price_gap_usd,
                "stage0_mid_from_ask": config.stage0_mid_from_ask,
                "stage0_high_from_ask": config.stage0_high_from_ask,
                "stage0_take_profit_pct_low": config.stage0_take_profit_pct_low,
                "stage0_take_profit_pct_mid": config.stage0_take_profit_pct_mid,
                "stage0_take_profit_pct_high": config.stage0_take_profit_pct_high,
                "stage0_sell_cap_price": config.stage0_sell_cap_price,
                "stage1_sell_enabled": config.stage1_sell_enabled,
                "stage1_sell_max_buy": config.stage1_sell_max_buy,
                "stage1_take_profit_pct": config.stage1_take_profit_pct,
                "stage1_sell_price_high": config.stage1_sell_price_high,
                "stage2_sell_enabled": config.stage2_sell_enabled,
                "stage2_sell_price": config.stage2_sell_price,
                "stage0_stop_loss_pct": config.stage0_stop_loss_pct,
                "stage1_stop_loss_pct": config.stage1_stop_loss_pct,
                "stage2_stop_loss_pct": config.stage2_stop_loss_pct,
                "stage3_stop_loss_pct": config.stage3_stop_loss_pct,
                "stage0_high_buy_price": config.stage0_high_buy_price,
                "stage0_high_buy_stop_loss_pct": config.stage0_high_buy_stop_loss_pct,
                "stage3_mid_seconds": config.stage3_mid_seconds,
                "stage3_mid_stop_loss_pct": config.stage3_mid_stop_loss_pct,
                "stage3_last_seconds": config.stage3_last_seconds,
                "stage3_last_seconds_stop_loss_pct": config.stage3_last_seconds_stop_loss_pct,
                "stage0_high_buy_after_pct": config.stage0_high_buy_after_pct,
                "conflict_policy": config.conflict_policy,
                "duplicate_policy": config.duplicate_policy,
            },
            "account": {
                "mode": self._mode,
                "balance": round(balance, 6),
                "starting_balance": round(starting_balance, 6),
                **pnl,
                "trades_today": trades_today,
                "positions_awaiting_settlement": sum(
                    1 for row in position_rows if row["awaiting_settlement"]
                ),
                "pending_redeems": len(self._redeem_queue),
            },
            "latency": {
                "last_ms": self._latencies[-1] if self._latencies else None,
                "avg_ms": latency_avg,
                "samples": len(self._latencies),
            },
            "positions": position_rows,
            "open_orders": order_rows,
            "sellable": sellable_rows,
            # the operator's limit sells of the live market (kept by the bot, not exchange orders)
            "limits": self._limits_payload(market),
            # limits of the one-tap buy (the dashboard / app offer amounts inside them)
            "tap": {
                "min_usdc": TAP_MIN_USDC,
                "max_usdc": float(getattr(self.settings, "tap_buy_max_usdc", 100.0)),
                "cooldown_ms": int(TAP_COOLDOWN_S * 1000),
                "min_ask": TAP_MIN_ASK,  # a side priced below this cannot be bought with a tap
            },
            "last_order": self._last_order,
            "market_data": self.data.status,
            "btc": self.btc.snapshot(market),
            "buy_guard": self.buy_guard_status(market, config),
            "live": self.live_readiness(),
            "config": {
                **config.to_public_dict(),
                "trade_amount_usdc": config.trade_amount_usdc,
            },
        }

    async def analytics_summary(self, mode: str) -> dict:
        marks = self._marks()
        with session_scope() as session:
            unrealized = self.portfolio.unrealized_pnl(session, mode, marks)
            return self.analytics.summary(session, mode, unrealized)

    async def recent_orders(self, mode: str, limit: int = 100) -> list[dict]:
        with session_scope() as session:
            floor_id = self.portfolio.orders_view_floor(session, mode)
            rows = session.execute(
                select(Order)
                .where(Order.mode == mode, Order.id > floor_id)
                .order_by(Order.id.desc())
                .limit(limit)
            ).scalars().all()
            market_ids = {o.market_id for o in rows}
            market_orders = session.execute(
                select(Order).where(Order.mode == mode, Order.market_id.in_(market_ids))
            ).scalars().all() if market_ids else []
            exit_results = sell_outcomes(market_orders, self.orders._key_stage, self.orders._key_action)

            def outcome(o: Order) -> tuple[str | None, float | None]:
                # Only a filled SELL row has a result: what that exit (take-profit or stop-loss) itself
                # realised. A BUY row stays blank, its outcome is shown on the SELL row.
                if (o.filled_size or 0) <= 0 or self.orders._key_action(o.order_key) != "SELL":
                    return None, None
                return exit_results.get(o.id, (None, None))

            return [
                {
                    "result": outcome(o)[0],
                    "result_pnl": outcome(o)[1],
                    "id": o.id,
                    "market_id": o.market_id,
                    "side": o.side,
                    "status": o.status,
                    "stage": self.orders._key_stage(o.order_key),
                    "tap": self.orders._key_stage(o.order_key) == TAP_STAGE,
                    "action": self.orders._key_action(o.order_key),
                    "requested_notional": o.requested_notional,
                    "requested_price": o.requested_price,
                    "submitted_price": o.submitted_price,
                    "filled_size": o.filled_size,
                    "avg_fill_price": o.avg_fill_price,
                    "fee": o.fee,
                    "latency_ms": o.latency_ms,
                    "route": o.route,
                    "exchange_order_id": o.exchange_order_id,
                    "error": o.error,
                    "reason": o.reason,
                    "created_at": iso_z(o.created_at),
                }
                for o in rows
            ]

    async def recent_positions(self, mode: str, limit: int = 100) -> list[dict]:
        with session_scope() as session:
            rows = session.execute(
                select(Position)
                .where(Position.mode == mode)
                .order_by(Position.id.desc())
                .limit(limit)
            ).scalars().all()
            return [
                {
                    "id": p.id,
                    "market_id": p.market_id,
                    "side": p.side,
                    "tag": p.tag or "",
                    "size": p.size,
                    "avg_price": p.avg_price,
                    "cost_basis": p.cost_basis,
                    "fees": p.fees,
                    "unrealized_pnl": p.unrealized_pnl,
                    "realized_pnl": p.realized_pnl,
                    "status": p.status,
                    "resolution": p.resolution,
                    "opened_at": iso_z(p.opened_at),
                    "closed_at": iso_z(p.closed_at),
                }
                for p in rows
            ]

    async def recent_triggers(self, limit: int = 100) -> list[dict]:
        with session_scope() as session:
            rows = session.execute(
                select(TriggerEvent).order_by(TriggerEvent.id.desc()).limit(limit)
            ).scalars().all()
            return [
                {
                    "id": t.id,
                    "market_id": t.market_id,
                    "side": t.side,
                    "best_ask": t.best_ask,
                    "ask_size": t.ask_size,
                    "remaining_seconds": t.remaining_seconds,
                    "threshold": t.threshold,
                    "triggered": t.triggered,
                    "decision": t.decision,
                    "reason": t.reason,
                    "created_at": iso_z(t.created_at),
                }
                for t in rows
            ]

    async def update_settings(self, changes: dict) -> dict:
        async with self._lock:
            with session_scope() as session:
                updated = self.runtime.update(session, changes)
            self.orders.reset_trigger_cache()
            self.logs.log(
                "WARNING",
                "settings",
                "strategy settings updated",
                payload=changes,
                mode=self._mode,
            )
        await self._broadcast_state()
        return updated.model_dump()

    async def reset_settings(self) -> dict:
        """Remove all dashboard overrides; the .env defaults apply immediately (paper and live)."""
        async with self._lock:
            with session_scope() as session:
                restored = self.runtime.reset(session)
            self.orders.reset_trigger_cache()
            self.logs.log(
                "WARNING",
                "settings",
                "settings reset to .env defaults",
                mode=self._mode,
            )
        await self._broadcast_state()
        return restored.model_dump()

    async def reset_paper_account(self, balance: float) -> dict:
        if self._status == "RUNNING":
            raise BotError("stop the bot before resetting the paper account")
        if balance <= 0:
            raise BotError("balance must be positive")
        async with self._lock:
            with session_scope() as session:
                self.portfolio.reset_paper(session, balance)
            self.logs.log(
                "WARNING",
                "system",
                f"paper account reset to {balance:.2f} USDC",
                mode="paper",
            )
        await self._broadcast_state()
        return await self.snapshot()
