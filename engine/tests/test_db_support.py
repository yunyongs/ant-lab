import pytest
from db_support import (
    DSN_VAR,
    REQUIRED_VAR,
    SERVER_ID_VAR,
    HarnessError,
    migration_files,
    resolve_admin_dsn,
    sort_migrations,
)

SERVER_ID = "unit"
MARKER = "antlab_testsrv_unit"


def env_for(dsn: str, **extra: str) -> dict[str, str]:
    return {DSN_VAR: dsn, SERVER_ID_VAR: SERVER_ID, **extra}


def good_dsn(**override: str) -> str:
    params = {"host": "127.0.0.1", "port": "55661", "user": "x", "dbname": MARKER, **override}
    return " ".join(f"{k}={v}" for k, v in params.items() if v is not None)


def test_r10_owner_port_is_refused():
    with pytest.raises(HarnessError, match="5432"):
        resolve_admin_dsn(env_for(good_dsn(port="5432")))


def test_r10_missing_port_is_refused():
    with pytest.raises(HarnessError, match="port"):
        resolve_admin_dsn(env_for("host=127.0.0.1 user=x dbname=" + MARKER))


@pytest.mark.parametrize(
    "dsn",
    [
        "port=55661 user=x dbname=" + MARKER,
        "host=/var/run/postgresql port=55661 user=x dbname=" + MARKER,
        "host=db.example.com port=55661 user=x dbname=" + MARKER,
        "host=127.0.0.1,db.example.com port=55661 user=x dbname=" + MARKER,
        "service=owner port=55661 host=127.0.0.1 dbname=" + MARKER,
        "host=127.0.0.1 hostaddr=10.0.0.5 port=55661 dbname=" + MARKER,
        "host=127.0.0.1 port=55661 user=x dbname=antlab_api",
        "host=127.0.0.1 port=55661 user=x",
    ],
)
def test_r10_unsafe_targets_are_refused(dsn):
    with pytest.raises(HarnessError):
        resolve_admin_dsn(env_for(dsn))


@pytest.mark.parametrize("var", ["PGHOSTADDR", "PGSERVICE", "PGSERVICEFILE", "PGOPTIONS"])
def test_r10_redirecting_environment_variables_are_refused(var):
    with pytest.raises(HarnessError, match=var):
        resolve_admin_dsn(env_for(good_dsn(), **{var: "x"}))


@pytest.mark.parametrize("server_id", ["", "Upper", "a b", "x" * 25])
def test_r10_server_id_is_required_and_validated(server_id):
    with pytest.raises(HarnessError, match=SERVER_ID_VAR):
        resolve_admin_dsn({DSN_VAR: good_dsn(), SERVER_ID_VAR: server_id})


def test_r10_isolated_target_is_accepted_and_missing_dsn_is_optional():
    dsn = good_dsn()
    assert resolve_admin_dsn(env_for(dsn)) == dsn
    assert resolve_admin_dsn(env_for(good_dsn(host="::1"))) is not None
    assert resolve_admin_dsn({}) is None
    assert resolve_admin_dsn({REQUIRED_VAR: "optional"}) is None


def test_r10_required_without_dsn_fails():
    with pytest.raises(HarnessError, match=DSN_VAR):
        resolve_admin_dsn({REQUIRED_VAR: "required"})


def test_r11_versions_sort_numerically_and_duplicates_are_rejected():
    assert sort_migrations(["V10__c.sql", "V2__b.sql", "V1__a.sql"]) == [
        "V1__a.sql",
        "V2__b.sql",
        "V10__c.sql",
    ]
    with pytest.raises(HarnessError, match="duplicate"):
        sort_migrations(["V2__a.sql", "V2__b.sql"])
    with pytest.raises(HarnessError):
        sort_migrations(["notes.sql"])


def test_migrations_directory_is_found():
    assert [p.name.split("__")[0] for p in migration_files()][:2] == ["V1", "V2"]
