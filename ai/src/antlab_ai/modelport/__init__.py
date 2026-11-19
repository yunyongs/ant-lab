"""Route-blind model port with OpenRouter, Ollama and scripted-fake adapters."""

from .fake import FakeModelPort, scripted_attempt
from .ollama_adapter import OllamaAdapter
from .openrouter_adapter import OpenRouterAdapter
from .port import (
    AdapterDisabled,
    AdapterUnavailable,
    BudgetRefusal,
    LocalRefusal,
    Message,
    ModelAttempt,
    ModelPort,
    ModelRequest,
    SubscriptionRefusal,
)
from .registry import Registry, SubscriptionWiring, build_registry
from .settings import ModelPortSettings

__all__ = [
    "AdapterDisabled",
    "AdapterUnavailable",
    "BudgetRefusal",
    "FakeModelPort",
    "LocalRefusal",
    "Message",
    "ModelAttempt",
    "ModelPort",
    "ModelPortSettings",
    "ModelRequest",
    "OllamaAdapter",
    "OpenRouterAdapter",
    "Registry",
    "SubscriptionRefusal",
    "SubscriptionWiring",
    "build_registry",
    "scripted_attempt",
]
