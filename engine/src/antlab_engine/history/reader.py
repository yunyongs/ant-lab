"""PostgreSQL adapter of HistoryReader: one REPEATABLE READ READ ONLY snapshot per call."""

from datetime import date

import psycopg
from psycopg import IsolationLevel

from antlab_engine.bootstrap.simple import replay_upper_bound
from antlab_engine.engine_errors import HistoryUnavailable, PoolNotFound, UnsafeDatabaseRole
from antlab_engine.settings import DatabaseSettings

from . import queries
from .model import (
    FxChange,
    HistoryMonth,
    HistorySnapshot,
    MonthReturn,
    PoolAsset,
    SessionFacts,
)

GRANTED_TABLES = ("asset", "asset_position", "month_block", "asset_month_return", "fx_month")
WRITE_PRIVILEGES = ("INSERT", "UPDATE", "DELETE", "TRUNCATE")

UNAVAILABLE = "The history database is not available."
DENIED = (
    "The history database refused access; run db/roles/antlab_engine.sql after the migrations "
    "(db/README.md)."
)
UNSAFE = "The history connection is not a read-only role."
NO_POOL = "No pool is stored for the base date."


def connection_parameters(settings: DatabaseSettings) -> dict[str, object]:
    """Explicit keyword parameters; they take precedence over libpq environment variables."""
    params: dict[str, object] = {
        "host": settings.host,
        "port": settings.port,
        "dbname": settings.name,
        "user": settings.user,
        "passfile": "/nonexistent/antlab-engine-passfile",  # a missing file: no lookup, no warning
        "sslmode": "disable",
        "connect_timeout": 5,
        "application_name": "antlab-engine",
        "options": "",
        "target_session_attrs": "any",
    }
    if settings.password:
        params["password"] = settings.password
    return params


class PostgresHistoryReader:
    """Connects on every read; construction opens no connection."""

    def __init__(self, settings: DatabaseSettings) -> None:
        self._settings = settings

    def read(self, base_date: date) -> HistorySnapshot:
        upper = replay_upper_bound(base_date)
        try:
            with psycopg.connect(**connection_parameters(self._settings)) as conn:  # type: ignore[arg-type]
                conn.isolation_level = IsolationLevel.REPEATABLE_READ
                conn.read_only = True
                return self._read(conn, base_date, upper)
        except psycopg.errors.InsufficientPrivilege:
            raise HistoryUnavailable(DENIED) from None
        except psycopg.Error:
            raise HistoryUnavailable(UNAVAILABLE) from None

    @staticmethod
    def _read(conn: psycopg.Connection, base_date: date, upper: date) -> HistorySnapshot:
        if conn.execute(
            queries.UNSAFE_ROLE_SQL,
            {"tables": list(GRANTED_TABLES), "privileges": list(WRITE_PRIVILEGES)},
        ).fetchone()[0]:  # type: ignore[index]
            raise UnsafeDatabaseRole(UNSAFE)
        role, read_only, isolation = conn.execute(queries.SESSION_SQL).fetchone()  # type: ignore[misc]
        params = {"upper": upper, "base_date": base_date}
        pool = tuple(PoolAsset(*row) for row in conn.execute(queries.POOL_SQL, params))
        if not pool:
            raise PoolNotFound(NO_POOL)
        months = tuple(HistoryMonth(*row) for row in conn.execute(queries.MONTHS_SQL, params))
        returns = tuple(MonthReturn(*row) for row in conn.execute(queries.RETURNS_SQL, params))
        fx = tuple(FxChange(*row) for row in conn.execute(queries.FX_SQL, params))
        return HistorySnapshot(
            base_date=base_date,
            upper_bound=upper,
            months=months,
            pool=pool,
            returns=returns,
            fx=fx,
            session=SessionFacts(role=role, read_only=read_only == "on", isolation=isolation),
        )
