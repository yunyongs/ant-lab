import dataclasses
import json
import re
import secrets
from datetime import date
from decimal import Decimal

import pytest
from db_support import engine_settings

from antlab_engine.engine_errors import PoolNotFound, UnsafeDatabaseRole
from antlab_engine.history import __main__ as smoke
from antlab_engine.history import queries
from antlab_engine.history.model import HistorySnapshot
from antlab_engine.history.reader import PostgresHistoryReader

pytestmark = pytest.mark.db

BASE_2026 = date(2026, 1, 2)
BASE_2025 = date(2025, 1, 2)
FIRST_2026 = date(2026, 1, 1)
FIRST_2025 = date(2025, 1, 1)
MONTHS_BEFORE_2026 = 101  # 2006-12, 2007-01..2012-04 (64), 2023-01..2025-12 (36)
MONTHS_BEFORE_2025 = 89  # the same without 2025


def read(db, base_date=BASE_2026, user="antlab_engine"):
    return PostgresHistoryReader(engine_settings(db, user)).read(base_date)


def assert_months_before(snapshot: HistorySnapshot, upper: date, count: int) -> None:
    starts = [m.month_start for m in snapshot.months]
    assert starts == sorted(starts)
    assert all(start < upper for start in starts), "a month at or after the upper bound was read"
    assert len(starts) == count


def floats_in(value) -> list[str]:
    if isinstance(value, float):
        return ["float"]
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return [f for f in dataclasses.fields(value) for f in floats_in(getattr(value, f.name))]
    if isinstance(value, tuple | list):
        return [found for item in value for found in floats_in(item)]
    return []


def test_h1_months_stop_before_january_of_the_base_year(history_db):
    snapshot = read(history_db)
    assert_months_before(snapshot, FIRST_2026, MONTHS_BEFORE_2026)
    assert snapshot.upper_bound == FIRST_2026
    assert snapshot.months[0].month_start == date(2006, 12, 1)  # no lower bound in the reader
    assert date(2026, 1, 1) not in {m.month_start for m in snapshot.months}


def test_h2_no_month_of_the_base_year_when_the_base_date_is_mid_year(history_db):
    snapshot = read(history_db, date(2025, 7, 1))
    assert_months_before(snapshot, FIRST_2025, MONTHS_BEFORE_2025)
    assert all(m.month_start.year < 2025 for m in snapshot.months)


def test_h3_a_months_query_without_the_upper_bound_is_caught(history_db, monkeypatch):
    monkeypatch.setattr(
        queries, "MONTHS_SQL", "SELECT id, month_start FROM month_block ORDER BY month_start, id"
    )
    snapshot = read(history_db)
    with pytest.raises(AssertionError):
        assert_months_before(snapshot, FIRST_2026, MONTHS_BEFORE_2026)


def test_h3_a_returns_query_without_the_upper_bound_is_caught(history_db, monkeypatch):
    monkeypatch.setattr(
        queries,
        "RETURNS_SQL",
        "SELECT r.asset_id, r.month_block_id, r.local_return, r.return_kind"
        " FROM asset_month_return r JOIN month_block m ON m.id = r.month_block_id"
        " WHERE r.asset_id IN (SELECT p.asset_id FROM asset_position p"
        " WHERE p.base_date = %(base_date)s) ORDER BY r.asset_id, m.month_start",
    )
    snapshot = read(history_db)
    allowed = {m.month_block_id for m in snapshot.months}
    assert any(r.month_block_id not in allowed for r in snapshot.returns)


def test_h4_pool_is_the_base_date_pool_and_rows_follow_it(history_db):
    s2026 = read(history_db)
    assert [(a.market, a.ticker) for a in s2026.pool] == [
        ("KR", "ZZ0001"),
        ("US", "ZZUS01"),
        ("US", "ZZUS02"),
    ]
    assert len(s2026.returns) == 3 * MONTHS_BEFORE_2026
    s2025 = read(history_db, BASE_2025)
    assert [a.ticker for a in s2025.pool] == ["ZZ0001", "ZZUS01"]
    assert len(s2025.returns) == 2 * MONTHS_BEFORE_2025
    assert {r.asset_id for r in s2025.returns} == {a.asset_id for a in s2025.pool}
    month_ids = {m.month_block_id for m in s2025.months}
    assert {r.month_block_id for r in s2025.returns} <= month_ids
    assert len(s2025.fx) == MONTHS_BEFORE_2025
    assert {f.month_block_id for f in s2025.fx} == month_ids


