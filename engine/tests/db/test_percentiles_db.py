from datetime import date
from decimal import Decimal

import pytest
from db_support import engine_settings
from fastapi.testclient import TestClient
from test_projections_route import problem

from antlab_engine.api.projection_panel import panel_from_snapshot
from antlab_engine.app import create_app
from antlab_engine.history.reader import PostgresHistoryReader
from antlab_engine.projection import Portfolio, rank
from antlab_engine.settings import DatabaseSettings, Settings

pytestmark = pytest.mark.db

TOKEN = "d" * 40
HEADERS = {"X-Antlab-Engine-Token": TOKEN}
BASE_DATE = date(2026, 1, 2)


def request(**changes) -> dict:
    value = {
        "seasonSeed": "14",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 24,
        "n": 200,
        "team": {
            "cash": "0.2",
            "weights": [
                {"asset": "KR:ZZ0001", "weight": "0.3"},
                {"asset": "US:ZZUS01", "weight": "0.3"},
                {"asset": "US:ZZUS02", "weight": "0.2"},
            ],
        },
        "benchmark": {"cash": "0.5", "weights": [{"asset": "KR:ZZ0001", "weight": "0.5"}]},
        "observed": {"endMultiple": "1.05", "sharpeDiff": "0.01"},
    }
    return {**value, **changes}


def client_for(settings: DatabaseSettings) -> TestClient:
    app = create_app(Settings(TOKEN, settings), history_reader=PostgresHistoryReader(settings))
    return TestClient(app, raise_server_exceptions=False)


def test_p9_a_percentile_from_the_database_equals_the_library_on_the_same_snapshot(history_db):
    settings = engine_settings(history_db)
    client = client_for(settings)
    response = client.post("/v1/percentiles", json=request(), headers=HEADERS)
    assert response.status_code == 200, response.text
    panel = panel_from_snapshot(PostgresHistoryReader(settings).read(BASE_DATE))
    team = Portfolio(
        "TEAM",
        {"KR:ZZ0001": Decimal("0.3"), "US:ZZUS01": Decimal("0.3"), "US:ZZUS02": Decimal("0.2")},
        Decimal("0.2"),
    )
    other = Portfolio("BENCH", {"KR:ZZ0001": Decimal("0.5")}, Decimal("0.5"))
    expected = rank(
        panel,
        BASE_DATE,
        season_seed=14,
        horizon=24,
        team=team,
        benchmark=other,
        observed_end_multiple=Decimal("1.05"),
        observed_sharpe_diff=Decimal("0.01"),
        n=200,
    )
    body = response.json()
    assert body["endMultiple"] == {
        "below": expected.end_multiple.below,
        "equal": expected.end_multiple.equal,
    }
    assert body["sharpeDiff"] == {
        "below": expected.sharpe_diff.below,
        "equal": expected.sharpe_diff.equal,
    }
    assert (
        client.post("/v1/percentiles", json=request(), headers=HEADERS).content == response.content
    )


def test_p9_the_database_history_is_too_short_for_ten_years(history_db):
    client = client_for(engine_settings(history_db))
    response = client.post("/v1/percentiles", json=request(horizonTurns=120), headers=HEADERS)
    problem(response, 422, "PATH_TOO_LONG")
