"""P8, P10: routes that are off or not built fail loudly; OLLAMA_KEY is never read."""

import os

import pytest
from modelport_helpers import KEY, OLLAMA_KEY

from antlab_ai.modelport import (
    AdapterDisabled,
    ModelPortSettings,
    OllamaAdapter,
    OpenRouterAdapter,
    Registry,
    build_registry,
)
from antlab_ai.openrouter.client import OpenRouterClient
from antlab_ai.openrouter.limiter import Limiter


class LoggingEnv(dict):
    """A mapping that records every name asked for."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.asked: list[str] = []

    def get(self, name, default=None):
        self.asked.append(name)
        return super().get(name, default)

    def __getitem__(self, name):
        self.asked.append(name)
        return super().__getitem__(name)

    def __contains__(self, name):
        self.asked.append(name)
        return super().__contains__(name)


@pytest.fixture
def client(ledger):
    return OpenRouterClient(KEY, Limiter(ledger, 1000), lambda *a: None, lambda s: None)  # type: ignore[arg-type]


@pytest.mark.parametrize("value", [None, "", "   ", "\t\n"])
def test_ollama_is_off_without_a_base_url(value, client):
    env = {} if value is None else {"OLLAMA_BASE_URL": value}
    registry = build_registry(ModelPortSettings.from_env(env), openrouter=client)
    assert "OLLAMA" not in registry.routes
    with pytest.raises(AdapterDisabled):
        registry.get("OLLAMA")


def test_ollama_key_alone_changes_nothing_and_the_harness_key_is_never_read(client):
    env = LoggingEnv(
        {"OLLAMA_KEY": "harness-secret", "OLLAMA_API_KEY": OLLAMA_KEY, "OPENROUTER_API_KEY": KEY}
    )
    registry = build_registry(ModelPortSettings.from_env(env), openrouter=client)
    assert registry.routes == {"OPENROUTER"}
    assert "OLLAMA_KEY" not in env.asked
    assert set(env.asked) <= {"OLLAMA_BASE_URL", "OLLAMA_API_KEY", "ANTLAB_MODEL_STRUCTURED_OUTPUT"}


def test_ollama_key_is_not_read_even_when_ollama_is_on(client):
    env = LoggingEnv({"OLLAMA_BASE_URL": "http://127.0.0.1:11434", "OLLAMA_KEY": "harness-secret"})
    settings = ModelPortSettings.from_env(env)
    assert settings.ollama_api_key is None
    assert "OLLAMA_KEY" not in env.asked


def test_default_environment_is_os_environ(monkeypatch):
    monkeypatch.setenv("OLLAMA_BASE_URL", "http://localhost:11434/")
    monkeypatch.delenv("OLLAMA_API_KEY", raising=False)
    settings = ModelPortSettings.from_env()
    assert settings.ollama_base_url == "http://localhost:11434"
    assert "OLLAMA_BASE_URL" in os.environ


def test_ollama_on_builds_the_adapter_with_the_named_key(client):
    env = {"OLLAMA_BASE_URL": "http://127.0.0.1:11434", "OLLAMA_API_KEY": OLLAMA_KEY}
    registry = build_registry(ModelPortSettings.from_env(env), openrouter=client)
    assert isinstance(registry.get("OLLAMA"), OllamaAdapter)
    assert isinstance(registry.get("OPENROUTER"), OpenRouterAdapter)


def test_openrouter_route_is_disabled_without_a_client():
    registry = build_registry(ModelPortSettings())
    with pytest.raises(AdapterDisabled):
        registry.get("OPENROUTER")


@pytest.mark.parametrize("route", ["CLAUDE_CLI", "CODEX_CLI"])
def test_subscription_routes_are_disabled_without_a_gate(route, client):
    registry = build_registry(ModelPortSettings(), openrouter=client)
    assert route not in registry.routes
    with pytest.raises(AdapterDisabled):
        registry.get(route)


def test_unknown_route_is_a_value_error(client):
    with pytest.raises(ValueError):
        build_registry(ModelPortSettings(), openrouter=client).get("GPT")


def test_registry_refuses_an_unknown_route():
    with pytest.raises(ValueError):
        Registry({"GPT": object()})  # type: ignore[dict-item]


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        (None, False),
        ("", False),
        ("false", False),
        ("FALSE", False),
        ("true", True),
        (" True ", True),
    ],
)
def test_structured_output_flag(value, expected):
    env = {} if value is None else {"ANTLAB_MODEL_STRUCTURED_OUTPUT": value}
    assert ModelPortSettings.from_env(env).structured_output is expected


@pytest.mark.parametrize("value", ["1", "yes", "on", "0", "truee"])
def test_structured_output_flag_must_be_true_or_false(value):
    with pytest.raises(ValueError, match="ANTLAB_MODEL_STRUCTURED_OUTPUT"):
        ModelPortSettings.from_env({"ANTLAB_MODEL_STRUCTURED_OUTPUT": value})


def test_flag_reaches_both_adapters(client, ledger):
    env = {"OLLAMA_BASE_URL": "http://127.0.0.1:11434", "ANTLAB_MODEL_STRUCTURED_OUTPUT": "true"}
    registry = build_registry(ModelPortSettings.from_env(env), openrouter=client)
    assert registry.get("OPENROUTER")._structured_output is True  # type: ignore[attr-defined]
    assert registry.get("OLLAMA")._structured_output is True  # type: ignore[attr-defined]


@pytest.mark.parametrize(
    "value", ["ftp://x", "localhost:11434", "http://u:p@h", "http://h?x=1", "http://h:bad"]
)
def test_a_bad_base_url_is_a_startup_error(value):
    with pytest.raises(ValueError, match="OLLAMA_BASE_URL"):
        ModelPortSettings.from_env({"OLLAMA_BASE_URL": value})
