"""PR D without a database: wiring, the SQL text and the coverage report shape."""

import re
from datetime import date

from conftest import TOKEN_HEADER
from fastapi.testclient import TestClient

from antlab_engine.app import create_app
from antlab_engine.history.queries import REGIME_COVERAGE_SQL, REGIME_OBSERVATIONS_SQL
from antlab_engine.regime import SourceSeries
from antlab_engine.regime import __main__ as coverage_cli
from antlab_engine.regime.pg_source import SOURCE_CODES, PostgresRegimeSource
from antlab_engine.regime.source import RegimeInputs
from antlab_engine.settings import DatabaseSettings, Settings

TOKEN = "k" * 40
UNREACHABLE = DatabaseSettings("127.0.0.1", "none", "antlab_engine", 1, "pw")
BODY = {"labelVersion": "m2-rl-v1", "months": ["2024-01"]}


def test_a_configured_database_builds_the_lazy_postgres_source():
    app = create_app(Settings(TOKEN, UNREACHABLE))
    assert isinstance(app.state.regime_source, PostgresRegimeSource)


def test_without_database_settings_the_route_stays_503(client, auth):
    assert client.app.state.regime_source is None
    response = client.post("/v1/regime/evaluate", json=BODY, headers=auth)
    assert response.status_code == 503
    assert response.json()["code"] == "HISTORY_UNAVAILABLE"


def test_a_given_source_wins_over_the_database_default():
    class Fake:
        def read(self, upper: date) -> RegimeInputs:
            return RegimeInputs(rows=(), calendars={})

    fake = Fake()
    assert create_app(Settings(TOKEN, UNREACHABLE), regime_source=fake).state.regime_source is fake


def test_an_unreachable_database_answers_503_without_naming_it():
    app = create_app(Settings(TOKEN, UNREACHABLE))
    response = TestClient(app, raise_server_exceptions=False).post(
        "/v1/regime/evaluate", json=BODY, headers={TOKEN_HEADER: TOKEN}
    )
    assert response.status_code == 503
    assert response.json()["code"] == "HISTORY_UNAVAILABLE"
    assert "127.0.0.1" not in response.text and "pw" not in response.text.split("detail")[-1]


def test_revisable_codes_are_the_library_revisable_sources():
    from antlab_engine.regime.pg_source import REVISABLE_CODES

    assert set(REVISABLE_CODES) == {"US_CPILFESL", "US_ICSA", "KR_CPI", "KR_CLI", "KR_EXPORTS"}


def test_the_known_codes_are_exactly_the_adapter_sources():
    assert tuple(s.value for s in SourceSeries) == SOURCE_CODES and len(SOURCE_CODES) == 16


def test_sql_is_parameterised_and_selects_only():
    for text in (REGIME_OBSERVATIONS_SQL, REGIME_COVERAGE_SQL):
        assert re.match(r"\s*(WITH|SELECT)\b", text)
        assert not re.search(r"\b(INSERT|UPDATE|DELETE|TRUNCATE|DROP|ALTER|CREATE)\b", text, re.I)
        assert "'" not in text.replace("'UTC'", "").replace("'DAILY'", "").replace("'WEEKLY'", "")
    assert "%(revisable)s" in REGIME_OBSERVATIONS_SQL
    assert "%(upper)s" in REGIME_OBSERVATIONS_SQL and "%(codes)s" in REGIME_OBSERVATIONS_SQL
    assert "<= %(upper)s" in REGIME_OBSERVATIONS_SQL


def entry(code, observations=2, later=0, rule_dated=1):
    return (code, "DAILY", False, observations, observations, date(2024, 1, 2), date(2024, 1, 3),
            0, 1, 1 + (later > 0), later, rule_dated)  # fmt: skip


def test_report_lists_missing_empty_and_unexpected_vintage_series():
    result = coverage_cli.report(
        [
            entry("US_DGS2"),
            entry("KR_KTB3Y", later=1),
            entry("US_CPILFESL", later=1),
            entry("US_VIX", 0),
        ]
    )
    assert result["series"]["US_DGS2"]["first"] == "2024-01-02"
    assert result["series"]["KR_KTB3Y"]["unexpectedVintages"] is True
    assert result["series"]["US_CPILFESL"]["unexpectedVintages"] is False
    assert result["noObservations"] == ["US_VIX"]
    assert "US_ICSA" in result["notInCatalog"] and "US_DGS2" not in result["notInCatalog"]
