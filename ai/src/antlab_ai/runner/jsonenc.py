"""JSON writer for outgoing runner bodies: a Decimal goes out as the exact number it was parsed from."""

from __future__ import annotations

import json
from decimal import Decimal
from typing import Any


class RunnerBodyError(TypeError):
    """A request body holds a value the runner never sends (a binary float, a non-finite number, a stranger)."""


def dumps(body: Any, *, decimals_under: str | None = None) -> str:
    """Compact JSON text. A binary float or a non-finite Decimal is always refused.

    A finite Decimal is written with its own digits (never through float) but only inside the
    value of the top-level key ``decimals_under``; anywhere else it is refused, because amounts
    travel as strings.
    """
    parts: list[str] = []
    _write(body, parts, decimals_under, False)
    return "".join(parts)


def _write(node: Any, out: list[str], key: str | None, decimal_ok: bool) -> None:
    if node is None:
        out.append("null")
    elif node is True:
        out.append("true")
    elif node is False:
        out.append("false")
    elif isinstance(node, int):
        out.append(str(int(node)))
    elif isinstance(node, str):
        out.append(json.dumps(node, ensure_ascii=False))
    elif isinstance(node, float):
        raise RunnerBodyError("float cannot be sent as JSON")
    elif isinstance(node, Decimal):
        if not decimal_ok:
            raise RunnerBodyError("Decimal cannot be sent as JSON here")
        if not node.is_finite():
            raise RunnerBodyError("a non-finite number cannot be sent as JSON")
        out.append(str(node))
    elif isinstance(node, dict):
        out.append("{")
        for index, (name, value) in enumerate(node.items()):
            if not isinstance(name, str):
                raise RunnerBodyError("a JSON key must be text")
            if index:
                out.append(",")
            out.append(json.dumps(name, ensure_ascii=False))
            out.append(":")
            _write(value, out, None, decimal_ok or (key is not None and name == key))
        out.append("}")
    elif isinstance(node, list | tuple):
        out.append("[")
        for index, value in enumerate(node):
            if index:
                out.append(",")
            _write(value, out, None, decimal_ok)
        out.append("]")
    else:
        raise RunnerBodyError(f"{type(node).__name__} cannot be sent as JSON")
