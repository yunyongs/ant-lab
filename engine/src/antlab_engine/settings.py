import os
from collections.abc import Mapping
from dataclasses import dataclass, field

TOKEN_VARIABLE = "ANTLAB_ENGINE_TOKEN"  # noqa: S105 - a variable name, not a secret
MIN_TOKEN_LENGTH = 32


class SettingsError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Settings:
    engine_token: str = field(repr=False)

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
        return cls(values.get(TOKEN_VARIABLE, ""))
