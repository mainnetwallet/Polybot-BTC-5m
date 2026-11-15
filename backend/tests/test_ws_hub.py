from __future__ import annotations

import asyncio
import json

from app.api import ws as ws_module
from app.api.ws import RealtimeHub, coalesce


class FakeSocket:
    def __init__(self, delay: float = 0.0, fail: bool = False) -> None:
        self.sent: list[dict] = []
        self.delay = delay
        self.fail = fail
        self.closed_with: int | None = None

    async def accept(self) -> None:
        return None

    async def send_text(self, text: str) -> None:
        if self.fail:
            raise RuntimeError("connection lost")
        if self.delay:
            await asyncio.sleep(self.delay)
        self.sent.append(json.loads(text))

    async def close(self, code: int = 1000) -> None:
        self.closed_with = code


def test_coalesce_keeps_logs_and_only_the_newest_state_and_tick():
    batch = [
        ("state", "s1"), ("log", "l1"), ("tick", "t1"), ("state", "s2"), ("log", "l2"), ("tick", "t2"),
    ]
    assert coalesce(batch) == ["l1", "s2", "l2", "t2"]


async def test_broadcast_reaches_every_client():
    hub = RealtimeHub()
    a, b = FakeSocket(), FakeSocket()
    await hub.connect(a)
    await hub.connect(b)
    await hub.broadcast({"type": "log", "data": {"message": "hi"}})
    await asyncio.sleep(0.05)
    assert a.sent == b.sent == [{"type": "log", "data": {"message": "hi"}}]
    assert hub.client_count == 2


async def test_slow_client_does_not_hold_back_the_others(monkeypatch):
    monkeypatch.setattr(ws_module, "SEND_TIMEOUT_S", 0.2)
    hub = RealtimeHub()
    slow, fast = FakeSocket(delay=5.0), FakeSocket()
    await hub.connect(slow)
    await hub.connect(fast)
    started = asyncio.get_running_loop().time()
    for i in range(5):
        await hub.broadcast({"type": "log", "data": {"n": i}})
    assert asyncio.get_running_loop().time() - started < 0.1  # broadcast never waits for the network
    await asyncio.sleep(0.5)
    assert [m["data"]["n"] for m in fast.sent] == [0, 1, 2, 3, 4]
    assert hub.client_count == 1  # the stalled client was dropped
    assert slow.closed_with == 1011


async def test_failing_client_is_dropped():
    hub = RealtimeHub()
    bad, good = FakeSocket(fail=True), FakeSocket()
    await hub.connect(bad)
    await hub.connect(good)
    await hub.broadcast({"type": "log", "data": {}})
    await asyncio.sleep(0.05)
    assert hub.client_count == 1
    assert len(good.sent) == 1


async def test_client_that_falls_too_far_behind_is_dropped(monkeypatch):
    monkeypatch.setattr(ws_module, "QUEUE_LIMIT", 3)
    hub = RealtimeHub()
    sock = FakeSocket(delay=5.0)
    await hub.connect(sock)
    for i in range(10):
        await hub.broadcast({"type": "log", "data": {"n": i}})
    await asyncio.sleep(0.05)
    assert hub.client_count == 0


async def test_send_to_targets_one_client():
    hub = RealtimeHub()
    a, b = FakeSocket(), FakeSocket()
    await hub.connect(a)
    await hub.connect(b)
    hub.send_to(a, {"type": "state", "data": {"x": 1}})
    await asyncio.sleep(0.05)
    assert a.sent == [{"type": "state", "data": {"x": 1}}]
    assert b.sent == []
