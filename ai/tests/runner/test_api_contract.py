"""C1, C2: the client's operations and bodies against docs/api/agent-runner.yaml."""

import copy
import json
from decimal import Decimal
from pathlib import Path

import pytest
from api_helpers import FakeTransport, contract_errors, fixture, settings, yaml_operations
from runner_helpers import reply

from antlab_ai.runner import api_client as api_client_module
from antlab_ai.runner import contract
from antlab_ai.runner.api_client import OPERATIONS, ApiClient
from antlab_ai.runner.contract import ContractError
from antlab_ai.runner.decision import parse_reply
from antlab_ai.runner.records import RecordBuilder

from antlab_ai.modelport.fake import scripted_attempt  # isort: skip


def test_operation_table_equals_the_yaml():
    assert len(OPERATIONS) == 20
    assert OPERATIONS == yaml_operations()
    assert contract.operations() == yaml_operations()


FIXTURE_SCHEMAS = [
    ("agent", "AgentDto"),
    ("season", "SeasonDto"),
    ("turn_view", "AgentTurnViewDto"),
    ("turn_result", "TurnResultDto"),
    ("decision_result", "DecisionResultDto"),
    ("problem", "Problem"),
]


@pytest.mark.parametrize(("name", "schema"), FIXTURE_SCHEMAS)
def test_response_fixtures_match_their_schema(name, schema):
    assert contract_errors(schema, fixture(name)) == []


@pytest.mark.parametrize("name", ["turn_view_small", "turn_view_pool180"])
def test_runner_turn_view_fixtures_carry_a_contract_valid_last_turn(name):
    fixtures = Path(__file__).resolve().parent / "fixtures"
    view = json.loads((fixtures / f"{name}.json").read_text(encoding="utf-8"))
    assert contract_errors("TurnResultDto", view["lastTurn"]) == []
    assert "alerts" in view["lastTurn"]
    broken = copy.deepcopy(view["lastTurn"])
    del broken["alerts"]
    assert contract_errors("TurnResultDto", broken) != []


def test_season_config_response_matches_its_schema():
    recorded = {
        "personaKey": "patient-value",
        "personaVersion": 1,
        "personaSha256": "a" * 64,
        "modelRoute": "OLLAMA",
        "modelRequested": "m/x",
        "promptTemplateVersion": "td1-p1",
        "runnerVersion": "0.2.0",
        "memoryVersion": None,
        "temperature": "0.000",
        "modelSeed": None,
        "maxTokens": 2048,
        "turnTimeoutMs": 120000,
        "budgetCalls": 36,
        "budgetUsd": "0.00000000",
    }
    assert contract_errors("AgentSeasonConfigDto", recorded) == []
    del recorded["personaSha256"]
    assert contract_errors("AgentSeasonConfigDto", recorded) != []


def _calls(n=1, first=1):
    builder = RecordBuilder("OLLAMA", "m/x")
    parsed = parse_reply(reply())
    return [builder.build(scripted_attempt(reply()), first + i, parsed) for i in range(n)]


def _season_config():
    return {
        "personaVersion": 1,
        "personaSha256": "a" * 64,
        "personaDisplayName": "가치",
        "modelRequested": "vendor/some-model:free",
        "temperature": "0",
        "modelSeed": 1,
        "maxTokens": 2048,
        "turnTimeoutMs": 120000,
        "promptTemplateVersion": "td1-p1",
        "runnerVersion": "0.2.0",
        "memoryVersion": None,
        "budgetCalls": 36,
        "budgetUsd": "0",
    }


def _drive_all(transport):
    client = ApiClient(settings(), transport=transport, sleep=lambda s: None)
    formation = {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}
    client.register_agent("patient-value", "OPENROUTER", "vendor/some-model:free", "이름")
    client.start_season(11, Decimal("-0.20"), formation, 12, _season_config())
    client.get_turn_view(11, 7)
    client.flush_calls(11, 7, 1, _calls(2))
    client.submit_decision(11, 7, 1, _calls(1, 3), parse_reply(reply()).decision)
    client.get_decision(11, 7, 1)
    client.advance_turn(11, 7, 1)


def test_every_request_body_matches_its_yaml_schema():
    transport = FakeTransport(
        (201, fixture("agent")),
        (201, fixture("season")),
        (200, fixture("turn_view")),
        (204, None),
        (200, fixture("decision_result")),
        (200, fixture("decision_result")),
        (201, fixture("turn_result")),
    )
    _drive_all(transport)
    schemas = {
        "registerAgent": "RegisterAgentRequest",
        "startAgentSeason": "StartAgentSeasonRequest",
        "flushAgentCalls": "CallsRequest",
        "submitAgentDecision": "DecisionRequest",
        "advanceAgentTurn": "AdvanceTurnRequest",
    }
    ops = list(OPERATIONS)[:7]  # the loop's seven; the A5 lifecycle operations are not driven yet
    assert len(transport.calls) == 7
    for call, op in zip(transport.calls, ops, strict=True):
        method, template = OPERATIONS[op]
        assert call["method"] == method
        assert call["url"].startswith("http://api.test:8080/internal/agent-runner/v1/")
        if op in schemas:
            assert contract_errors(schemas[op], call["body"]) == [], op
        else:
            assert call["body"] is None
    assert transport.calls[1]["body"]["riskLimit"] == "-0.20"


