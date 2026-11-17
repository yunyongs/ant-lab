import pytest

from antlab_engine.app import create_app, create_app_from_env
from antlab_engine.settings import DatabaseSettings, Settings, SettingsError

DB_ENV = {
    "ANTLAB_ENGINE_DB_HOST": "db",
    "ANTLAB_ENGINE_DB_NAME": "antlab_api",
    "ANTLAB_ENGINE_DB_USER": "antlab_engine",
}
TOKEN = "t" * 40

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
        Settings.from_env({"ANTLAB_ENGINE_TOKEN": value, **DB_ENV})
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
        Settings.from_env(DB_ENV)


@pytest.mark.parametrize("length", [32, 64])
def test_valid_tokens_are_accepted(length):
    assert (
        Settings.from_env({"ANTLAB_ENGINE_TOKEN": "a" * length, **DB_ENV}).engine_token
        == "a" * length
    )


def test_repr_hides_the_token():
    assert "a" * 40 not in repr(Settings("a" * 40))


def test_from_env_reads_the_process_environment(monkeypatch):
    monkeypatch.setenv("ANTLAB_ENGINE_TOKEN", "b" * 40)
    for name, value in DB_ENV.items():
        monkeypatch.setenv(name, value)
    assert Settings.from_env().engine_token == "b" * 40


def test_app_from_env_fails_before_serving(monkeypatch):
    monkeypatch.delenv("ANTLAB_ENGINE_TOKEN", raising=False)
    for name, value in DB_ENV.items():
        monkeypatch.setenv(name, value)
    with pytest.raises(SettingsError):
        create_app_from_env()


def test_s1_database_settings_from_env_use_defaults():
    settings = Settings.from_env({"ANTLAB_ENGINE_TOKEN": TOKEN, **DB_ENV})
    assert settings.database == DatabaseSettings("db", "antlab_api", "antlab_engine", 5432, "")


def test_s1_explicit_port_and_password():
    env = {**DB_ENV, "ANTLAB_ENGINE_DB_PORT": "55432", "ANTLAB_ENGINE_DB_PASSWORD": "pw-value"}
    database = DatabaseSettings.from_env(env)
    assert database.port == 55432 and database.password == "pw-value"  # noqa: S105


@pytest.mark.parametrize("variable", sorted(DB_ENV))
@pytest.mark.parametrize("value", [None, "", "   ", " db", "d b", "db\n"])
def test_s2_required_database_variables_name_the_variable_only(variable, value):
    env = {**DB_ENV, "ANTLAB_ENGINE_TOKEN": TOKEN}
    if value is None:
        del env[variable]
    else:
        env[variable] = value
    with pytest.raises(SettingsError, match=variable) as error:
        Settings.from_env(env)
    assert value is None or value.strip() == "" or value not in str(error.value)


@pytest.mark.parametrize("value", ["abc", "0", "65536", " 5432", "5432 ", "-1", "1.5", "٣٠"])
def test_s3_invalid_ports_are_rejected(value):
    with pytest.raises(SettingsError, match="ANTLAB_ENGINE_DB_PORT"):
        DatabaseSettings.from_env({**DB_ENV, "ANTLAB_ENGINE_DB_PORT": value})


@pytest.mark.parametrize("value", ["1", "5432", "65535"])
def test_s3_boundary_ports_are_accepted(value):
    assert DatabaseSettings.from_env({**DB_ENV, "ANTLAB_ENGINE_DB_PORT": value}).port == int(value)


def test_s4_repr_and_errors_hide_password_and_token():
    password = "s3cret-test-value"  # noqa: S105 - fake value
    env = {**DB_ENV, "ANTLAB_ENGINE_TOKEN": TOKEN, "ANTLAB_ENGINE_DB_PASSWORD": password}
    settings = Settings.from_env(env)
    assert password not in repr(settings) and TOKEN not in repr(settings)
    with pytest.raises(SettingsError) as error:
        Settings.from_env({**env, "ANTLAB_ENGINE_DB_PORT": "abc"})
    assert password not in str(error.value) and TOKEN not in str(error.value)


def test_s5_settings_without_database_stay_valid():
    assert Settings(TOKEN).database is None
