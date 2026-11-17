import time

import pytest
from db_support import engine_settings
from fastapi.testclient import TestClient
from synthetic_months import sample_shape
from test_paths_route import FakeReader, problem, request

from antlab_engine.app import create_app
from antlab_engine.history.reader import PostgresHistoryReader
from antlab_engine.settings import DatabaseSettings, Settings

pytestmark = pytest.mark.db

TOKEN = "d" * 40
HEADERS = {"X-Antlab-Engine-Token": TOKEN}


def client_for(settings: DatabaseSettings) -> TestClient:
    app = create_app(Settings(TOKEN, settings), history_reader=PostgresHistoryReader(settings))
    return TestClient(app, raise_server_exceptions=False)


def test_p8_a_60_turn_path_from_the_database_equals_the_fake_reader_path(history_db, capsys):
    client = client_for(engine_settings(history_db))
    reference = TestClient(
        create_app(Settings(TOKEN), history_reader=FakeReader(sample_shape())),
        raise_server_exceptions=False,
    )
    for version in ("m2-sb6-v1", "p0-boot6-v1"):
        started = time.perf_counter()
        response = client.post("/v1/paths", json=request(engineVersion=version), headers=HEADERS)
        elapsed = time.perf_counter() - started
        assert response.status_code == 200, response.text
        expected = reference.post("/v1/paths", json=request(engineVersion=version), headers=HEADERS)
        assert response.json() == expected.json()
        assert len(response.json()["months"]) == 60
        with capsys.disabled():
            print(
                f"\nM5 {version}: POST /v1/paths {elapsed * 1000:.0f} ms (database read included)"
            )


def test_p8_a_base_date_without_a_pool_is_404(history_db):
    client = client_for(engine_settings(history_db))
    response = client.post("/v1/paths", json=request(baseDate="2020-01-02"), headers=HEADERS)
    problem(response, 404, "POOL_NOT_FOUND")


def test_p8_an_unsafe_role_is_a_generic_500(history_db):
    admin = history_db.admin_params["user"]
    client = client_for(engine_settings(history_db, user=admin))
    response = client.post("/v1/paths", json=request(), headers=HEADERS)
    body = problem(response, 500, "INTERNAL_ERROR")
    assert body["detail"] == "The engine could not process the request."
    assert admin not in response.text


def test_p8_an_unreachable_database_is_503(history_db):
    settings = DatabaseSettings(host="127.0.0.1", port=1, name="antlab_api", user="antlab_engine")
    response = client_for(settings).post("/v1/paths", json=request(), headers=HEADERS)
    problem(response, 503, "HISTORY_UNAVAILABLE")
