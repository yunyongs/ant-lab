import pytest

from antlab_engine.app import create_app, create_app_from_env
from antlab_engine.settings import Settings, SettingsError

INVALID = [
    "",
    "   ",
    "a" * 31,
    "a" * 31 + " ",
    "a" * 16 + " " + "a" * 16,
    "a" * 16 + "\t" + "a" * 16,
    "a" * 31 + "\n",
    "가" * 32,
    "a" * 31 + "é",
]


@pytest.mark.parametrize("value", INVALID)
def test_invalid_tokens_are_rejected_without_echo(value):
    with pytest.raises(SettingsError, match="ANTLAB_ENGINE_TOKEN") as error:
        Settings.from_env({"ANTLAB_ENGINE_TOKEN": value})
    assert value.strip() == "" or value not in str(error.value)


@pytest.mark.parametrize("value", INVALID)
def test_direct_construction_is_validated_without_echo(value):
    with pytest.raises(SettingsError, match="ANTLAB_ENGINE_TOKEN") as error:
        Settings(value)
    assert value.strip() == "" or value not in str(error.value)


def test_create_app_rejects_a_blank_token():
    with pytest.raises(SettingsError):
        create_app(Settings(""))


def test_unset_token_is_rejected():
    with pytest.raises(SettingsError, match="ANTLAB_ENGINE_TOKEN"):
        Settings.from_env({})


@pytest.mark.parametrize("length", [32, 64])
def test_valid_tokens_are_accepted(length):
    assert Settings.from_env({"ANTLAB_ENGINE_TOKEN": "a" * length}).engine_token == "a" * length


def test_repr_hides_the_token():
    assert "a" * 40 not in repr(Settings("a" * 40))


def test_from_env_reads_the_process_environment(monkeypatch):
    monkeypatch.setenv("ANTLAB_ENGINE_TOKEN", "b" * 40)
    assert Settings.from_env().engine_token == "b" * 40


def test_app_from_env_fails_before_serving(monkeypatch):
    monkeypatch.delenv("ANTLAB_ENGINE_TOKEN", raising=False)
    with pytest.raises(SettingsError):
        create_app_from_env()
