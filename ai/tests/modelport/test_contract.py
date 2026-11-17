"""P1, P11: one contract, three implementations (fake, OpenRouter, Ollama on fake transports)."""

from decimal import Decimal
from uuid import UUID

import pytest
from modelport_helpers import KEY, OK_BODY, OLLAMA_OK, Scripted, ScriptedOllama, make_request

from antlab_ai.modelport import (
    FakeModelPort,
    OllamaAdapter,
    OpenRouterAdapter,
    scripted_attempt,
)
from antlab_ai.modelport.port import STATUSES, ModelAttempt
from antlab_ai.openrouter.client import OpenRouterClient
from antlab_ai.openrouter.limiter import Limiter

# Each scenario: (provider answer as the transport sees it, expected port status)
SCENARIOS = {
    "ok": (200, "OK"),
    "upstream": (503, "UPSTREAM"),
    "timeout": (TimeoutError(), "TIMEOUT"),
    "limited": (429, "LIMITED"),
    "error": (401, "ERROR"),
}


def _openrouter_answer(scenario):
    raw, _ = SCENARIOS[scenario]
    if isinstance(raw, BaseException):
        return raw
    return (200, OK_BODY, {}) if raw == 200 else (raw, {}, {})


def _ollama_answer(scenario):
    raw, _ = SCENARIOS[scenario]
    if isinstance(raw, BaseException):
        return raw
    return (200, OLLAMA_OK, {}) if raw == 200 else (raw, {}, {})


def _fake_answer(scenario):
    raw, status = SCENARIOS[scenario]
    if status == "OK":
        return scripted_attempt("hello")
    return scripted_attempt(None, status=status, http_status=None if status == "TIMEOUT" else raw)


@pytest.fixture(params=["fake", "openrouter", "ollama"])
def port_for(request, ledger):
    route = request.param

    def build(scenario):
        if route == "fake":
            return FakeModelPort([_fake_answer(scenario)])
        if route == "openrouter":
            client = OpenRouterClient(
                KEY,
                Limiter(ledger, 1000),
                Scripted(_openrouter_answer(scenario)),
                lambda s: None,
                lambda: 0.0,
            )
            return OpenRouterAdapter(client)
        return OllamaAdapter(
            "http://127.0.0.1:11434", transport=ScriptedOllama(_ollama_answer(scenario))
        )

    return build


def test_ok_reply_fills_the_attempt(port_for):
    port = port_for("ok")
    attempt = port.attempt(make_request())
    assert attempt.status == "OK"
    assert attempt.text == "hello"
    assert attempt.latency_ms >= 0
    assert attempt.cost_usd is None or isinstance(attempt.cost_usd, Decimal)
    assert attempt.credit_refusal is False
    assert isinstance(attempt.request_text, str)
    assert attempt.status in STATUSES


@pytest.mark.parametrize("scenario", ["upstream", "timeout", "limited", "error"])
def test_provider_failures_are_statuses_not_exceptions(port_for, scenario):
    port = port_for(scenario)
    attempt = port.attempt(make_request())
    assert attempt.status == SCENARIOS[scenario][1]
    assert attempt.text is None or isinstance(attempt.text, str)
    assert attempt.status != "INVALID"


def test_every_status_is_a_port_status_and_invalid_is_not_one():
    assert "INVALID" not in STATUSES
    with pytest.raises(ValueError):
        ModelAttempt("INVALID", 200, "x", None, None, None, None, None, 0, None, None, False, "{}")


def test_attempt_rejects_non_decimal_cost():
    with pytest.raises(TypeError):
        ModelAttempt("OK", 200, "x", None, None, None, None, 0.0, 0, None, None, False, "{}")  # type: ignore[arg-type]


def test_request_text_is_the_body_actually_sent(ledger):
    transport = Scripted((200, OK_BODY, {}))
    client = OpenRouterClient(KEY, Limiter(ledger, 1000), transport, lambda s: None, lambda: 0.0)
    attempt = OpenRouterAdapter(client).attempt(make_request())
    assert attempt.request_text.encode() == transport.bodies[0]
    ollama = ScriptedOllama((200, OLLAMA_OK, {}))
    attempt = OllamaAdapter("http://127.0.0.1:11434", transport=ollama).attempt(make_request())
    assert attempt.request_text.encode() == ollama.bodies[0]


def test_openrouter_attempt_carries_the_ledger_id(port_for, ledger):
    port = port_for("ok")
    attempt = port.attempt(make_request())
    if isinstance(port, OpenRouterAdapter):
        assert isinstance(attempt.ledger_call_id, UUID)
    else:
        assert attempt.ledger_call_id is None


# P11: the scripted fake
def test_fake_returns_the_script_in_order_and_records_requests():
    first, second = scripted_attempt("a"), scripted_attempt("b", status="UPSTREAM", http_status=503)
    fake = FakeModelPort([first, second])
    req1, req2 = make_request(), make_request(seed=2)
    assert fake.attempt(req1) is first
    assert fake.attempt(req2) is second
    assert fake.requests == [req1, req2]
    assert fake.remaining == 0


def test_fake_fails_loudly_when_the_script_runs_out():
    fake = FakeModelPort([scripted_attempt("a")])
    fake.attempt(make_request())
    with pytest.raises(AssertionError, match="exhausted"):
        fake.attempt(make_request())


def test_fake_raises_scripted_exceptions_as_that_attempt():
    class Boom(RuntimeError):
        pass

    fake = FakeModelPort([Boom("x"), scripted_attempt("ok")])
    with pytest.raises(Boom):
        fake.attempt(make_request())
    assert fake.attempt(make_request()).text == "ok"


@pytest.mark.parametrize(
    "overrides",
    [
        {"max_tokens": 0},
        {"max_tokens": True},
        {"temperature": 0.0},
        {"temperature": Decimal("NaN")},
        {"temperature": Decimal("2.5")},
        {"temperature": Decimal("-0.1")},
        {"seed": -1},
        {"seed": True},
        {"timeout_ms": 0},
        {"messages": ()},
        {"model_id": ""},
        {"caller": ""},
    ],
)
def test_request_rejects_bad_values(overrides):
    with pytest.raises((ValueError, TypeError)):
        make_request(**overrides)
