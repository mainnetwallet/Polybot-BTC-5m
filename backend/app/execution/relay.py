"""Order relay (Cloudflare Worker) URL, shared by the live and the paper engine."""

from __future__ import annotations

from app.config.settings import Settings


def order_relay_url(settings: Settings) -> str:
    """Relay prefix for the order POST: ``<ORDER_PROXY_URL>/<CLOB_BASE_URL>``.

    Empty (orders go direct) when ORDER_PROXY_ENABLED is false, when ORDER_PROXY_URL is empty, or when
    CLOB_BASE_URL already points at the relay (then every call uses it already).
    """
    if not settings.order_proxy_enabled:
        return ""
    base = (settings.order_proxy_url or "").strip().rstrip("/")
    if not base:
        return ""
    clob = settings.clob_base_url.strip().rstrip("/")
    if clob.startswith(base):
        return ""
    return f"{base}/{clob}"
