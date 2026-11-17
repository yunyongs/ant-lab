import time
from datetime import date

import pytest
from db_support import engine_settings
from fastapi.testclient import TestClient
from test_projections_route import model_result, problem

from antlab_engine.app import create_app
from antlab_engine.history.reader import PostgresHistoryReader
from antlab_engine.settings import DatabaseSettings, Settings

pytestmark = pytest.mark.db

TOKEN = "d" * 40
HEADERS = {"X-Antlab-Engine-Token": TOKEN}
BASE_DATE = date(2026, 1, 2)


def request(**changes) -> dict:
    value = {
        "seasonSeed": "14",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 60,
        "n": 200,
        "riskLimit": "-0.30",
        "portfolios": [
            {
                "key": "TEAM",
                "cash": "0.2",
                "weights": [
                    {"asset": "KR:ZZ0001", "weight": "0.3"},
                    {"asset": "US:ZZUS01", "weight": "0.3"},
                    {"asset": "US:ZZUS02", "weight": "0.2"},
                ],
            },
            {"key": "CASH", "cash": "1", "weights": []},
        ],
    }
    return {**value, **changes}


def client_for(settings: DatabaseSettings) -> TestClient:
    app = create_app(Settings(TOKEN, settings), history_reader=PostgresHistoryReader(settings))
    return TestClient(app, raise_server_exceptions=False)


def test_p8_a_projection_from_the_database_equals_the_library_on_the_same_snapshot(
    history_db, capsys
):
    settings = engine_settings(history_db)
    client = client_for(settings)
    started = time.perf_counter()
    response = client.post("/v1/projections", json=request(), headers=HEADERS)
    elapsed = time.perf_counter() - started
    assert response.status_code == 200, response.text
    snapshot = PostgresHistoryReader(settings).read(BASE_DATE)
    expected = model_result(snapshot, request())
    assert response.json()["portfolios"] == expected["portfolios"]
    assert (
        client.post("/v1/projections", json=request(), headers=HEADERS).content == response.content
    )
    with capsys.disabled():
        print(f"\nM7 db: POST /v1/projections n=200 H=60 {elapsed * 1000:.0f} ms (read included)")


def test_p8_the_database_history_is_too_short_for_ten_years(history_db):
    client = client_for(engine_settings(history_db))
    response = client.post("/v1/projections", json=request(horizonTurns=120), headers=HEADERS)
    problem(response, 422, "PATH_TOO_LONG")


def test_p8_an_asset_outside_the_pool_is_400(history_db):
    portfolio = {"key": "TEAM", "cash": "0.5", "weights": [{"asset": "US:NOPE", "weight": "0.5"}]}
    client = client_for(engine_settings(history_db))
    response = client.post("/v1/projections", json=request(portfolios=[portfolio]), headers=HEADERS)
    problem(response, 400, "INVALID_ARGUMENT")


def test_p8_a_base_date_without_a_pool_is_404(history_db):
    client = client_for(engine_settings(history_db))
    response = client.post("/v1/projections", json=request(baseDate="2020-01-02"), headers=HEADERS)
    problem(response, 404, "POOL_NOT_FOUND")


def test_p8_an_unsafe_role_is_a_generic_500(history_db):
    admin = history_db.admin_params["user"]
    client = client_for(engine_settings(history_db, user=admin))
    response = client.post("/v1/projections", json=request(), headers=HEADERS)
    body = problem(response, 500, "INTERNAL_ERROR")
    assert body["detail"] == "The engine could not process the request."
    assert admin not in response.text


def test_p8_an_unreachable_database_is_503():
    settings = DatabaseSettings(host="127.0.0.1", port=1, name="antlab_api", user="antlab_engine")
    response = client_for(settings).post("/v1/projections", json=request(), headers=HEADERS)
    problem(response, 503, "HISTORY_UNAVAILABLE")
