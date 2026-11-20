"""positions.tag: keeps one-tap buys apart from the strategy stages

Revision ID: 0003_position_tag
Revises: 0002_order_route
Create Date: 2026-11-20
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003_position_tag"
down_revision = "0002_order_route"
branch_labels = None
depends_on = None

OLD_UNIQUE = "uq_position_mode_market_side"
NEW_UNIQUE = "uq_position_mode_market_side_tag"


def _has_column(table: str, column: str) -> bool:
    inspector = sa.inspect(op.get_bind())
    return any(col["name"] == column for col in inspector.get_columns(table))


def _unique_names(table: str) -> set[str]:
    inspector = sa.inspect(op.get_bind())
    return {uc["name"] for uc in inspector.get_unique_constraints(table) if uc.get("name")}


def upgrade() -> None:
    # 0001 builds the schema from the current models, so a brand-new database already has the column
    # and the new unique constraint; only an older database needs the change.
    names = _unique_names("positions")
    has_col = _has_column("positions", "tag")
    if has_col and NEW_UNIQUE in names and OLD_UNIQUE not in names:
        return
    with op.batch_alter_table("positions", recreate="always") as batch:
        if not has_col:
            batch.add_column(sa.Column("tag", sa.String(length=8), nullable=False, server_default=""))
        if OLD_UNIQUE in names:
            batch.drop_constraint(OLD_UNIQUE, type_="unique")
        if NEW_UNIQUE not in names:
            batch.create_unique_constraint(NEW_UNIQUE, ["mode", "market_id", "side", "tag"])


def downgrade() -> None:
    # Tap positions would collide with stage positions of the same side, so they are dropped first.
    op.execute("DELETE FROM positions WHERE tag != ''")
    with op.batch_alter_table("positions", recreate="always") as batch:
        batch.drop_constraint(NEW_UNIQUE, type_="unique")
        batch.drop_column("tag")
        batch.create_unique_constraint(OLD_UNIQUE, ["mode", "market_id", "side"])
