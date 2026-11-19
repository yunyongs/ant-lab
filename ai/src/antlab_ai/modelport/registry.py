"""Route name -> adapter. Routes that are off or not built yet fail loudly, never silently."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from ..openrouter.client import OpenRouterClient
from .ollama_adapter import OllamaAdapter, OllamaTransport, urllib_ollama_transport
from .openrouter_adapter import OpenRouterAdapter
from .port import AdapterDisabled, ModelPort
from .settings import ModelPortSettings
from .subscription.claude_cli import ClaudeCliAdapter
from .subscription.codex_cli import CodexCliAdapter
from .subscription.common import CliConfig, SubscriptionGate

OPENROUTER = "OPENROUTER"
OLLAMA = "OLLAMA"
CLAUDE_CLI = "CLAUDE_CLI"
CODEX_CLI = "CODEX_CLI"
ROUTES = (OPENROUTER, CLAUDE_CLI, CODEX_CLI, OLLAMA)


@dataclass(frozen=True)
class SubscriptionWiring:
    """Subscription routes exist only when a gate and a CLI config are handed in together."""

    gate: SubscriptionGate
    claude: CliConfig | None = None
    codex: CliConfig | None = None


class Registry:
    def __init__(self, adapters: Mapping[str, ModelPort]):
        unknown = set(adapters) - set(ROUTES)
        if unknown:
            raise ValueError("registry holds an unsupported route")
        self._adapters = dict(adapters)

    @property
    def routes(self) -> frozenset[str]:
        return frozenset(self._adapters)

    def get(self, route: str) -> ModelPort:
        if route not in ROUTES:
            raise ValueError("unknown route")
        adapter = self._adapters.get(route)
        if adapter is None:
            raise AdapterDisabled(route)
        return adapter

    def __repr__(self) -> str:
        return f"Registry(routes={sorted(self._adapters)})"


def build_registry(
    settings: ModelPortSettings,
    *,
    openrouter: OpenRouterClient | None = None,
    ollama_transport: OllamaTransport = urllib_ollama_transport,
    subscription: SubscriptionWiring | None = None,
) -> Registry:
    """Adapters for the configured routes; OLLAMA exists only when OLLAMA_BASE_URL was set.

    The CLI routes are built only from a SubscriptionWiring; without one they stay disabled.
    """
    adapters: dict[str, ModelPort] = {}
    if openrouter is not None:
        adapters[OPENROUTER] = OpenRouterAdapter(
            openrouter, structured_output=settings.structured_output
        )
    if settings.ollama_base_url is not None:
        adapters[OLLAMA] = OllamaAdapter(
            settings.ollama_base_url,
            settings.ollama_api_key,
            structured_output=settings.structured_output,
            transport=ollama_transport,
        )
    if subscription is not None:
        if subscription.claude is not None:
            adapters[CLAUDE_CLI] = ClaudeCliAdapter(
                subscription.claude,
                subscription.gate,
                structured_output=settings.structured_output,
            )
        if subscription.codex is not None:
            adapters[CODEX_CLI] = CodexCliAdapter(
                subscription.codex,
                subscription.gate,
                structured_output=settings.structured_output,
            )
    return Registry(adapters)
