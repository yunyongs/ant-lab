"""P2–P7: the OpenRouter route on a fake transport and a temp ledger."""

from decimal import Decimal

import pytest
from modelport_helpers import KEY, MODEL, OK_BODY, Scripted, body_of, make_request

from antlab_ai.modelport import LocalRefusal, OpenRouterAdapter
from antlab_ai.openrouter.client import CHAT_DEADLINE_S, OpenRouterClient, SendCap, SendCapReached
from antlab_ai.openrouter.limiter import Limiter

SCHEMA = {"type": "object", "properties": {"orders": {"type": "array"}}}


def adapter_for(client, **kwargs):
    return OpenRouterAdapter(client, **kwargs)


# P2
def test_body_fields_come_from_the_request(make_client):
    client, transport = make_client((200, OK_BODY, {}))
    adapter_for(client).attempt(make_request(temperature=Decimal("0.3"), seed=7, max_tokens=99))
    body = body_of(transport.bodies[0])
    assert body["provider"] == {"data_collection": "deny"}
    assert body["usage"] == {"include": True}
    assert body["model"] == MODEL
    assert body["temperature"] == 0.3
    assert body["seed"] == 7
    assert body["max_tokens"] == 99
    assert [m["role"] for m in body["messages"]] == ["system", "user"]
    assert not {"tools", "tool_choice", "plugins", "response_format"} & set(body)


def test_seed_is_omitted_when_none_and_zero_temperature_is_an_integer(make_client):
    client, transport = make_client((200, OK_BODY, {}))
    adapter_for(client).attempt(make_request(seed=None))
    assert "seed" not in body_of(transport.bodies[0])
    assert b'"temperature": 0,' in transport.bodies[0]


def test_response_format_needs_both_the_flag_and_a_schema(make_client):
    for flag, schema, expected in [
        (False, SCHEMA, False),
        (True, None, False),
        (True, SCHEMA, True),
    ]:
        client, transport = make_client((200, OK_BODY, {}))
        adapter_for(client, structured_output=flag).attempt(make_request(output_schema=schema))
        body = body_of(transport.bodies[0])
        assert ("response_format" in body) is expected
        if expected:
            assert body["response_format"]["type"] == "json_schema"
            assert body["response_format"]["json_schema"]["schema"] == SCHEMA


def test_timeout_is_rounded_up_and_capped_at_the_chat_deadline(make_client):
    for timeout_ms, expected in [(1, 1), (1500, 2), (120000, 120), (900000, CHAT_DEADLINE_S)]:
        client, transport = make_client((200, OK_BODY, {}))
        adapter_for(client).attempt(make_request(timeout_ms=timeout_ms))
        assert transport.timeouts == [expected]


# P3
@pytest.mark.parametrize(
    "response",
    [(200, OK_BODY, {}), (503, {}, {}), (429, {}, {"Retry-After": "30"}), TimeoutError()],
)
def test_one_transport_call_and_one_ledger_pair_per_attempt(make_client, ledger, response):
    client, transport = make_client(response)
    attempt = adapter_for(client).attempt(make_request())
    rows = ledger.rows()
    assert len(transport.bodies) == 1
    assert [r["phase"] for r in rows] == ["attempt", "outcome"]
    assert str(attempt.ledger_call_id) == rows[0]["call_id"] == rows[1]["call_id"]
    assert rows[0]["caller"] == "product:ag02"
    assert rows[1]["status"] == attempt.status


def test_a_503_is_not_retried_inside_the_adapter(make_client, ledger):
    sleeps = []
    client, transport = make_client((503, {}, {}), (200, OK_BODY, {}))
    client.sleep = sleeps.append
    attempt = adapter_for(client).attempt(make_request())
    assert attempt.status == "UPSTREAM"
    assert len(transport.bodies) == 1
    assert sleeps == []
    assert len(transport.responses) == 1  # the second answer was never consumed


# P4
@pytest.mark.parametrize(
    ("response", "status", "retry_after", "credit"),
    [
        ((200, OK_BODY, {}), "OK", None, False),
        ((200, {"error": {"message": "x"}}, {}), "UPSTREAM", None, False),
        ((200, {}, {}), "UPSTREAM", None, False),
        ((200, {"choices": []}, {}), "UPSTREAM", None, False),
        ((408, {}, {}), "UPSTREAM", None, False),
        ((502, {}, {}), "UPSTREAM", None, False),
        (ConnectionResetError(), "UPSTREAM", None, False),
        (TimeoutError(), "TIMEOUT", None, False),
        ((429, {}, {"Retry-After": "30"}), "LIMITED", 30, False),
        ((429, {}, {}), "LIMITED", None, False),
        ((402, {}, {"Retry-After": "30"}), "LIMITED", None, True),
        (
            (404, {"error": {"message": "No endpoints: data policy"}}, {}),
            "POLICY_REFUSED",
            None,
            False,
        ),
        ((400, {}, {}), "ERROR", None, False),
        ((401, {}, {}), "ERROR", None, False),
        ((403, {}, {}), "ERROR", None, False),
        ((404, {"error": {"message": "model not found"}}, {}), "ERROR", None, False),
    ],
)
def test_status_table(make_client, response, status, retry_after, credit):
    client, _ = make_client(response)
    attempt = adapter_for(client).attempt(make_request())
    assert attempt.status == status
    assert attempt.retry_after_s == retry_after
    assert attempt.credit_refusal is credit
    assert (attempt.text is not None) is (status == "OK")


