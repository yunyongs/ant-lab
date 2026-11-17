import logging
import re
from datetime import date
from typing import Annotated

import psycopg
import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from psycopg import pq
from psycopg.conninfo import make_conninfo

from antlab_engine.api.deps import history_reader
from antlab_engine.app import create_app
from antlab_engine.engine_errors import HistoryUnavailable
from antlab_engine.history import __main__ as smoke
from antlab_engine.history.model import (
    HistoryMonth,
    HistoryReader,
    HistorySnapshot,
    SessionFacts,
)
from antlab_engine.history.reader import PostgresHistoryReader, connection_parameters
from antlab_engine.settings import DatabaseSettings, Settings

PASSWORD = "s3cret-test-value"  # noqa: S105 - fake value
UNREACHABLE = DatabaseSettings(
    host="127.0.0.1", port=1, name="antlab_api", user="antlab_engine", password=PASSWORD
)
TOKEN = "t" * 40


class FakeReader:
    def read(self, base_date: date) -> HistorySnapshot:
        raise NotImplementedError


def test_h10_connection_failure_hides_password_and_dates(caplog):
    caplog.set_level(logging.DEBUG)
    with pytest.raises(HistoryUnavailable) as error:
        PostgresHistoryReader(UNREACHABLE).read(date(2026, 1, 2))
    exc = error.value
    for text in (str(exc), repr(exc), caplog.text, repr(UNREACHABLE)):
        assert PASSWORD not in text
        assert not re.search(r"\d{4}-\d{2}", text)
    assert exc.__cause__ is None and exc.__suppress_context__


def test_h11_app_creation_and_healthz_open_no_connection(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("psycopg.connect was called")

    monkeypatch.setattr(psycopg, "connect", forbidden)
    app = create_app(Settings(TOKEN, UNREACHABLE))
    response = TestClient(app).get("/healthz")
    assert response.status_code == 200


def test_h12_the_given_reader_is_the_dependency():
    reader = FakeReader()
    app = create_app(Settings(TOKEN), history_reader=reader)
    assert app.state.history_reader is reader

    probe = FastAPI()
    probe.state.history_reader = reader

    @probe.get("/probe")
    def probe_route(
        found: Annotated[HistoryReader, Depends(history_reader)],
    ) -> dict[str, bool]:
        return {"same": found is reader}

    assert TestClient(probe).get("/probe").json() == {"same": True}


def test_h12_dependency_override_wins():
    app = create_app(Settings(TOKEN, UNREACHABLE))
    replacement = FakeReader()

    @app.get("/probe-reader")
    def probe(found: Annotated[HistoryReader, Depends(history_reader)]) -> dict[str, bool]:
        return {"same": found is replacement}

    app.dependency_overrides[history_reader] = lambda: replacement
    response = TestClient(app).get("/probe-reader", headers={"X-Antlab-Engine-Token": TOKEN})
    assert response.json() == {"same": True}


def test_without_database_settings_the_dependency_reports_unavailable():
    app = create_app(Settings(TOKEN))
    assert app.state.history_reader is None
    request = type("Request", (), {"app": app})()
    with pytest.raises(HistoryUnavailable):
        history_reader(request)  # type: ignore[arg-type]


def test_default_reader_is_postgres_and_lazy():
    app = create_app(Settings(TOKEN, UNREACHABLE))
    assert isinstance(app.state.history_reader, PostgresHistoryReader)


def test_connection_parameters_are_explicit_and_always_carry_a_password():
    params = connection_parameters(DatabaseSettings("h", "d", "u", 5433))
    assert params["host"] == "h" and params["port"] == 5433
    assert params["passfile"].startswith("/nonexistent/") and params["options"] == ""
    assert params["password"] == "" and params["hostaddr"] == ""
    assert params["gssencmode"] == "disable"
    assert connection_parameters(UNREACHABLE)["password"] == PASSWORD


def test_smoke_reports_missing_settings_without_values(monkeypatch, capsys):
    for name in ("HOST", "NAME", "USER", "PORT", "PASSWORD"):
        monkeypatch.delenv(f"ANTLAB_ENGINE_DB_{name}", raising=False)
    assert smoke.main(["--base-date", "2026-01-02"]) == 1
    err = capsys.readouterr().err
    assert "ANTLAB_ENGINE_DB_HOST" in err


def test_smoke_reports_the_error_code_of_an_unreachable_database(monkeypatch, capsys):
    values = {
        "ANTLAB_ENGINE_DB_HOST": "127.0.0.1",
        "ANTLAB_ENGINE_DB_PORT": "1",
        "ANTLAB_ENGINE_DB_NAME": "antlab_api",
        "ANTLAB_ENGINE_DB_USER": "antlab_engine",
        "ANTLAB_ENGINE_DB_PASSWORD": PASSWORD,
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    assert smoke.main(["--base-date", "2026-01-02"]) == 1
    captured = capsys.readouterr()
    assert "HISTORY_UNAVAILABLE" in captured.err
    assert PASSWORD not in captured.err + captured.out


def effective_options(params):
    conn = pq.PGconn.connect_start(make_conninfo("", **params).encode())
    try:
        return {o.keyword.decode(): (o.val or b"").decode() for o in conn.info}
    finally:
        conn.finish()


def test_libpq_environment_cannot_change_effective_options(monkeypatch):
    for name, value in {
        "PGHOSTADDR": "10.255.255.1",
        "PGPASSWORD": "leak",
        "PGGSSENCMODE": "require",
        "PGOPTIONS": "-c default_transaction_read_only=off",
        "PGHOST": "bogus",
        "PGPORT": "1",
        "PGUSER": "bogus",
        "PGDATABASE": "bogus",
        "PGSSLMODE": "require",
    }.items():
        monkeypatch.setenv(name, value)
    settings = DatabaseSettings("127.0.0.1", "antlab_api", "antlab_engine")
    eff = effective_options(connection_parameters(settings))
    assert (eff["host"], eff["hostaddr"], eff["port"], eff["user"], eff["dbname"]) == (
        "127.0.0.1",
        "",
        "5432",
        "antlab_engine",
        "antlab_api",
    )
    assert (eff["password"], eff["options"], eff["gssencmode"], eff["sslmode"]) == (
        "",
        "",
        "disable",
        "disable",
    )


def test_a_snapshot_repr_does_not_print_replay_months():
    snapshot = HistorySnapshot(
        base_date=date(2026, 1, 2),
        upper_bound=date(2026, 1, 1),
        months=(HistoryMonth(1, date(2023, 5, 1)),),
        pool=(),
        returns=(),
        fx=(),
        session=SessionFacts(role="r", read_only=True, isolation="repeatable read"),
    )
    assert "2023" not in repr(snapshot)
