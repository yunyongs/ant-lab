"""POST /v1/manager-parts through the app, with a fake world reader and a 20-bot reference."""

import json
import logging
import re
from copy import deepcopy

import pytest
from fastapi.testclient import TestClient
from leaderboard_support import (
    SETTINGS_DOC,
    TEST_SIZE,
    FakeWorldReader,
    identity_of,
    request_doc,
    team_record,
)

from antlab_engine import __version__
from antlab_engine.api.manager_parts_models import ManagerPartsResult
from antlab_engine.app import create_app
from antlab_engine.engine_errors import PoolNotFound
from antlab_engine.leaderboard import REFERENCE_SIZE
from antlab_engine.leaderboard.reference import ReferenceService
from antlab_engine.settings import Settings
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError
from antlab_engine.sim.model import SimInputError

ROUTE = "/v1/manager-parts"
DATE_TEXT = re.compile(r"[0-9]{4}-[0-9]{2}")


def client_for(token, reader, size=TEST_SIZE):
    service = ReferenceService(reader, size=size)
    app = create_app(Settings(token), reference_service=service)
    return TestClient(app, raise_server_exceptions=False), service


@pytest.fixture
def setup(token, auth):
    reader = FakeWorldReader()
    client, service = client_for(token, reader)
    record = team_record(service, reader, identity_of())
    return client, service, reader, record, auth


def post(client, auth, body):
    return client.post(ROUTE, json=body, headers=auth)


def test_a_season_gets_its_parts_with_no_dates_months_or_won(setup):
    client, service, _, record, auth = setup
    response = post(client, auth, request_doc(record))
    assert response.status_code == 200, response.text
    body = response.json()
    ManagerPartsResult.model_validate(body)
    assert body["referenceVersion"] == "ref69-v1"
    assert body["referenceKey"] == service.key(identity_of())
    assert body["referenceSize"] == TEST_SIZE
    assert body["botVersion"] == "random/1"
    assert body["engineVersion"] == __version__
    assert set(body["parts"]) == {"return", "riskAdjusted", "risk"}
    assert body["percentiles"]["riskItem"] is not None
    assert not DATE_TEXT.search(json.dumps(body))
    assert "KR:" not in response.text and "US:" not in response.text
    assert record["totals"][-1] not in response.text


def test_the_same_request_gives_byte_identical_answers_with_and_without_the_cache(setup, token):
    client, _, _, record, auth = setup
    first = post(client, auth, request_doc(record)).content
    assert post(client, auth, request_doc(record)).content == first
    fresh, _ = client_for(token, FakeWorldReader())
    assert post(fresh, auth, request_doc(record)).content == first


def test_the_key_ignores_the_order_of_json_keys(setup):
    client, _, _, record, auth = setup
    body = request_doc(record)
    reordered = {k: body[k] for k in reversed(list(body))}
    reordered["settings"] = {k: body["settings"][k] for k in reversed(list(body["settings"]))}
    reordered["team"] = {k: body["team"][k] for k in reversed(list(body["team"]))}
    a = post(client, auth, body)
    b = post(client, auth, reordered)
    assert a.content == b.content


def test_seasons_with_the_same_months_and_settings_share_one_reference(setup):
    client, service, reader, record, auth = setup
    other = team_record(service, reader, identity_of(), kind="random", index=777)
    a = post(client, auth, request_doc(record)).json()
    b = post(client, auth, request_doc(other)).json()
    assert a["referenceKey"] == b["referenceKey"]
    assert reader.calls == 3  # two team records plus one reference build
    assert a["parts"] != b["parts"]


def test_a_different_risk_limit_is_a_different_reference(setup):
    client, _, _, record, auth = setup
    a = post(client, auth, request_doc(record)).json()
    b = post(client, auth, request_doc(record, {**SETTINGS_DOC, "riskLimit": "-0.30"})).json()
    assert a["referenceKey"] != b["referenceKey"]


def test_the_production_service_uses_two_hundred_bots(token):
    app = create_app(Settings(token))
    assert app.state.reference_service.size == REFERENCE_SIZE == 200


# ---------------------------------------------------------------------------- refusals


def _bump_last_place(text: str) -> str:
    digits = text.split(".")[1]
    last = int(digits[-1])
    return text[:-1] + str((last + 1) % 10) if last != 9 else text[:-1] + "8"


def test_a_benchmark_return_off_by_one_unit_is_a_mismatch_without_values(setup, caplog):
    client, _, _, record, auth = setup
    body = request_doc(record)
    changed = _bump_last_place(body["team"]["benchmarkReturns"][3])
    body["team"]["benchmarkReturns"][3] = changed
    caplog.set_level(logging.DEBUG)
    response = post(client, auth, body)
    assert response.status_code == 409
    problem = response.json()
    assert problem["code"] == "REFERENCE_MISMATCH"
    assert response.headers["content-type"].startswith("application/problem+json")
    assert changed not in response.text and not DATE_TEXT.search(response.text)
    assert changed not in caplog.text and not DATE_TEXT.search(caplog.text)


@pytest.mark.parametrize("field", ["sharpe", "benchSharpe"])
def test_a_sharpe_off_by_one_unit_is_a_mismatch_before_any_read(token, auth, field):
    reader = FakeWorldReader()
    client, service = client_for(token, reader)
    record = team_record(service, reader, identity_of())
    calls = reader.calls
    body = request_doc(record)
    body["team"][field] = _bump_last_place(body["team"][field])
    response = post(client, auth, body)
    assert response.status_code == 409
    assert response.json()["code"] == "REFERENCE_MISMATCH"
    assert reader.calls == calls


