"""The role script on polluted databases: converges what is safe, refuses the rest, atomically."""

import psycopg
import pytest
from db_support import (
    ENGINE_ROLE,
    GRANTED_TABLES,
    ROLE_SCRIPT,
    _writable_column,
    assert_denied,
    assert_read_denied,
    run_role_script,
)

pytestmark = pytest.mark.db

REFUSED = psycopg.errors.RaiseException


def membership_count(admin: psycopg.Connection) -> int:
    return admin.execute(
        "SELECT count(*) FROM pg_auth_members WHERE member = %s::regrole", (ENGINE_ROLE,)
    ).fetchone()[0]


def effective_table_privileges(admin: psycopg.Connection) -> set[tuple[str, str, str]]:
    rows = admin.execute(
        "SELECT n.nspname, c.relname, p FROM pg_class c"
        " JOIN pg_namespace n ON n.oid = c.relnamespace"
        " CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES',"
        "'TRIGGER']) AS p"
        " WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT LIKE 'pg\\_%%'"
        " AND n.nspname <> 'information_schema' AND has_table_privilege(%s, c.oid, p)",
        (ENGINE_ROLE,),
    ).fetchall()
    return {tuple(r) for r in rows}


CLEAN = {("public", t, "SELECT") for t in GRANTED_TABLES}


