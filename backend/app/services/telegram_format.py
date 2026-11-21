"""How every Telegram message looks: HTML text plus inline buttons.

Pure functions (no I/O, no state), so the layout is easy to test and to change in one place.

Layout rules, the same for every message:

* line 1 is a short bold headline with ONE status icon (what happened);
* then a small aligned table (``<pre>``) with the numbers, then one or two plain sentences (what it means);
* the buttons under an alert / reply are the actions the owner can take next.

Everything dynamic goes through :func:`esc`, so a stray ``<`` or ``&`` in a reason can never break the message.
"""

from __future__ import annotations

import html
import re
from collections.abc import Sequence

Rows = Sequence[tuple[str, str]]
Keyboard = dict

# --------------------------------------------------------------------------- helpers


def esc(value: object) -> str:
    """HTML-escape dynamic text (Telegram's HTML mode only needs & < >)."""
    return html.escape(str(value), quote=False)


def strip_tags(text: str) -> str:
    """HTML message -> plain text. Used as a fallback when Telegram refuses the markup."""
    return html.unescape(re.sub(r"<[^>]+>", "", text))


def table(rows: Rows) -> str:
    """Aligned two-column block (monospace), e.g. ``BTC            56,108.64``."""
    rows = [(str(k), str(v)) for k, v in rows]
    if not rows:
        return ""
    width = max(len(k) for k, _ in rows)
    return "<pre>" + esc("\n".join(f"{k.ljust(width)}  {v}" for k, v in rows)) + "</pre>"


def _join(*blocks: str) -> str:
    """Blocks separated by one blank line; empty blocks are skipped."""
    return "\n\n".join(block for block in blocks if block)


# --------------------------------------------------------------------------- buttons


def _button(text: str, data: str) -> dict:
    return {"text": text, "callback_data": data}


# callback_data is "cmd:<command>" or "cmd:<command>:<argument>"; the service turns it back into a command.
MAIN_KEYBOARD: Keyboard = {
    "inline_keyboard": [
        [_button("▶️ Buy on", "cmd:buy"), _button("⏹ Off for today", "cmd:stop")],
        [_button("📊 Status", "cmd:status"), _button("⏱ Auto-resume", "cmd:delay")],
    ]
}

DELAY_KEYBOARD: Keyboard = {
    "inline_keyboard": [
        [
            _button("15 min", "cmd:delay:15"),
            _button("30 min", "cmd:delay:30"),
            _button("1 h", "cmd:delay:60"),
            _button("2 h", "cmd:delay:120"),
        ],
        [_button("Never auto-resume", "cmd:delay:off"), _button("📊 Status", "cmd:status")],
    ]
}

BOT_MENU = [
    ("status", "Bot and buying status"),
    ("buy", "Turn buying on"),
    ("off", "Turn buying off for today"),
    ("delay", "Auto-resume wait after a guard trip"),
    ("help", "Show the commands"),
]


def parse_callback(data: str | None) -> tuple[str, str] | None:
    """'cmd:delay:30' -> ('delay', '30'); 'cmd:buy' -> ('buy', ''); anything else -> None."""
    if not data or not data.startswith("cmd:"):
        return None
    parts = data.split(":", 2)
    command = parts[1] if len(parts) > 1 else ""
    if command not in {"buy", "stop", "status", "delay", "help"}:
        return None
    return command, (parts[2][:16] if len(parts) > 2 else "")


# --------------------------------------------------------------------------- alerts


def guard_alert(title: str, details: Rows, resume_minutes: int | None, resume_at: str | None) -> str:
    """A buy guard switched buying off. `resume_at` is 'HH:MM' (UTC) or None when auto-resume is off."""
    if resume_minutes and resume_at:
        resume = (
            f"⏱ Auto-resume in <b>{esc(resume_minutes)} min</b> (~{esc(resume_at)} UTC). "
            "All guards are fully re-armed when it happens."
        )
    else:
        resume = "⏱ Auto-resume is <b>off</b>: buying stays off until you turn it on."
    return _join(
        f"🛑 <b>Buying stopped</b> · {esc(title)}",
        table(details),
        "No new buys in any stage. The bot keeps running: open positions keep their take-profit / stop-loss "
        "and settle normally.",
        resume,
    )


