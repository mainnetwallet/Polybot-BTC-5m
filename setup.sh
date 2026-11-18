#!/usr/bin/env bash
# Linux / VPS counterpart of setup.bat: venv, packages, .env from .env.example, data/ and logs/.
# start.sh runs this by itself when something is missing, so you normally never call it.

set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"

if [ -t 1 ]; then C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_GRAY=$'\033[90m'; C_OFF=$'\033[0m'
else C_CYAN=""; C_GREEN=""; C_RED=""; C_GRAY=""; C_OFF=""; fi

step() { printf '%s==> %s%s\n' "$C_CYAN" "$1" "$C_OFF"; }
dim()  { printf '%s    %s%s\n' "$C_GRAY" "$1" "$C_OFF"; }
fail() { printf '%sERROR: %s%s\n' "$C_RED" "$1" "$C_OFF" >&2; exit 1; }

echo
printf '%sPolymarket BTC 5m bot%s\n' "$C_GREEN" "$C_OFF"
dim "Linux setup (backend + dashboard)"
echo

step "Checking Python"
command -v python3 >/dev/null 2>&1 || fail "python3 was not found. Install Python 3.11 or newer (Ubuntu/Debian: sudo apt install -y python3 python3-venv python3-pip)."
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' \
    || fail "Python 3.11 or newer is required; found $(python3 -V 2>&1)."
python3 -c 'import venv, ensurepip' >/dev/null 2>&1 \
    || fail "The venv module is missing (Ubuntu/Debian: sudo apt install -y python3-venv)."
dim "$(python3 -V 2>&1)"

step "Checking Node.js"
command -v node >/dev/null 2>&1 || fail "Node.js was not found. Install Node.js 20.19+ (or 22.12+)."
command -v npm >/dev/null 2>&1 || fail "npm was not found. Install Node.js with npm."
node_version="$(node --version | sed 's/^v//')"
node_major="${node_version%%.*}"
[ "$node_major" -ge 20 ] || fail "Node.js 20.19+ is required by Vite 7; found $node_version."
dim "Node $node_version"

if [ ! -x .venv/bin/python ]; then
    step "Creating Python virtual environment (.venv)"
    python3 -m venv .venv || fail "Could not create the virtual environment."
fi

step "Installing backend dependencies"
.venv/bin/python -m pip install --upgrade pip --quiet
.venv/bin/python -m pip install -r backend/requirements.txt || fail "pip install failed. Check your network connection and retry."
sha256sum backend/requirements.txt | cut -d' ' -f1 > .venv/.requirements.sha

step "Installing dashboard dependencies"
(
    cd frontend
    if [ -f package-lock.json ]; then
        npm ci --no-fund --no-audit || npm install --no-fund --no-audit
    else
        npm install --no-fund --no-audit
    fi
) || fail "npm install failed. Check your network connection and retry."
if [ -f frontend/package-lock.json ]; then
    sha256sum frontend/package-lock.json | cut -d' ' -f1 > frontend/node_modules/.lock.sha
fi

if [ ! -f .env ]; then
    step "Creating .env from .env.example"
    cp .env.example .env
    chmod 600 .env
    dim "Edit .env (nano .env) before you go live. Paper trading needs no edits."
fi

mkdir -p data logs

step "Initializing the SQLite schema"
(cd backend && ../.venv/bin/python -c "from app.database.migrate import ensure_schema; ensure_schema(); print('    database ready')") \
    || fail "Database initialization failed."

echo
printf '%sSetup complete.%s\n' "$C_GREEN" "$C_OFF"
dim "Next: ./start.sh"
echo
