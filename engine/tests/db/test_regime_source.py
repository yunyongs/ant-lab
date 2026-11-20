"""PostgresRegimeSource and the coverage CLI against an isolated scratch database (PR D)."""

import json
import threading
from datetime import date, datetime, timezone
from decimal import Decimal

import psycopg
import pytest
from db_support import (
    ENGINE_ROLE,
    assert_write_denied,
    engine_settings,
    run_role_script,
)
from fastapi.testclient import TestClient
from regime_db_support import add, load_catalog, load_realistic

from antlab_engine.app import create_app
from antlab_engine.engine_errors import HistoryUnavailable, UnsafeDatabaseRole
from antlab_engine.history import queries as history_queries
from antlab_engine.history.reader import read_snapshot
from antlab_engine.regime import SourceSeries, adapt
from antlab_engine.regime import __main__ as coverage_cli
from antlab_engine.regime.pg_source import PostgresRegimeSource
from antlab_engine.settings import DatabaseSettings, Settings

pytestmark = pytest.mark.db

TOKEN = "t" * 40
UPPER = date(2024, 2, 29)
EVENING = datetime(2024, 3, 10, 21, 30, tzinfo=timezone.utc)  # noqa: UP017


def read(db, upper=UPPER, user=ENGINE_ROLE):
    return PostgresRegimeSource(engine_settings(db, user)).read(upper)


def keyed(inputs):
    return {(r.source.value, r.ref_date, r.published_on): r for r in inputs.rows}


@pytest.fixture(scope="module")
def realistic_db(scratch_db):
    """Shared read-only fixture: catalog plus the realistic panel (tests here never write)."""
    with scratch_db.admin() as admin:
        job = load_catalog(admin)
        load_realistic(admin, job)
    return scratch_db


def test_s1_session_is_the_read_only_repeatable_read_engine_role(realistic_db):
    with read_snapshot(engine_settings(realistic_db)) as conn:
        role, read_only, isolation = conn.execute(history_queries.SESSION_SQL).fetchone()
    assert (role, read_only, isolation) == (ENGINE_ROLE, "on", "repeatable read")


def test_s1_the_connection_is_read_only_even_when_the_role_default_is_off(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(f"ALTER ROLE {ENGINE_ROLE} SET default_transaction_read_only = off")
    with read_snapshot(engine_settings(fresh_db)) as conn:
        assert conn.execute(history_queries.SESSION_SQL).fetchone()[1:] == ("on", "repeatable read")


def test_s2_a_write_through_the_source_connection_is_refused(realistic_db):
    with (
        read_snapshot(engine_settings(realistic_db)) as conn,
        pytest.raises(psycopg.errors.ReadOnlySqlTransaction),
    ):
        conn.execute("INSERT INTO series (code) VALUES ('X')")


@pytest.mark.parametrize("table", ["series", "series_observation"])
@pytest.mark.parametrize("verb", ["INSERT", "UPDATE", "DELETE", "TRUNCATE"])
def test_s2_the_role_has_no_write_right_on_the_macro_tables(realistic_db, table, verb):
    with realistic_db.engine() as conn:
        assert_write_denied(conn, verb, table)


def test_s3_the_snapshot_does_not_see_a_row_committed_after_it_started(fresh_db):
    scratch_db = fresh_db
    with scratch_db.admin() as admin:
        job = load_catalog(admin)
        add(admin, job, "US_DGS2", date(2024, 1, 2), "4.00")
    count = "SELECT count(*) FROM series_observation"
    with read_snapshot(engine_settings(scratch_db)) as conn:
        before = conn.execute(count).fetchone()[0]
        with scratch_db.admin() as admin:
            add(admin, job, "US_DGS2", date(2024, 1, 3), "4.10")
        assert conn.execute(count).fetchone()[0] == before
    with read_snapshot(engine_settings(scratch_db)) as conn:
        assert conn.execute(count).fetchone()[0] == before + 1


def test_s4_nothing_known_after_the_upper_bound_is_returned(fresh_db):
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "321.1", realtime_start=UPPER)
        add(admin, job, "US_CPILFESL", date(2024, 2, 1), "322.2", realtime_start=date(2024, 3, 1))
        add(admin, job, "KR_CPI", date(2024, 1, 1), "113.0", available_on=date(2024, 3, 7))
        add(admin, job, "KR_CPI", date(2024, 2, 1), "113.9", available_on=UPPER)
        add(admin, job, "US_DGS2", date(2024, 3, 1), "4.5")  # ref_date after upper
        add(admin, job, "US_DGS2", date(2024, 2, 29), "4.4")  # ref_date == upper
        add(admin, job, "KR_KTB3Y", date(2024, 2, 1), "3.3", available_on=date(2024, 2, 1))
        add(
            admin,
            job,
            "KR_KTB3Y",
            date(2024, 2, 1),
            "3.4",
            available_on=date(2024, 2, 1),
            vintage_no=2,
        )
    inputs = read(fresh_db)
    seen = {(r.source.value, r.ref_date) for r in inputs.rows}
    assert ("US_CPILFESL", date(2024, 1, 1)) in seen  # published exactly on upper
    assert ("US_CPILFESL", date(2024, 2, 1)) not in seen  # published after upper
    assert ("KR_CPI", date(2024, 1, 1)) not in seen  # available after upper
    assert ("KR_CPI", date(2024, 2, 1)) in seen
    assert ("US_DGS2", date(2024, 3, 1)) not in seen
    assert ("US_DGS2", date(2024, 2, 29)) in seen
    assert all(r.ref_date <= UPPER for r in inputs.rows)
    assert all(r.published_on is None or r.published_on <= UPPER for r in inputs.rows)


