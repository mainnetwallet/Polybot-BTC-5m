from __future__ import annotations

from urllib.parse import urlparse

from fastapi import HTTPException, Request

SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})


def build_origin_checker(cors_origins: list[str]):
    """Return ``is_allowed(origin, host)`` for browser-sent Origin headers.

    No Origin header (scripts, curl, the Android app) is not browser-driven and passes. An Origin passes
    when it is listed in CORS_ORIGINS or is this server itself (same host:port, also behind a tunnel).
    The literal ``null`` origin (sandboxed iframes, file://) never matches and is refused.
    """
    allowed = {origin.rstrip("/").lower() for origin in cors_origins}

    def is_allowed(origin: str | None, host: str | None) -> bool:
        if origin is None:
            return True
        normalized = origin.strip().rstrip("/").lower()
        if normalized in allowed:
            return True
        host = (host or "").strip().lower()
        return bool(host) and urlparse(normalized).netloc == host

    return is_allowed


def build_cross_site_guard(cors_origins: list[str]):
    """FastAPI dependency: refuse state-changing requests that another website made the browser send."""
    is_allowed = build_origin_checker(cors_origins)

    def reject_cross_site(request: Request) -> None:
        if request.method in SAFE_METHODS:
            return
        if not is_allowed(request.headers.get("origin"), request.headers.get("host")):
            raise HTTPException(status_code=403, detail="Cross-site request blocked")

    return reject_cross_site
