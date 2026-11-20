"""Telegram alerts and a tiny remote control for the buy switch.

* ``send`` pushes an alert to TELEGRAM_CHAT_ID (never raises: a Telegram outage must not touch trading).
* The poll loop long-polls ``getUpdates`` and hands plain-text commands from that one chat to the bot:
  ``Buy`` (buying on), ``Off`` / ``Stop`` (buying off for the rest of the day), ``Delay 30`` (change the
  auto-resume wait, in minutes), ``Status``. Messages from any other chat are ignored.
* Commands that were sent while the backend was down are dropped at start-up, so an old "Buy" can never
  switch buying on later.

Telegram is off when TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is empty.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

import httpx

from app.config.settings import Settings

logger = logging.getLogger(__name__)

API_BASE = "https://api.telegram.org"
POLL_TIMEOUT_SECONDS = 25
MAX_MESSAGE_CHARS = 4000  # Telegram's hard limit is 4096

# command word -> canonical command. Matched on the first word, case-insensitive, with an optional leading "/".
COMMANDS = {
    "buy": "buy",
    "start": "buy",
    "on": "buy",
    "stop": "stop",
    "off": "stop",
    "permanent": "stop",
    "perm": "stop",
    "status": "status",
    "delay": "delay",
    "wait": "delay",
    "timer": "delay",
}

# (command, argument) -> reply text shown to the user. The argument is the rest of the message ("30" for "Delay 30").
CommandHandler = Callable[[str, str], Awaitable[str | None]]

MAX_DELAY_MINUTES = 1440  # same cap as the BUY_GUARD_RESUME_MINUTES setting


def parse_message(text: str | None) -> tuple[str, str] | None:
    """'Buy', '/buy', ' delay 30 ' -> ('buy', ''), ('delay', '30'). Anything that is not a known command -> None."""
    if not text:
        return None
    words = text.strip().split(None, 1)
    if not words:
        return None
    word = words[0].lstrip("/").split("@", 1)[0].lower()  # "/buy@my_bot" in group chats
    command = COMMANDS.get(word)
    if command is None:
        return None
    return command, (words[1].strip() if len(words) > 1 else "")


def parse_command(text: str | None) -> str | None:
    """Like parse_message but only the command word: 'Buy', '/buy', ' buy please' -> 'buy'."""
    parsed = parse_message(text)
    return parsed[0] if parsed else None


def parse_minutes(arg: str | None) -> int | None:
    """'30' / '30m' / '45 min' -> 30 / 30 / 45, '2h' / '1.5 hours' -> 120 / 90, 'off' / 'never' / '0' -> 0.

    A bare number is minutes. Anything unreadable, negative or above a day -> None.
    """
    if not arg:
        return None
    text = arg.strip().lower().replace(",", ".")
    if text in {"off", "never", "none", "no"}:
        return 0
    number = ""
    for char in text:
        if char.isdigit() or char == ".":
            number += char
        else:
            break
    unit = text[len(number):].strip()
    if not number or number.count(".") > 1 or number == ".":
        return None
    try:
        value = float(number)
    except ValueError:
        return None
    if unit in {"", "m", "min", "mins", "minute", "minutes"}:
        minutes = value
    elif unit in {"h", "hr", "hrs", "hour", "hours"}:
        minutes = value * 60
    else:
        return None
    minutes = int(round(minutes))
    return minutes if 0 <= minutes <= MAX_DELAY_MINUTES else None


class TelegramService:
    def __init__(self, settings: Settings, on_command: CommandHandler | None = None) -> None:
        self._token = settings.telegram_bot_token.strip()
        self._chat_id = settings.telegram_chat_id.strip()
        self._on_command = on_command
        self._enabled = bool(self._token and self._chat_id)
        self._client: httpx.AsyncClient | None = None
        self._task: asyncio.Task | None = None
        self._running = False
        self._offset: int | None = None
        self.last_error: str | None = None

    @property
    def enabled(self) -> bool:
        return self._enabled

    def _url(self, method: str) -> str:
        return f"{API_BASE}/bot{self._token}/{method}"

    def _safe(self, text: str) -> str:
        """Never let the bot token end up in a log line or an error shown on the dashboard."""
        return text.replace(self._token, "***") if self._token else text

    async def start(self) -> None:
        if not self._enabled or self._running:
            return
        self._running = True
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(POLL_TIMEOUT_SECONDS + 10.0, connect=10.0))
        self._task = asyncio.create_task(self._poll_loop(), name="telegram-poll")

    async def stop(self) -> None:
        self._running = False
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        client, self._client = self._client, None
        if client is not None:
            await client.aclose()

    async def send(self, text: str) -> bool:
        """Send `text` to the configured chat. Returns False (and logs) on any failure; never raises."""
        if not self._enabled:
            return False
        client = self._client
        close_after = False
        if client is None:  # alert sent while the poll loop is not running (e.g. in tests / before start)
            client = httpx.AsyncClient(timeout=httpx.Timeout(15.0, connect=10.0))
            close_after = True
        try:
            response = await client.post(
                self._url("sendMessage"),
                json={
                    "chat_id": self._chat_id,
                    "text": text[:MAX_MESSAGE_CHARS],
                    "disable_web_page_preview": True,
                },
            )
            response.raise_for_status()
            self.last_error = None
            return True
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self.last_error = self._safe(f"{type(exc).__name__}: {exc}")
            logger.warning("telegram send failed: %s", self.last_error)
            return False
        finally:
            if close_after:
                await client.aclose()

    # ------------------------------------------------------------------ polling
    async def _get_updates(self, timeout: int, offset: int | None) -> list[dict]:
        assert self._client is not None
        params: dict = {"timeout": timeout, "allowed_updates": '["message"]'}
        if offset is not None:
            params["offset"] = offset
        response = await self._client.get(self._url("getUpdates"), params=params)
        response.raise_for_status()
        data = response.json()
        if not data.get("ok"):
            raise RuntimeError(str(data.get("description") or "getUpdates not ok"))
        result = data.get("result")
        return result if isinstance(result, list) else []

    async def _skip_backlog(self) -> None:
        """Remember the newest update id so commands sent while the backend was down are not run later."""
        updates = await self._get_updates(timeout=0, offset=-1)
        if updates:
            self._offset = int(updates[-1]["update_id"]) + 1

    async def _poll_loop(self) -> None:
        backoff = 2.0
        backlog_skipped = False
        while self._running:
            try:
                if not backlog_skipped:
                    await self._skip_backlog()
                    backlog_skipped = True
                updates = await self._get_updates(POLL_TIMEOUT_SECONDS, self._offset)
                backoff = 2.0
                self.last_error = None
                for update in updates:
                    self._offset = int(update["update_id"]) + 1
                    await self._handle_update(update)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.last_error = self._safe(f"{type(exc).__name__}: {exc}")
                logger.warning("telegram poll failed: %s", self.last_error)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60.0)

    async def _handle_update(self, update: dict) -> None:
        message = update.get("message")
        if not isinstance(message, dict):
            return
        chat = message.get("chat") or {}
        if str(chat.get("id", "")) != self._chat_id:
            return  # only the owner's chat may control the bot
        parsed = parse_message(message.get("text"))
        if parsed is None or self._on_command is None:
            return
        command, arg = parsed
        try:
            reply = await self._on_command(command, arg)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.exception("telegram command %s failed", command)
            reply = f"Command '{command}' failed: {exc}"
        if reply:
            await self.send(reply)
