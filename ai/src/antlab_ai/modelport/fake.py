"""Scripted model port for tests and offline runs: one scripted answer per attempt."""

from __future__ import annotations

from collections.abc import Sequence
from decimal import Decimal
from uuid import UUID

from .port import ModelAttempt, ModelRequest


def scripted_attempt(
    text: str | None = "",
    *,
    status: str = "OK",
    http_status: int | None = 200,
    cost_usd: Decimal | None = Decimal(0),
    ledger_call_id: UUID | None = None,
    retry_after_s: int | None = None,
    credit_refusal: bool = False,
    request_text: str = "{}",
) -> ModelAttempt:
    return ModelAttempt(
        status=status,
        http_status=http_status,
        text=text,
        model_actual="fake/model",
        provider="fake",
        tokens_in=1,
        tokens_out=1,
        cost_usd=cost_usd,
        latency_ms=0,
        ledger_call_id=ledger_call_id,
        retry_after_s=retry_after_s,
        credit_refusal=credit_refusal,
        request_text=request_text,
    )


class FakeModelPort:
    """Returns the script in order; an exception in the script is raised as that attempt."""

    def __init__(self, script: Sequence[ModelAttempt | BaseException]):
        self._script = list(script)
        self.requests: list[ModelRequest] = []

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        self.requests.append(req)
        if not self._script:
            raise AssertionError("fake model script exhausted")
        item = self._script.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    @property
    def remaining(self) -> int:
        return len(self._script)

    def __repr__(self) -> str:
        return f"FakeModelPort(remaining={len(self._script)})"
