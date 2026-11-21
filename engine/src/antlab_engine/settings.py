import os
import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date

from antlab_engine.bootstrap.simple import REPLAY_START as DEFAULT_REPLAY_START

TOKEN_VARIABLE = "ANTLAB_ENGINE_TOKEN"  # noqa: S105 - a variable name, not a secret
MIN_TOKEN_LENGTH = 32

DB_HOST_VARIABLE = "ANTLAB_ENGINE_DB_HOST"
DB_PORT_VARIABLE = "ANTLAB_ENGINE_DB_PORT"
DB_NAME_VARIABLE = "ANTLAB_ENGINE_DB_NAME"
DB_USER_VARIABLE = "ANTLAB_ENGINE_DB_USER"
DB_PASSWORD_VARIABLE = "ANTLAB_ENGINE_DB_PASSWORD"  # noqa: S105 - a variable name, not a secret
DEFAULT_DB_PORT = 5432
METRICS_PORT_VARIABLE = "ANTLAB_ENGINE_METRICS_PORT"
DEFAULT_METRICS_PORT = 8001
METRICS_HOST_VARIABLE = "ANTLAB_ENGINE_METRICS_HOST"
DEFAULT_METRICS_HOST = "127.0.0.1"  # the container sets 0.0.0.0 in compose
WORKERS_VARIABLE = "WEB_CONCURRENCY"
REPLAY_START_VARIABLE = "ANTLAB_HISTORY_REPLAY_START"  # the same name the api reads
_ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")


class SettingsError(ValueError):
    pass


def _required(values: Mapping[str, str], variable: str) -> str:
    value = values.get(variable, "")
    if value.strip() == "" or value != value.strip() or any(ord(c) < 33 for c in value):
        raise SettingsError(f"{variable} is required and must not contain whitespace")
    return value


def _port(values: Mapping[str, str]) -> int:
    value = values.get(DB_PORT_VARIABLE)
    if value is None:
        return DEFAULT_DB_PORT
    if not (value.isascii() and value.isdigit() and 1 <= int(value) <= 65535):
        raise SettingsError(f"{DB_PORT_VARIABLE} must be a port number from 1 to 65535")
    return int(value)


def _metrics_port(values: Mapping[str, str]) -> int:
    value = values.get(METRICS_PORT_VARIABLE)
    if value is None:
        return DEFAULT_METRICS_PORT
    if not (value.isascii() and value.isdigit() and 1 <= int(value) <= 65535):
        raise SettingsError(f"{METRICS_PORT_VARIABLE} must be a port number from 1 to 65535")
    return int(value)


def _replay_start(values: Mapping[str, str]) -> date:
    value = values.get(REPLAY_START_VARIABLE)
    if value is None:
        return DEFAULT_REPLAY_START
    try:
        if not _ISO_DATE.fullmatch(value):
            raise ValueError
        return _first_of_month(date.fromisoformat(value))
    except ValueError:
        raise SettingsError(
            f"{REPLAY_START_VARIABLE} must be the first day of a month as YYYY-MM-DD, got {value!r}"
        ) from None


def _first_of_month(value: date) -> date:
    if value.day != 1:
        raise ValueError
    return value


def _single_worker(values: Mapping[str, str]) -> None:
    workers = values.get(WORKERS_VARIABLE, "1").strip() or "1"
    if not (workers.isascii() and workers.isdigit() and int(workers) == 1):
        raise SettingsError(
            f"{WORKERS_VARIABLE} must be 1: each worker would own its own metrics registry and port"
        )


@dataclass(frozen=True, slots=True)
class DatabaseSettings:
    host: str
    name: str
    user: str
    port: int = DEFAULT_DB_PORT
    password: str = field(default="", repr=False)

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "DatabaseSettings":
        values = os.environ if environ is None else environ
        return cls(
            host=_required(values, DB_HOST_VARIABLE),
            name=_required(values, DB_NAME_VARIABLE),
            user=_required(values, DB_USER_VARIABLE),
            port=_port(values),
            password=values.get(DB_PASSWORD_VARIABLE, ""),
        )


@dataclass(frozen=True, slots=True)
class Settings:
    engine_token: str = field(repr=False)
    database: DatabaseSettings | None = None
    metrics_port: int | None = None
    metrics_host: str = DEFAULT_METRICS_HOST
    replay_start: date = DEFAULT_REPLAY_START

    def __post_init__(self) -> None:
        if type(self.replay_start) is not date or self.replay_start.day != 1:
            raise SettingsError("replay_start must be the first day of a month")
        token = self.engine_token
        if len(token) < MIN_TOKEN_LENGTH or any(not 33 <= ord(c) <= 126 for c in token):
            raise SettingsError(
                f"{TOKEN_VARIABLE} must be at least {MIN_TOKEN_LENGTH} printable ASCII "
                "characters without whitespace"
            )

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "Settings":
        values = os.environ if environ is None else environ
        token = values.get(TOKEN_VARIABLE, "")
        cls(token)  # the token is checked first
        _single_worker(values)
        return cls(
            token,
            DatabaseSettings.from_env(values),
            _metrics_port(values),
            values.get(METRICS_HOST_VARIABLE, DEFAULT_METRICS_HOST),
            _replay_start(values),
        )
