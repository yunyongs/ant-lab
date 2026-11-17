"""Route name -> adapter. Routes that are off or not built yet fail loudly, never silently."""

from __future__ import annotations

from collections.abc import Mapping

from ..openrouter.client import OpenRouterClient
from .ollama_adapter import OllamaAdapter, OllamaTransport, urllib_ollama_transport
from .openrouter_adapter import OpenRouterAdapter
from .port import AdapterDisabled, AdapterUnavailable, ModelPort
from .settings import ModelPortSettings

OPENROUTER = "OPENROUTER"
OLLAMA = "OLLAMA"
CLAUDE_CLI = "CLAUDE_CLI"
CODEX_CLI = "CODEX_CLI"
ROUTES = (OPENROUTER, CLAUDE_CLI, CODEX_CLI, OLLAMA)
_LATER = (CLAUDE_CLI, CODEX_CLI)


class Registry:
    def __init__(self, adapters: Mapping[str, ModelPort]):
        unknown = set(adapters) - set(ROUTES)
        if unknown or set(adapters) & set(_LATER):
            raise ValueError("registry holds an unsupported route")
        self._adapters = dict(adapters)

    @property
    def routes(self) -> frozenset[str]:
        return frozenset(self._adapters)

    def get(self, route: str) -> ModelPort:
        if route in _LATER:
            raise AdapterUnavailable("AG-03")
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
) -> Registry:
    """Adapters for the configured routes; OLLAMA exists only when OLLAMA_BASE_URL was set."""
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
    return Registry(adapters)
