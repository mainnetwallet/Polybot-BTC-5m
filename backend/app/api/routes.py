from __future__ import annotations

import hmac
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import ValidationError
from sqlalchemy import select

from app.api.origin import build_cross_site_guard
from app.api.schemas import (
    BuysRequest,
    KillSwitchRequest,
    ManualSellAllRequest,
    ManualSellRequest,
    ModeRequest,
    ClearHistoryRequest,
    ResetDailyRequest,
    ResetPaperRequest,
    ResetServerRequest,
    SettingsPatch,
    StartRequest,
    TapBuyRequest,
)
from app.api.auth import AuthService
from app.config.runtime import SettingsService
from app.config.settings import Settings
from app.database.models import LogEntry, utcnow
from app.database.migrate import current_revision
from app.database.session import session_scope
from app.services.bot import RESET_SERVER_PHRASE, BotError, LiveGateError, ModeSwitchError, TradingBot, iso_z


def build_router(
    bot: TradingBot,
    runtime: SettingsService,
    settings: Settings,
    analytics,
    auth: AuthService | None = None,
) -> APIRouter:
    def require_token(request: Request) -> None:
        """Dashboard login (session cookie) and/or the optional API_TOKEN header for scripts.

        Neither configured = open, as before. When either is configured, a request must present a valid
        session cookie or the API token.
        """
        expected = settings.api_token.strip()
        login_on = auth is not None and auth.enabled
        if not expected and not login_on:
            return
        if login_on and auth.request_authenticated(request.cookies):
            return
        if expected:
            supplied = request.headers.get("x-api-key", "")
            authorization = request.headers.get("authorization", "")
            if not supplied and authorization.lower().startswith("bearer "):
                supplied = authorization[7:].strip()
            if supplied and hmac.compare_digest(supplied.encode(), expected.encode()):
                return
        raise HTTPException(status_code=401, detail="Authentication required")

    reject_cross_site = build_cross_site_guard(settings.cors_origin_list)

    # /health stays open so uptime monitors keep working; everything else needs the token.
    public = APIRouter(prefix="/api")
    router = APIRouter(prefix="/api", dependencies=[Depends(reject_cross_site), Depends(require_token)])

    def _mode(value: str | None) -> str:
        if value is None:
            return bot.mode
        if value not in {"paper", "live"}:
            raise HTTPException(status_code=400, detail="mode must be 'paper' or 'live'")
        return value

    @public.get("/health")
    async def health() -> dict:
        return {
            "status": "ok",
            "time": iso_z(utcnow()),
            "bot_status": bot.status,
            "mode": bot.mode,
            "market_data": bot.data.status,
            "db_revision": current_revision(),
        }

    @router.get("/state")
    async def state() -> dict:
        return await bot.snapshot()

    @router.post("/bot/start")
    async def start(request: StartRequest) -> dict:
        try:
            return await bot.start_bot(request.confirm_live, request.confirmation_phrase)
        except (LiveGateError, BotError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/bot/stop")
    async def stop() -> dict:
        return await bot.stop_bot("operator")

    @router.post("/mode")
    async def switch_mode(request: ModeRequest) -> dict:
        try:
            return await bot.switch_mode(
                request.mode, request.confirm_live, request.confirmation_phrase
            )
        except (ModeSwitchError, LiveGateError, BotError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/kill-switch")
    async def kill_switch(request: KillSwitchRequest) -> dict:
        try:
            return await bot.set_kill_switch(request.active)
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/buys")
    async def set_buys(request: BuysRequest) -> dict:
        """Turn NEW buys on or off (all stages). Exits, settlement and market data are not affected."""
        return await bot.set_buys(request.enabled, "dashboard")

    @router.post("/orders/tap-buy")
    async def tap_buy(request: TapBuyRequest) -> dict:
        """One-tap buy: one market buy of `amount` USDC, separate from the strategy stages (see TradingBot.tap_buy)."""
        try:
            return await bot.tap_buy(request.market_id, request.side, request.amount, request.request_id)
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/orders/sell")
    async def manual_sell(request: ManualSellRequest) -> dict:
        """Sell what one order's stage still holds, right now, at the market (no confirmation step)."""
        try:
            return await bot.manual_sell(request.market_id, request.side, request.stage)
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/orders/sell-all")
    async def manual_sell_all(request: ManualSellAllRequest) -> dict:
        """Sell what every stage still holds in this market, right now, at the market (no confirmation step)."""
        try:
            return await bot.manual_sell_all(request.market_id)
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.get("/settings")
    async def get_settings_route() -> dict:
        return runtime.get().to_public_dict()

    @router.patch("/settings")
    async def patch_settings(request: SettingsPatch) -> dict:
        changes = request.changes()
        if not changes:
            raise HTTPException(status_code=400, detail="no supported settings supplied")
        try:
            return await bot.update_settings(changes)
        except (ValidationError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/settings/reset")
    async def reset_settings_route() -> dict:
        return await bot.reset_settings()

    @router.get("/btc/ticks")
    async def btc_ticks(
        since_ms: int | None = Query(default=None, ge=0),
        limit: int = Query(default=7300, ge=1, le=7300),
    ) -> dict:
        """Recent Chainlink BTC/USD ticks the server has seen, so the dashboard can draw candles of past rounds."""
        rows = bot.btc.recent_ticks(since_ms, limit)
        return {"ticks": [[ts_ms, price] for ts_ms, price in rows]}

    @router.get("/orders")
    async def orders(
        mode: str | None = Query(default=None),
        limit: int = Query(default=100, ge=1, le=500),
    ) -> list[dict]:
        return await bot.recent_orders(_mode(mode), limit)

    @router.get("/positions")
    async def positions(
        mode: str | None = Query(default=None),
        limit: int = Query(default=100, ge=1, le=500),
    ) -> list[dict]:
        return await bot.recent_positions(_mode(mode), limit)

    @router.get("/triggers")
    async def triggers(limit: int = Query(default=100, ge=1, le=500)) -> list[dict]:
        return await bot.recent_triggers(limit)

    @router.get("/analytics")
    async def analytics_route(mode: str | None = Query(default=None)) -> dict:
        return await bot.analytics_summary(_mode(mode))

    @router.get("/logs")
    async def logs(
        limit: int = Query(default=200, ge=1, le=1000),
        category: str | None = Query(default=None),
        level: Literal["INFO", "WARNING", "ERROR", "CRITICAL"] | None = Query(default=None),
    ) -> list[dict]:
        with session_scope() as session:
            query = select(LogEntry)
            if category:
                query = query.where(LogEntry.category == category)
            if level:
                query = query.where(LogEntry.level == level)
            query = query.order_by(LogEntry.id.desc()).limit(limit)
            rows = session.execute(query).scalars().all()
            return [
                {
                    "id": row.id,
                    "level": row.level,
                    "category": row.category,
                    "message": row.message,
                    "payload": row.payload,
                    "mode": row.mode,
                    "created_at": iso_z(row.created_at),
                }
                for row in rows
            ]

    @router.get("/live/readiness")
    async def live_readiness() -> dict:
        return bot.live_readiness()

    @router.post("/live/validate")
    async def live_validate() -> dict:
        try:
            result = await bot.live_engine.validate()
        except Exception as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"ok": True, **result}

    @router.post("/paper/reset")
    async def reset_paper(request: ResetPaperRequest) -> dict:
        if not request.confirm:
            raise HTTPException(status_code=400, detail="confirm must be true")
        try:
            return await bot.reset_paper_account(request.balance)
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/orders/clear")
    async def clear_orders(request: ClearHistoryRequest) -> dict:
        if not request.confirm:
            raise HTTPException(status_code=400, detail="confirm must be true")
        try:
            return await bot.clear_order_history()
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/daily/reset")
    async def reset_daily(request: ResetDailyRequest) -> dict:
        if not request.confirm:
            raise HTTPException(status_code=400, detail="confirm must be true")
        try:
            return await bot.reset_daily_stats()
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/server/reset")
    async def reset_server(request: ResetServerRequest) -> dict:
        if not request.confirm:
            raise HTTPException(status_code=400, detail="confirm must be true")
        if request.confirmation_phrase.strip() != RESET_SERVER_PHRASE:
            raise HTTPException(
                status_code=400,
                detail=f"type {RESET_SERVER_PHRASE} to confirm the full reset",
            )
        try:
            return await bot.reset_server()
        except BotError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.get("/config")
    async def config() -> dict:
        return {
            "series_slug": settings.series_slug,
            "clob_ws_url": settings.clob_ws_url,
            "strategy": "FINAL_60_SECONDS_MARKET_BUY",
            "stage1_threshold": runtime.get().stage1_threshold,
            "stage1_seconds": runtime.get().stage1_seconds,
        }

    root = APIRouter()
    root.include_router(public)
    root.include_router(router)
    return root
