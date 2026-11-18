from __future__ import annotations

from pathlib import Path

from alembic import command
from alembic.config import Config

from app.config.settings import ROOT_DIR


def alembic_config() -> Config:
    cfg = Config(str(ROOT_DIR / "backend" / "alembic.ini"))
    cfg.set_main_option("script_location", str(ROOT_DIR / "backend" / "migrations"))
    return cfg


def upgrade_database() -> None:
    command.upgrade(alembic_config(), "head")


def current_revision() -> str | None:
    from alembic.runtime.migration import MigrationContext

    from app.database.session import get_engine

    with get_engine().connect() as connection:
        return MigrationContext.configure(connection).get_current_revision()


def ensure_schema() -> None:
    # SQLite file lives in data/; create it before Alembic opens the DB.
    Path(ROOT_DIR / "data").mkdir(parents=True, exist_ok=True)
    # Always run to head (a no-op when current): an existing database must pick up new migrations too,
    # e.g. the orders.route column, not only a brand-new one.
    upgrade_database()
