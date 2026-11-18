"""orders.route: how the order POST was sent (proxy | direct)

Revision ID: 0002_order_route
Revises: 0001_initial
Create Date: 2026-11-19
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002_order_route"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def _has_column(table: str, column: str) -> bool:
    inspector = sa.inspect(op.get_bind())
    return any(col["name"] == column for col in inspector.get_columns(table))


def upgrade() -> None:
    # 0001 builds the schema from the current models, so a brand-new database already has the column.
    if not _has_column("orders", "route"):
        op.add_column("orders", sa.Column("route", sa.String(length=12), nullable=True))


def downgrade() -> None:
    if _has_column("orders", "route"):
        with op.batch_alter_table("orders") as batch:
            batch.drop_column("route")
