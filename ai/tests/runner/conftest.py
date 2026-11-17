import json
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS = REPO_ROOT / "src" / "test" / "resources" / "agent" / "turn-decision"
FIXTURES = Path(__file__).resolve().parent / "fixtures"


@pytest.fixture(scope="session")
def repo_root() -> Path:
    return REPO_ROOT


@pytest.fixture(scope="session")
def corpus() -> Path:
    return CORPUS


@pytest.fixture(scope="session")
def manifest() -> dict:
    return json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def small_view() -> dict:
    return json.loads((FIXTURES / "turn_view_small.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def pool180_view() -> dict:
    return json.loads((FIXTURES / "turn_view_pool180.json").read_text(encoding="utf-8"))