def _invalid_bodies(record):
    def change(path, value):
        body = request_doc(record)
        target = body
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value
        return body

    base = request_doc(record)
    duplicate = deepcopy(base)
    duplicate["months"][1] = duplicate["months"][0]
    short = deepcopy(base)
    short["months"] = short["months"][:11]
    extra = deepcopy(base)
    extra["seasonId"] = 7
    bad_returns = deepcopy(base)
    bad_returns["team"]["returns"][0] = _bump_last_place(bad_returns["team"]["returns"][0])
    return {
        "unknown version": change(["referenceVersion"], "ref69-v2"),
        "path length 120": change(["settings", "pathLength"], 120),
        "risk limit outside the four": change(["settings", "riskLimit"], "-0.25"),
        "formation sum 99": change(
            ["settings", "startFormation"],
            {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 29},
        ),
        "fractional won": change(["team", "totals", 3], "100000000.5"),
        "number return": change(["team", "returns", 0], 0.01),
        "zero cash": change(["settings", "initialCash"], "0"),
        "month not first of month": change(["months", 0], "2009-01-02"),
        "risk score scale": change(["team", "riskScore"], "87.5"),
        "repeated month": duplicate,
        "months shorter than the path": short,
        "unknown field": extra,
        "returns not from totals": bad_returns,
    }


@pytest.mark.parametrize(
    "case",
    [
        "unknown version",
        "path length 120",
        "risk limit outside the four",
        "formation sum 99",
        "fractional won",
        "number return",
        "zero cash",
        "month not first of month",
        "risk score scale",
        "repeated month",
        "months shorter than the path",
        "unknown field",
        "returns not from totals",
    ],
)
def test_invalid_requests_are_400_before_any_read(token, auth, case):
    reader = FakeWorldReader()
    client, service = client_for(token, reader)
    record = team_record(service, reader, identity_of())
    calls = reader.calls
    response = post(client, auth, _invalid_bodies(record)[case])
    assert response.status_code == 400, response.text
    assert response.json()["code"] == "INVALID_ARGUMENT"
    assert reader.calls == calls


def test_a_season_without_a_risk_score_ranks_risk_by_drawdown_alone(setup):
    client, _, _, record, auth = setup
    body = request_doc(record)
    body["team"]["riskScore"] = None
    result = post(client, auth, body).json()
    assert result["percentiles"]["riskItem"] is None
    assert result["parts"]["risk"] == result["percentiles"]["mdd"]


def test_without_a_token_the_route_is_unauthenticated(setup):
    client, _, _, record, _ = setup
    response = client.post(ROUTE, json=request_doc(record))
    assert response.status_code == 401


def test_without_a_database_the_history_is_unavailable(token, auth, setup):
    _, _, _, record, _ = setup
    client = TestClient(create_app(Settings(token)), raise_server_exceptions=False)
    response = post(client, auth, request_doc(record))
    assert response.status_code == 503
    assert response.json()["code"] == "HISTORY_UNAVAILABLE"


def test_a_build_while_another_runs_is_busy_with_retry_after(setup):
    client, service, _, record, auth = setup
    service._slot.acquire()
    try:
        response = post(client, auth, request_doc(record))
    finally:
        service._slot.release()
    assert response.status_code == 503
    assert response.json()["code"] == "ENGINE_BUSY"
    assert response.headers["retry-after"] == "1"


class Refusing:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def read(self, identity, seed):
        raise self.error


def test_a_world_that_cannot_be_built_is_422_feed_input(setup, token, caplog):
    _, _, _, record, auth = setup
    error = FeedGenerationError(Failure.FEED_INPUT, "2009-03 KR:069500 has no return")
    client, _ = client_for(token, Refusing(error))
    caplog.set_level(logging.DEBUG)
    response = post(client, auth, request_doc(record))
    assert response.status_code == 422
    assert response.json()["code"] == "FEED_INPUT"
    assert "KR:" not in response.text and not DATE_TEXT.search(response.text)
    assert "KR:" not in caplog.text and not DATE_TEXT.search(caplog.text)


def test_no_pool_for_the_base_date_is_404(setup, token):
    _, _, _, record, auth = setup
    client, _ = client_for(token, Refusing(PoolNotFound("No pool is stored for the base date.")))
    response = post(client, auth, request_doc(record))
    assert response.status_code == 404
    assert response.json()["code"] == "POOL_NOT_FOUND"


def test_a_success_logs_nothing_about_the_season(setup, caplog):
    client, _, _, record, auth = setup
    caplog.set_level(logging.DEBUG)
    assert post(client, auth, request_doc(record)).status_code == 200
    assert not DATE_TEXT.search(caplog.text)
    assert record["totals"][-1] not in caplog.text and "KR:" not in caplog.text


def test_a_world_the_bots_cannot_play_is_422_no_reference(setup, token, caplog):
    _, _, _, record, auth = setup
    client, _ = client_for(token, Refusing(SimInputError("turn 3: no return for KR:069500")))
    caplog.set_level(logging.DEBUG)
    response = post(client, auth, request_doc(record))
    assert response.status_code == 422
    assert response.json()["code"] == "NO_REFERENCE"
    assert "KR:" not in response.text and "KR:" not in caplog.text


def test_totals_hold_turns_one_to_t(setup):
    client, _, _, record, auth = setup
    body = request_doc(record)
    assert len(body["team"]["totals"]) == 12
    body["team"]["totals"] = list(record["totals"])  # with a turn 0 value: 13 values
    response = post(client, auth, body)
    assert response.status_code == 400
