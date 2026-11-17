"""OpenRouter route: one `attempt_once` on the AG-01 client, ledger rows included."""

from __future__ import annotations

import math
from uuid import UUID

from ..openrouter.client import CHAT_DEADLINE_S, OpenRouterClient
from ..openrouter.limiter import RefusedLocal
from .port import LocalRefusal, ModelAttempt, ModelRequest


def response_format(schema: dict | object) -> dict:
    """OpenRouter structured-output parameter for one JSON schema."""
    return {
        "type": "json_schema",
        "json_schema": {"name": "turn_decision", "strict": True, "schema": schema},
    }


class OpenRouterAdapter:
    def __init__(self, client: OpenRouterClient, *, structured_output: bool = False):
        self._client = client
        self._structured_output = structured_output

    def __repr__(self) -> str:
        return f"OpenRouterAdapter(structured_output={self._structured_output})"

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        timeout_s = min(max(1, math.ceil(req.timeout_ms / 1000)), CHAT_DEADLINE_S)
        fmt = (
            response_format(req.output_schema)
            if self._structured_output and req.output_schema is not None
            else None
        )
        try:
            outcome = self._client.attempt_once(
                req.model_id,
                [(m.role, m.content) for m in req.messages],
                max_tokens=req.max_tokens,
                temperature=req.temperature,
                seed=req.seed,
                timeout_s=timeout_s,
                caller=req.caller,
                response_format=fmt,
            )
        except RefusedLocal:
            raise LocalRefusal("local limiter refused the call") from None
        result = outcome.result
        return ModelAttempt(
            status=result.status,
            http_status=outcome.http,
            text=result.text if result.status == "OK" else None,
            model_actual=result.model_actual,
            provider=result.provider,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
            cost_usd=result.cost_usd,
            latency_ms=result.latency_ms,
            ledger_call_id=UUID(outcome.call_id),
            retry_after_s=outcome.retry_after_s,
            credit_refusal=outcome.credit_refusal,
            request_text=outcome.body.decode("utf-8"),
        )
