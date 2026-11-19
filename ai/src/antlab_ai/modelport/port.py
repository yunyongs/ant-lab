"""The route-blind model port: one provider request per call, no retries, no parsing."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Final, Protocol
from uuid import UUID

OK: Final = "OK"
UPSTREAM: Final = "UPSTREAM"
TIMEOUT: Final = "TIMEOUT"
LIMITED: Final = "LIMITED"
POLICY_REFUSED: Final = "POLICY_REFUSED"
ERROR: Final = "ERROR"
# INVALID is deliberately absent: judging a reply is the runner's job, not the adapter's.
STATUSES: Final = (OK, UPSTREAM, TIMEOUT, LIMITED, POLICY_REFUSED, ERROR)

ROLES: Final = ("system", "user", "assistant")
DEFAULT_CALLER: Final = "product:ag02"
MAX_TEMPERATURE: Final = Decimal(2)


class LocalRefusal(RuntimeError):
    """The local limiter refused: nothing was sent and no ledger row exists."""


class BudgetRefusal(RuntimeError):
    """The season budget would be exceeded or the worst-case cost is unknown: nothing was sent."""


class AdapterDisabled(LookupError):
    """The route exists but is switched off by configuration."""


class AdapterUnavailable(LookupError):
    """The route is planned but not implemented yet."""


class SubscriptionRefusal(RuntimeError):
    """A subscription condition failed before any process was spawned; the text is the fixed code."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class Message:
    role: str
    content: str

    def __post_init__(self) -> None:
        if self.role not in ROLES:
            raise ValueError("message role is not allowed")
        if not isinstance(self.content, str):
            raise TypeError("message content must be text")


@dataclass(frozen=True)
class ModelRequest:
    model_id: str
    messages: tuple[Message, ...]
    max_tokens: int
    temperature: Decimal
    seed: int | None
    timeout_ms: int
    output_schema: Mapping[str, object] | None = None
    caller: str = DEFAULT_CALLER

    def __post_init__(self) -> None:
        if not isinstance(self.model_id, str) or not self.model_id:
            raise ValueError("model_id must be non-empty text")
        if not isinstance(self.messages, tuple) or not self.messages:
            raise ValueError("messages must be a non-empty tuple")
        if not all(isinstance(m, Message) for m in self.messages):
            raise TypeError("messages must hold Message values")
        if type(self.max_tokens) is not int or self.max_tokens < 1:
            raise ValueError("max_tokens must be a positive integer")
        if (
            type(self.temperature) is not Decimal
            or not self.temperature.is_finite()
            or not 0 <= self.temperature <= MAX_TEMPERATURE
        ):
            raise ValueError("temperature must be a Decimal between 0 and 2")
        if self.seed is not None and (type(self.seed) is not int or self.seed < 0):
            raise ValueError("seed must be a non-negative integer or None")
        if type(self.timeout_ms) is not int or self.timeout_ms < 1:
            raise ValueError("timeout_ms must be a positive integer")
        if not isinstance(self.caller, str) or not self.caller:
            raise ValueError("caller must be non-empty text")


@dataclass(frozen=True)
class ModelAttempt:
    """What one provider request produced; `text` is the raw reply, never edited."""

    status: str
    http_status: int | None
    text: str | None = field(repr=False)
    model_actual: str | None
    provider: str | None
    tokens_in: int | None
    tokens_out: int | None
    cost_usd: Decimal | None
    latency_ms: int
    ledger_call_id: UUID | None
    retry_after_s: int | None
    credit_refusal: bool
    request_text: str = field(repr=False)

    def __post_init__(self) -> None:
        if self.status not in STATUSES:
            raise ValueError("status is not a port status")
        if self.cost_usd is not None and type(self.cost_usd) is not Decimal:
            raise TypeError("cost_usd must be Decimal")
        if self.latency_ms < 0:
            raise ValueError("latency_ms must not be negative")


class ModelPort(Protocol):
    def attempt(self, req: ModelRequest) -> ModelAttempt:
        """Send exactly one provider request, or none and raise LocalRefusal/BudgetRefusal."""
        ...
