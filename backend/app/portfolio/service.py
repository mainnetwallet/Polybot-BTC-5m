"""Accounts, fills, positions and PnL. Paper and live are never combined."""

from __future__ import annotations

import re
from datetime import datetime, time, timezone

from sqlalchemy import and_, delete, func, or_, select
from sqlalchemy.orm import Session

from app.config.settings import Settings
from app.database.models import (
    Account,
    AppState,
    BotSetting,
    Fill,
    LogEntry,
    Market,
    Order,
    Position,
    PriceUpdate,
    TriggerEvent,
    utcnow,
)
from app.execution.base import ExecutionReport

def start_of_day_utc(now: datetime | None = None) -> datetime:
    now = now or utcnow()
    if now.tzinfo is not None:
        now = now.astimezone(timezone.utc).replace(tzinfo=None)
    return datetime.combine(now.date(), time.min)


# One-tap buys are manual trades: they carry stage number 9 in their order key (buy ...:tap:<id>:s9, sell ...:s9:sell:<id>)
# and live in their own Position row (tag "tap"), so the strategy stages never see them.
TAP_STAGE = 9
TAP_TAG = "tap"
_TAP_KEY = re.compile(r":s9(?::sell:|$)")


def order_tag(order_key: str | None) -> str:
    """Position tag an order belongs to: "tap" for one-tap orders, "" for the strategy stages."""
    return TAP_TAG if _TAP_KEY.search(order_key or "") else ""


DAILY_RESET_KEY = "daily_reset_at:{mode}"
ORDERS_CLEARED_KEY = "orders_cleared_before:{mode}"