def test_s4_a_revision_stored_after_the_upper_bound_stays_out(fresh_db):
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "0.40")
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "0.41", vintage_no=2, observed_at=EVENING)
    assert [r.value for r in read(fresh_db).rows] == [Decimal("0.4")]
    later = read(fresh_db, date(2024, 3, 10))
    assert [r.value for r in later.rows] == [Decimal("0.4"), Decimal("0.41")]
    assert later.rows[1].published_on == date(2024, 3, 10)


def test_s5_dates_map_to_published_on_by_the_as_of_rule(realistic_db):
    rows = keyed(read(realistic_db))
    assert rows[("US_DGS2", date(2024, 1, 2), None)].value == Decimal("4.01")  # back-fill
    cpi1 = rows[("US_CPILFESL", date(2023, 12, 1), date(2024, 1, 11))]  # realtime_start
    cpi2 = rows[("US_CPILFESL", date(2023, 12, 1), date(2024, 2, 13))]
    assert (cpi1.value, cpi2.value) == (Decimal("320.1"), Decimal("320.2"))
    assert ("KR_CPI", date(2023, 12, 1), date(2024, 1, 7)) in rows  # available_on
    sahm = rows[("US_SAHM", date(2023, 12, 1), None)]
    assert sahm.value == Decimal("0.43")
    assert all(isinstance(r.value, Decimal) for r in read(realistic_db).rows)


def test_s5_a_vintage_two_without_dates_is_known_from_its_utc_storage_day(fresh_db):
    stored = datetime(2024, 2, 20, 23, 30, tzinfo=timezone.utc)  # noqa: UP017
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "0.40")
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "0.41", vintage_no=2, observed_at=stored)
    published = [r.published_on for r in read(fresh_db).rows]
    assert published == [None, date(2024, 2, 20)]


def test_s6_realistic_us_rows_are_dated_after_their_reference_date(realistic_db):
    inputs = read(realistic_db)
    adapted = adapt(inputs.rows, inputs.calendars)
    us = {c for c in SourceSeries if c.value.startswith("US_")}
    checked = [o for o in adapted.observations if f"US_{o.series.name[3:]}" in us]
    assert checked
    assert {o.series.value for o in checked} >= {
        "US_DGS2",
        "US_T10Y3M",
        "US_VIX",
        "US_ICSA",
        "US_CPILFESL",
        "US_SAHM",
        "US_BAA10Y",
        "US_DTWEXBGS",
    }
    assert all(o.available_on > o.ref_date for o in checked)
    assert adapted.rule_dated_rows[next(iter(adapted.rule_dated_rows))] >= 0
    assert adapted.dtwex_linked is False