def test_c1_script_refuses_without_or_with_a_wrong_expected_database(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(f"GRANT pg_read_server_files TO {ENGINE_ROLE}")
        try:
            with pytest.raises(REFUSED, match="other_db"):
                run_role_script(admin, expected_database="other_db")
            assert membership_count(admin) == 1
            with fresh_db.admin() as bare, pytest.raises(REFUSED, match="name the target database"):
                bare.execute(ROLE_SCRIPT.read_text(encoding="utf-8"))  # type: ignore[arg-type]
            assert membership_count(admin) == 1
        finally:
            run_role_script(admin)
        assert membership_count(admin) == 0


def test_c1_a_refusal_after_changes_rolls_everything_back(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(f"GRANT pg_read_server_files TO {ENGINE_ROLE}")
        admin.execute(f"ALTER ROLE {ENGINE_ROLE} SET work_mem = '1GB'")
        admin.execute("GRANT SELECT ON member TO PUBLIC")
        try:
            with pytest.raises(REFUSED, match="refusing to apply"):
                run_role_script(admin)
            assert membership_count(admin) == 1
            settings = admin.execute(
                "SELECT unnest(setconfig) FROM pg_db_role_setting WHERE setrole = %s::regrole",
                (ENGINE_ROLE,),
            ).fetchall()
            assert ("work_mem=1GB",) in settings
        finally:
            admin.execute("REVOKE SELECT ON member FROM PUBLIC")
            admin.execute(f"ALTER ROLE {ENGINE_ROLE} RESET work_mem")
            run_role_script(admin)
        assert membership_count(admin) == 0


def test_c1_a_new_role_is_not_left_behind_by_a_refusal(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(
            "CREATE FUNCTION public.leak() RETURNS bigint LANGUAGE sql SECURITY DEFINER"
            " AS 'SELECT count(*) FROM member'"
        )
        try:
            with pytest.raises(REFUSED, match="SECURITY DEFINER"):
                run_role_script(admin)
        finally:
            admin.execute("DROP FUNCTION public.leak()")
            run_role_script(admin)


def test_c2_direct_grants_in_other_schemas_and_columns_are_revoked(fresh_db):
    with fresh_db.admin() as admin:
        column = _writable_column(admin, "member")
        admin.execute("CREATE SCHEMA other")
        admin.execute("CREATE TABLE other.t (a int)")
        admin.execute("CREATE VIEW other.v AS SELECT 1 AS a")
        admin.execute(f"GRANT USAGE, CREATE ON SCHEMA other TO {ENGINE_ROLE}")
        admin.execute(f"GRANT SELECT, INSERT ON other.t TO {ENGINE_ROLE}")
        admin.execute(f"GRANT SELECT ON other.v TO {ENGINE_ROLE}")
        admin.execute(f"GRANT SELECT ({column}), UPDATE ({column}) ON member TO {ENGINE_ROLE}")
        admin.execute(f"GRANT CREATE ON SCHEMA public TO {ENGINE_ROLE}")
        admin.execute(f"GRANT TEMPORARY, CREATE ON DATABASE {fresh_db.name} TO {ENGINE_ROLE}")
        assert effective_table_privileges(admin) != CLEAN
        run_role_script(admin)
        assert effective_table_privileges(admin) == CLEAN
        run_role_script(admin)
        assert effective_table_privileges(admin) == CLEAN
    with fresh_db.engine() as conn:
        assert_read_denied(conn, "other.t")
        assert_read_denied(conn, "other.v")
        assert_read_denied(conn, "member")
        assert_denied(conn, "CREATE TABLE public.x (a int)")
        assert_denied(conn, "CREATE TABLE other.x (a int)")
        assert_denied(conn, "CREATE TEMP TABLE x (a int)")


@pytest.mark.parametrize(
    ("setup", "undo", "match"),
    [
        (
            "GRANT SELECT ON member TO PUBLIC",
            "REVOKE SELECT ON member FROM PUBLIC",
            "SELECT on public.member",
        ),
        (
            "GRANT INSERT ON asset TO PUBLIC",
            "REVOKE INSERT ON asset FROM PUBLIC",
            "INSERT on public.asset",
        ),
        (
            "GRANT SELECT (id) ON member TO PUBLIC",
            "REVOKE SELECT (id) ON member FROM PUBLIC",
            "public.member",
        ),
        (
            "GRANT CREATE ON SCHEMA public TO PUBLIC",
            "REVOKE CREATE ON SCHEMA public FROM PUBLIC",
            'schema "public"',
        ),
        (
            "CREATE VIEW public.pv AS SELECT * FROM member; GRANT SELECT ON public.pv TO PUBLIC",
            "DROP VIEW public.pv",
            "public.pv",
        ),
        (
            "CREATE SCHEMA other; CREATE TABLE other.t (a int); GRANT SELECT ON other.t TO PUBLIC",
            "DROP SCHEMA other CASCADE",
            "other.t",
        ),
        (
            "GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO PUBLIC",
            "REVOKE USAGE ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC",
            "sequence",
        ),
        (
            "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO PUBLIC",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM PUBLIC",
            "default privileges",
        ),
        (
            "CREATE FUNCTION public.leak() RETURNS bigint LANGUAGE sql SECURITY DEFINER"
            " AS 'SELECT count(*) FROM member'",
            "DROP FUNCTION public.leak()",
            r"SECURITY DEFINER function leak\(\)",
        ),
        ("CREATE EXTENSION dblink", "DROP EXTENSION dblink", 'extension "dblink"'),
        (
            "GRANT EXECUTE ON FUNCTION pg_read_file(text) TO PUBLIC",
            "REVOKE EXECUTE ON FUNCTION pg_read_file(text) FROM PUBLIC",
            "pg_read_file",
        ),
    ],
)
def test_c2_c3_unsafe_public_state_is_refused_then_converges_once_removed(
    fresh_db, setup, undo, match
):
    with fresh_db.admin() as admin:
        admin.execute(setup)
        try:
            with pytest.raises(REFUSED, match=match):
                run_role_script(admin)
        finally:
            admin.execute(undo)
        run_role_script(admin)
        assert effective_table_privileges(admin) == CLEAN
    with fresh_db.engine() as conn:
        assert_read_denied(conn, "member")


def test_c2_public_select_on_a_granted_table_is_the_granted_privilege_itself(fresh_db):
    # SELECT on a granted table is the granted privilege itself, whoever holds it.
    with fresh_db.admin() as admin:
        admin.execute("GRANT SELECT ON asset TO PUBLIC")
        try:
            run_role_script(admin)
        finally:
            admin.execute("REVOKE SELECT ON asset FROM PUBLIC")


def test_c3_default_privileges_granted_to_the_role_are_removed(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(f"ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO {ENGINE_ROLE}")
        admin.execute(
            f"ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO {ENGINE_ROLE}"
        )
        run_role_script(admin)
        assert admin.execute("SELECT count(*) FROM pg_default_acl").fetchone() == (0,)
        admin.execute("CREATE TABLE public.later (a int)")
        assert effective_table_privileges(admin) == CLEAN


def test_c3_invoker_functions_do_not_block_the_script(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute("CREATE FUNCTION public.harmless() RETURNS int LANGUAGE sql AS 'SELECT 1'")
        run_role_script(admin)
        assert effective_table_privileges(admin) == CLEAN
    with fresh_db.engine() as conn:
        assert conn.execute("SELECT public.harmless()").fetchone() == (1,)
        assert_denied(conn, "CREATE FUNCTION public.f() RETURNS int LANGUAGE sql AS 'SELECT 1'")


@pytest.mark.parametrize(
    "setup",
    [
        "CREATE TABLE public.owned (a int); ALTER TABLE public.owned OWNER TO antlab_engine",
        "CREATE SCHEMA mine AUTHORIZATION antlab_engine",
        "CREATE FUNCTION public.owned_fn() RETURNS int LANGUAGE sql AS 'SELECT 1';"
        " ALTER FUNCTION public.owned_fn() OWNER TO antlab_engine",
    ],
)
def test_c4_owning_any_object_is_refused_before_anything_changes(fresh_db, setup):
    with fresh_db.admin() as admin:
        admin.execute(setup)
        admin.execute(f"GRANT pg_read_server_files TO {ENGINE_ROLE}")
        try:
            with pytest.raises(REFUSED, match="owns"):
                run_role_script(admin)
            assert membership_count(admin) == 1
        finally:
            admin.execute(f"DROP OWNED BY {ENGINE_ROLE}")
            run_role_script(admin)
        assert membership_count(admin) == 0


def test_c5_per_database_role_settings_are_reset(fresh_db):
    with fresh_db.admin() as admin:
        admin.execute(
            f"ALTER ROLE {ENGINE_ROLE} IN DATABASE {fresh_db.name}"
            " SET default_transaction_read_only = off"
        )
        admin.execute(
            f"ALTER ROLE {ENGINE_ROLE} IN DATABASE {fresh_db.name} SET statement_timeout = 0"
        )
        with fresh_db.engine() as conn:
            assert conn.execute("SHOW default_transaction_read_only").fetchone() == ("off",)
        run_role_script(admin)
        assert admin.execute(
            "SELECT count(*) FROM pg_db_role_setting"
            " WHERE setrole = %s::regrole AND setdatabase <> 0",
            (ENGINE_ROLE,),
        ).fetchone() == (0,)
    with fresh_db.engine() as conn:
        assert conn.execute("SHOW default_transaction_read_only").fetchone() == ("on",)
        assert conn.execute("SHOW statement_timeout").fetchone() == ("30s",)


@pytest.mark.parametrize(
    "target", ["superuser", "pg_read_all_data", "pg_write_all_data", "pg_monitor"]
)
def test_c8_set_role_is_denied(scratch_db, target):
    if target == "superuser":
        target = scratch_db.admin_params["user"]
    with scratch_db.engine() as conn:
        assert_denied(conn, f"SET ROLE {target}")


def test_c8_session_authorization_cannot_be_changed(scratch_db):
    with scratch_db.engine() as conn:
        assert_denied(conn, f"SET SESSION AUTHORIZATION {scratch_db.admin_params['user']}")
