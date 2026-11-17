"""Scratch-database harness for the DB tests.

Tests never touch the owner's server. The role script changes cluster-wide state (role
settings, memberships), so a scratch database alone is not isolation. The harness needs an
explicit admin DSN (loopback host, explicit non-default port, no service or socket indirection),
an explicit server id, and proof on the server itself: a marker database named from the id exists
and the owner's practice database does not (DEPLOY §3.2).
"""

import re
import secrets
from collections.abc import Iterable, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

import psycopg
from psycopg import sql
from psycopg.conninfo import conninfo_to_dict

from antlab_engine.settings import DatabaseSettings

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATION_DIR = REPO_ROOT / "src" / "main" / "resources" / "db" / "migration"
ROLE_SCRIPT = REPO_ROOT / "db" / "roles" / "antlab_engine.sql"

DSN_VAR = "ANTLAB_ENGINE_TEST_ADMIN_DSN"
SERVER_ID_VAR = "ANTLAB_ENGINE_TEST_SERVER_ID"
MARKER_PREFIX = "antlab_testsrv_"
OWNER_DATABASES = ("antlab",)
LOOPBACK_HOSTS = ("127.0.0.1", "::1", "localhost")
FORBIDDEN_ENV_VARS = ("PGHOSTADDR", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS")
FORBIDDEN_DSN_KEYS = (
    "service",
    "servicefile",
    "hostaddr",
    "passfile",
    "options",
    "target_session_attrs",
)
REQUIRED_VAR = "ANTLAB_ENGINE_DB_TESTS"
OWNER_PORT = 5432
ENGINE_ROLE = "antlab_engine"
GRANTED_TABLES = ("asset", "asset_position", "month_block", "asset_month_return", "fx_month")
DENIED_TABLES = ("member", "refresh_token", "season_path", "turn_price", "cash_ledger")
WRITE_VERBS = ("INSERT", "UPDATE", "DELETE", "TRUNCATE")

_MIGRATION_NAME = re.compile(r"^V(\d+)__.+\.sql$")


class HarnessError(Exception):
    """The DB test environment is missing or unsafe."""


def sort_migrations(names: Iterable[str]) -> list[str]:
    """Order Flyway file names by numeric version; reject a repeated version."""
    versions: dict[int, str] = {}
    for name in names:
        match = _MIGRATION_NAME.match(name)
        if match is None:
            raise HarnessError(f"not a versioned migration file name: {name}")
        version = int(match.group(1))
        if version in versions:
            raise HarnessError(f"duplicate migration version {version}")
        versions[version] = name
    return [versions[v] for v in sorted(versions)]


def marker_database(server_id: str) -> str:
    return f"{MARKER_PREFIX}{server_id}"


def resolve_admin_dsn(env: Mapping[str, str]) -> str | None:
    """Return the admin DSN, None when DB tests are optional and unconfigured.

    Raises HarnessError for a missing DSN when the tests are required, and for any DSN that is not
    an explicit loopback TCP target on a non-default port with the marker database as its dbname.
    """
    dsn = env.get(DSN_VAR, "").strip()
    if not dsn:
        if env.get(REQUIRED_VAR, "").strip() == "required":
            raise HarnessError(f"{REQUIRED_VAR}=required but {DSN_VAR} is not set")
        return None
    server_id = env.get(SERVER_ID_VAR, "").strip()
    if not re.fullmatch(r"[a-z0-9_]{1,24}", server_id):
        raise HarnessError(
            f"{SERVER_ID_VAR} must be set to the test server id ([a-z0-9_], up to 24)"
        )
    for var in FORBIDDEN_ENV_VARS:
        if env.get(var):
            raise HarnessError(f"{var} is set in the environment and could redirect the connection")
    params = conninfo_to_dict(dsn)
    for key in FORBIDDEN_DSN_KEYS:
        if key in params:
            raise HarnessError(f"{DSN_VAR} must not set {key}")
    host = params.get("host", "")
    if host not in LOOPBACK_HOSTS:
        raise HarnessError(f"{DSN_VAR} needs host to be one of {LOOPBACK_HOSTS} (got {host!r})")
    port = params.get("port", "")
    if not port.isdigit() or int(port) == OWNER_PORT:
        raise HarnessError(
            f"{DSN_VAR} needs an explicit port other than {OWNER_PORT} (owner default)"
        )
    if params.get("dbname") != marker_database(server_id):
        raise HarnessError(
            f"{DSN_VAR} dbname must be the marker database {marker_database(server_id)}"
        )
    return dsn


def verify_isolated_server(admin_params: Mapping[str, str], server_id: str) -> None:
    """Prove the server is the isolated test server (marker present, owner database absent)."""
    marker = marker_database(server_id)
    params = {k: v for k, v in admin_params.items() if k != "dbname"}
    with psycopg.connect(**params, dbname=marker, autocommit=True) as conn:
        names = {r[0] for r in conn.execute("SELECT datname FROM pg_database").fetchall()}
        if marker not in names or conn.execute("SELECT current_database()").fetchone()[0] != marker:
            raise HarnessError(f"marker database {marker} not found")
        present = names.intersection(OWNER_DATABASES)
        if present:
            raise HarnessError(f"this looks like the owner's server (database {sorted(present)})")


def migration_files() -> list[Path]:
    names = sort_migrations(p.name for p in MIGRATION_DIR.glob("V*.sql"))
    return [MIGRATION_DIR / n for n in names]


def run_role_script(conn: psycopg.Connection, expected_database: str | None = None) -> None:
    """Run the role script; the expected database defaults to the connection's own database."""
    if expected_database is None:
        expected_database = conn.execute("SELECT current_database()").fetchone()[0]
    conn.execute("SELECT set_config('antlab.expected_database', %s, false)", (expected_database,))
    conn.execute(ROLE_SCRIPT.read_text(encoding="utf-8"))  # type: ignore[arg-type]


@dataclass(frozen=True)
class ScratchDb:
    admin_params: dict[str, str]
    name: str

    def admin(self) -> psycopg.Connection:
        return psycopg.connect(**self.admin_params, dbname=self.name, autocommit=True)

    def engine(self) -> psycopg.Connection:
        params = {k: v for k, v in self.admin_params.items() if k in ("host", "port")}
        return psycopg.connect(
            **params, dbname=self.name, user=ENGINE_ROLE, passfile="/dev/null", autocommit=True
        )


@contextmanager
def scratch_database(admin_dsn: str, server_id: str):
    """Create a random database, apply the Flyway SQL and the role script, drop it at the end."""
    admin_params = conninfo_to_dict(admin_dsn)
    admin_params.pop("dbname", None)
    verify_isolated_server(admin_params, server_id)
    name = f"antlab_engine_test_{secrets.token_hex(4)}"
    with psycopg.connect(**admin_params, dbname="postgres", autocommit=True) as server:
        server.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    db = ScratchDb(admin_params, name)
    try:
        with db.admin() as conn:
            for path in migration_files():
                conn.execute(path.read_text(encoding="utf-8"))  # type: ignore[arg-type]
            run_role_script(conn)
        yield db
    finally:
        with psycopg.connect(**admin_params, dbname="postgres", autocommit=True) as server:
            server.execute(
                sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name))
            )