def test_h4_pool_order_follows_market_and_ticker_not_insertion_order(history_db):
    with history_db.admin() as admin:
        ids = dict(admin.execute("SELECT ticker, id FROM asset").fetchall())
    assert ids["ZZUS02"] < ids["ZZUS01"] < ids["ZZ0001"], "the fixture must insert out of order"
    pool = read(history_db).pool
    assert [(a.market, a.ticker) for a in pool] == sorted((a.market, a.ticker) for a in pool)
    assert [a.asset_id for a in pool] != sorted(a.asset_id for a in pool)


def test_h4_unknown_base_date_has_no_pool(history_db):
    with pytest.raises(PoolNotFound):
        read(history_db, date(2024, 6, 3))


def test_h4_returns_of_assets_outside_the_pool_are_not_read(history_db):
    snapshot = read(history_db, BASE_2025)
    with history_db.admin() as admin:
        zzus02 = admin.execute("SELECT id FROM asset WHERE ticker = 'ZZUS02'").fetchone()[0]
    assert zzus02 not in {r.asset_id for r in snapshot.returns}


def test_h5_values_are_decimal_and_exact_and_never_float(history_db):
    snapshot = read(history_db)
    assert floats_in(snapshot) == []
    assert all(isinstance(r.local_return, Decimal) for r in snapshot.returns)
    assert all(isinstance(f.fx_change, Decimal) for f in snapshot.fx)
    ids = {m.month_start: m.month_block_id for m in snapshot.months}
    assets = {a.ticker: a.asset_id for a in snapshot.pool}
    by_key = {(r.asset_id, r.month_block_id): r for r in snapshot.returns}
    zero = by_key[(assets["ZZ0001"], ids[date(2023, 1, 1)])]
    negative = by_key[(assets["ZZUS01"], ids[date(2023, 2, 1)])]
    assert zero.local_return == Decimal(0)
    assert str(negative.local_return) == "-0.12345678"
    kinds = {r.return_kind for r in snapshot.returns if r.asset_id == assets["ZZUS02"]}
    assert kinds == {"ACTUAL", "PROXY_INDEX"}


def test_h6_reads_are_deterministic_and_sorted(history_db):
    first, second = read(history_db), read(history_db)
    assert first == second
    returns = [(r.asset_id, r.month_block_id) for r in first.returns]
    month_order = {m.month_block_id: i for i, m in enumerate(first.months)}
    assert returns == sorted(returns, key=lambda k: (k[0], month_order[k[1]]))
    assert [f.month_block_id for f in first.fx] == [m.month_block_id for m in first.months]


def test_h7_session_is_a_repeatable_read_only_snapshot(history_db):
    session = read(history_db).session
    assert (session.role, session.read_only, session.isolation) == (
        "antlab_engine",
        True,
        "repeatable read",
    )


def test_h8_an_admin_connection_is_refused(history_db):
    with pytest.raises(UnsafeDatabaseRole):
        read(history_db, user=history_db.admin_params["user"])


def test_h8_a_role_with_a_write_privilege_is_refused(history_db):
    role = f"antlab_engine_wr_{secrets.token_hex(4)}"
    with history_db.admin() as admin:
        admin.execute(f"CREATE ROLE {role} LOGIN")
        try:
            admin.execute(
                f"GRANT SELECT ON asset, asset_position, month_block, asset_month_return,"
                f" fx_month TO {role}"
            )
            assert read(history_db, user=role).session.role == role  # read-only grants are fine
            admin.execute(f"GRANT INSERT ON fx_month TO {role}")
            with pytest.raises(UnsafeDatabaseRole):
                read(history_db, user=role)
        finally:
            admin.execute(f"DROP OWNED BY {role}")
            admin.execute(f"DROP ROLE {role}")


@pytest.mark.parametrize(
    "grant",
    [
        "GRANT INSERT ON cash_ledger TO {role}",
        "GRANT pg_write_all_data TO {role}",
        "ALTER ROLE {role} CREATEROLE",
        "ALTER ROLE {role} BYPASSRLS",
    ],
)
def test_h8_a_role_that_can_write_other_tables_or_manage_roles_is_refused(history_db, grant):
    role = f"antlab_engine_x_{secrets.token_hex(4)}"
    with history_db.admin() as admin:
        admin.execute(f"CREATE ROLE {role} LOGIN")
        try:
            admin.execute(
                f"GRANT SELECT ON asset, asset_position, month_block, asset_month_return,"
                f" fx_month TO {role}"
            )
            admin.execute(grant.format(role=role))
            with pytest.raises(UnsafeDatabaseRole):
                read(history_db, user=role)
        finally:
            admin.execute(f"DROP OWNED BY {role}")
            admin.execute(f"DROP ROLE {role}")


