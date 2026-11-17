"""C9: building call records from model attempts."""

import json
from decimal import Decimal
from uuid import uuid4

import pytest
from api_helpers import contract_errors
from runner_helpers import reply

from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.runner.decision import Parsed, Rejected, parse_reply
from antlab_ai.runner.records import (
    RecordBuilder,
    RunnerDefect,
    calls_request,
    cost_string,
    decision_request,
    outcome_of,
)

PARSED = Parsed({"schema": "turn-decision/1"})
REJECTED = Rejected((("/reply", "json"),))


def builder(route="OPENROUTER", model="vendor/m:free"):
    return RecordBuilder(route, model)


def openrouter_attempt(**kw):
    kw.setdefault("ledger_call_id", uuid4())
    return scripted_attempt(**kw)


def test_ok_reply_with_a_good_verdict_is_an_ok_record():
    attempt = openrouter_attempt(text=reply(), request_text='{"model":"m"}')
    record = builder().build(attempt, 1, PARSED)
    assert record["status"] == "OK"
    assert record["modelRequested"] == "vendor/m:free"
    assert record["kind"] == "TURN_DECISION"
    assert record["ledgerCallId"] == str(attempt.ledger_call_id)
    assert record["responseText"] == reply()
    assert record["requestText"] == '{"model":"m"}'
    assert contract_errors("CallRecordRequest", record) == []


def test_ok_reply_with_a_rejected_verdict_is_invalid():
    record = builder().build(openrouter_attempt(text="nope"), 2, REJECTED)
    assert record["status"] == "INVALID"
    assert record["responseText"] == "nope"
    assert record["attemptNo"] == 2


@pytest.mark.parametrize("status", ["UPSTREAM", "TIMEOUT", "LIMITED", "POLICY_REFUSED", "ERROR"])
def test_failed_attempts_keep_their_status_and_have_no_verdict(status):
    attempt = openrouter_attempt(text=None, status=status, http_status=503, cost_usd=None)
    record = builder().build(attempt, 1, None)
    assert record["status"] == status
    assert record["responseText"] is None
    assert record["costUsd"] is None
    with pytest.raises(RunnerDefect):
        builder().build(attempt, 1, REJECTED)


def test_an_ok_reply_without_a_verdict_is_a_defect():
    with pytest.raises(RunnerDefect):
        builder().build(openrouter_attempt(text="x"), 1, None)


def test_openrouter_needs_a_ledger_call_id_but_other_routes_do_not():
    bare = scripted_attempt(text=reply())
    with pytest.raises(RunnerDefect):
        builder().build(bare, 1, PARSED)
    assert builder("OLLAMA").build(bare, 1, PARSED)["ledgerCallId"] is None


def test_unknown_route_or_empty_model_is_a_defect():
    with pytest.raises(RunnerDefect):
        RecordBuilder("NOPE", "m")
    with pytest.raises(RunnerDefect):
        RecordBuilder("OLLAMA", "")


@pytest.mark.parametrize(
    "text",
    ["has\x00nul", "key sk-or-v1-" + "a1" * 12, "x" * 262_145],
)
def test_unstorable_request_text_is_a_defect_before_any_send(text):
    with pytest.raises(RunnerDefect):
        builder("OLLAMA").build(scripted_attempt(request_text=text, text=reply()), 1, PARSED)


@pytest.mark.parametrize(
    "text",
    ["has\x00nul", "Bearer abcdefghij123456789", "x" * 262_145],
)
def test_unstorable_reply_is_not_stored_and_the_attempt_stays_invalid(text):
    record = builder("OLLAMA").build(scripted_attempt(text=text), 1, REJECTED)
    assert record["status"] == "INVALID"
    assert record["responseText"] is None


def test_provider_labels_are_cut_to_the_contract_and_unstorable_ones_dropped():
    long = scripted_attempt(text=reply())
    record = builder("OLLAMA").build(
        type(long)(**{**long.__dict__, "model_actual": "m" * 300, "provider": "p" * 150}), 1, PARSED
    )
    assert len(record["modelActual"]) == 200 and len(record["provider"]) == 100
    bad = type(long)(**{**long.__dict__, "model_actual": "a\x00b", "provider": None})
    assert builder("OLLAMA").build(bad, 1, PARSED)["modelActual"] is None


