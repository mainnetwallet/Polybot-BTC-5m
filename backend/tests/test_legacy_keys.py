"""The old names (final_window_seconds, trigger_threshold) keep working after the rename to stage1."""

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


# ----------------------------------------------- trigger_threshold -> stage1_threshold
def test_threshold_new_and_old_env_names(monkeypatch):
    monkeypatch.delenv("TRIGGER_THRESHOLD", raising=False)
    monkeypatch.setenv("STAGE1_THRESHOLD", "0.81")
    assert Settings().stage1_threshold == 0.81
    monkeypatch.delenv("STAGE1_THRESHOLD")
    monkeypatch.setenv("TRIGGER_THRESHOLD", "0.82")
    assert Settings().stage1_threshold == 0.82
    monkeypatch.delenv("TRIGGER_THRESHOLD")
    assert Settings(_env_file=None).stage1_threshold == 0.85
    assert Settings(stage1_threshold=0.83).stage1_threshold == 0.83


def test_saved_override_under_old_threshold_key_is_moved():
    service = SettingsService(Settings())
    with session_scope() as session:
        session.query(BotSetting).delete()
        session.add(BotSetting(key="trigger_threshold", value=0.91))
        session.add(BotSetting(key="final_window_seconds", value=95))
    with session_scope() as session:
        service.load(session)
        runtime = service.get()
        assert (runtime.stage1_threshold, runtime.stage1_seconds) == (0.91, 95)
        assert session.get(BotSetting, "trigger_threshold") is None
        assert session.get(BotSetting, "stage1_threshold").value == 0.91
        service.reset(session)
        session.query(BotSetting).delete()
