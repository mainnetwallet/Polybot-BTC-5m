from __future__ import annotations

import os
import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
ROOT_DIR = BACKEND_DIR.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

TEST_DB = ROOT_DIR / "data" / "test_polymarket_bot.db"
for suffix in ("", "-wal", "-shm"):
    Path(str(TEST_DB) + suffix).unlink(missing_ok=True)

os.environ["DATABASE_URL"] = f"sqlite:///{TEST_DB.as_posix()}"
os.environ["LIVE_TRADING_ENABLED"] = "false"
os.environ["POLYMARKET_GEO_VERIFIED"] = "false"
os.environ["POLYMARKET_PRIVATE_KEY"] = ""
os.environ["BTC_PRICE_ENABLED"] = "false"
os.environ["ORDER_PROXY_ENABLED"] = "false"  # the shipped default is off; tests never call the real relay
os.environ["BTC_TICK_HISTORY_FILE"] = ""  # tests never touch the real tick history
os.environ["DISCOVERY_REFRESH_SECONDS"] = "3600"
os.environ["RESOLUTION_POLL_SECONDS"] = "3600"
# The shared test books carry a 0.50 bid; with the production stop-loss defaults (20 / 15 %) every
# bot-level test would be stopped out right after its buy. The stop-loss tests switch it on explicitly.
for _stage in range(4):
    os.environ[f"STAGE{_stage}_STOP_LOSS_PCT"] = "0"
os.environ["STAGE0_HIGH_BUY_STOP_LOSS_PCT"] = "0"
# The app defaults moved on (stage 0 min ask .69 / gap 70, stage 1 take-profit 8 %); the bot-level
# tests were written against the older values, so they stay pinned here. Default tests read Settings.model_fields.
os.environ["STAGE0_MIN_ASK"] = "0.60"
os.environ["STAGE0_MIN_PRICE_GAP_USD"] = "80"
os.environ["STAGE1_TAKE_PROFIT_PCT"] = "10"
# The shipped defaults moved again (50 USDC per trade, stage 0 window 160-100s / max ask 0.89 / tiers 10-7-5 %,
# stage 1 sell 0.88 / 0.98, stage 2 sell 0.98, stage 2/3 gaps 30/25). The bot-level tests keep the earlier values
# they were written against; tests of the defaults themselves read Settings.model_fields.
os.environ["TRADE_AMOUNT_USDC"] = "1.0"
os.environ["STAGE0_START_SECONDS"] = "200"
os.environ["STAGE0_MIN_ASK"] = "0.60"
os.environ["STAGE0_MAX_ASK"] = "0.94"
os.environ["STAGE0_MID_FROM_ASK"] = "0.70"
os.environ["STAGE0_HIGH_FROM_ASK"] = "0.80"
os.environ["STAGE0_TAKE_PROFIT_PCT_LOW"] = "15"
os.environ["STAGE0_TAKE_PROFIT_PCT_MID"] = "10"
os.environ["STAGE0_TAKE_PROFIT_PCT_HIGH"] = "5"
os.environ["STAGE0_HIGH_BUY_PRICE"] = "0.90"
os.environ["STAGE1_SELL_MAX_BUY"] = "0.86"
os.environ["STAGE1_SELL_PRICE_HIGH"] = "0.99"
os.environ["STAGE2_SELL_PRICE"] = "0.99"
os.environ["STAGE2_MIN_PRICE_GAP_USD"] = "25"
os.environ["STAGE3_MIN_PRICE_GAP_USD"] = "30"

import pytest  # noqa: E402

from app.database.models import Base  # noqa: E402
from app.database.session import get_engine, session_scope  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def schema():
    from app.database.migrate import ensure_schema

    ensure_schema()
    yield
    get_engine().dispose()
    for suffix in ("", "-wal", "-shm"):
        Path(str(TEST_DB) + suffix).unlink(missing_ok=True)


@pytest.fixture(autouse=True)
def clean_db(schema):
    with session_scope() as session:
        for table in reversed(Base.metadata.sorted_tables):
            session.execute(table.delete())
    yield
