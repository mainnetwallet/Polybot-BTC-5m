#!/usr/bin/env bash
# Linux / VPS counterpart of start.bat. ONE command: git pull, install what changed, restart API + dashboard.
#
#   ./start.sh               update (git pull) and (re)start API + dashboard in the background
#   ./start.sh --attach      stay in this terminal; Ctrl+C stops everything
#   ./start.sh --no-pull     do not run git pull
#   ./start.sh --no-restart  keep an already running healthy instance
#   ./start.sh --local       dashboard only on 127.0.0.1 (reach it with an SSH tunnel)
#
# Stop with ./stop.sh. The bot always starts STOPPED: press Start in the dashboard.

set -o pipefail
cd "$(dirname "$(readlink -f "$0")")" || exit 1
ROOT="$PWD"
LOGS="$ROOT/logs"
PIDS="$LOGS/pids"
VENV_PY="$ROOT/.venv/bin/python"
API_URL="http://127.0.0.1:8000"
UI_URL="http://127.0.0.1:5173"

if [ -t 1 ]; then C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_GRAY=$'\033[90m'; C_OFF=$'\033[0m'
else C_CYAN=""; C_GREEN=""; C_RED=""; C_YELLOW=""; C_GRAY=""; C_OFF=""; fi

step() { printf '%s==> %s%s\n' "$C_CYAN" "$1" "$C_OFF"; }
dim()  { printf '%s    %s%s\n' "$C_GRAY" "$1" "$C_OFF"; }
warn() { printf '%s    %s%s\n' "$C_YELLOW" "$1" "$C_OFF"; }
fail() { printf '%sERROR: %s%s\n' "$C_RED" "$1" "$C_OFF" >&2; exit 1; }

PULL=1; RESTART=1; ATTACH=0; LOCAL=0
for arg in "$@"; do
    case "$arg" in
        --no-pull) PULL=0 ;;
        --no-restart) RESTART=0 ;;
        --attach) ATTACH=1 ;;
        --local) LOCAL=1 ;;
        -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) fail "Unknown option: $arg (see ./start.sh --help)" ;;
    esac
done

mkdir -p "$LOGS" "$PIDS"

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Value of KEY from .env (the last uncommented KEY=value line wins), without surrounding quotes.
env_get() {
    [ -f "$ROOT/.env" ] || return 0
    grep -E "^[[:space:]]*$1[[:space:]]*=" "$ROOT/.env" | tail -n1 \
        | sed -E "s/^[^=]*=[[:space:]]*//; s/[[:space:]]+\$//; s/^\"(.*)\"\$/\\1/; s/^'(.*)'\$/\\1/"
}

# Succeeds when the URL answers with anything below HTTP 500. Proxy variables are ignored on purpose.
http_ok() {
    "$VENV_PY" - "$1" <<'PY' 2>/dev/null
import sys
import urllib.error as err
import urllib.request as req
try:
    req.build_opener(req.ProxyHandler({})).open(sys.argv[1], timeout=3)
except err.HTTPError as exc:
    sys.exit(0 if exc.code < 500 else 1)
except Exception:
    sys.exit(1)
PY
}

wait_http() { # url timeout_seconds pid
    local url="$1" timeout="$2" pid="$3" i
    for i in $(seq 1 $((timeout * 2))); do
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then return 1; fi
        http_ok "$url" && return 0
        sleep 0.5
    done
    return 1
}

read_pid() {
    local i pid
    for i in $(seq 1 10); do
        pid="$(head -n1 "$PIDS/$1.pid" 2>/dev/null | tr -dc '0-9')"
        [ -n "$pid" ] && { echo "$pid"; return 0; }
        sleep 0.2
    done
    return 1
}

show_tail() {
    [ -f "$1" ] || return 0
    printf '%s--- tail of %s ---%s\n' "$C_GRAY" "$1" "$C_OFF"
    tail -n 25 "$1" | sed "s/^/    /"
}

