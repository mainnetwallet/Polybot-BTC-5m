from __future__ import annotations

import ipaddress
from urllib.parse import urlparse

from fastapi import HTTPException, Request

SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})
LOOPBACK_NAMES = frozenset({"localhost", "127.0.0.1", "::1"})
TUNNEL_SUFFIXES = (".trycloudflare.com",)


def host_name(host: str | None) -> str:
    """Hostname of a Host header / URL netloc: no port, no brackets, lower case."""
    value = (host or "").strip().lower()
    if value.startswith("["):  # [::1]:8000
        return value[1:].partition("]")[0]
    if value.count(":") == 1:  # name:port or ip:port (a bare IPv6 address has several colons)
        value = value.partition(":")[0]
    return value


def _is_ip(name: str) -> bool:
    try:
        ipaddress.ip_address(name)
    except ValueError:
        return False
    return True


def build_host_checker(cors_origins: list[str], trusted_hosts: list[str] | None):
    """Return ``is_trusted(host)``: may the Host header of a request name this server?

    ``trusted_hosts=None`` turns the check off (a login or API token protects the API, and a DNS-rebinding
    page has neither). With a list, a Host must be loopback, an IP address, a cloudflared quick tunnel, a host of
    CORS_ORIGINS or an entry of TRUSTED_HOSTS (``example.com`` or ``.example.com`` for all subdomains).
    A rebinding attack needs a hostname of its own, so it can never match.
    """
    if trusted_hosts is None:
        return lambda host: True
    exact = set(LOOPBACK_NAMES)
    suffixes = list(TUNNEL_SUFFIXES)
    for entry in trusted_hosts:
        name = host_name(entry.strip())
        if not name:
            continue
        if entry.strip().startswith("."):
            suffixes.append("." + name.lstrip("."))
        else:
            exact.add(name)
    for origin in cors_origins:
        name = host_name(urlparse(origin.strip()).netloc)
        if name:
            exact.add(name)

    def is_trusted(host: str | None) -> bool:
        name = host_name(host)
        if not name:
            return False
        return name in exact or _is_ip(name) or name.endswith(".localhost") or any(name.endswith(s) for s in suffixes)

    return is_trusted


def build_origin_checker(cors_origins: list[str], trusted_hosts: list[str] | None = None):
    """Return ``is_allowed(origin, host)`` for browser-sent Origin headers.

    No Origin header (scripts, curl, the Android app) is not browser-driven and passes. An Origin passes
    when it is listed in CORS_ORIGINS or is this server itself (same host:port, also behind a tunnel); with
    ``trusted_hosts`` set, "this server itself" must also be a Host name the owner would use (see build_host_checker).
    The literal ``null`` origin (sandboxed iframes, file://) never matches and is refused.
    """
    allowed = {origin.rstrip("/").lower() for origin in cors_origins}
    host_trusted = build_host_checker(cors_origins, trusted_hosts)

    def is_allowed(origin: str | None, host: str | None) -> bool:
        if origin is None:
            return True
        normalized = origin.strip().rstrip("/").lower()
        if normalized in allowed:
            return True
        host = (host or "").strip().lower()
        return bool(host) and urlparse(normalized).netloc == host and host_trusted(host)

    return is_allowed


def build_cross_site_guard(cors_origins: list[str], trusted_hosts: list[str] | None = None):
    """FastAPI dependency: refuse state-changing requests that another website made the browser send."""
    is_allowed = build_origin_checker(cors_origins, trusted_hosts)

    def reject_cross_site(request: Request) -> None:
        if request.method in SAFE_METHODS:
            return
        if not is_allowed(request.headers.get("origin"), request.headers.get("host")):
            raise HTTPException(status_code=403, detail="Cross-site request blocked")

    return reject_cross_site
