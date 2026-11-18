#!/usr/bin/env bash
# Linux / VPS counterpart of stop.bat: stops the bot through the API (using API_TOKEN or the admin login from
# .env), then ends the API and dashboard processes that start.sh launched for this project folder.

cd "$(dirname "$(readlink -f "$0")")" || exit 1
ROOT="$PWD"
PIDS="$ROOT/logs/pids"

if [ -t 1 ]; then C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_GRAY=$'\033[90m'; C_OFF=$'\033[0m'
else C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_GRAY=""; C_OFF=""; fi

step() { printf '%s==> %s%s\n' "$C_CYAN" "$1" "$C_OFF"; }
dim()  { printf '%s    %s%s\n' "$C_GRAY" "$1" "$C_OFF"; }
warn() { printf '%s    %s%s\n' "$C_YELLOW" "$1" "$C_OFF"; }

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Value of KEY from .env (the last uncommented KEY=value line wins), without surrounding quotes.
env_get() {
    [ -f "$ROOT/.env" ] || return 0
    grep -E "^[[:space:]]*$1[[:space:]]*=" "$ROOT/.env" | tail -n1 \
        | sed -E "s/^[^=]*=[[:space:]]*//; s/[[:space:]]+\$//; s/^\"(.*)\"\$/\\1/; s/^'(.*)'\$/\\1/"
}

# Process id that listens on a local TCP port (empty when unknown). Reads /proc, so it needs neither ss nor lsof.
port_owner() {
    local py="$ROOT/.venv/bin/python"
    [ -x "$py" ] || py="$(command -v python3)" || return 0
    "$py" -I - "$1" <<'PY' 2>/dev/null
import os, sys

port = int(sys.argv[1])
inodes = set()
for name in ("/proc/net/tcp", "/proc/net/tcp6"):
    try:
        rows = open(name).read().splitlines()[1:]
    except OSError:
        continue
    for row in rows:
        cols = row.split()
        if cols[3] == "0A" and int(cols[1].split(":")[1], 16) == port:  # 0A = LISTEN
            inodes.add(cols[9])
if inodes:
    for pid in (p for p in os.listdir("/proc") if p.isdigit()):
        try:
            for fd in os.listdir(f"/proc/{pid}/fd"):
                try:
                    link = os.readlink(f"/proc/{pid}/fd/{fd}")
                except OSError:
                    continue
                if link.startswith("socket:[") and link[8:-1] in inodes:
                    print(pid)
                    sys.exit(0)
        except OSError:
            continue
PY
}

# True only for processes that belong to this project folder.
is_project_process() {
    local pid="$1" cwd cmd
    [ -d "/proc/$pid" ] || return 1
    cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)"
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    case "$cwd" in "$ROOT"|"$ROOT"/*) return 0 ;; esac
    case "$cmd" in *"$ROOT"*) return 0 ;; esac
    return 1
}

# True when the pid file really points at the process start.sh launched.
is_tracked_process() {
    local pid="$1" name="$2" cmd
    [ -d "/proc/$pid" ] || return 1
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
    case "$name" in
        backend)  case "$cmd" in *uvicorn*app.main:app*) return 0 ;; esac ;;
        frontend) case "$cmd" in *vite*) return 0 ;; esac ;;
    esac
    return 1
}

signal_tree() { # signal pid
    local child
    for child in $(pgrep -P "$2" 2>/dev/null); do signal_tree "$1" "$child"; done
    kill "-$1" "$2" 2>/dev/null
}

terminate() { # pid: ask nicely, then force after 10 s
    local pid="$1" i
    signal_tree TERM "$pid"
    for i in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 0.5
    done
    signal_tree KILL "$pid"
}

wait_port_closed() {
    local i
    for i in $(seq 1 20); do
        port_open "$1" || return 0
        sleep 0.5
    done
    port_open "$1" && return 1 || return 0
}

# Asks the running backend to stop the bot. The control API needs credentials as soon as API_TOKEN or
# ADMIN_PASSWORD is set in .env: the API token is sent when there is one, otherwise the admin account signs in.
stop_bot_through_api() {
    local py="$ROOT/.venv/bin/python"
    [ -x "$py" ] || py="$(command -v python3)" || return 1
    API_TOKEN="$(env_get API_TOKEN)" ADMIN_USERNAME="$(env_get ADMIN_USERNAME)" ADMIN_PASSWORD="$(env_get ADMIN_PASSWORD)" \
    "$py" - <<'PY'
import json, os, sys
import http.cookiejar as cj
import urllib.error as err
import urllib.request as req

base = "http://127.0.0.1:8000"
token = os.environ.get("API_TOKEN", "").strip()
password = os.environ.get("ADMIN_PASSWORD", "")
user = os.environ.get("ADMIN_USERNAME", "").strip() or "admin"
opener = req.build_opener(req.ProxyHandler({}), req.HTTPCookieProcessor(cj.CookieJar()))
try:
    if not token and password:
        body = json.dumps({"username": user, "password": password}).encode()
        login = req.Request(base + "/api/auth/login", data=body, headers={"Content-Type": "application/json"}, method="POST")
        opener.open(login, timeout=5).read()
    headers = {"X-API-Key": token} if token else {}
    opener.open(req.Request(base + "/api/bot/stop", data=b"", headers=headers, method="POST"), timeout=5).read()
except err.HTTPError as exc:
    if exc.code in (401, 403):
        print(f"    The API rejected the credentials from .env (HTTP {exc.code}); the bot could not be stopped gracefully.")
    sys.exit(1)
except Exception:
    sys.exit(1)
PY
}

echo
printf '%sPolymarket BTC bot - stopping services%s\n' "$C_GREEN" "$C_OFF"
echo

if port_open 8000; then
    if stop_bot_through_api; then
        dim "Bot stopped through the API; state is persisted."
    else
        dim "No graceful bot stop through the API; continuing with process shutdown."
    fi
    sleep 0.8
fi

for name in frontend backend; do
    pidfile="$PIDS/$name.pid"
    [ -f "$pidfile" ] || continue
    pid="$(head -n1 "$pidfile" 2>/dev/null | tr -dc '0-9')"
    if [ -n "$pid" ] && is_tracked_process "$pid" "$name"; then
        step "Stopping $name (PID $pid)"
        terminate "$pid"
    elif [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        warn "PID $pid from $name.pid is not the tracked $name process; leaving it alone."
    fi
    rm -f "$pidfile"
done

for port in 8000 5173; do
    wait_port_closed "$port" && continue
    pid="$(port_owner "$port")"
    if [ -n "$pid" ] && is_project_process "$pid"; then
        step "Stopping leftover project process on port $port (PID $pid)"
        signal_tree KILL "$pid"
        wait_port_closed "$port" || warn "WARNING: port $port is still in use; close it manually."
    elif [ -n "$pid" ]; then
        warn "WARNING: port $port is held by PID $pid, which is not part of this project; leaving it alone."
    else
        warn "WARNING: port $port is still in use and its owner could not be identified; close it manually."
    fi
done

echo
printf '%sStopped. Data is saved in data/polymarket_bot.db.%s\n' "$C_GREEN" "$C_OFF"
echo
exit 0
