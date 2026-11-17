"""P12: no key value in any repr, log line, exception text or request text."""

import logging

import pytest
from modelport_helpers import (
    KEY,
    OK_BODY,
    OLLAMA_KEY,
    OLLAMA_OK,
    Scripted,
    ScriptedOllama,
    make_request,
)

from antlab_ai.modelport import (
    FakeModelPort,
    ModelPortSettings,
    OllamaAdapter,
    OpenRouterAdapter,
    build_registry,
)
from antlab_ai.openrouter.client import OpenRouterClient
from antlab_ai.openrouter.limiter import Limiter

SECRETS = (KEY, OLLAMA_KEY)


def assert_clean(text: str) -> None:
    for secret in SECRETS:
        assert secret not in text
    assert "Authorization" not in text
    assert "Bearer" not in text


def test_reprs_mask_every_secret(ledger):
    client = OpenRouterClient(KEY, Limiter(ledger, 1000), Scripted(), lambda s: None)
    settings = ModelPortSettings("http://127.0.0.1:11434", OLLAMA_KEY, True)
    registry = build_registry(settings, openrouter=client)
    ollama = OllamaAdapter("http://127.0.0.1:11434", OLLAMA_KEY)
    for obj in (client, settings, registry, ollama, OpenRouterAdapter(client), FakeModelPort([])):
        assert_clean(repr(obj))
        assert_clean(str(obj))
    assert "key=set" in repr(ollama) or "api_key=set" in repr(ollama)


def test_request_text_never_holds_a_header_or_key(make_client):
    client, _ = make_client((200, OK_BODY, {}))
    attempt = OpenRouterAdapter(client).attempt(make_request())
    assert_clean(attempt.request_text)
    ollama = OllamaAdapter(
        "http://127.0.0.1:11434", OLLAMA_KEY, transport=ScriptedOllama((200, OLLAMA_OK, {}))
    )
    assert_clean(ollama.attempt(make_request()).request_text)


@pytest.mark.parametrize(
    "response", [(200, OK_BODY, {}), (503, {}, {}), (401, {}, {}), TimeoutError(), OSError("x")]
)
def test_logs_and_exceptions_of_openrouter_attempts_are_clean(make_client, caplog, response):
    caplog.set_level(logging.DEBUG)
    client, _ = make_client(response)
    attempt = OpenRouterAdapter(client).attempt(make_request())
    assert_clean(caplog.text)
    assert_clean(repr(attempt))


def test_refusals_do_not_leak_the_key(make_client, caplog):
    client, _ = make_client((200, OK_BODY, {}))
    adapter = OpenRouterAdapter(client)
    with pytest.raises(ValueError) as policy:
        adapter.attempt(make_request(model_id="anthropic/x:free"))
    assert_clean(str(policy.value))
    for _ in range(18):
        client.limiter.reserve("harness:x", "vendor/model:free")
    with pytest.raises(Exception) as local:
        adapter.attempt(make_request())
    assert_clean(str(local.value) + repr(local.value))
    assert_clean(caplog.text)


def test_ollama_failure_paths_are_clean(caplog):
    caplog.set_level(logging.DEBUG)
    for response in (TimeoutError(), OSError(f"boom {OLLAMA_KEY}"), (500, {}, {}), (401, {}, {})):
        ollama = OllamaAdapter(
            "http://127.0.0.1:11434",
            OLLAMA_KEY,
            transport=ScriptedOllama(response),
        )
        attempt = ollama.attempt(make_request())
        assert_clean(repr(attempt))
    assert_clean(caplog.text)