@pytest.mark.parametrize("http", [99, 600, 0, -5])
def test_http_status_outside_the_contract_range_is_dropped(http):
    attempt = scripted_attempt(text=reply(), http_status=http)
    assert builder("OLLAMA").build(attempt, 1, PARSED)["httpStatus"] is None


@pytest.mark.parametrize("number", [0, -1, 32768, True, "1"])
def test_attempt_number_must_be_inside_the_contract_range(number):
    with pytest.raises(RunnerDefect):
        builder("OLLAMA").build(scripted_attempt(text=reply()), number, PARSED)


@pytest.mark.parametrize(
    ("cost", "expected"),
    [
        (Decimal("0.000000001"), "0.00000001"),
        (Decimal("1E-3"), "0.00100000"),
        (Decimal(0), "0.00000000"),
        (Decimal("0.123456789"), "0.12345679"),
        (Decimal("0.12345678"), "0.12345678"),
        (Decimal("12"), "12.00000000"),
        (Decimal("99999999999.5"), "99999999999.50000000"),
        (None, None),
    ],
)
def test_cost_is_rounded_up_to_eight_decimals(cost, expected):
    assert cost_string(cost) == expected


@pytest.mark.parametrize(
    "cost",
    [
        Decimal(-1),
        Decimal("NaN"),
        Decimal("Infinity"),
        0.5,
        Decimal("1E+40"),
        Decimal("123456789012.5"),
    ],
)
def test_unusable_cost_is_a_defect(cost):
    with pytest.raises(RunnerDefect):
        cost_string(cost)


def _records(*pairs):
    b = builder("OLLAMA")
    return [
        b.build(scripted_attempt(text=t, status=s, request_text="{}"), i + 1, v)
        for i, (s, t, v) in enumerate(pairs)
    ]


def test_outcome_follows_the_last_call():
    ok = ("OK", reply(), Parsed(json.loads(reply())))
    bad = ("OK", "nope", REJECTED)
    down = ("UPSTREAM", None, None)
    assert outcome_of(_records(ok)) == "DECISION"
    assert outcome_of(_records(bad, ok)) == "DECISION"
    assert outcome_of(_records(bad, bad)) == "INVALID"
    assert outcome_of(_records(bad, down)) == "NO_RESPONSE"
    assert outcome_of(_records(down, down, down)) == "NO_RESPONSE"
    with pytest.raises(RunnerDefect):
        outcome_of([])


def test_decision_request_carries_the_reply_unchanged_only_for_a_decision():
    parsed = parse_reply(reply(reason="한글 사유", orders=[]))
    records = _records(("OK", reply(reason="한글 사유", orders=[]), parsed))
    body = decision_request(records, parsed.decision)
    assert body["outcome"] == "DECISION" and body["decision"] is parsed.decision
    assert contract_errors("DecisionRequest", body) == []
    with pytest.raises(RunnerDefect):
        decision_request(records, None)
    invalid = _records(("OK", "nope", REJECTED))
    with pytest.raises(RunnerDefect):
        decision_request(invalid, parsed.decision)
    assert decision_request(invalid, None)["decision"] is None


def test_requests_need_one_to_twenty_numbered_calls():
    b = builder("OLLAMA")

    def rec(n):
        return b.build(scripted_attempt(text=None, status="UPSTREAM"), n, None)

    assert calls_request([rec(3), rec(4)])["calls"][0]["attemptNo"] == 3
    with pytest.raises(RunnerDefect):
        calls_request([])
    with pytest.raises(RunnerDefect):
        calls_request([rec(1), rec(3)])
    with pytest.raises(RunnerDefect):
        calls_request([rec(n) for n in range(1, 22)])
    assert len(calls_request([rec(n) for n in range(1, 21)])["calls"]) == 20
