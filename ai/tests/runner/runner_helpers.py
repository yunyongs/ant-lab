"""Shared helpers of the runner tests."""

import json


def reply(**overrides) -> str:
    """A shape-valid reply as text; keyword overrides replace top-level keys."""
    body = {
        "schema": "turn-decision/1",
        "formation_change": None,
        "orders": [],
        "theses": [],
        "reason": "hold",
    }
    body.update(overrides)
    return json.dumps(body, ensure_ascii=False)