def test_ok_attempt_copies_usage_and_cost_as_decimal(make_client):
    body = {
        **OK_BODY,
        "usage": {"prompt_tokens": 5, "completion_tokens": 2, "cost": Decimal("0.0001")},
    }
    client, _ = make_client((200, body, {}))
    attempt = adapter_for(client).attempt(make_request())
    assert (attempt.tokens_in, attempt.tokens_out) == (5, 2)
    assert attempt.cost_usd == Decimal("0.0001")
    assert attempt.http_status == 200
    assert (attempt.model_actual, attempt.provider) == (MODEL, "Prov")


# P5
@pytest.mark.parametrize(
    "model",
    [
        "anthropic/claude:free",
        "openai/gpt:free",
        "openrouter/auto:free",
        "ANTHROPIC/x:free",
        "vendor/paid-model",
        "no-slash:free",
        "vendor/model:free\n",
        "",
    ],
)
def test_policy_refusal_happens_before_any_send(make_client, ledger, model):
    client, transport = make_client((200, OK_BODY, {}))
    adapter = adapter_for(client)
    with pytest.raises(ValueError):
        adapter.attempt(make_request(model_id=model))
    assert transport.bodies == []
    assert ledger.rows() == []


# P6
def test_local_cap_refuses_without_sending_or_recording(make_client, ledger):
    client, transport = make_client((200, OK_BODY, {}))
    for _ in range(18):
        client.limiter.reserve("harness:x", MODEL)
    before = len(ledger.rows())
    with pytest.raises(LocalRefusal):
        adapter_for(client).attempt(make_request())
    assert transport.bodies == []
    assert len(ledger.rows()) == before


def test_product_daily_cap_refuses_locally(ledger):
    transport = Scripted((200, OK_BODY, {}))
    client = OpenRouterClient(KEY, Limiter(ledger, 50), transport, lambda s: None, lambda: 0.0)
    for _ in range(10):
        client.limiter.reserve("product:ag02", MODEL)
    with pytest.raises(LocalRefusal):
        adapter_for(client).attempt(make_request())
    assert transport.bodies == []


def test_send_cap_propagates_as_its_own_error(ledger):
    transport = Scripted((200, OK_BODY, {}), (200, OK_BODY, {}))
    client = OpenRouterClient(
        KEY, Limiter(ledger, 1000), transport, lambda s: None, lambda: 0.0, send_cap=SendCap(1)
    )
    adapter = adapter_for(client)
    adapter.attempt(make_request())
    with pytest.raises(SendCapReached):
        adapter.attempt(make_request())
    assert len(transport.bodies) == 1
    assert len(ledger.rows()) == 2


# P7 (the AG-01 suites are not edited; this checks the new method keeps chat() semantics)
def test_chat_still_retries_and_each_try_is_its_own_ledger_pair(make_client, ledger):
    client, transport = make_client((503, {}, {}), (200, OK_BODY, {}))
    result = client.chat(MODEL, "s", "u")
    assert result.status == "OK"
    assert len(transport.bodies) == 2
    assert [r["phase"] for r in ledger.rows()] == ["attempt", "outcome"] * 2
    assert body_of(transport.bodies[0])["temperature"] == 0
    assert "seed" not in body_of(transport.bodies[0])
    assert transport.timeouts == [CHAT_DEADLINE_S] * 2


@pytest.mark.parametrize("timeout_s", [0, -1, CHAT_DEADLINE_S + 1, True, 1.5])
def test_attempt_once_rejects_bad_timeouts_before_sending(make_client, timeout_s):
    client, transport = make_client((200, OK_BODY, {}))
    with pytest.raises(ValueError):
        client.attempt_once(
            MODEL,
            [("user", "x")],
            max_tokens=8,
            temperature=Decimal(0),
            seed=None,
            timeout_s=timeout_s,
            caller="product:ag02",
        )
    assert transport.bodies == []


@pytest.mark.parametrize("seed", [-1, True, 2**31, 1.0])
def test_attempt_once_rejects_bad_seeds(make_client, seed):
    client, transport = make_client((200, OK_BODY, {}))
    with pytest.raises(ValueError):
        client.attempt_once(
            MODEL,
            [("user", "x")],
            max_tokens=8,
            temperature=Decimal(0),
            seed=seed,
            timeout_s=10,
            caller="product:ag02",
        )
    assert transport.bodies == []
