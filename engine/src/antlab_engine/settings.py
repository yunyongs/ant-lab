import os
from collections.abc import Mapping
from dataclasses import dataclass, field

TOKEN_VARIABLE = "ANTLAB_ENGINE_TOKEN"  # noqa: S105 - a variable name, not a secret
MIN_TOKEN_LENGTH = 32

DB_HOST_VARIABLE = "ANTLAB_ENGINE_DB_HOST"
DB_PORT_VARIABLE = "ANTLAB_ENGINE_DB_PORT"
DB_NAME_VARIABLE = "ANTLAB_ENGINE_DB_NAME"
DB_USER_VARIABLE = "ANTLAB_ENGINE_DB_USER"
DB_PASSWORD_VARIABLE = "ANTLAB_ENGINE_DB_PASSWORD"  # noqa: S105 - a variable name, not a secret
DEFAULT_DB_PORT = 5432


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

    def __post_init__(self) -> None:
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
        return cls(token, DatabaseSettings.from_env(values))
