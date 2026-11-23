"""POST /v1/manager-parts on the parity history and events, read as the engine role."""

import json
from datetime import date
from pathlib import Path

import pytest
from conftest import TOKEN_HEADER
from db_support import engine_settings
from fastapi.testclient import TestClient
from leaderboard_support import SETTINGS_DOC, identity_of, request_doc, team_series

from antlab_engine.app import create_app
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import BotWorld
from antlab_engine.leaderboard.parts import checked_components, season_parts
from antlab_engine.leaderboard.reference import (
    PostgresWorldReader,
    ReferenceService,
    world_seed,
)
from antlab_engine.settings import Settings
from antlab_engine.sim import money
from antlab_engine.sim.report import plain

pytestmark = pytest.mark.db

START_2007 = date(2007, 1, 1)
SIZE = 20
ORACLE = json.loads(
    (
        Path(__file__).resolve().parents[3] / "src/test/resources/sim/parity/events-v1.json"
    ).read_text(encoding="utf-8")
)
WALKS = {w["name"]: w["months"] for w in ORACLE["seasons"]}


def months(name: str) -> list[str]:
    return list(WALKS[name])


@pytest.fixture
def setup(world_db, token):
    reader = PostgresWorldReader(engine_settings(world_db), START_2007)
    service = ReferenceService(reader, size=SIZE)
    app = create_app(Settings(token), reference_service=service)
    return TestClient(app, raise_server_exceptions=False), service, reader, {TOKEN_HEADER: token}


def played(service, reader, walk="mixed", kind="disciplined"):
    identity = identity_of(months=months(walk))
    world = reader.read(identity, world_seed(service.key(identity)))
    bare = BotWorld(world.seed, world.base_date, world.inputs)
    return identity, dict(run_bot(bare, make_bot(kind, 0, 1, bare.seed)).record)


def test_a_database_season_gets_the_parts_of_its_own_world(setup):
    client, service, reader, auth = setup
    identity, record = played(service, reader)
    response = client.post(
        "/v1/manager-parts", json=request_doc(record, months=months("mixed")), headers=auth
    )
    assert response.status_code == 200, response.text
    body = response.json()
    team = team_series(record)
    expected = season_parts(team, checked_components(team, 12), service.reference(identity))
    assert body["referenceKey"] == service.key(identity)
    assert body["parts"]["return"] == plain(money.round8(expected.parts.ret))
    assert body["percentiles"]["mdd"] == plain(money.round8(expected.percentiles.mdd))


def test_a_season_played_on_other_months_is_a_reference_mismatch(setup):
    client, service, reader, auth = setup
    _, record = played(service, reader)
    reordered = months("mixed")[::-1]
    response = client.post(
        "/v1/manager-parts", json=request_doc(record, months=reordered), headers=auth
    )
    assert response.status_code == 409
    assert response.json()["code"] == "REFERENCE_MISMATCH"
    assert "20" not in response.json()["detail"]


def test_an_unreviewed_month_is_feed_input(setup):
    client, service, reader, auth = setup
    _, record = played(service, reader)
    response = client.post(
        "/v1/manager-parts", json=request_doc(record, months=months("unreviewed")), headers=auth
    )
    assert response.status_code == 422
    assert response.json()["code"] == "FEED_INPUT"


def test_months_that_are_not_stored_are_invalid(setup):
    client, service, reader, auth = setup
    _, record = played(service, reader)
    unknown = ["2030-01-01", *months("mixed")[1:]]
    response = client.post(
        "/v1/manager-parts", json=request_doc(record, months=unknown), headers=auth
    )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_ARGUMENT"


def test_the_route_reads_with_the_season_settings(setup):
    client, service, reader, auth = setup
    doc = {**SETTINGS_DOC, "riskLimit": "-0.40"}
    identity = identity_of(doc, months=months("mixed"))
    world = reader.read(identity, world_seed(service.key(identity)))
    assert world.inputs.settings == identity.settings
