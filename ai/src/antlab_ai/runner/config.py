"""Season config the runner sends (one fixed set of defaults) and the scripted model for offline runs."""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass, replace
from decimal import Decimal
from pathlib import Path
from typing import Any, Final
from uuid import uuid4

from antlab_ai import __version__
from antlab_ai.modelport.fake import FakeModelPort, scripted_attempt
from antlab_ai.modelport.port import ModelAttempt
from antlab_ai.runner.persona import Persona
from antlab_ai.runner.prompt import PROMPT_TEMPLATE_VERSION

OPENROUTER: Final = "OPENROUTER"
OLLAMA: Final = "OLLAMA"
CLAUDE_CLI: Final = "CLAUDE_CLI"
CODEX_CLI: Final = "CODEX_CLI"
SUBSCRIPTION_ROUTES: Final = (CLAUDE_CLI, CODEX_CLI)
KNOWN_ROUTES: Final = (OPENROUTER, CLAUDE_CLI, CODEX_CLI, OLLAMA)
# How the season budget treats cost: a USD bound, no bound possible (pause before sending), or a
# flat subscription fee bounded by the call budget, the batch call cap and the usage guard.
KNOWN: Final = "KNOWN"
UNKNOWN: Final = "UNKNOWN"
FLAT_FEE: Final = "FLAT_FEE"
COST_MODES: Final = (KNOWN, UNKNOWN, FLAT_FEE)
MODEL_SEED: Final = 1
MAX_TOKENS: Final = 2048
TEMPERATURE: Final = Decimal(0)
CALLS_PER_TURN: Final = 3
TURN_TIMEOUT_MS: Final = {
    OPENROUTER: 120_000,
    OLLAMA: 300_000,
    CLAUDE_CLI: 600_000,
    CODEX_CLI: 600_000,
}
DEFAULT_TIMEOUT_MS: Final = 120_000
MAX_SCRIPT_ENTRIES: Final = 10_000
_SCRIPT_STATUSES: Final = ("OK", "UPSTREAM", "TIMEOUT", "LIMITED", "POLICY_REFUSED", "ERROR")


@dataclass(frozen=True)
class RunnerConfig:
    """What the loop needs about one season; ``cost_mode`` says how the budget treats cost."""

    model_requested: str
    route: str
    persona: Persona
    path_length: int
    turn_timeout_ms: int
    max_tokens: int = MAX_TOKENS
    temperature: Decimal = TEMPERATURE
    seed: int = MODEL_SEED
    budget_calls: int = 0
    budget_usd: Decimal = Decimal(0)
    cost_mode: str = KNOWN

    def __post_init__(self) -> None:
        if self.cost_mode not in COST_MODES:
            raise ValueError("cost_mode is not a known mode")
        if self.budget_calls < 1:
            object.__setattr__(self, "budget_calls", CALLS_PER_TURN * self.path_length)


def default_timeout_ms(route: str) -> int:
    return TURN_TIMEOUT_MS.get(route, DEFAULT_TIMEOUT_MS)


def season_config(config: RunnerConfig) -> dict[str, Any]:
    """The ``AgentSeasonConfig`` body; free models only, so the USD budget is zero.

    A subscription route has no seed to set: the config records ``modelSeed: null``. Temperature and
    max tokens are recorded as requested, not as applied (neither CLI exposes them).
    """
    return {
        "personaVersion": config.persona.version,
        "personaSha256": config.persona.sha256,
        "personaDisplayName": config.persona.display_name,
        "modelRequested": config.model_requested,
        "temperature": format(config.temperature, "f"),
        "modelSeed": None if config.route in SUBSCRIPTION_ROUTES else config.seed,
        "maxTokens": config.max_tokens,
        "turnTimeoutMs": config.turn_timeout_ms,
        "promptTemplateVersion": PROMPT_TEMPLATE_VERSION,
        "runnerVersion": __version__,
        "memoryVersion": None,
        "budgetCalls": config.budget_calls,
        "budgetUsd": format(config.budget_usd, "f"),
    }


class ScriptError(ValueError):
    pass


def _entry(raw: Any) -> list[ModelAttempt]:
    if not isinstance(raw, dict) or not set(raw) <= {
        "reply",
        "text",
        "status",
        "http_status",
        "retry_after_s",
        "credit_refusal",
        "repeat",
    }:
        raise ScriptError("a script entry is not an object with known keys")
    status = raw.get("status", "OK")
    if status not in _SCRIPT_STATUSES:
        raise ScriptError("a script entry has an unknown status")
    if "reply" in raw and "text" in raw:
        raise ScriptError("a script entry has both reply and text")
    text = json.dumps(raw["reply"], ensure_ascii=False) if "reply" in raw else raw.get("text")
    if text is not None and not isinstance(text, str):
        raise ScriptError("script text must be a string or null")
    repeat = raw.get("repeat", 1)
    if type(repeat) is not int or not 1 <= repeat <= MAX_SCRIPT_ENTRIES:
        raise ScriptError("repeat must be a positive integer")
    http = raw.get("http_status", 200 if status == "OK" else None)
    retry = raw.get("retry_after_s")
    for value in (http, retry):
        if value is not None and type(value) is not int:
            raise ScriptError("http_status and retry_after_s must be integers")
    if raw.get("credit_refusal") and http != 402:
        raise ScriptError("credit_refusal needs http_status 402")
    attempt = scripted_attempt(
        text if status == "OK" else None,
        status=status,
        http_status=http,
        retry_after_s=retry,
        credit_refusal=bool(raw.get("credit_refusal", False)),
    )
    return [attempt] * repeat


def load_script(path: Path) -> list[ModelAttempt]:
    """A JSON list of scripted attempts; ``repeat`` expands an entry in place."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ScriptError("the model script cannot be read as JSON") from None
    if not isinstance(data, list) or not data:
        raise ScriptError("the model script must be a non-empty JSON list")
    attempts: list[ModelAttempt] = []
    for raw in data:
        attempts.extend(_entry(raw))
        if len(attempts) > MAX_SCRIPT_ENTRIES:
            raise ScriptError("the model script is too long")
    return attempts


class ScriptedPort(FakeModelPort):
    """The scripted fake for offline runs. On the OPENROUTER route every attempt gets a fresh ledger
    call id, because the API requires one there; nothing is written to any ledger."""

    def __init__(self, script: Sequence[ModelAttempt], route: str) -> None:
        super().__init__(script)
        self._route = route

    def attempt(self, req):  # type: ignore[no-untyped-def]
        result = super().attempt(req)
        if self._route == OPENROUTER and result.ledger_call_id is None:
            return replace(result, ledger_call_id=uuid4())
        return result