# launch NAME DIR OUT ERR COMMAND...   Starts a detached process (own session, no stdin) and records its pid.
launch() {
    local name="$1" dir="$2" out="$3" errfile="$4"
    shift 4
    rm -f "$PIDS/$name.pid"
    (
        cd "$dir" || exit 1
        if command -v setsid >/dev/null 2>&1; then
            setsid bash -c 'echo $$ > "$0"; exec "$@"' "$PIDS/$name.pid" "$@" </dev/null >"$out" 2>"$errfile" &
        else
            nohup bash -c 'echo $$ > "$0"; exec "$@"' "$PIDS/$name.pid" "$@" </dev/null >"$out" 2>"$errfile" &
        fi
    )
}

echo
printf '%sPolymarket BTC 5m bot%s\n' "$C_GREEN" "$C_OFF"
dim "Starting backend and dashboard"
echo

# 1. Fresh restart: stop the previous instance first.
if [ "$RESTART" = 1 ]; then
    step "Stopping any previous instance (fresh restart)"
    "$ROOT/stop.sh"
fi

# 2. Update the code.
if [ "$PULL" = 1 ] && [ -d "$ROOT/.git" ] && command -v git >/dev/null 2>&1; then
    step "Updating the code (git pull)"
    if git pull --ff-only; then
        dim "Now at: $(git log -1 --format='%h %s')"
    else
        warn "git pull failed: starting the version that is already here (fix the git problem, then run ./start.sh again)."
    fi
fi

# 3. Install on first run, and again whenever the dependency lists changed.
if [ ! -x "$VENV_PY" ] || [ ! -d "$ROOT/frontend/node_modules" ] || [ ! -f "$ROOT/.env" ]; then
    step "First run detected - running setup"
    "$ROOT/setup.sh" || fail "Setup did not complete. Fix the reported problem and run ./start.sh again."
else
    req_sha="$(sha256sum "$ROOT/backend/requirements.txt" | cut -d' ' -f1)"
    if [ "$(cat "$ROOT/.venv/.requirements.sha" 2>/dev/null)" != "$req_sha" ]; then
        step "Python requirements changed - installing"
        "$VENV_PY" -m pip install -r "$ROOT/backend/requirements.txt" || fail "pip install failed. Check your network connection and retry."
        echo "$req_sha" > "$ROOT/.venv/.requirements.sha"
    fi
    if [ -f "$ROOT/frontend/package-lock.json" ]; then
        lock_sha="$(sha256sum "$ROOT/frontend/package-lock.json" | cut -d' ' -f1)"
        if [ "$(cat "$ROOT/frontend/node_modules/.lock.sha" 2>/dev/null)" != "$lock_sha" ]; then
            step "Dashboard packages changed - installing"
            (cd "$ROOT/frontend" && { npm ci --no-fund --no-audit || npm install --no-fund --no-audit; }) \
                || fail "npm install failed. Check your network connection and retry."
            echo "$lock_sha" > "$ROOT/frontend/node_modules/.lock.sha"
        fi
    fi
fi

# 4. Ports must be free (or already serve this project when --no-restart is used).
api_running=0; ui_running=0
if port_open 8000; then
    http_ok "$API_URL/api/health" || fail "Port 8000 is already in use by another process that does not answer /api/health. Close it and retry."
    api_running=1
fi
if port_open 5173; then
    http_ok "$UI_URL/" || fail "Port 5173 is already in use by another process that does not serve the dashboard. Close it and retry."
    ui_running=1
fi

# 5. Where the dashboard listens. Open to the network only when a dashboard login is set.
dash_host="0.0.0.0"
admin_password="$(env_get ADMIN_PASSWORD)"
if [ "$LOCAL" = 1 ]; then
    dash_host="127.0.0.1"