def test_s7_a_series_outside_the_catalog_is_not_read(fresh_db):
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        admin.execute(
            "INSERT INTO series (code, source, source_series_id, unit, frequency,"
            " release_lag_days, revisable)"
            " VALUES ('ZZ_OTHER','FRED','ZZOTHER','INDEX','DAILY',1,false)"
        )
        add(admin, job, "ZZ_OTHER", date(2024, 1, 2), "1.0")
        add(admin, job, "US_DGS2", date(2024, 1, 2), "4.0")
    assert [r.source.value for r in read(fresh_db).rows] == ["US_DGS2"]


def test_s8_calendars_are_empty_for_both_markets(realistic_db):
    calendars = read(realistic_db).calendars
    assert {m.value: len(days) for m, days in calendars.items()} == {"US": 0, "KR": 0}


def test_s9_a_role_without_the_grant_is_reported_as_denied(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(f"REVOKE SELECT ON series_observation FROM {ENGINE_ROLE}")
    with pytest.raises(HistoryUnavailable, match="refused access"):
        read(fresh_db)


def test_s9_a_superuser_connection_is_refused(realistic_db):
    admin_user = realistic_db.admin_params["user"]
    with pytest.raises(UnsafeDatabaseRole):
        read(realistic_db, user=admin_user)


def test_s9_an_unreachable_server_is_unavailable_and_names_nothing(realistic_db):
    settings = DatabaseSettings("127.0.0.1", "nope", ENGINE_ROLE, 1, "s3cret-pass")
    with pytest.raises(HistoryUnavailable) as raised:
        PostgresRegimeSource(settings).read(UPPER)
    text = str(raised.value)
    assert "s3cret-pass" not in text and "127.0.0.1" not in text and "nope" not in text


def test_s10_the_route_serves_the_postgres_source_end_to_end(realistic_db):
    app = create_app(Settings(TOKEN, engine_settings(realistic_db)))
    client = TestClient(app, raise_server_exceptions=False)
    response = client.post(
        "/v1/regime/evaluate",
        json={"labelVersion": "m2-rl-v1", "months": ["2024-01", "2024-02"]},
        headers={"X-Antlab-Engine-Token": TOKEN},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert [m["month"] for m in body["months"]] == ["2024-01", "2024-02"]
    assert body["coverage"]["ruleDatedRows"]["US_DGS2"] == 40
    assert body["coverage"]["dtwexLinked"] is False


def test_s10_a_correction_of_a_non_revisable_series_is_ignored_not_a_422(fresh_db):
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        add(admin, job, "KR_KTB3Y", date(2024, 2, 1), "3.3", available_on=date(2024, 2, 1))
        add(
            admin,
            job,
            "KR_KTB3Y",
            date(2024, 2, 1),
            "3.4",
            available_on=date(2024, 2, 5),
            vintage_no=2,
        )
        add(admin, job, "US_DGS2", date(2024, 2, 1), "4.0")
        add(admin, job, "US_DGS2", date(2024, 2, 1), "4.1", vintage_no=2, observed_at=EVENING)
        add(admin, job, "US_CPILFESL", date(2024, 1, 1), "321.1", realtime_start=date(2024, 2, 13))
        add(
            admin,
            job,
            "US_CPILFESL",
            date(2024, 1, 1),
            "321.2",
            realtime_start=date(2024, 2, 20),
            vintage_no=2,
        )
    inputs = read(fresh_db, date(2024, 3, 31))
    values = sorted((r.source.value, r.value) for r in inputs.rows)
    assert values == [
        ("KR_KTB3Y", Decimal("3.3")),
        ("US_CPILFESL", Decimal("321.1")),
        ("US_CPILFESL", Decimal("321.2")),
        ("US_DGS2", Decimal("4.0")),
    ]
    app = create_app(Settings(TOKEN, engine_settings(fresh_db)))
    response = TestClient(app, raise_server_exceptions=False).post(
        "/v1/regime/evaluate",
        json={"labelVersion": "m2-rl-v1", "months": ["2024-03"]},
        headers={"X-Antlab-Engine-Token": TOKEN},
    )
    assert response.status_code == 200, response.text


def test_s11_two_reads_in_parallel_do_not_interfere(realistic_db):
    results: list[int] = []
    threads = [
        threading.Thread(target=lambda: results.append(len(read(realistic_db).rows)))
        for _ in range(3)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(set(results)) == 1 and results[0] > 0


def run_coverage(db, monkeypatch, capsys):
    settings = engine_settings(db)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_HOST", settings.host)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_PORT", str(settings.port))
    monkeypatch.setenv("ANTLAB_ENGINE_DB_NAME", settings.name)
    monkeypatch.setenv("ANTLAB_ENGINE_DB_USER", settings.user)
    code = coverage_cli.main(["coverage"])
    return code, capsys.readouterr()


def test_s12_the_coverage_cli_prints_counts_dates_and_gaps_but_no_value(
    realistic_db, monkeypatch, capsys
):
    code, out = run_coverage(realistic_db, monkeypatch, capsys)
    assert code == 0 and out.err == ""
    report = json.loads(out.out)
    dgs2 = report["series"]["US_DGS2"]
    assert dgs2["observations"] == dgs2["referenceDates"] == 40
    assert (dgs2["first"], dgs2["last"]) == ("2024-01-01", "2024-02-23")
    assert dgs2["gaps"] == 0 and dgs2["ruleDatedRows"] == 40
    cpi = report["series"]["US_CPILFESL"]
    assert (cpi["observations"], cpi["referenceDates"], cpi["maxVintage"]) == (3, 2, 2)
    assert cpi["unexpectedVintages"] is False and cpi["ruleDatedRows"] == 0
    assert report["series"]["KR_CPI"]["ruleDatedRows"] == 0
    assert "FRED_DTWEXM" in report["noObservations"]
    assert report["notInCatalog"] == []
    for value in ("4.01", "321.300", "113.40", "1289.50", "320.2"):
        assert value not in out.out


def test_s12_the_cli_reports_gaps_and_unexpected_vintages(fresh_db, monkeypatch, capsys):
    with fresh_db.admin() as admin:
        job = load_catalog(admin)
        for day in (date(2024, 1, 2), date(2024, 1, 3), date(2024, 1, 20), date(2024, 1, 21)):
            add(admin, job, "US_DGS2", day, "4.0")
        add(admin, job, "KR_KTB3Y", date(2024, 2, 1), "3.3", available_on=date(2024, 2, 1))
        add(
            admin,
            job,
            "KR_KTB3Y",
            date(2024, 2, 1),
            "3.4",
            available_on=date(2024, 2, 5),
            vintage_no=2,
        )
    code, out = run_coverage(fresh_db, monkeypatch, capsys)
    report = json.loads(out.out)
    assert code == 0
    assert report["series"]["US_DGS2"]["gaps"] == 1
    assert report["series"]["US_DGS2"]["longestGapDays"] == 17
    assert report["series"]["KR_KTB3Y"]["unexpectedVintages"] is True


def test_s12_the_cli_fails_cleanly_without_settings_or_database(monkeypatch, capsys):
    for name in ("HOST", "PORT", "NAME", "USER", "PASSWORD"):
        monkeypatch.delenv(f"ANTLAB_ENGINE_DB_{name}", raising=False)
    assert coverage_cli.main(["coverage"]) == 1
    assert "SETTINGS" in capsys.readouterr().err
    monkeypatch.setenv("ANTLAB_ENGINE_DB_HOST", "127.0.0.1")
    monkeypatch.setenv("ANTLAB_ENGINE_DB_PORT", "1")
    monkeypatch.setenv("ANTLAB_ENGINE_DB_NAME", "x")
    monkeypatch.setenv("ANTLAB_ENGINE_DB_USER", ENGINE_ROLE)
    assert coverage_cli.main(["coverage"]) == 1
    assert "HISTORY_UNAVAILABLE" in capsys.readouterr().err


def test_s13_the_role_script_needs_the_macro_tables(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute("DROP TABLE series_observation, series CASCADE")
        with pytest.raises(psycopg.errors.RaiseException, match="does not exist"):
            run_role_script(admin)
