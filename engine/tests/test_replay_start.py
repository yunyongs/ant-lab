"""ANTLAB_HISTORY_REPLAY_START: paths and projections never use an earlier month."""

from datetime import date

import pytest
from fastapi.testclient import TestClient
from synthetic_months import BASE_DATE as PATH_BASE_DATE
from synthetic_months import ext348, month_range
from synthetic_snapshot import snapshot
from test_paths_route import FakeReader
from test_percentiles_api import body as percentile_body
from test_projections_route import ASSETS, SnapshotReader
from test_projections_route import body as projection_body

from antlab_engine.app import create_app
from antlab_engine.bootstrap import simple
from antlab_engine.settings import Settings, SettingsError

TOKEN = "t" * 40
DB_ENV = {
    "ANTLAB_ENGINE_DB_HOST": "db",
    "ANTLAB_ENGINE_DB_NAME": "antlab_api",
    "ANTLAB_ENGINE_DB_USER": "antlab_engine",
}
LATER = date(2010, 1, 1)
HEADERS = {"X-Antlab-Engine-Token": TOKEN}


def client_for(reader, start: date | None = None) -> TestClient:
    settings = Settings(TOKEN) if start is None else Settings(TOKEN, replay_start=start)
    return TestClient(create_app(settings, history_reader=reader), raise_server_exceptions=False)


def env(**extra: str) -> dict[str, str]:
    return {"ANTLAB_ENGINE_TOKEN": TOKEN, **DB_ENV, **extra}


def test_the_default_is_the_engine_constant():
    assert Settings.from_env(env()).replay_start == simple.REPLAY_START == date(2007, 1, 1)


def test_the_variable_sets_the_start():
    assert Settings.from_env(env(ANTLAB_HISTORY_REPLAY_START="1997-01-01")).replay_start == date(
        1997, 1, 1
    )


@pytest.mark.parametrize(
    "value",
    ["", " ", "2007-01-15", "2007-1-01", "20070101", "2007-01", "2007-13-01", "abc", "2007-01-01 "],
)
def test_a_bad_value_fails_startup_and_names_the_variable(value):
    with pytest.raises(SettingsError, match="ANTLAB_HISTORY_REPLAY_START"):
        Settings.from_env(env(ANTLAB_HISTORY_REPLAY_START=value))


@pytest.mark.parametrize("value", [date(2007, 1, 15), "2007-01-01", None])
def test_direct_construction_is_validated(value):
    with pytest.raises(SettingsError, match="replay_start"):
        Settings(TOKEN, replay_start=value)


def test_info_reports_the_start():
    assert client_for(None, LATER).get("/v1/info", headers=HEADERS).json()["replayStart"] == (
        "2010-01-01"
    )
    assert client_for(None).get("/v1/info", headers=HEADERS).json()["replayStart"] == "2007-01-01"


def months_of(response) -> list[str]:
    assert response.status_code == 200, response.text
    return response.json()["months"]


@pytest.mark.parametrize("version", ["p0-boot6-v1", "m2-sb6-v1"])
def test_paths_never_draw_a_month_before_the_start(version):
    reader = FakeReader(ext348())
    later = client_for(reader, LATER)
    default = client_for(reader)
    drawn_early = False
    for seed in range(40):
        payload = {
            "engineVersion": version,
            "seed": str(seed),
            "length": 24,
            "baseDate": PATH_BASE_DATE.isoformat(),
        }
        for month in months_of(later.post("/v1/paths", json=payload, headers=HEADERS)):
            assert month >= "2010-01"
        drawn_early |= any(
            m < "2010-01"
            for m in months_of(default.post("/v1/paths", json=payload, headers=HEADERS))
        )
    assert drawn_early  # the control: the same data does reach earlier months without the setting


def test_the_path_digest_covers_only_the_months_from_the_start():
    reader = FakeReader(month_range(date(2006, 1, 1), date(2025, 12, 1)))
    payload = {
        "engineVersion": "m2-sb6-v1",
        "seed": "3",
        "length": 24,
        "baseDate": PATH_BASE_DATE.isoformat(),
    }
    digests = {
        start: client_for(reader, start)
        .post("/v1/paths", json=payload, headers=HEADERS)
        .json()["historyDigest"]
        for start in (date(2007, 1, 1), LATER)
    }
    assert digests[date(2007, 1, 1)] != digests[LATER]
    cut = FakeReader(month_range(LATER, date(2025, 12, 1)))
    assert (
        client_for(cut, LATER)
        .post("/v1/paths", json=payload, headers=HEADERS)
        .json()["historyDigest"]
        == digests[LATER]
    )


def with_changes(months: list[date]) -> SnapshotReader:
    changes = {(asset, month): "0.5" for asset in ASSETS for month in months}
    return SnapshotReader(snapshot(ASSETS, changes=changes))


WARM_UP = month_range(date(2007, 1, 1), date(2009, 12, 1))


def test_projections_ignore_months_before_the_start():
    plain = client_for(SnapshotReader(), LATER).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    odd = client_for(with_changes(WARM_UP), LATER).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    assert plain.status_code == odd.status_code == 200
    assert odd.content == plain.content


def test_projections_do_use_those_months_with_the_default_start():
    plain = client_for(SnapshotReader()).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    odd = client_for(with_changes(WARM_UP)).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    assert odd.content != plain.content


def test_percentiles_ignore_months_before_the_start():
    payload = percentile_body()
    plain = client_for(SnapshotReader(), LATER).post(
        "/v1/percentiles", json=payload, headers=HEADERS
    )
    odd = client_for(with_changes(WARM_UP), LATER).post(
        "/v1/percentiles", json=payload, headers=HEADERS
    )
    assert plain.status_code == odd.status_code == 200
    assert odd.content == plain.content
    default = client_for(with_changes(WARM_UP)).post(
        "/v1/percentiles", json=payload, headers=HEADERS
    )
    assert default.content != plain.content


def test_the_projection_digest_changes_with_the_start():
    a = client_for(SnapshotReader(), LATER).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    b = client_for(SnapshotReader()).post(
        "/v1/projections", json=projection_body(), headers=HEADERS
    )
    assert a.json()["historyDigest"] != b.json()["historyDigest"]