elif [ -z "$admin_password" ] || [ "$admin_password" = "ChangeMe-123" ]; then
    dash_host="127.0.0.1"
    warn "ADMIN_PASSWORD is empty in .env, so the dashboard listens on 127.0.0.1 only."
    warn "Set ADMIN_PASSWORD (and API_TOKEN) in .env and run ./start.sh again to open it on the server's IP,"
    warn "or use an SSH tunnel:  ssh -L 5173:127.0.0.1:5173 USER@SERVER   then open http://127.0.0.1:5173"
fi

backend_pid=""; frontend_pid=""

if [ "$api_running" = 1 ]; then
    dim "API already running on 127.0.0.1:8000"
else
    step "Starting API (FastAPI) on 127.0.0.1:8000"
    launch backend "$ROOT/backend" "$LOGS/backend.out.log" "$LOGS/backend.err.log" \
        "$VENV_PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
    backend_pid="$(read_pid backend)" || fail "Could not start the API. See logs/backend.err.log."
    dim "PID $backend_pid"
fi

if [ "$ui_running" = 1 ]; then
    dim "Dashboard already running on port 5173"
else
    vite_entry="$ROOT/frontend/node_modules/vite/bin/vite.js"
    [ -f "$vite_entry" ] || fail "Vite is not installed in frontend/node_modules. Run ./setup.sh and retry."
    step "Starting dashboard (Vite) on $dash_host:5173"
    launch frontend "$ROOT/frontend" "$LOGS/frontend.out.log" "$LOGS/frontend.err.log" \
        node "$vite_entry" --host "$dash_host"
    frontend_pid="$(read_pid frontend)" || fail "Could not start the dashboard. See logs/frontend.err.log."
    dim "PID $frontend_pid"
fi

abort_start() { # message
    "$ROOT/stop.sh" >/dev/null 2>&1
    fail "$1"
}

if [ -n "$backend_pid" ]; then
    step "Waiting for the API to answer /api/health"
    if ! wait_http "$API_URL/api/health" 45 "$backend_pid"; then
        show_tail "$LOGS/backend.err.log"
        abort_start "The backend did not start within 45 seconds. See logs/backend.err.log and logs/backend.log."
    fi
    dim "API ready"
fi

if [ -n "$frontend_pid" ]; then
    step "Waiting for the dashboard"
    if ! wait_http "$UI_URL/" 45 "$frontend_pid"; then
        show_tail "$LOGS/frontend.err.log"
        abort_start "The dashboard did not start within 45 seconds. See logs/frontend.err.log."
    fi
    dim "Dashboard ready"
fi

mode="$("$VENV_PY" - "$API_URL/api/health" <<'PY' 2>/dev/null
import json, sys
import urllib.request as req
try:
    data = json.load(req.build_opener(req.ProxyHandler({})).open(sys.argv[1], timeout=5))
    print(str(data.get("mode", "paper")).upper())
except Exception:
    print("PAPER")
PY
)"

server_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo
printf '%sPolymarket BTC 5m bot is running.%s\n' "$C_GREEN" "$C_OFF"
if [ "$dash_host" = "0.0.0.0" ]; then
    printf '    Dashboard: http://%s:5173   (open port 5173 in the server firewall)\n' "${server_ip:-YOUR_SERVER_IP}"
else
    printf '    Dashboard: http://127.0.0.1:5173   (this server only)\n'
fi
printf '    API docs:  %s/docs   (this server only)\n' "$API_URL"
printf '    Mode:      %s\n' "${mode:-PAPER}"
dim "Logs:      logs/backend.log, logs/frontend.out.log"
if [ "$ATTACH" = 1 ]; then
    dim "Stop:      press Ctrl+C here, or run ./stop.sh"
else
    dim "Stop:      ./stop.sh      Restart/update: ./start.sh"
fi
echo

if [ "$ATTACH" = 1 ]; then
    trap '"$ROOT/stop.sh"; exit 0' INT TERM
    while port_open 8000 || port_open 5173; do sleep 1; done
    warn "Servers are no longer running."
fi

exit 0
