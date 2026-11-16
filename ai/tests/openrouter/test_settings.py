import os

import pytest

from antlab_ai.settings import Settings

KEY = "sk-or-v1-TESTSECRET"


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in ("OPENROUTER_API_KEY", "ANTLAB_OPENROUTER_LEDGER", "ANTLAB_OPENROUTER_TIER"):
        monkeypatch.delenv(name, raising=False)


def test_env_wins_over_file_and_file_does_not_mutate_environ(tmp_path, monkeypatch):
    env_file = tmp_path / "e"
    env_file.write_text("OPENROUTER_API_KEY=from-file\nANTLAB_OPENROUTER_TIER=1000\n")
    monkeypatch.setenv("OPENROUTER_API_KEY", "from-env")
    settings = Settings.from_env(str(env_file))
    assert settings.api_key == "from-env"
    assert settings.tier == 1000  # only present in the file
    assert "ANTLAB_OPENROUTER_TIER" not in os.environ


def test_defaults():
    settings = Settings.from_env()
    assert (settings.api_key, settings.ledger_path, settings.tier) == (None, None, 50)


@pytest.mark.parametrize("tier", ["0", "49", "51", "999", "1001", "abc", ""])
def test_invalid_tier_is_a_startup_error(tier, monkeypatch):
    monkeypatch.setenv("ANTLAB_OPENROUTER_TIER", tier)
    with pytest.raises(ValueError, match="ANTLAB_OPENROUTER_TIER"):
        Settings.from_env()


def test_live_requires_key_and_names_only_the_variable(monkeypatch):
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", "/abs/ledger.jsonl")
    with pytest.raises(ValueError, match="OPENROUTER_API_KEY"):
        Settings.from_env(live=True)


def test_live_requires_absolute_ledger_path(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", KEY)
    with pytest.raises(ValueError, match="ANTLAB_OPENROUTER_LEDGER") as unset:
        Settings.from_env(live=True)
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", "relative/ledger.jsonl")
    with pytest.raises(ValueError, match="ANTLAB_OPENROUTER_LEDGER") as relative:
        Settings.from_env(live=True)
    assert KEY not in str(unset.value) + str(relative.value)


def test_dry_mode_needs_neither_key_nor_ledger():
    assert Settings.from_env(live=False).ledger_path is None


def test_repr_masks_key(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", KEY)
    settings = Settings.from_env()
    assert "TESTSECRET" not in repr(settings) + str(settings)