@pytest.mark.parametrize("privilege", ["UPDATE", "DELETE", "TRUNCATE"])
def test_h8_a_role_with_only_one_write_privilege_is_refused(history_db, privilege):
    role = f"antlab_engine_{privilege.lower()}_{secrets.token_hex(4)}"
    with history_db.admin() as admin:
        admin.execute(f"CREATE ROLE {role} LOGIN")
        try:
            admin.execute(
                f"GRANT SELECT ON asset, asset_position, month_block, asset_month_return,"
                f" fx_month TO {role}"
            )
            admin.execute(f"GRANT {privilege} ON fx_month TO {role}")
            with pytest.raises(UnsafeDatabaseRole):
                read(history_db, user=role)
        finally:
            admin.execute(f"DROP OWNED BY {role}")
            admin.execute(f"DROP ROLE {role}")


def test_h9_libpq_environment_does_not_change_the_connection(history_db, monkeypatch):
    for name, value in {
        "PGHOST": "bogus.invalid",
        "PGPORT": "1",
        "PGUSER": "bogus",
        "PGDATABASE": "bogus",
        "PGPASSWORD": "leak",
        "PGOPTIONS": "-c default_transaction_read_only=off",
        "PGSSLMODE": "require",
        "PGCONNECT_TIMEOUT": "99",
        "PGHOSTADDR": "10.255.255.1",
        "PGGSSENCMODE": "require",
    }.items():
        monkeypatch.setenv(name, value)
    session = read(history_db).session
    assert (session.role, session.read_only, session.isolation) == (
        "antlab_engine",
        True,
        "repeatable read",
    )


def test_h13_smoke_prints_counts_and_session_facts_only(history_db, monkeypatch, capsys):
    settings = engine_settings(history_db)
    for name, value in {
        "ANTLAB_ENGINE_DB_HOST": settings.host,
        "ANTLAB_ENGINE_DB_PORT": str(settings.port),
        "ANTLAB_ENGINE_DB_NAME": settings.name,
        "ANTLAB_ENGINE_DB_USER": settings.user,
    }.items():
        monkeypatch.setenv(name, value)
    assert smoke.main(["--base-date", "2026-01-02"]) == 0
    out = capsys.readouterr().out
    assert json.loads(out) == {
        "role": "antlab_engine",
        "readOnly": True,
        "isolation": "repeatable read",
        "months": MONTHS_BEFORE_2026,
        "poolAssets": 3,
        "returns": 3 * MONTHS_BEFORE_2026,
        "fx": MONTHS_BEFORE_2026,
    }
    assert not re.search(r"\d{4}-\d{2}", out)


def test_reader_never_writes_even_when_asked_to_by_a_broken_query(history_db, monkeypatch):
    monkeypatch.setattr(
        queries, "FX_SQL", "DELETE FROM fx_month RETURNING month_block_id, 'USD', 0"
    )
    from antlab_engine.engine_errors import HistoryUnavailable

    with pytest.raises(HistoryUnavailable):
        read(history_db)
    with history_db.admin() as admin:
        assert admin.execute("SELECT count(*) FROM fx_month").fetchone()[0] > 0


def test_the_engine_role_cannot_write_through_the_readers_own_connection(history_db):
    import psycopg

    from antlab_engine.history.reader import connection_parameters

    params = connection_parameters(engine_settings(history_db))
    with psycopg.connect(**params, autocommit=True) as conn:  # type: ignore[arg-type]
        for statement in (
            "INSERT INTO fx_month (month_block_id, currency, fx_change) VALUES (1, 'USD', 0)",
            "DELETE FROM fx_month",
            "UPDATE month_block SET events_reviewed = TRUE",
            "TRUNCATE asset_month_return",
        ):
            with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
                conn.execute(statement)  # type: ignore[arg-type]
            conn.execute("BEGIN READ WRITE")
            try:
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    conn.execute(statement)  # type: ignore[arg-type]
            finally:
                conn.execute("ROLLBACK")
    with history_db.admin() as admin:
        rows = admin.execute("SELECT count(*) FROM fx_month").fetchone()[0]
        months = admin.execute("SELECT count(*) FROM month_block").fetchone()[0]
        assert rows == months == MONTHS_BEFORE_2026 + 1