def _writable_column(conn: psycopg.Connection, table: str) -> str:
    row = conn.execute(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
        " AND table_name = %s AND is_identity = 'NO' AND is_generated = 'NEVER'"
        " ORDER BY ordinal_position LIMIT 1",
        (table,),
    ).fetchone()
    assert row is not None, f"no plain column found on {table}"
    return row[0]


def write_statement(conn: psycopg.Connection, verb: str, table: str) -> str:
    if verb == "INSERT":
        return f"INSERT INTO {table} DEFAULT VALUES"
    if verb == "UPDATE":
        column = _writable_column(conn, table)
        return f"UPDATE {table} SET {column} = {column}"
    if verb == "DELETE":
        return f"DELETE FROM {table}"
    if verb == "TRUNCATE":
        return f"TRUNCATE {table}"
    raise ValueError(verb)


def assert_denied(conn: psycopg.Connection, statement: str, *, read_write: bool = True) -> None:
    """Run the statement in its own transaction; it must fail with SQLSTATE 42501."""
    conn.execute("BEGIN READ WRITE" if read_write else "BEGIN")
    try:
        try:
            conn.execute(statement)  # type: ignore[arg-type]
        except psycopg.errors.InsufficientPrivilege:
            return
        except psycopg.Error as exc:
            raise AssertionError(f"expected 42501, got {exc.sqlstate} for: {statement}") from exc
        raise AssertionError(f"statement was not denied: {statement}")
    finally:
        conn.execute("ROLLBACK")


def assert_write_denied(conn: psycopg.Connection, verb: str, table: str) -> None:
    assert_denied(conn, write_statement(conn, verb, table))


def assert_read_denied(conn: psycopg.Connection, table: str) -> None:
    assert_denied(conn, f"SELECT * FROM {table} LIMIT 1", read_write=False)


def engine_settings(db: ScratchDb, user: str = ENGINE_ROLE) -> DatabaseSettings:
    return DatabaseSettings(
        host=db.admin_params["host"], port=int(db.admin_params["port"]), name=db.name, user=user
    )
