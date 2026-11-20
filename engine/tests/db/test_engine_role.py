import re

import psycopg
import pytest
from db_support import (
    DENIED_TABLES,
    ENGINE_ROLE,
    GRANTED_TABLES,
    ROLE_SCRIPT,
    WRITE_VERBS,
    assert_denied,
    assert_read_denied,
    assert_write_denied,
    run_role_script,
)

pytestmark = pytest.mark.db

PRIVILEGES = ("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")


def table_privileges(admin: psycopg.Connection) -> set[tuple[str, str]]:
    granted = set()
    tables = admin.execute(
        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
        " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
    ).fetchall()
    for (table,) in tables:
        for privilege in PRIVILEGES:
            allowed = admin.execute(
                "SELECT has_table_privilege(%s, %s, %s)", (ENGINE_ROLE, table, privilege)
            ).fetchone()
            if allowed[0]:
                granted.add((table, privilege))
    return granted


def test_r1_grants_are_select_on_seven_tables_only(scratch_db):
    with scratch_db.admin() as admin:
        assert table_privileges(admin) == {(t, "SELECT") for t in GRANTED_TABLES}
        rows = admin.execute(
            "SELECT table_name, privilege_type FROM information_schema.role_table_grants"
            " WHERE grantee = %s",
            (ENGINE_ROLE,),
        ).fetchall()
        assert sorted(rows) == sorted((t, "SELECT") for t in GRANTED_TABLES)
        column_grants = admin.execute(
            "SELECT count(*) FROM pg_attribute WHERE attacl IS NOT NULL AND attacl::text LIKE %s",
            (f"%{ENGINE_ROLE}=%",),
        ).fetchone()
        assert column_grants[0] == 0
        sequences = admin.execute(
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            " WHERE n.nspname = 'public' AND c.relkind = 'S'"
        ).fetchall()
        assert sequences
        for (sequence,) in sequences:
            for privilege in ("USAGE", "SELECT", "UPDATE"):
                allowed = admin.execute(
                    "SELECT has_sequence_privilege(%s, %s, %s)", (ENGINE_ROLE, sequence, privilege)
                ).fetchone()
                assert not allowed[0], (sequence, privilege)


def test_r1_can_select_every_granted_table(scratch_db):
    with scratch_db.engine() as conn:
        for table in GRANTED_TABLES:
            assert conn.execute(f"SELECT count(*) FROM {table}").fetchone() == (0,)


def test_r2_script_is_idempotent_and_converges(scratch_db):
    with scratch_db.admin() as admin:
        before = table_privileges(admin)
        run_role_script(admin)
        run_role_script(admin)
        assert table_privileges(admin) == before
        admin.execute(f"GRANT INSERT ON month_block TO {ENGINE_ROLE}")
        admin.execute(f"GRANT SELECT ON member TO {ENGINE_ROLE}")
        assert table_privileges(admin) != before
        run_role_script(admin)
        assert table_privileges(admin) == before


@pytest.mark.parametrize("table", GRANTED_TABLES)
@pytest.mark.parametrize("verb", WRITE_VERBS)
def test_r3_every_write_is_denied(scratch_db, verb, table):
    with scratch_db.engine() as conn:
        assert_write_denied(conn, verb, table)


@pytest.mark.parametrize(
    "statement",
    [
        "CREATE TABLE public.x (a int)",
        "CREATE TEMP TABLE x (a int)",
        "CREATE SCHEMA x",
        "SELECT nextval(pg_get_serial_sequence('month_block', 'id'))",
    ],
)
def test_r4_ddl_and_sequences_are_denied(scratch_db, statement):
    with scratch_db.engine() as conn:
        assert_denied(conn, statement)


@pytest.mark.parametrize("table", DENIED_TABLES)
def test_r5_other_tables_are_unreadable(scratch_db, table):
    with scratch_db.engine() as conn:
        assert_read_denied(conn, table)


def test_r6_sessions_default_to_read_only(scratch_db):
    with scratch_db.engine() as conn:
        assert conn.execute("SHOW default_transaction_read_only").fetchone() == ("on",)
        assert conn.execute("SHOW statement_timeout").fetchone() == ("30s",)
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            conn.execute("INSERT INTO month_block DEFAULT VALUES")


@pytest.mark.parametrize(
    "statement",
    [
        "SELECT lo_create(0)",
        "SELECT lo_creat(-1)",
        "SELECT lo_from_bytea(0, 'abc'::bytea)",
        "SELECT lo_import('/etc/hostname')",
        "SELECT pg_logical_emit_message(true, 'a', 'b')",
        "SELECT pg_read_file('/etc/hostname')",
        "SELECT pg_ls_dir('/')",
        "COPY (SELECT 1) TO PROGRAM 'true'",
        "COPY (SELECT 1) TO '/tmp/antlab_engine_probe'",
        "CREATE EXTENSION dblink",
    ],
)
def test_r6_privileged_side_effects_are_denied_even_when_read_only_is_switched_off(
    scratch_db, statement
):
    with scratch_db.engine() as conn:
        conn.execute("SET default_transaction_read_only = off")
        assert_denied(conn, statement)


