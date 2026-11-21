"""C3-C7: token, problem bodies, retries, URL safety, decimals."""

import copy
import logging
from decimal import Decimal

import pytest
from api_helpers import BASE, TOKEN, FakeTransport, Sleeps, fixture, settings

from antlab_ai.runner import api_client
from antlab_ai.runner.api_client import (
    TOKEN_HEADER,
    ApiClient,
    ApiProblem,
    ApiUnavailable,
)
from antlab_ai.runner.jsonenc import RunnerBodyError
from antlab_ai.runner.settings import RunnerSettings, SettingsError

FORMATION = {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}
CONFIG = {
    "personaVersion": 1,
    "personaSha256": "a" * 64,
    "personaDisplayName": "가치",
    "modelRequested": "m/x:free",
    "temperature": "0",
    "maxTokens": 2048,
    "turnTimeoutMs": 120000,
    "promptTemplateVersion": "td1-p1",
    "runnerVersion": "0.2.0",
    "budgetCalls": 36,
    "budgetUsd": "0",
}


def client_for(*script, sleeps=None):
    transport = FakeTransport(*script)
    return ApiClient(settings(), transport=transport, sleep=sleeps or Sleeps()), transport


# ---- C3 token ---------------------------------------------------------------------------


def test_token_header_on_every_call_and_never_authorization():
    client, transport = client_for(
        (200, fixture("turn_view")),
        (201, fixture("turn_result")),
        (200, fixture("decision_result")),
    )
    client.get_turn_view(11, 7)
    client.advance_turn(11, 7, 1)
    client.get_decision(11, 7, 1)
    for call in transport.calls:
        assert call["headers"][TOKEN_HEADER] == TOKEN
        assert not any(name.lower() == "authorization" for name in call["headers"])


@pytest.mark.parametrize(
    "token", [None, "", "   ", "x" * 31, "a b" + "x" * 40, "tok\nen" + "x" * 40]
)
def test_missing_or_short_token_is_a_startup_error(token):
    env = {"ANTLAB_API_URL": BASE}
    if token is not None:
        env["ANTLAB_AGENT_RUNNER_TOKEN"] = token
    with pytest.raises(SettingsError):
        RunnerSettings.from_env(env)


def test_settings_from_env_and_defaults():
    got = RunnerSettings.from_env(
        {"ANTLAB_API_URL": BASE + "/", "ANTLAB_AGENT_RUNNER_TOKEN": TOKEN}
    )
    assert got.api_url == BASE
    assert str(got.state_dir) == "data/ai/runner"
    custom = RunnerSettings.from_env(
        {
            "ANTLAB_API_URL": BASE,
            "ANTLAB_AGENT_RUNNER_TOKEN": TOKEN,
            "ANTLAB_RUNNER_STATE_DIR": "/var/run/x",
        }
    )
    assert str(custom.state_dir) == "/var/run/x"


@pytest.mark.parametrize(
    "url",
    [
        None,
        "",
        "ftp://api.test",
        "api.test:8080",
        "http://",
        "http://u:p@api.test",
        "http://a/?q=1",
    ],
)
def test_bad_api_url_is_refused(url):
    env = {"ANTLAB_AGENT_RUNNER_TOKEN": TOKEN}
    if url is not None:
        env["ANTLAB_API_URL"] = url
    with pytest.raises(SettingsError) as info:
        RunnerSettings.from_env(env)
    assert TOKEN not in str(info.value)


def test_token_is_absent_from_repr_logs_and_exceptions(caplog):
    caplog.set_level(logging.DEBUG)
    sleeps = Sleeps()
    client, _ = client_for((403, {"code": "UNAUTHENTICATED", "detail": TOKEN}), sleeps=sleeps)
    assert TOKEN not in repr(client)
    assert TOKEN not in repr(settings())
    with pytest.raises(ApiProblem) as info:
        client.get_turn_view(11, 7)
    assert TOKEN not in str(info.value) and TOKEN not in repr(info.value)
    client, _ = client_for(
        OSError(f"connect failed {TOKEN}"), OSError("x"), OSError("y"), OSError("z")
    )
    with pytest.raises(ApiUnavailable) as unreachable:
        client.get_turn_view(11, 7)
    assert TOKEN not in str(unreachable.value) and unreachable.value.__cause__ is None
    assert TOKEN not in caplog.text


# ---- C4 problem bodies ------------------------------------------------------------------


def test_problem_json_keeps_status_and_code_only():
    problem = fixture("problem")
    problem["detail"] = "SECRET-DETAIL-MARKER"
    client, _ = client_for((409, problem))
    with pytest.raises(ApiProblem) as info:
        client.advance_turn(11, 7, 1)
    assert (info.value.status, info.value.code) == (409, "TURN_MISMATCH")
    assert "SECRET-DETAIL-MARKER" not in str(info.value) + repr(vars(info.value))