class Portfolio:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    # ------------------------------------------------------------------
    def daily_floor(self, session: Session, mode: str) -> datetime:
        """Start of the current daily window: UTC midnight or the last manual daily reset."""
        floor = start_of_day_utc()
        row = session.get(BotSetting, DAILY_RESET_KEY.format(mode=mode))
        if row is not None and isinstance(row.value, str):
            try:
                marker = datetime.fromisoformat(row.value)
            except ValueError:
                return floor
            if marker.tzinfo is not None:
                marker = marker.astimezone(timezone.utc).replace(tzinfo=None)
            if marker > floor:
                floor = marker
        return floor

    def reset_daily(self, session: Session, mode: str) -> None:
        """Zero today's trade count and daily profit/loss without deleting any history."""
        key = DAILY_RESET_KEY.format(mode=mode)
        stamp = utcnow().replace(tzinfo=None).isoformat()
        row = session.get(BotSetting, key)
        if row is None:
            session.add(BotSetting(key=key, value=stamp))
        else:
            row.value = stamp
        session.flush()

    def orders_view_floor(self, session: Session, mode: str) -> int:
        """Order id up to which the order-history list is hidden (0 = nothing hidden)."""
        row = session.get(BotSetting, ORDERS_CLEARED_KEY.format(mode=mode))
        try:
            return int(row.value) if row is not None else 0
        except (TypeError, ValueError):
            return 0

    def clear_orders_view(self, session: Session, mode: str) -> int:
        """Hide every order up to the newest one from the order-history list.

        Nothing is deleted: orders, fills and positions stay in the database, so daily PnL,
        trades today, analytics and risk limits keep counting exactly as before.
        Returns how many orders were hidden by this call.
        """
        newest = session.execute(
            select(func.coalesce(func.max(Order.id), 0)).where(Order.mode == mode)
        ).scalar_one()
        before = self.orders_view_floor(session, mode)
        hidden = session.execute(
            select(func.count(Order.id)).where(Order.mode == mode, Order.id > before, Order.id <= newest)
        ).scalar_one()
        key = ORDERS_CLEARED_KEY.format(mode=mode)
        row = session.get(BotSetting, key)
        if row is None:
            session.add(BotSetting(key=key, value=int(newest)))
        else:
            row.value = int(newest)
        session.flush()
        return int(hidden)

    def reset_all(self, session: Session) -> None:
        """Wipe every table (orders, fills, positions, markets, prices, triggers, logs,
        setting overrides, bot state) and recreate fresh accounts."""
        for model in (
            Fill,
            Order,
            Position,
            TriggerEvent,
            PriceUpdate,
            Market,
            LogEntry,
            BotSetting,
            AppState,
            Account,
        ):
            session.execute(delete(model))
        session.flush()
        session.add(AppState(id=1, status="STOPPED", mode="paper", kill_switch=False))
        self.ensure_accounts(session)

    # ------------------------------------------------------------------
    def ensure_accounts(self, session: Session) -> None:
        paper = session.execute(select(Account).where(Account.mode == "paper")).scalar_one_or_none()
        if paper is None:
            session.add(
                Account(
                    mode="paper",
                    balance=self._settings.paper_starting_balance,
                    starting_balance=self._settings.paper_starting_balance,
                )
            )
        live = session.execute(select(Account).where(Account.mode == "live")).scalar_one_or_none()
        if live is None:
            session.add(Account(mode="live", balance=0.0, starting_balance=0.0))
        session.flush()

    def account(self, session: Session, mode: str) -> Account:
        account = session.execute(select(Account).where(Account.mode == mode)).scalar_one_or_none()
        if account is None:
            self.ensure_accounts(session)
            account = session.execute(select(Account).where(Account.mode == mode)).scalar_one()
        return account

    def balance(self, session: Session, mode: str) -> float:
        return float(self.account(session, mode).balance)

    def set_cached_balance(self, session: Session, mode: str, balance: float) -> None:
        if mode == "paper":
            return  # paper balance is authoritative locally
        account = self.account(session, mode)
        account.balance = float(balance)

    # ------------------------------------------------------------------
    def apply_report(
        self, session: Session, mode: str, order: Order, report: ExecutionReport
    ) -> None:
        if not report.fills:
            return
        for part in report.fills:
            session.add(
                Fill(
                    order_id=order.id,
                    mode=mode,
                    market_id=order.market_id,
                    side=order.side,
                    price=part.price,
                    size=part.size,
                    fee=part.fee,
                    exchange_trade_id=part.exchange_trade_id,
                )
            )
        total_size = sum(p.size for p in report.fills)
        total_cost = sum(p.price * p.size for p in report.fills)
        total_fee = sum(p.fee for p in report.fills)

        tag = order_tag(order.order_key)
        position = session.execute(
            select(Position).where(
                Position.mode == mode,
                Position.market_id == order.market_id,
                Position.side == order.side,
                Position.tag == tag,
            )
        ).scalar_one_or_none()
        if position is None:
            position = Position(
                mode=mode,
                market_id=order.market_id,
                side=order.side,
                tag=tag,
                size=0.0,
                avg_price=0.0,
                cost_basis=0.0,
                fees=0.0,
                status="open",
            )
            session.add(position)
            session.flush()

        position.size = round(position.size + total_size, 10)
        position.cost_basis = round(position.cost_basis + total_cost, 10)
        position.fees = round(position.fees + total_fee, 10)
        position.avg_price = (
            round(position.cost_basis / position.size, 8) if position.size > 0 else 0.0
        )
        position.status = "open"

        if mode == "paper":
            account = self.account(session, "paper")
            account.balance = round(account.balance - total_cost - total_fee, 10)

    # A sell leaving fewer shares than this is dust (the exchange cannot trade it); the position is
    # closed and the dust written off.
    DUST_SHARES = 0.01

    def apply_sell_report(
        self,
        session: Session,
        mode: str,
        order: Order,
        report: ExecutionReport,
        basis: tuple[float, float] | None = None,
    ) -> float:
        """Book a SELL: shrink the position, add the realised PnL, credit the paper balance.

        Returns the realised PnL of this sale (proceeds - sell fee - cost basis and buy fees of the
        shares sold). Nothing is booked when there is no position to sell from.
        """
        if not report.fills:
            return 0.0
        for part in report.fills:
            session.add(
                Fill(
                    order_id=order.id,
                    mode=mode,
                    market_id=order.market_id,
                    side=order.side,
                    price=part.price,
                    size=part.size,
                    fee=part.fee,
                    exchange_trade_id=part.exchange_trade_id,
                )
            )
        position = session.execute(
            select(Position).where(
                Position.mode == mode,
                Position.market_id == order.market_id,
                Position.side == order.side,
                Position.tag == order_tag(order.order_key),
            )
        ).scalar_one_or_none()
        if position is None or position.size <= 0:
            return 0.0

        reported = sum(p.size for p in report.fills)
        sold = min(reported, position.size)
        scale = sold / reported if reported > 0 else 0.0
        proceeds = sum(p.price * p.size for p in report.fills) * scale
        sell_fee = sum(p.fee for p in report.fills) * scale
        if basis is not None:
            # exact cost of the shares being sold (stage 0's own buys), not the position's blended
            # average: other stages may have added shares to the same position at other prices
            cost_removed = min(sold * basis[0], position.cost_basis)
            buy_fee_removed = min(sold * basis[1], position.fees)
        else:
            fraction = sold / position.size
            cost_removed = position.cost_basis * fraction
            buy_fee_removed = position.fees * fraction
        realized = proceeds - sell_fee - cost_removed - buy_fee_removed

        position.size = round(position.size - sold, 10)
        position.cost_basis = round(position.cost_basis - cost_removed, 10)
        position.fees = round(position.fees - buy_fee_removed, 10)
        position.realized_pnl = round(position.realized_pnl + realized, 10)
        if position.size < self.DUST_SHARES:
            # write the dust off so the closed position's PnL stays complete
            position.realized_pnl = round(
                position.realized_pnl - position.cost_basis - position.fees, 10
            )
            position.size = 0.0
            position.cost_basis = 0.0
            position.fees = 0.0
            position.avg_price = 0.0
            position.unrealized_pnl = 0.0
            position.status = "closed"
            position.resolution = None
            position.closed_at = utcnow()
        else:
            position.avg_price = round(position.cost_basis / position.size, 8)

        if mode == "paper":
            account = self.account(session, "paper")
            account.balance = round(account.balance + proceeds - sell_fee, 10)
        return round(realized, 10)

    # ------------------------------------------------------------------
    def unrealized_pnl(
        self,
        session: Session,
        mode: str,
        marks: dict[tuple[str, str], float],
        market_id: str | None = None,
        include_tap: bool = True,
    ) -> float:
        """Compute unrealized PnL from best-bid marks without writing to SQLite.

        `include_tap=False` leaves one-tap positions out (the risk limits only look at the strategy stages)."""
        positions = session.execute(
            select(Position).where(Position.mode == mode, Position.status == "open")
        ).scalars().all()
        if not include_tap:
            positions = [p for p in positions if p.tag != TAP_TAG]
        total = 0.0
        for position in positions:
            if market_id is not None and position.market_id != market_id:
                total += position.unrealized_pnl
                continue
            mark = marks.get((position.market_id, position.side))
            if mark is None:
                total += position.unrealized_pnl
                continue
            total += position.size * mark - position.cost_basis - position.fees
        return round(total, 10)

    def snapshot_unrealized(
        self,
        session: Session,
        mode: str,
        market_id: str,
        marks: dict[tuple[str, str], float],
    ) -> None:
        """Persist bid-marked PnL for open positions of a market that has just ended."""
        positions = session.execute(
            select(Position).where(
                Position.mode == mode,
                Position.market_id == market_id,
                Position.status == "open",
            )
        ).scalars().all()
        for position in positions:
            mark = marks.get((position.market_id, position.side))
            if mark is None:
                # No bid left on this side at expiry. When the opposite side is clearly
                # winning (bid >= 0.5) the empty side is worthless, so value it at 0 instead
                # of leaving the stored $0.00 (which hid a loss while "settling").
                opposite = "DOWN" if position.side == "UP" else "UP"
                other = marks.get((position.market_id, opposite))
                if other is None or other < 0.5:
                    continue
                mark = 0.0
            position.unrealized_pnl = round(
                position.size * mark - position.cost_basis - position.fees, 10
            )

    def open_positions(self, session: Session, mode: str, include_tap: bool = True) -> list[Position]:
        rows = list(
            session.execute(
                select(Position).where(Position.mode == mode, Position.status == "open")
            ).scalars().all()
        )
        return rows if include_tap else [p for p in rows if p.tag != TAP_TAG]

    def realized_today(self, session: Session, mode: str, include_tap: bool = True) -> float:
        query = select(func.coalesce(func.sum(Position.realized_pnl), 0.0)).where(
            Position.mode == mode,
            Position.status == "closed",
            Position.closed_at >= self.daily_floor(session, mode),
        )
        if not include_tap:
            query = query.where(Position.tag != TAP_TAG)
        value = session.execute(query).scalar_one()
        return round(float(value), 10)

    def pnl_summary(self, session: Session, mode: str, unrealized: float, include_tap: bool = True) -> dict:
        realized = self.realized_today(session, mode, include_tap)
        return {
            "realized_pnl": round(realized, 6),
            "unrealized_pnl": round(unrealized, 6),
            "daily_pnl": round(realized + unrealized, 6),
        }

    def trades_today(self, session: Session, mode: str) -> int:
        value = session.execute(
            select(func.count(Order.id)).where(
                Order.mode == mode,
                Order.created_at >= self.daily_floor(session, mode),
                # A trade is an order that is live on the book or actually filled. CANCELED /
                # EXPIRED orders only count if they filled something (e.g. a partial IOC fill);
                # a fully canceled/expired order with no fill is not a trade.
                or_(
                    Order.status.in_(["SUBMITTED", "PARTIALLY_FILLED", "FILLED"]),
                    and_(Order.status.in_(["CANCELED", "EXPIRED"]), Order.filled_size > 0),
                ),
                # Stage 0 take-profit SELLs are exits, not entries: they (and their FOK retries)
                # must not eat the max_trades_per_day cap.
                Order.order_key.not_like("%:sell:%"),
                # One-tap buys are manual: they must not eat the max_trades_per_day cap of the strategy.
                Order.order_key.not_like("%:tap:%"),
            )
        ).scalar_one()
        return int(value)

    def settle(self, session: Session, mode: str, market_id: str, outcome: str) -> list[Position]:
        positions = session.execute(
            select(Position).where(
                Position.mode == mode,
                Position.market_id == market_id,
                Position.status == "open",
            )
        ).scalars().all()
        closed: list[Position] = []
        total_payout = 0.0
        for position in positions:
            payout = position.size if position.side == outcome else 0.0
            pnl = round(payout - position.cost_basis - position.fees, 10)
            position.realized_pnl = round(position.realized_pnl + pnl, 10)  # keeps earlier sale PnL
            position.unrealized_pnl = 0.0
            position.status = "closed"
            position.resolution = outcome
            position.closed_at = utcnow()
            total_payout += payout
            closed.append(position)
        if mode == "paper" and total_payout:
            account = self.account(session, "paper")
            account.balance = round(account.balance + total_payout, 10)
        return closed

    def reset_paper(self, session: Session, balance: float) -> None:
        session.execute(delete(Fill).where(Fill.mode == "paper"))
        session.execute(delete(Order).where(Order.mode == "paper"))
        session.execute(delete(Position).where(Position.mode == "paper"))
        marker = session.get(BotSetting, ORDERS_CLEARED_KEY.format(mode="paper"))
        if marker is not None:
            session.delete(marker)
        account = self.account(session, "paper")
        account.balance = float(balance)
        account.starting_balance = float(balance)
        session.flush()
