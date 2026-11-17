from __future__ import annotations

import asyncio
import hmac
import json
import logging

from app.api.origin import build_origin_checker
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

logger = logging.getLogger(__name__)


SEND_TIMEOUT_S = 20.0  # a client that cannot take one frame in this time is dropped; it reconnects by itself
# (phones on mobile data behind a tunnel regularly stall for 5-10 s: dropping them then only made them
# reconnect, re-download the whole state and stall again)
QUEUE_LIMIT = 256  # frames a client may fall behind before it is dropped
_LATEST_ONLY = ("state", "tick")  # a newer frame replaces an older one of the same kind


class _Client:
    __slots__ = ("ws", "queue", "task")

    def __init__(self, ws: WebSocket) -> None:
        self.ws = ws
        self.queue: asyncio.Queue[tuple[str, str]] = asyncio.Queue(maxsize=QUEUE_LIMIT)
        self.task: asyncio.Task | None = None


def coalesce_frames(batch: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """Same as [coalesce] but keeps the (kind, text) pairs."""
    last: dict[str, int] = {}
    for index, (kind, _text) in enumerate(batch):
        if kind in _LATEST_ONLY:
            last[kind] = index
    return [(kind, text) for index, (kind, text) in enumerate(batch) if kind not in _LATEST_ONLY or last[kind] == index]


def coalesce(batch: list[tuple[str, str]]) -> list[str]:
    """Frames to actually send: every log in order, but only the newest state and the newest tick.

    A client that was slow for a moment would otherwise replay seconds of stale states one by one and
    show old prices and positions while it catches up."""
    return [text for _kind, text in coalesce_frames(batch)]


class RealtimeHub:
    """Fans events out to the connected dashboards/apps.

    Every client has its own queue and writer task, so broadcast() never waits for the network: one phone on a
    weak link (or a half-dead connection behind the tunnel) can no longer stall the state loop, the tick
    stream or the log pipeline for everybody else."""

    def __init__(self) -> None:
        self._clients: dict[WebSocket, _Client] = {}
        self._closers: set[asyncio.Task] = set()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        client = _Client(websocket)
        client.task = asyncio.create_task(self._writer(client), name="ws-writer")
        self._clients[websocket] = client

    async def disconnect(self, websocket: WebSocket) -> None:
        client = self._clients.pop(websocket, None)
        if client is not None and client.task is not None:
            client.task.cancel()

    @property
    def client_count(self) -> int:
        return len(self._clients)

    def send_to(self, websocket: WebSocket, event: dict) -> None:
        """Queue one event for a single client (the first state a new client gets)."""
        client = self._clients.get(websocket)
        if client is not None:
            self._enqueue(client, str(event.get("type", "")), _encode(event))

    async def broadcast(self, event: dict) -> None:
        if not self._clients:
            return
        kind = str(event.get("type", ""))
        text = _encode(event)  # serialised once for everybody
        for client in list(self._clients.values()):
            self._enqueue(client, kind, text)

    def _enqueue(self, client: _Client, kind: str, text: str) -> None:
        try:
            client.queue.put_nowait((kind, text))
            return
        except asyncio.QueueFull:
            pass
        # A full queue is mostly stale state/tick frames (several per second while the phone's link stalls).
        # Only the newest of each is ever sent, so squeeze the rest out instead of dropping the client.
        if self._squeeze(client):
            try:
                client.queue.put_nowait((kind, text))
                return
            except asyncio.QueueFull:
                pass
        logger.debug("ws client too far behind, dropping it")
        self._drop(client)

    @staticmethod
    def _squeeze(client: _Client) -> bool:
        """Remove superseded state/tick frames from a client's queue. True when room was made."""
        pending: list[tuple[str, str]] = []
        while True:
            try:
                pending.append(client.queue.get_nowait())
            except asyncio.QueueEmpty:
                break
        kept = coalesce_frames(pending)
        for item in kept:
            client.queue.put_nowait(item)
        return len(kept) < len(pending)

    async def _writer(self, client: _Client) -> None:
        try:
            while True:
                batch = [await client.queue.get()]
                while True:
                    try:
                        batch.append(client.queue.get_nowait())
                    except asyncio.QueueEmpty:
                        break
                for text in coalesce(batch):
                    await asyncio.wait_for(client.ws.send_text(text), timeout=SEND_TIMEOUT_S)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.debug("ws client dropped: %s", exc)
            self._drop(client)

    def _drop(self, client: _Client) -> None:
        if self._clients.pop(client.ws, None) is None:
            return
        if client.task is not None and client.task is not asyncio.current_task():
            client.task.cancel()
        closer = asyncio.ensure_future(self._close(client.ws))
        self._closers.add(closer)
        closer.add_done_callback(self._closers.discard)

    @staticmethod
    async def _close(websocket: WebSocket) -> None:
        try:
            await asyncio.wait_for(websocket.close(code=1011), timeout=2.0)
        except Exception:
            pass


def _encode(event: dict) -> str:
    return json.dumps(event, separators=(",", ":"), ensure_ascii=False)


def build_ws_router(
    hub: RealtimeHub, snapshot_factory, api_token: str = "", auth=None, cors_origins: list[str] | None = None
) -> APIRouter:
    router = APIRouter()
    origin_allowed = build_origin_checker(cors_origins or [])

    @router.websocket("/api/ws")
    async def websocket_endpoint(websocket: WebSocket) -> None:
        # Browsers attach Origin to WebSocket handshakes and do not apply CORS to them, so another website
        # could otherwise stream the live state. Clients without Origin (Android app, scripts) are unaffected.
        if not origin_allowed(websocket.headers.get("origin"), websocket.headers.get("host")):
            await websocket.close(code=1008)
            return
        expected = api_token.strip()
        login_on = auth is not None and auth.enabled
        if expected or login_on:
            allowed = login_on and auth.request_authenticated(websocket.cookies)
            if not allowed and expected:
                supplied = websocket.query_params.get("token") or websocket.headers.get("x-api-key", "")
                allowed = bool(supplied) and hmac.compare_digest(supplied.encode(), expected.encode())
            if not allowed:
                await websocket.close(code=1008)
                return
        await hub.connect(websocket)
        try:
            hub.send_to(websocket, {"type": "state", "data": await snapshot_factory()})
            while True:
                # Client messages are ignored; this keeps disconnect detection alive.
                await websocket.receive_text()
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            logger.debug("ws client dropped: %s", exc)
        finally:
            await hub.disconnect(websocket)

    return router