@pytest.mark.parametrize(
    "payload",
    [
        b"<html>nope</html>",
        b"",
        {"title": "x"},
        {"code": "lower_case"},
        {"code": "A" * 41},
        [1],
        {"code": 5},
    ],
)
def test_other_error_bodies_have_no_code(payload):
    client, _ = client_for((400, payload))
    with pytest.raises(ApiProblem) as info:
        client.get_decision(11, 7, 1)
    assert (info.value.status, info.value.code) == (400, None)


# ---- C5 retries -------------------------------------------------------------------------


def test_five_hundred_twice_then_ok_is_three_calls_with_backoff():
    sleeps = Sleeps()
    client, transport = client_for(
        (503, None), (500, None), (200, fixture("turn_view")), sleeps=sleeps
    )
    assert client.get_turn_view(11, 7)["season"]["id"] == 7
    assert len(transport.calls) == 3
    assert sleeps.delays == [1, 2]


def test_retries_stop_after_three_and_report_the_last_status():
    sleeps = Sleeps()
    client, transport = client_for(
        (502, None), (502, None), (502, None), (500, fixture("problem")), sleeps=sleeps
    )
    with pytest.raises(ApiProblem) as info:
        client.get_turn_view(11, 7)
    assert info.value.status == 500
    assert len(transport.calls) == 4
    assert sleeps.delays == [1, 2, 4]


def test_connection_errors_are_retried_then_unavailable():
    sleeps = Sleeps()
    client, transport = client_for(
        ConnectionResetError(), TimeoutError(), OSError(), OSError(), sleeps=sleeps
    )
    with pytest.raises(ApiUnavailable):
        client.advance_turn(11, 7, 1)
    assert len(transport.calls) == 4
    assert sleeps.delays == [1, 2, 4]


def test_start_season_is_never_retried():
    for first in ((503, None), OSError()):
        sleeps = Sleeps()
        client, transport = client_for(first, (201, fixture("season")), sleeps=sleeps)
        with pytest.raises((ApiProblem, ApiUnavailable)):
            client.start_season(11, "-0.20", FORMATION, 12, CONFIG)
        assert len(transport.calls) == 1
        assert sleeps.delays == []


@pytest.mark.parametrize("status", [400, 401, 404, 409, 422])
def test_client_errors_are_not_retried(status):
    sleeps = Sleeps()
    client, transport = client_for(
        (status, fixture("problem")), (200, fixture("turn_view")), sleeps=sleeps
    )
    with pytest.raises(ApiProblem):
        client.get_turn_view(11, 7)
    assert len(transport.calls) == 1 and sleeps.delays == []


# ---- C6 URL safety ----------------------------------------------------------------------


def test_a_redirect_is_an_error_and_no_second_request_is_made():
    client, transport = client_for((302, None), (200, fixture("turn_view")))
    with pytest.raises(ApiProblem) as info:
        client.get_turn_view(11, 7)
    assert info.value.status == 302
    assert len(transport.calls) == 1


def test_the_real_transport_refuses_redirects():
    handler = api_client._NoRedirect()
    assert handler.redirect_request(object(), None, 302, "", {}, "http://evil/") is None
    assert any(isinstance(h, api_client._NoRedirect) for h in api_client._OPENER.handlers)


@pytest.mark.parametrize("bad", [True, "7", 0, -1, 7.0, None])
def test_path_ids_must_be_plain_positive_integers(bad):
    client, transport = client_for()
    with pytest.raises(ValueError):
        client.get_turn_view(bad, 7)
    with pytest.raises(ValueError):
        client.get_turn_view(11, bad)
    assert transport.calls == []


@pytest.mark.parametrize("bad", [True, "1", -1, 1.0])
def test_turn_number_must_be_a_plain_non_negative_integer(bad):
    client, transport = client_for()
    with pytest.raises(ValueError):
        client.get_decision(11, 7, bad)
    assert transport.calls == []


def test_turn_zero_is_allowed():
    client, transport = client_for((200, fixture("decision_result")))
    client.get_decision(11, 7, 0)
    assert transport.calls[0]["url"].endswith("/seasons/7/turns/0/decision")


# ---- C7 decimals ------------------------------------------------------------------------


