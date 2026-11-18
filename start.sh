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

# ---------------------------------------------------------------------------
# Optional: Cloudflare quick tunnel + secret Gist link publisher (for the Android app).
# Runs only when QUICK_TUNNEL=1 is set in .env (same as start.bat):
#   QUICK_TUNNEL=1   start a free Cloudflare quick tunnel to http://localhost:8000
#   GIST_TOKEN=...   GitHub classic token with ONLY the "gist" scope
#   GIST_ID=...      created on the first run and written to .env automatically
# The tunnel URL changes on every start; the app reads the current one from the secret Gist ("gist:<id>").
# ---------------------------------------------------------------------------
TUNNEL_URL=""
TUNNEL_GIST=""
CLOUDFLARED=""

# Sets KEY=value in .env: every uncommented KEY line is replaced, otherwise the line is appended.
set_env_value() {
    local key="$1" value="$2" tmp="$ROOT/.env.tmp.$$"
    awk -v k="$key" -v v="$value" '
        $0 ~ "^[ \t]*" k "[ \t]*=" { print k "=" v; found = 1; next }
        { print }
        END { if (!found) print k "=" v }' "$ROOT/.env" > "$tmp" && cat "$tmp" > "$ROOT/.env"
    rm -f "$tmp"
}

# Stops the tunnel recorded in logs/pids/cloudflared.pid (only if it really is this project's tunnel).
stop_tunnel() {
    local pidfile="$PIDS/cloudflared.pid" pid cmd
    [ -f "$pidfile" ] || return 0
    pid="$(head -n1 "$pidfile" 2>/dev/null | tr -dc '0-9')"
    if [ -n "$pid" ] && [ -d "/proc/$pid" ]; then
        cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
        case "$cmd" in *cloudflared*tunnel*localhost:8000*) kill "$pid" 2>/dev/null ;; esac
    fi
    rm -f "$pidfile"
}

# Finds cloudflared, or downloads the official static binary into .bin/ (no root needed).
ensure_cloudflared() {
    local arch url part ok=0
    if command -v cloudflared >/dev/null 2>&1; then CLOUDFLARED="$(command -v cloudflared)"; return 0; fi
    if [ -x "$ROOT/.bin/cloudflared" ]; then CLOUDFLARED="$ROOT/.bin/cloudflared"; return 0; fi
    case "$(uname -m)" in
        x86_64|amd64) arch=amd64 ;;
        aarch64|arm64) arch=arm64 ;;
        armv7l|armv6l|arm) arch=arm ;;
        i386|i686) arch=386 ;;
        *) warn "There is no cloudflared download for the CPU type $(uname -m)."; return 1 ;;
    esac
    url="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$arch"
    part="$ROOT/.bin/cloudflared.part"
    warn "Downloading cloudflared ($arch) into .bin/ ..."
    mkdir -p "$ROOT/.bin"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$part" "$url" && ok=1
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$part" "$url" && ok=1
    else
        warn "Neither curl nor wget is installed."
    fi
    if [ "$ok" != 1 ]; then rm -f "$part"; warn "Could not download cloudflared from $url"; return 1; fi
    chmod +x "$part"
    if ! "$part" --version >/dev/null 2>&1; then rm -f "$part"; warn "The downloaded cloudflared does not run on this machine."; return 1; fi
    mv "$part" "$ROOT/.bin/cloudflared"
    CLOUDFLARED="$ROOT/.bin/cloudflared"
}

# Writes the tunnel URL into the file pc-url.txt of the secret Gist and prints the Gist id. Creates the Gist on the
# first run (or when the stored one no longer exists). The token goes through the environment, never the command line.
publish_gist() { # url token gist_id
    TUNNEL_URL_IN="$1" GIST_TOKEN_IN="$2" GIST_ID_IN="$3" "$VENV_PY" - <<'PY'
import json, os, sys
import urllib.error as err
import urllib.request as req

api = os.environ.get("GITHUB_API", "https://api.github.com").rstrip("/")
headers = {
    "Authorization": "Bearer " + os.environ["GIST_TOKEN_IN"],
    "User-Agent": "polybot-btc-5m",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json; charset=utf-8",
}
files = {"pc-url.txt": {"content": os.environ["TUNNEL_URL_IN"]}}


def call(method, path, body):
    request = req.Request(api + path, data=json.dumps(body).encode("utf-8"), headers=headers, method=method)
    with req.urlopen(request, timeout=20) as response:
        return json.load(response)


try:
    gist_id = os.environ.get("GIST_ID_IN", "").strip()
    if gist_id:
        try:
            call("PATCH", "/gists/" + gist_id, {"files": files})
            print(gist_id)
            sys.exit(0)
        except err.HTTPError as exc:
            if exc.code != 404:
                raise
            print("Gist " + gist_id + " no longer exists; creating a new secret Gist.", file=sys.stderr)
    created = call("POST", "/gists", {"description": "polybot-btc-5m link", "public": False, "files": files})
    print(created["id"])
except err.HTTPError as exc:
    print("GitHub answered HTTP %d" % exc.code, file=sys.stderr)
    sys.exit(2)
except Exception as exc:
    print(str(exc), file=sys.stderr)
    sys.exit(2)
PY
}

