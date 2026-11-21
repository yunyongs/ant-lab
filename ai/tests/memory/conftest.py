import json

import pytest
from memory_helpers import CORPUS

from antlab_ai.memory.filter import load_terms


@pytest.fixture(scope="session")
def manifest() -> dict:
    return json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def terms(manifest):
    """Shipped terms file plus the corpus titles (no tags.csv, so the corpus alone decides)."""
    return load_terms(tags_path=None, extra_titles=manifest["titles"])


@pytest.fixture(scope="session")
def shipped_terms():
    """Shipped terms file plus every events/tags.csv title."""
    return load_terms()
