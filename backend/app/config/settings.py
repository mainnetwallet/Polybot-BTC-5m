"""Environment-backed application settings.

Secrets (private key / API credentials) are read from the environment only and
are never returned by the API or written to the database.
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT_DIR = Path(__file__).resolve().parents[3]
DATA_DIR = ROOT_DIR / "data"
LOG_DIR = ROOT_DIR / "logs"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(ROOT_DIR / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        populate_by_name=True,
        case_sensitive=False,
    )

    # --- server ---------------------------------------------------------
    host: str = "127.0.0.1"
    port: int = 8000
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"
    # Optional shared secret for the control API (X-API-Key / Bearer header, ``?token=`` on the
    # WebSocket). Empty = no auth, fine on 127.0.0.1; set it before binding HOST to 0.0.0.0.
    api_token: str = ""
    # Dashboard login. One admin account; the password lives in .env only. Empty ADMIN_PASSWORD = login off.
    admin_username: str = "admin"
    admin_password: str = ""
    # Optional extra signing secret for session cookies (changing it logs every browser out).
    auth_secret: str = ""
    # How long one login stays valid in a browser (sliding: it renews whenever the dashboard is open).
    auth_session_days: int = 365
    log_level: str = "INFO"

    # --- database -------------------------------------------------------
    database_url: str = f"sqlite:///{(DATA_DIR / 'polymarket_bot.db').as_posix()}"

    # --- polymarket endpoints ------------------------------------------
    gamma_base_url: str = "https://gamma-api.polymarket.com"
    clob_base_url: str = "https://clob.polymarket.com"
    data_api_base_url: str = "https://data-api.polymarket.com"
    # Relay (Cloudflare Worker) for the BUY order POST only, in live AND paper mode.
    # ORDER_PROXY_ENABLED=true (opt-in, off by default): every live buy is posted to <ORDER_PROXY_URL>/<CLOB_BASE_URL>/order,
    # always, with no direct fallback (if the relay is down the buy is refused and retried like any failed
    # order). A paper buy sends no order, but it makes the same round trip to the relay (GET /time, so the
    # paper latency includes the relay hop) and is refused the same way when the relay is down.
    # false (default): buys go direct to Polymarket (paper: no relay call). Take-profit / stop-loss sells and every
    # other call (market data, balance, positions, cancel) are always direct.
    order_proxy_enabled: bool = False
    order_proxy_url: str = ""  # empty = no relay; set your own relay URL only if you run one
    clob_ws_url: str = "wss://ws-subscriptions-clob.polymarket.com/ws/market"
    rtds_ws_url: str = "wss://ws-live-data.polymarket.com"
    # Since 2026-08-14 Polymarket's 5-minute crypto markets settle on the Chainlink 60-second TWAP (both
    # the price to beat and the final price); the bot streams only that official series (no spot fallback).
    btc_price_enabled: bool = True  # dashboard-only official Polymarket BTC price / price-to-beat
    # Real BTC ticks are appended here so the dashboard's candle history survives a backend restart
    # (empty = keep them in memory only). Only ticks the server really received are ever stored.
    btc_tick_history_file: str = str(DATA_DIR / "btc_ticks.csv")
    series_slug: str = "btc-up-or-down-5m"
    event_slug_prefix: str = "btc-updown-5m"  # event slug = <prefix>-<window start unix>
    http_user_agent: str = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    )

    # --- strategy -------------------------------------------------------
    # TRIGGER_THRESHOLD is the old env name and is still read.
    stage1_threshold: float = Field(
        default=0.85, validation_alias=AliasChoices("STAGE1_THRESHOLD", "TRIGGER_THRESHOLD")
    )
    # An ask at or above this inside the stage 1 window blocks further stage 1 buys in that market.
    stage1_block_ask: float = 0.99
    # An ask at or above this inside the stage 2 / stage 3 window blocks further buys of that stage in that market.
    stage2_block_ask: float = 0.99
    stage3_block_ask: float = 0.99
    # FINAL_WINDOW_SECONDS is the old env name and is still read.
    stage1_seconds: int = Field(
        default=90, validation_alias=AliasChoices("STAGE1_SECONDS", "FINAL_WINDOW_SECONDS")
    )
    # Extra staged entries inside the stage 1: once `remaining <= stageN_seconds`
    # and a best ask >= stageN_threshold, one more buy per stage (same market).
    # Per-stage master switches. A stage that is switched off does nothing at all: no buys, no
    # take-profit sell and no stop-loss for its shares (stage 0 has its own switch, stage0_enabled).
    stage1_enabled: bool = True
    stage2_enabled: bool = True
    stage3_enabled: bool = True
    stage2_seconds: int = 50
    stage2_threshold: float = 0.88
    stage3_seconds: int = 30
    stage3_threshold: float = 0.90
    max_execution_price: float = 0.98
    # Stage 1 never buys when the best ask is above this (0.97 = 97 cents). Stages 2/3 only use max_execution_price.
    stage1_max_ask: float = 0.97
    # Stage 2 never buys when the best ask is above this (0.97: it buys at 0.97, not at 0.98 or more). Keeps the
    # fixed 0.98 take-profit above the buy price, so a take-profit can never be a loss after fees.
    stage2_max_ask: float = 0.97
    # Stage 2 does not buy when best ask - best bid is wider than this (0 = off). The stop-loss looks at the bid,
    # the buy pays the ask: a wide spread would stop the position out right after the buy.
    stage2_max_spread: float = 0.05
    # Stage 3 never buys when the best ask is above this (0.98: it buys up to and including 0.98, MAX_EXECUTION_PRICE
    # is the hard cap on top of it).
    stage3_max_ask: float = 0.98
    # Stage 3 does not buy when best ask - best bid is wider than this (0 = off).
    stage3_max_spread: float = 0.05
    # Stage 3 does not buy unless the best bid is at least this far ABOVE the stop-loss level that applies right now
    # (the stop tightens to 3 % / 2 % in the last seconds). 0 = off. Stops a buy from being stopped out at once.
    stage3_min_stop_headroom: float = 0.01
    trade_amount_usdc: float = 2.0
    # Book age only resets on a websocket book event, so a stable price on a quiet book looks
    # "old" without being wrong, so too low a limit blocks stage 2/3 (armed by the clock) on a quiet
    # book. 2s: a signal is never built on a book older than that. Exits do not check book age.
    stale_data_max_age_ms: int = 2000
    # Retry guard for failed/cancelled entries (per stage, per market)
    # A FOK entry that did not fill leaves nothing on the book, so it is retried almost at once.
    # 15 attempts x 250 ms keeps a retry window of a few seconds.
    entry_max_attempts: int = 15
    entry_retry_gap_ms: int = 250
    # Stage 0 has an ~80 s window (180 s -> 100 s left), so 15 attempts (~4 s) would end it long before the
    # window does when a live FOK keeps missing on a thin book. 240 x 250 ms covers the whole window.
    stage0_entry_max_attempts: int = 240
    # Gap between two sell attempts of the same exit (take-profit / stop-loss) when the previous
    # FOK sell did not fill. Kept short: an exit must not wait a full second for the next try.
    exit_retry_gap_ms: int = 10
    # Sells are FAK (fill what the book gives above the floor, the rest is retried), not FOK. A
    # take-profit floor sits this many ticks (0.01 each) under the take-profit price, so a bid that
    # dipped during the order's latency still fills. 0 = floor exactly at the take-profit price.
    sell_floor_tolerance_ticks: int = 1
    max_open_positions: int = 1
    # Price-to-beat filter: a buy needs the current BTC price at least this many USD beyond the
    # price to beat in the bought direction (UP: current >= beat + gap, DOWN: current <= beat - gap).
    # 0 disables the rule. With the rule on, missing/stale BTC data blocks the buy (fail closed).
    min_price_gap_usd: float = 45.0  # stage 1
    # Each add-on stage has its own gap: stage 2 = 38 USD, stage 3 = 35 USD by default.
    stage2_min_price_gap_usd: float = 38.0
    stage3_min_price_gap_usd: float = 35.0
    # Stage 0: an early, independent entry with its own take-profit SELL. While more than `stage0_end_seconds`
    # and at most `stage0_start_seconds` remain (independent of the stage 1), buy a side whose best ask is
    # <= stage0_max_ask and whose BTC price gap beyond the price to beat is >= stage0_min_price_gap_usd.
    # The shares are sold again as soon as the best bid is the tier's take-profit percentage above the buy price.
    stage0_enabled: bool = True
    stage0_start_seconds: int = 180
    stage0_end_seconds: int = 100
    stage0_min_ask: float = 0.70
    # Max ask by buy price: a buy below stage0_high_buy_price goes up to stage0_early_max_ask (0.85); an expensive
    # buy (ask >= stage0_high_buy_price, after stage0_high_buy_after_pct of the window) up to stage0_max_ask (0.96).
    # Asks in between (0.85 to 0.89) do not buy.
    stage0_max_ask: float = 0.96
    stage0_early_max_ask: float = 0.85
    # An ask at or above this inside the stage 0 window blocks further stage 0 buys in that market.
    stage0_block_ask: float = 0.98
    stage0_min_price_gap_usd: float = 80.0
    # Own BTC gap for the expensive buys (ask >= stage0_high_buy_price, 0.89-0.96): stage0_min_price_gap_usd
    # (80) keeps applying to the cheaper buys. 0 = no gap needed for the expensive buys.
    stage0_high_min_price_gap_usd: float = 70.0
    # Take-profit for stage 0: sell once the best bid is this many percent ABOVE the average price paid per
    # share (cost * (1 + pct / 100), capped at stage0_sell_cap_price). The percentage depends on what stage 0
    # paid per share:
    #   cost <  stage0_mid_from_ask   -> stage0_take_profit_pct_low  (default 0.70-0.80 buy -> +10 %)
    #   cost <  stage0_high_from_ask  -> stage0_take_profit_pct_mid  (default 0.80-0.85 buy -> +7 %)
    #   otherwise                     -> stage0_take_profit_pct_high (default 0.85+ buy -> +5 %)
    # 0 = no take-profit for that tier: the shares are held to settlement.
    stage0_mid_from_ask: float = 0.80
    stage0_high_from_ask: float = 0.85
    stage0_take_profit_pct_low: float = 10.0
    stage0_take_profit_pct_mid: float = 7.0
    stage0_take_profit_pct_high: float = 5.0
    # Highest bid the stage 0 take-profit waits for: a stage 0 position is sold once the best bid reaches
    # min(cost * (1 + pct / 100), stage0_sell_cap_price). Keep it above stage0_max_ask.
    stage0_sell_cap_price: float = 0.98
    # Stage 1 take-profit: stage 1 shares are sold again as soon as the best bid reaches a price that
    # depends on what they cost per share:
    #   cost <= stage1_sell_max_buy (0.88) -> cost * (1 + stage1_take_profit_pct / 100), i.e. +8 %
    #                                         (capped at 0.99; 0 = no take-profit for these shares)
    #   cost above it                      -> stage1_sell_price_high (0.98).
    stage1_sell_enabled: bool = True
    stage1_sell_max_buy: float = 0.88
    stage1_take_profit_pct: float = 8.0
    stage1_sell_price_high: float = 0.98
    # Stage 2 take-profit: stage 2 shares are sold as soon as the best bid reaches stage2_sell_price.
    stage2_sell_enabled: bool = True
    stage2_sell_price: float = 0.98
    # Stop-loss per entry stage, in percent of the average price paid per share: once the best bid
    # is at or below cost * (1 - pct / 100), that stage's own shares are sold at the market.
    # 0 switches the stop-loss off for that stage. Stage 0 = 60 %, stage 1 = 18 %, stage 2 = 10 %, stage 3 = 5 % by default.
    stage0_stop_loss_pct: float = 60.0
    stage1_stop_loss_pct: float = 18.0
    stage2_stop_loss_pct: float = 10.0
    stage3_stop_loss_pct: float = 5.0
    # Stage 0 shares that cost stage0_high_buy_price (0.89) or more use this tighter stop-loss instead of
    # stage0_stop_loss_pct. 0 = off (the normal stage 0 stop-loss applies to every buy price).
    stage0_high_buy_price: float = 0.89
    stage0_high_buy_stop_loss_pct: float = 30.0
    # Last-seconds stop-loss for stage 3: once `stage3_last_seconds` or fewer seconds remain, stage 3
    # shares use this (tight) percentage instead of stage3_stop_loss_pct. 0 = off.
    # Middle tier: with `stage3_mid_seconds` or fewer seconds left (more than stage3_last_seconds).
    stage3_mid_seconds: int = 20
    stage3_mid_stop_loss_pct: float = 3.0
    stage3_last_seconds: int = 10
    stage3_last_seconds_stop_loss_pct: float = 2.0
    # Buys at stage0_high_buy_price (0.89) or more are only allowed once this percent of the stage 0 window
    # (start -> end) has passed: 50 with the 180s -> 100s window = from 140s left. 0 = the whole window.
    stage0_high_buy_after_pct: float = 50.0
    duplicate_policy: str = "one_per_market"  # one_per_market | one_per_side | allow
    conflict_policy: str = "up_first"  # up_first | down_first | highest_ask | block_both

    # --- risk -----------------------------------------------------------
    max_daily_loss: float = 2.0
    daily_profit_target: float = 4.0
    # False (default): MAX_DAILY_LOSS / DAILY_PROFIT_TARGET look at REALIZED pnl only (closed positions), so the
    # spread + entry fee shown right after a buy cannot halt the other stages. True = realized + unrealized.
    daily_limit_include_unrealized: bool = False
    max_trades_per_day: int = 600

    # --- paper ----------------------------------------------------------
    paper_starting_balance: float = 100.0
    paper_fill_ratio: float = 1.0
    paper_slippage_bps: float = 0.0
    paper_fee_rate: float = 0.07
    paper_fee_exponent: float = 1.0

    # --- live -----------------------------------------------------------
    live_trading_enabled: bool = False
    live_confirmation_phrase: str = "GO LIVE"
    polymarket_private_key: str = ""
    polymarket_funder_address: str = ""
    polymarket_signature_type: int = 0
    polymarket_chain_id: int = 137
    polymarket_api_key: str = ""
    polymarket_api_secret: str = ""
    polymarket_api_passphrase: str = ""
    polymarket_geo_verified: bool = False
    # After a market resolves, winning tokens only become pUSD once redeemed on-chain
    # (docs.polymarket.com/trading/positions/manage). Opt-in: submits real transactions.
    live_auto_redeem: bool = False
    polymarket_relayer_api_key: str = ""  # gasless redeem; empty = EOA tx paid in POL
    polymarket_relayer_api_key_address: str = ""

    # --- runtime loops --------------------------------------------------
    discovery_refresh_seconds: float = 3.0
    resolution_poll_seconds: float = 5.0
    ws_heartbeat_seconds: float = 10.0
    ws_price_persist_interval_ms: int = 500

    @field_validator("database_url")
    @classmethod
    def _absolutize_sqlite(cls, value: str) -> str:
        if value.startswith("sqlite:///") and not value.startswith("sqlite:////"):
            raw = value[len("sqlite:///"):]
            path = Path(raw)
            if not path.is_absolute():
                path = (ROOT_DIR / raw).resolve()
            return f"sqlite:///{path.as_posix()}"
        return value

    @field_validator("log_level")
    @classmethod
    def _normalise_level(cls, value: str) -> str:
        return value.upper()

    @property
    def api_exposed_without_token(self) -> bool:
        loopback = {"127.0.0.1", "localhost", "::1"}
        return (
            self.host.strip().lower() not in loopback
            and not self.api_token.strip()
            and not self.admin_password
        )

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def live_credentials_present(self) -> bool:
        return bool(self.polymarket_private_key.strip())


@lru_cache
def get_settings() -> Settings:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    return Settings()