# Sets TUNNEL_URL / TUNNEL_GIST. Returns 1 when the tunnel is off, refused or failed; never aborts the start-up.
start_quick_tunnel() {
    [ "$(env_get QUICK_TUNNEL)" = "1" ] || return 1
    step "Quick tunnel requested (QUICK_TUNNEL=1)"

    local api_token gist_token host_value old_gist gist_id url tpid i reachable=0
    api_token="$(env_get API_TOKEN | tr -d '[:space:]')"
    gist_token="$(env_get GIST_TOKEN | tr -d '[:space:]')"
    # Safety: never publish an API that has no token. The Android app authenticates with API_TOKEN.
    if [ -z "$api_token" ]; then
        printf '%s    Tunnel NOT started: API_TOKEN is empty in .env.%s\n' "$C_RED" "$C_OFF"
        printf '%s    Without a token anyone who learns the tunnel link could control the bot.%s\n' "$C_RED" "$C_OFF"
        warn "Set a long random API_TOKEN in .env (for example: openssl rand -hex 32) and run ./start.sh again."
        return 1
    fi
    if [ -z "$gist_token" ]; then
        printf '%s    Tunnel NOT started: GIST_TOKEN is empty in .env.%s\n' "$C_RED" "$C_OFF"
        warn "Without it the app cannot learn the new link after each restart. Create a classic GitHub token with ONLY the 'gist' scope."
        return 1
    fi
    host_value="$(env_get HOST | tr 'A-Z' 'a-z')"
    case "$host_value" in
        ""|127.0.0.1|localhost|::1) dim "HOST must stay 127.0.0.1 (cloudflared connects locally)." ;;
        *) warn "WARNING: HOST=$host_value in .env. Keep HOST=127.0.0.1: cloudflared connects locally and nothing else should reach the API directly." ;;
    esac
    if [ "$(env_get ADMIN_PASSWORD)" = "ChangeMe-123" ]; then
        printf '%s    Tunnel NOT started: ADMIN_PASSWORD is still the old example password (ChangeMe-123), which is public.%s\n' "$C_RED" "$C_OFF"
        return 1
    fi

    ensure_cloudflared || { warn "cloudflared is not available; the bot keeps running without the tunnel."; return 1; }

    stop_tunnel   # a tunnel from an earlier run would keep the old URL alive; replace it
    rm -f "$LOGS/cloudflared.log" "$LOGS/cloudflared.out.log"
    launch cloudflared "$ROOT" "$LOGS/cloudflared.out.log" "$LOGS/cloudflared.log" \
        "$CLOUDFLARED" tunnel --no-autoupdate --url http://localhost:8000
    tpid="$(read_pid cloudflared)" || { warn "Could not start cloudflared."; return 1; }
    dim "cloudflared PID $tpid"

    # cloudflared prints the link to stderr. api.trycloudflare.com only shows up in error lines: ignore it.
    url=""
    for i in $(seq 1 45); do
        sleep 1
        kill -0 "$tpid" 2>/dev/null || break
        url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGS/cloudflared.log" 2>/dev/null | grep -v '^https://api\.' | head -n1)"
        [ -n "$url" ] && break
    done
    if [ -z "$url" ]; then
        warn "Could not get a tunnel link. See logs/cloudflared.log. The bot keeps running without the tunnel."
        show_tail "$LOGS/cloudflared.log"
        stop_tunnel
        return 1
    fi
    printf '%s    Tunnel link: %s%s\n' "$C_GREEN" "$url" "$C_OFF"
    TUNNEL_URL="$url"

    old_gist="$(env_get GIST_ID | tr -d '[:space:]')"
    if gist_id="$(publish_gist "$url" "$gist_token" "$old_gist" 2>"$LOGS/gist.err")" && [ -n "$gist_id" ]; then
        [ "$gist_id" = "$old_gist" ] || set_env_value GIST_ID "$gist_id"
        TUNNEL_GIST="$gist_id"
    else
        warn "Could not update the Gist: $(tail -n1 "$LOGS/gist.err" 2>/dev/null)"
        warn "Check that GIST_TOKEN is a classic token with the 'gist' scope. The link above still works if you type it into the app."
        return 0
    fi

    # A fresh quick-tunnel hostname needs a few seconds before it resolves; wait a little, but never fail.
    for i in $(seq 1 15); do
        if "$VENV_PY" -c 'import sys, urllib.request as u; u.urlopen(u.Request(sys.argv[1], headers={"User-Agent": "polybot-btc-5m"}), timeout=4)' "$url/healthz" 2>/dev/null; then
            reachable=1; break
        fi
        sleep 1
    done
    [ "$reachable" = 1 ] || dim "The tunnel does not answer yet; it usually needs a few more seconds before the phone can use it."
    return 0
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

start_quick_tunnel || true

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
is_private_ip() {
    case "$1" in
        10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*|100.6[4-9].*|100.[7-9][0-9].*|100.1[01][0-9].*|100.12[0-7].*|169.254.*) return 0 ;;
    esac
    return 1
}
echo
printf '%sPolymarket BTC 5m bot is running.%s\n' "$C_GREEN" "$C_OFF"
if [ "$dash_host" != "0.0.0.0" ]; then
    printf '    Dashboard: http://127.0.0.1:5173   (this server only)\n'
elif [ -z "$server_ip" ] || is_private_ip "$server_ip"; then
    printf '    Dashboard: http://YOUR_SERVER_PUBLIC_IP:5173   (open port 5173 in the firewall; the server'"'"'s local address %s is not public)\n' "${server_ip:-unknown}"
else
    printf '    Dashboard: http://%s:5173   (open port 5173 in the server firewall)\n' "$server_ip"
fi
printf '    API docs:  %s/docs   (this server only)\n' "$API_URL"
printf '    Mode:      %s\n' "${mode:-PAPER}"
if [ -n "$TUNNEL_URL" ]; then
    printf '    Tunnel:    %s\n' "$TUNNEL_URL"
    if [ -n "$TUNNEL_GIST" ]; then
        printf '%s    In the app enter: gist:%s  (and your API_TOKEN)%s\n' "$C_GREEN" "$TUNNEL_GIST" "$C_OFF"
    fi
fi
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
