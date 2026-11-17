import os

import psycopg
import pytest
from db_support import SERVER_ID_VAR, HarnessError, scratch_database, verify_isolated_server

pytestmark = pytest.mark.db


def server_id() -> str:
    return os.environ[SERVER_ID_VAR].strip()


def test_c6_the_test_server_passes_the_identity_check(scratch_db):
    verify_isolated_server(scratch_db.admin_params, server_id())


def test_c6_a_server_without_the_marker_database_is_refused(scratch_db):
    with pytest.raises(psycopg.OperationalError):
        verify_isolated_server(scratch_db.admin_params, "no_such_server")


def test_c6_a_server_holding_the_owner_practice_database_is_refused(scratch_db):
    with scratch_db.admin() as admin:
        admin.execute("CREATE DATABASE antlab")
        try:
            with pytest.raises(HarnessError, match="owner's server"):
                verify_isolated_server(scratch_db.admin_params, server_id())
            with (
                pytest.raises(HarnessError, match="owner's server"),
                scratch_database(_dsn(scratch_db), server_id()),
            ):
                pytest.fail("scratch database was created on a refused server")
        finally:
            admin.execute("DROP DATABASE antlab")


def _dsn(db) -> str:
    return psycopg.conninfo.make_conninfo(**db.admin_params, dbname=f"antlab_testsrv_{server_id()}")