def test_r6_no_large_object_function_is_executable_by_the_engine_role(scratch_db):
    with scratch_db.admin() as admin:
        rows = admin.execute(
            "SELECT p.oid::regprocedure::text, has_function_privilege(%s, p.oid, 'EXECUTE')"
            " FROM pg_proc p WHERE p.pronamespace = 'pg_catalog'::regnamespace"
            " AND (p.proname LIKE 'lo\\_%%' OR p.proname IN ('loread', 'lowrite'))",
            (ENGINE_ROLE,),
        ).fetchall()
    assert rows
    assert [name for name, allowed in rows if allowed] == []


def test_r6_role_has_no_memberships(scratch_db):
    with scratch_db.admin() as admin:
        rows = admin.execute(
            "SELECT roleid::regrole::text FROM pg_auth_members WHERE member = %s::regrole",
            (ENGINE_ROLE,),
        ).fetchall()
    assert rows == []


def test_r6_script_removes_memberships_and_function_grants_and_stale_settings(scratch_db):
    with scratch_db.admin() as admin:
        try:
            admin.execute(f"GRANT pg_read_server_files TO {ENGINE_ROLE}")
            admin.execute("GRANT EXECUTE ON FUNCTION lo_from_bytea(oid, bytea) TO PUBLIC")
            admin.execute(f"ALTER ROLE {ENGINE_ROLE} SET work_mem = '1GB'")
            with scratch_db.engine() as conn:
                conn.execute("SET default_transaction_read_only = off")
                assert conn.execute(
                    "SELECT pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
                ).fetchone() == (True,)
                assert conn.execute("SELECT lo_from_bytea(0, 'x'::bytea) > 0").fetchone() == (True,)
            # The large object the role just created is owned by it, which the script refuses.
            admin.execute(
                "SELECT lo_unlink(oid) FROM pg_largeobject_metadata WHERE lomowner = %s::regrole",
                (ENGINE_ROLE,),
            )
            run_role_script(admin)
        finally:
            run_role_script(admin)
        assert admin.execute(
            "SELECT count(*) FROM pg_auth_members WHERE member = %s::regrole", (ENGINE_ROLE,)
        ).fetchone() == (0,)
        settings = admin.execute(
            "SELECT unnest(setconfig) FROM pg_db_role_setting WHERE setrole = %s::regrole",
            (ENGINE_ROLE,),
        ).fetchall()
        assert sorted(s[0] for s in settings) == [
            "default_transaction_read_only=on",
            "search_path=public",
            "statement_timeout=30s",
        ]
        with scratch_db.engine() as conn:
            assert_denied(conn, "SELECT lo_from_bytea(0, 'x'::bytea)")


def test_r7_role_attributes(scratch_db):
    with scratch_db.admin() as admin:
        row = admin.execute(
            "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication,"
            " rolbypassrls, rolconnlimit FROM pg_roles WHERE rolname = %s",
            (ENGINE_ROLE,),
        ).fetchone()
    assert row == (True, False, False, False, False, False, 5)


def test_r8_write_check_fails_when_a_write_grant_exists(scratch_db):
    with scratch_db.admin() as admin:
        admin.execute(f"GRANT INSERT ON month_block TO {ENGINE_ROLE}")
        try:
            with scratch_db.engine() as conn, pytest.raises(AssertionError):
                assert_write_denied(conn, "INSERT", "month_block")
        finally:
            run_role_script(admin)
        with scratch_db.engine() as conn:
            assert_write_denied(conn, "INSERT", "month_block")


def test_r8_read_check_fails_when_an_extra_select_grant_exists(scratch_db):
    with scratch_db.admin() as admin:
        admin.execute(f"GRANT SELECT ON member TO {ENGINE_ROLE}")
        try:
            with scratch_db.engine() as conn, pytest.raises(AssertionError):
                assert_read_denied(conn, "member")
        finally:
            run_role_script(admin)
        with scratch_db.engine() as conn:
            assert_read_denied(conn, "member")


def test_r9_script_text_has_no_secret_or_hard_coded_database():
    text = re.sub(r"--[^\n]*", "", ROLE_SCRIPT.read_text(encoding="utf-8"))
    assert not re.search(r"\bPASSWORD\b", text, re.IGNORECASE)
    assert "antlab_api" not in text
    assert not re.search(r"^\s*\\", text, re.MULTILINE), "psql meta-command found"
    select_grants = re.findall(r"GRANT\s+SELECT\s+ON\s+([^;]+?)\s+TO\b", text, re.IGNORECASE)
    assert len(select_grants) == 1
    assert [t.strip() for t in select_grants[0].split(",")] == list(GRANTED_TABLES)
