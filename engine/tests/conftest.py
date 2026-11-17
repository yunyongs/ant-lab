import os
import secrets
from pathlib import Path

import pytest
from db_support import SERVER_ID_VAR, HarnessError, resolve_admin_dsn, scratch_database
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


def pytest_collection_modifyitems(config, items):
    if os.environ.get("ANTLAB_GOLDEN_SAMPLE_DIR"):
        return
    skip = pytest.mark.skip(reason="ANTLAB_GOLDEN_SAMPLE_DIR is not set")
    for item in items:
        if "sample" in item.keywords:
            item.add_marker(skip)


@pytest.fixture(scope="session")
def scratch_db():
    """A migrated scratch database with the role script applied (skipped without a DSN)."""
    try:
        dsn = resolve_admin_dsn(os.environ)
    except HarnessError as exc:
        pytest.fail(str(exc), pytrace=False)
    if dsn is None:
        pytest.skip("ANTLAB_ENGINE_TEST_ADMIN_DSN is not set")
    with scratch_database(dsn, os.environ[SERVER_ID_VAR].strip()) as db:
        yield db


@pytest.fixture
def fresh_db():
    """A function-scoped scratch database for tests that pollute state (same isolation checks)."""
    try:
        dsn = resolve_admin_dsn(os.environ)
    except HarnessError as exc:
        pytest.fail(str(exc), pytrace=False)
    if dsn is None:
        pytest.skip("ANTLAB_ENGINE_TEST_ADMIN_DSN is not set")
    with scratch_database(dsn, os.environ[SERVER_ID_VAR].strip()) as db:
        yield db


HISTORY_FIXTURE = Path(__file__).resolve().parent / "fixtures" / "history_fake.sql"


@pytest.fixture(scope="session")
def history_db():
    """A scratch database with the role script applied and the fake history loaded."""
    try:
        dsn = resolve_admin_dsn(os.environ)
    except HarnessError as exc:
        pytest.fail(str(exc), pytrace=False)
    if dsn is None:
        pytest.skip("ANTLAB_ENGINE_TEST_ADMIN_DSN is not set")
    with scratch_database(dsn, os.environ[SERVER_ID_VAR].strip()) as db:
        with db.admin() as admin:
            admin.execute(HISTORY_FIXTURE.read_text(encoding="utf-8"))  # type: ignore[arg-type]
        yield db
