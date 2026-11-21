"""Call records the runner sends to the API: one per model attempt, built from the attempt as made."""

from __future__ import annotations

from collections.abc import Sequence
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from typing import Any, Final

from antlab_ai.modelport.port import OK, ModelAttempt
from antlab_ai.runner import contract
from antlab_ai.runner.decision import Parsed, Rejected, is_storable

INVALID: Final = "INVALID"
KIND: Final = "TURN_DECISION"
CALL_KINDS: Final = ("TURN_DECISION", "REFLECTION", "PLAYBOOK")
OUTCOMES: Final = ("DECISION", "INVALID", "NO_RESPONSE")
MAX_CALLS: Final = 20
MAX_MODEL_ACTUAL: Final = 200
MAX_PROVIDER: Final = 100
_COST_STEP: Final = Decimal("0.00000001")
_COST_INTEGER_DIGITS: Final = 11
_MAX_ATTEMPT_NO: Final = 32767


class RunnerDefect(Exception):
    """The runner itself is about to send something the contract forbids. Nothing is sent."""


def cost_string(cost: Decimal | None) -> str | None:
    """USD cost with 8 decimals, rounded up so a cost is never under-counted."""
    if cost is None:
        return None
    if type(cost) is not Decimal or not cost.is_finite() or cost < 0:
        raise RunnerDefect("cost is not a non-negative finite Decimal")
    try:
        text = format(cost.quantize(_COST_STEP, rounding=ROUND_CEILING), "f")
    except InvalidOperation:
        raise RunnerDefect("cost is out of range") from None
    # The API accepts 11 integer digits and 8 decimals (@Digits); more would refuse the whole call list.
    if len(text.partition(".")[0]) > _COST_INTEGER_DIGITS:
        raise RunnerDefect("cost is out of range")
    return text


def _metadata(text: str | None, limit: int) -> str | None:
    """Provider-supplied labels are cut to the contract length; an unstorable one is dropped."""
    if text is None or not is_storable(text):
        return None
    return text[:limit]


class RecordBuilder:
    """Builds ``CallRecordRequest`` dicts for one season: the model and route come from its config."""

    def __init__(self, route: str, model_requested: str) -> None:
        if route not in ("OPENROUTER", "CLAUDE_CLI", "CODEX_CLI", "OLLAMA"):
            raise RunnerDefect("model route is not a contract route")
        if not isinstance(model_requested, str) or not model_requested:
            raise RunnerDefect("model_requested must be non-empty text")
        self._route = route
        self._model_requested = model_requested

    def build(
        self, attempt: ModelAttempt, attempt_no: int, verdict: Parsed | Rejected | None
    ) -> dict[str, Any]:
        """``verdict`` is the runner's judgement of an OK reply, and must be None otherwise."""
        if attempt.status == OK:
            if verdict is None:
                raise RunnerDefect("an OK reply needs the runner's verdict")
            status = OK if isinstance(verdict, Parsed) else INVALID
        else:
            if verdict is not None:
                raise RunnerDefect("only an OK reply carries a verdict")
            status = attempt.status
        return self.build_as(attempt, attempt_no, status, KIND)

    def build_as(
        self, attempt: ModelAttempt, attempt_no: int, status: str, kind: str
    ) -> dict[str, Any]:
        """A record with the status and call kind decided by the caller (the memory calls)."""
        if kind not in CALL_KINDS:
            raise RunnerDefect("call kind is not a contract kind")
        allowed = (OK, INVALID) if attempt.status == OK else (attempt.status,)
        if status not in allowed:
            raise RunnerDefect("record status does not follow the attempt")
        if type(attempt_no) is not int or not 1 <= attempt_no <= _MAX_ATTEMPT_NO:
            raise RunnerDefect("attempt number is outside the contract range")
        if not is_storable(attempt.request_text):
            raise RunnerDefect("request text cannot be stored")
        if self._route == "OPENROUTER" and attempt.ledger_call_id is None:
            raise RunnerDefect("an OPENROUTER attempt needs its ledger call id")
        http = attempt.http_status
        record = {
            "attemptNo": attempt_no,
            "kind": kind,
            "ledgerCallId": None if attempt.ledger_call_id is None else str(attempt.ledger_call_id),
            "status": status,
            "httpStatus": http if http is not None and 100 <= http <= 599 else None,
            "modelRequested": self._model_requested,
            "modelActual": _metadata(attempt.model_actual, MAX_MODEL_ACTUAL),
            "provider": _metadata(attempt.provider, MAX_PROVIDER),
            "requestText": attempt.request_text,
            # An unstorable reply (NUL, key shape, over 256 KiB) is not stored; the verdict already says why.
            "responseText": attempt.text if is_storable(attempt.text) else None,
            "tokensIn": attempt.tokens_in,
            "tokensOut": attempt.tokens_out,
            "costUsd": cost_string(attempt.cost_usd),
            "latencyMs": attempt.latency_ms,
        }
        check_record(record)
        return record


def check_record(record: dict[str, Any]) -> None:
    """Contract and storability check of one call record; RunnerDefect when it would be refused."""
    try:
        contract.check("CallRecordRequest", record)
    except contract.ContractError:
        raise RunnerDefect("call record does not match the contract") from None
    for name in ("requestText", "responseText"):
        if not is_storable(record.get(name)):
            raise RunnerDefect(f"{name} cannot be stored")


def outcome_of(records: Sequence[dict[str, Any]]) -> str:
    """The turn outcome from its last call, the rule the API applies."""
    if not records:
        raise RunnerDefect("a turn outcome needs at least one call")
    last = records[-1]["status"]
    if last == OK:
        return "DECISION"
    if last == INVALID:
        return "INVALID"
    return "NO_RESPONSE"


def _check_run(records: Sequence[dict[str, Any]]) -> None:
    if not 1 <= len(records) <= MAX_CALLS:
        raise RunnerDefect("a request carries 1 to 20 calls")
    first = records[0]["attemptNo"]
    for offset, record in enumerate(records):
        check_record(record)
        if record["attemptNo"] != first + offset:
            raise RunnerDefect("attempt numbers must follow one another")


def calls_request(records: Sequence[dict[str, Any]]) -> dict[str, Any]:
    _check_run(records)
    return {"calls": list(records)}


def decision_request(
    records: Sequence[dict[str, Any]], decision: dict[str, Any] | None
) -> dict[str, Any]:
    """The turn's final submit. The reply goes in unchanged, and only for the DECISION outcome."""
    _check_run(records)
    outcome = outcome_of(records)
    if (outcome == "DECISION") != (decision is not None):
        raise RunnerDefect("a decision is present exactly when the outcome is DECISION")
    return {"outcome": outcome, "decision": decision, "calls": list(records)}
