"""The pick hash of `db/24`: the first 8 bytes of MD5, big-endian, sign bit cleared."""

import hashlib

from .rules import RULE_VERSION

_MAX = (1 << 63) - 1


def hash_text(text: str) -> int:
    digest = hashlib.md5(text.encode("utf-8"), usedforsecurity=False).digest()  # noqa: S324
    return int.from_bytes(digest[:8], "big") & _MAX


def pick(seed: int, turn_no: int, usage: str, key: str) -> int:
    return hash_text(f"{seed}:{turn_no}:{RULE_VERSION}:{usage}:{key}")


def byte_key(text: str) -> bytes:
    """Sort key of the UTF-8 byte order (PostgreSQL COLLATE "C" in a UTF-8 database)."""
    return text.encode("utf-8")


def utf16_key(text: str) -> bytes:
    """Sort key of Java `String.compareTo` (UTF-16 code units)."""
    return text.encode("utf-16-be")