def _walk(node):
    yield node
    if isinstance(node, dict):
        for value in node.values():
            yield from _walk(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk(value)


def test_parsed_turn_view_holds_no_float():
    view = copy.deepcopy(fixture("turn_view"))
    view["extra"] = {"ratio": 1.5, "list": [2.25, 3]}
    client, _ = client_for((200, view))
    got = client.get_turn_view(11, 7)
    assert not any(isinstance(node, float) for node in _walk(got))
    assert got["extra"]["ratio"] == Decimal("1.5")


def test_risk_limit_goes_out_as_text_and_a_float_is_refused():
    client, transport = client_for((201, fixture("season")))
    client.start_season(11, Decimal("-0.20"), FORMATION, 12, CONFIG)
    assert transport.calls[0]["body"]["riskLimit"] == "-0.20"
    with pytest.raises(ValueError):
        client.start_season(11, -0.2, FORMATION, 12, CONFIG)


def test_a_float_or_decimal_in_a_body_is_never_serialized():
    with pytest.raises(RunnerBodyError):
        api_client._dumps({"x": Decimal("1")})
    with pytest.raises(RunnerBodyError):
        api_client._dumps({"x": object()})
    for body in (
        {"x": 1.5},
        {"decision": {"a": 1.5}},
        {"decision": {"a": [1.5]}},
        {"calls": [{"c": 0.1}]},
    ):
        with pytest.raises(RunnerBodyError):
            api_client._dumps(body)
    assert api_client._dumps({"a": "한글", "n": 1}) == '{"a":"한글","n":1}'.encode()


def test_nan_in_a_response_is_not_json():
    client, _ = client_for((200, b'{"season": NaN}'))
    with pytest.raises(Exception) as info:
        client.get_turn_view(11, 7)
    assert type(info.value).__name__ == "ContractError"


def test_register_reports_created_versus_found():
    client, _ = client_for((201, fixture("agent")), (200, fixture("agent")))
    assert client.register_agent("patient-value", "OPENROUTER", "m/x:free", "n")[1] is True
    assert client.register_agent("patient-value", "OPENROUTER", "m/x:free", "n")[1] is False


# ---- AG-04 E: config and league operations -----------------------------------------------


def test_start_season_with_a_league_sends_the_id_and_may_omit_the_length():
    client, transport = client_for((201, fixture("season")))
    client.start_season(11, "-0.20", FORMATION, None, CONFIG, league_id=5)
    body = transport.calls[0]["body"]
    assert body["leagueId"] == 5 and "pathLength" not in body
    client, transport = client_for((201, fixture("season")))
    client.start_season(11, "-0.20", FORMATION, 12, CONFIG)
    assert "leagueId" not in transport.calls[0]["body"]


@pytest.mark.parametrize("bad", [0, -1, True, "5", 1.5])
def test_a_bad_league_id_is_refused_before_any_request(bad):
    client, transport = client_for()
    with pytest.raises(ValueError):
        client.get_league(bad)
    with pytest.raises(ValueError):
        client.start_season(11, "-0.20", FORMATION, None, CONFIG, league_id=bad)
    assert transport.calls == []


def test_create_league_is_never_retried_and_sends_only_given_fields():
    for first in ((503, None), OSError()):
        client, transport = client_for(first, (201, None))
        with pytest.raises((ApiProblem, ApiUnavailable)):
            client.create_league("Cup")
        assert len(transport.calls) == 1
        assert transport.calls[0]["body"] == {"name": "Cup"}


def test_create_league_refuses_a_body_the_contract_does_not_allow():
    from antlab_ai.runner.contract import ContractError

    client, transport = client_for()
    with pytest.raises(ContractError):
        client.create_league("", 12)
    with pytest.raises(ContractError):
        client.create_league("Cup", max_entries=1)
    assert transport.calls == []


def test_the_config_and_league_calls_use_the_contract_paths_and_methods():
    league = {
        "id": 5,
        "name": "Cup",
        "status": "OPEN",
        "pathLength": 12,
        "maxEntries": 8,
        "entries": [],
    }
    config = {
        "personaKey": "patient-value",
        "personaVersion": 1,
        "personaSha256": "a" * 64,
        "modelRoute": "OPENROUTER",
        "modelRequested": "m/x:free",
        "promptTemplateVersion": "td1-p1",
        "runnerVersion": "0.2.0",
        "memoryVersion": None,
        "temperature": "0",
        "modelSeed": 1,
        "maxTokens": 2048,
        "turnTimeoutMs": 120000,
        "budgetCalls": 36,
        "budgetUsd": "0",
    }
    client, transport = client_for((200, config), (200, league), (200, league))
    assert client.get_season_config(11, 7)["personaKey"] == "patient-value"
    client.get_league(5)
    client.close_league(5)
    urls = [(c["method"], c["url"].removeprefix(BASE)) for c in transport.calls]
    assert urls == [
        ("GET", "/internal/agent-runner/v1/agents/11/seasons/7/config"),
        ("GET", "/internal/agent-runner/v1/leagues/5"),
        ("POST", "/internal/agent-runner/v1/leagues/5/close"),
    ]