@pytest.mark.parametrize(
    ("operation", "fixture_name", "status", "drop"),
    [
        ("register", "agent", 201, "modelId"),
        ("start", "season", 201, "currentFormation"),
        ("view", "turn_view", 200, "pool"),
        ("decide", "decision_result", 200, "schemaViolations"),
        ("get_decision", "decision_result", 200, "replayed"),
        ("advance", "turn_result", 201, "formationDeviation"),
    ],
)
def test_a_response_missing_a_required_field_is_a_contract_error(
    operation, fixture_name, status, drop
):
    body = copy.deepcopy(fixture(fixture_name))
    del body[drop]
    client = ApiClient(settings(), transport=FakeTransport((status, body)), sleep=lambda s: None)
    formation = {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}
    calls = {
        "register": lambda: client.register_agent("patient-value", "OPENROUTER", "m/x:free", "n"),
        "start": lambda: client.start_season(11, "-0.20", formation, 12, _season_config()),
        "view": lambda: client.get_turn_view(11, 7),
        "decide": lambda: client.submit_decision(11, 7, 1, _calls(), parse_reply(reply()).decision),
        "get_decision": lambda: client.get_decision(11, 7, 1),
        "advance": lambda: client.advance_turn(11, 7, 1),
    }
    with pytest.raises(ContractError):
        calls[operation]()


@pytest.mark.parametrize("bad", [None, "1", 1.5, True])
def test_a_registered_agent_with_a_missing_or_mistyped_owner_member_id_is_a_contract_error(bad):
    body = copy.deepcopy(fixture("agent"))
    body["ownerMemberId"] = bad
    client = ApiClient(settings(), transport=FakeTransport((201, body)), sleep=lambda s: None)
    with pytest.raises(ContractError):
        client.register_agent("patient-value", "OPENROUTER", "vendor/some-model:free", "n")


def test_a_registered_agent_without_owner_member_id_is_a_contract_error():
    body = copy.deepcopy(fixture("agent"))
    del body["ownerMemberId"]
    client = ApiClient(settings(), transport=FakeTransport((200, body)), sleep=lambda s: None)
    with pytest.raises(ContractError):
        client.register_agent("patient-value", "OPENROUTER", "vendor/some-model:free", "n")


def test_an_unexpected_success_status_is_a_contract_error():
    client = ApiClient(settings(), transport=FakeTransport((200, None)), sleep=lambda s: None)
    with pytest.raises(ContractError):
        client.flush_calls(11, 7, 1, _calls())


def test_a_bad_request_body_is_refused_before_it_is_sent():
    transport = FakeTransport()
    client = ApiClient(settings(), transport=transport, sleep=lambda s: None)
    with pytest.raises(ContractError):
        client.register_agent("Bad Key", "OPENROUTER", "m/x:free", "n")
    with pytest.raises(ContractError):
        client.advance_turn(11, 7, -1)
    assert transport.calls == []


def test_a_response_that_is_not_json_is_a_contract_error():
    client = ApiClient(settings(), transport=FakeTransport((200, b"<html>")), sleep=lambda s: None)
    with pytest.raises(ContractError):
        client.get_turn_view(11, 7)


def test_token_header_name_is_the_one_the_contract_names():
    scheme = contract.load_contract()["components"]["securitySchemes"]["runnerToken"]
    assert scheme["in"] == "header"
    assert scheme["name"] == "X-Antlab-Runner-Token"
    assert api_client_module.TOKEN_HEADER == scheme["name"]
    transport = FakeTransport((200, fixture("decision_result")))
    ApiClient(settings(), transport=transport).get_decision(11, 7, 1)
    assert set(transport.calls[0]["headers"]) >= {scheme["name"]}


@pytest.mark.parametrize("bad", ["abc", "1e-2", "+1", "1.", ".5", "", "0x10", " 1"])
def test_decimal_format_is_enforced_in_bodies(bad):
    config = _season_config() | {"budgetUsd": bad}
    body = {
        "riskLimit": "-0.20",
        "formation": {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30},
        "config": config,
    }
    with pytest.raises(ContractError):
        contract.check("StartAgentSeasonRequest", body)
    with pytest.raises(ContractError):
        contract.check(
            "StartAgentSeasonRequest", body | {"config": _season_config(), "riskLimit": bad}
        )


def test_decimal_format_is_enforced_in_responses():
    season = copy.deepcopy(fixture("season"))
    season["cashBalance"] = "12e3"
    with pytest.raises(ContractError):
        contract.check("SeasonDto", season)
