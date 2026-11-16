import secrets
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from antlab_engine.app import create_app
from antlab_engine.settings import Settings

ENGINE_DIR = Path(__file__).resolve().parents[1]
CONTRACT_PATH = ENGINE_DIR.parent / "docs" / "api" / "engine.yaml"
TOKEN_HEADER = "X-Antlab-Engine-Token"  # noqa: S105 - a header name, not a secret


@pytest.fixture
def token() -> str:
    return secrets.token_hex(32)


@pytest.fixture
def app(token):
    return create_app(Settings(token))


@pytest.fixture
def client(app):
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def auth(token) -> dict[str, str]:
    return {TOKEN_HEADER: token}
