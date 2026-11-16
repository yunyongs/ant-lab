"""Product model policy: free, named models only."""

from __future__ import annotations

import re

_ID = re.compile(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(:free)?")
_FORBIDDEN_VENDORS = ("anthropic", "openai", "openrouter")


def validate_model(model: str, paid_enabled: bool = False) -> None:
    if not _ID.fullmatch(model):
        raise ValueError("model is not allowed")
    if model.split("/", 1)[0].lower() in _FORBIDDEN_VENDORS:
        raise ValueError("model is not allowed")
    if not paid_enabled and not model.endswith(":free"):
        raise ValueError("model is not allowed")
