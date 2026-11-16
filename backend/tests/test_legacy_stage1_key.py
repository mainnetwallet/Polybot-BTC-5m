"""The old `final_window_seconds` / FINAL_WINDOW_SECONDS names keep working after the rename to stage1."""

from __future__ import annotations

from app.config.runtime import SettingsService
from app.config.settings import Settings
from app.database.models import BotSetting
from app.database.session import session_scope


def test_new_env_name(monkeypatch):
    monkeypatch.delenv("FINAL_WINDOW_SECONDS", raising=False)
    monkeypatch.setenv("STAGE1_SECONDS", "77")
    assert Settings().stage1_seconds == 77


def test_old_env_name_is_still_read(monkeypatch):
    monkeypatch.delenv("STAGE1_SECONDS", raising=False)
    monkeypatch.setenv("FINAL_WINDOW_SECONDS", "66")
    assert Settings().stage1_seconds == 66


def test_default_and_keyword_still_work(monkeypatch):
    monkeypatch.delenv("STAGE1_SECONDS", raising=False)
    monkeypatch.delenv("FINAL_WINDOW_SECONDS", raising=False)
    assert Settings(_env_file=None).stage1_seconds == 90
    assert Settings(stage1_seconds=55).stage1_seconds == 55


def test_saved_override_under_old_key_is_moved():
    service = SettingsService(Settings())
    with session_scope() as session:
        session.query(BotSetting).delete()
        session.add(BotSetting(key="final_window_seconds", value=95))
    with session_scope() as session:
        service.load(session)
        assert service.get().stage1_seconds == 95
        assert session.get(BotSetting, "final_window_seconds") is None
        assert session.get(BotSetting, "stage1_seconds").value == 95
        service.reset(session)  # the old override must not come back after a reset
    with session_scope() as session:
        service.load(session)
        assert session.get(BotSetting, "final_window_seconds") is None


def test_new_key_wins_over_old_key():
    service = SettingsService(Settings())
    with session_scope() as session:
        session.query(BotSetting).delete()
        session.add(BotSetting(key="final_window_seconds", value=95))
        session.add(BotSetting(key="stage1_seconds", value=100))
    with session_scope() as session:
        service.load(session)
        assert service.get().stage1_seconds == 100
        assert session.get(BotSetting, "final_window_seconds") is None
        session.query(BotSetting).delete()