def auto_resumed(*, new_day: bool, minutes: int, title: str) -> str:
    if new_day:
        why = "New trading day (00:00 UTC). All guards are re-armed."
    else:
        why = (
            f"{esc(minutes)} min passed since <i>{esc(title)}</i> and no action was taken. All guards are "
            "re-armed: if BTC is still past a limit you get a new alert and buying stops again."
        )
    return _join("✅ <b>Buying resumed</b> · automatic", why)


def switched(enabled: bool, source: str) -> str:
    """Buying was switched from somewhere other than this chat (dashboard / app)."""
    if enabled:
        return f"✅ <b>Buying turned on</b> · from {esc(source)}"
    return _join(
        f"⛔ <b>Buying turned off</b> · from {esc(source)}",
        "Stays off for the rest of the day (no auto-resume timer) and comes back at 00:00 UTC. "
        "Tap <b>Buy on</b> to resume earlier.",
    )


# --------------------------------------------------------------------------- replies to commands


def buy_reply(changed: bool, notes: Sequence[str]) -> str:
    head = "✅ <b>Buying is ON</b>" if changed else "ℹ️ Buying was <b>already ON</b>"
    return _join(head, "\n".join(esc(note) for note in notes))


def stop_reply(changed: bool, reason: str) -> str:
    if changed:
        return _join(
            "⛔ <b>Buying is OFF</b> for the rest of the day",
            "No auto-resume timer. Exits keep running. Buying comes back by itself at 00:00 UTC (next day); "
            "tap <b>Buy on</b> to resume earlier.",
        )
    return _join("ℹ️ Buying was <b>already OFF</b>", esc(reason))


def status_reply(
    *,
    bot: str,
    buying_on: bool,
    gap: str | None,
    trend: str | None,
    off_reason: str,
    resume_line: str,
) -> str:
    rows: list[tuple[str, str]] = [("Bot", bot), ("Buying", "ON" if buying_on else "OFF")]
    if gap:
        rows.append(("BTC gap", gap))
    if trend:
        rows.append(("BTC trend", trend))
    return _join(
        "📊 <b>Status</b>",
        table(rows),
        f"⛔ {esc(off_reason)}" if not buying_on and off_reason else "",
        f"⏱ {esc(resume_line)}",
    )


def delay_reply(current_minutes: int) -> str:
    now = f"{current_minutes} min" if current_minutes > 0 else "off"
    return _join(
        "⏱ <b>Auto-resume</b>",
        f"Wait after a guard trip: {esc(now)}.",
        "Pick a new wait below, or send <code>Delay 45</code> (minutes), <code>Delay 2h</code> or "
        "<code>Delay off</code>.",
    )


def delay_set_reply(minutes: int, detail: str) -> str:
    if minutes == 0:
        return _join(
            "⏱ <b>Auto-resume is OFF</b>",
            "After a guard trip buying stays off until you turn it on.",
        )
    return _join(f"⏱ <b>Auto-resume wait is now {esc(minutes)} min</b>", esc(detail.strip()))


def help_reply(status: str) -> str:
    return _join(
        status,
        "<b>Commands</b>\n"
        "/status · bot and buying status\n"
        "/buy · turn buying on\n"
        "/off · turn buying off for today\n"
        "/delay 30 · auto-resume wait (minutes, <code>2h</code>, <code>off</code>)",
    )


def error_reply(command: str, error: object) -> str:
    return _join(f"⚠️ <b>Command failed</b> · {esc(command)}", f"<code>{esc(error)}</code>")
