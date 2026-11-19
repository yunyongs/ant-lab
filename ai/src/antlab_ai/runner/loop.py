"""The season loop: read the turn, ask the model, apply the failure table, submit, advance.

The runner never edits a model reply and never computes a price, fill or score: it sends what the
model wrote and lets the API's engine judge it (PLAN 4.16 principle). Every attempt is journaled
right after it is made, so a restart resumes the same turn without a second application.
"""

from __future__ import annotations

import json
import random
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from decimal import Decimal
from functools import cache
from typing import Any, Final, Protocol

from antlab_ai.modelport.port import (
    BudgetRefusal,
    LocalRefusal,
    ModelAttempt,
    ModelPort,
    ModelRequest,
    SubscriptionRefusal,
)
from antlab_ai.openrouter.client import SendCap, SendCapReached
from antlab_ai.runner import failure
from antlab_ai.runner.api_client import ApiProblem, ApiUnavailable
from antlab_ai.runner.config import (
    FLAT_FEE,
    KNOWN,
    SUBSCRIPTION_ROUTES,
    UNKNOWN,
    RunnerConfig,
    default_timeout_ms,
)
from antlab_ai.runner.decision import (
    REPLY_POINTER,
    SCHEMA_PATH,
    Parsed,
    Rejected,
    parse_reply,
)
from antlab_ai.runner.journal import Journal, TurnState
from antlab_ai.runner.prompt import build_messages, reask_messages
from antlab_ai.runner.records import RecordBuilder, RunnerDefect

FINISHED: Final = "FINISHED"
PAUSED: Final = "PAUSED"
STOPPED: Final = "STOPPED"
CALL_CAP: Final = "CALL_CAP"
# Gate refusals that mean "the usage meter says stop or cannot be trusted": the owner resumes later.
USAGE_REFUSALS: Final = frozenset({"USAGE_UNKNOWN", "USAGE_START", "USAGE_STOP"})
# Integrity codes of a decision the API rolled back as a whole: an engine defect, not a model fault.
ENGINE_CODES: Final = frozenset(
    {"LEDGER_MISMATCH", "LINEUP_MISMATCH", "INVALID_FORMATION", "EVENT_TIME_CONFLICT"}
)


class RunnerAbort(Exception):
    """The run cannot go on and nothing is retried: a runner, config or journal defect. The text is a fixed code."""


class StillPaused(RunnerAbort):
    """The journal says the season is paused; run again with the resume option."""


class Api(Protocol):
    def get_turn_view(self, agent_id: int, season_id: int) -> dict[str, Any]: ...
    def flush_calls(
        self, agent_id: int, season_id: int, turn_no: int, calls: Sequence[dict[str, Any]]
    ) -> None: ...
    def submit_decision(
        self,
        agent_id: int,
        season_id: int,
        turn_no: int,
        calls: Sequence[dict[str, Any]],
        decision: dict[str, Any] | None,
    ) -> dict[str, Any]: ...
    def get_decision(self, agent_id: int, season_id: int, turn_no: int) -> dict[str, Any]: ...
    def advance_turn(
        self, agent_id: int, season_id: int, expected_turn_no: int
    ) -> dict[str, Any]: ...


class FlatFeeGate(Protocol):
    """What a flat-fee season needs from its subscription gate before it asks the model."""

    def calls_left(self) -> int: ...


@dataclass(frozen=True)
class RunResult:
    state: str  # FINISHED, PAUSED or STOPPED
    reason: str | None  # pause reason, season status or CALL_CAP
    decisions: int  # turns decided in this run
    sends: int  # model attempts made in this run


@cache
def _output_schema() -> dict[str, Any]:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def _results(turn: TurnState) -> list[failure.Result]:
    out = []
    for record, retry in zip(turn.attempts, turn.retry_after, strict=True):
        status = record["status"]
        out.append(
            failure.Result(
                status,
                retry,
                credit_refusal=status == failure.LIMITED and record.get("httpStatus") == 402,
            )
        )
    return out


def _violations(record: dict[str, Any]) -> tuple[tuple[str, str], ...]:
    """Why a stored reply was rejected, recomputed from its text so a restart builds the same re-ask."""
    verdict = parse_reply(record.get("responseText"))
    if isinstance(verdict, Rejected):
        return verdict.violations
    return ((REPLY_POINTER, "json"),)


def _spent(journal: Journal) -> Decimal:
    total = Decimal(0)
    for turn in journal.state.turns.values():
        for record in turn.attempts:
            if record.get("costUsd") is not None:
                total += Decimal(record["costUsd"])
    return total


class SeasonRunner:
    def __init__(
        self,
        *,
        api: Api,
        journal: Journal,
        port: ModelPort,
        config: RunnerConfig,
        agent_id: int,
        season_id: int,
        sleep: Callable[[float], None] = time.sleep,
        jitter: Callable[[], float] = lambda: random.uniform(0, 0.5),
        send_cap: SendCap | None = None,
        gate: FlatFeeGate | None = None,
    ) -> None:
        if (config.cost_mode == FLAT_FEE) != (gate is not None):
            raise RunnerDefect("a flat-fee season needs a subscription gate, and only it")
        if config.cost_mode == FLAT_FEE and config.route not in SUBSCRIPTION_ROUTES:
            raise RunnerDefect("flat-fee cost is only for a subscription route")
        self._gate = gate
        self._api = api
        self._journal = journal
        self._port = port
        self._cfg = config
        self._agent = agent_id
        self._season = season_id
        self._sleep = sleep
        self._jitter = jitter
        self._cap = send_cap
        self._builder = RecordBuilder(config.route, config.model_requested)
        self._decisions = 0
        self._sends = 0

    # ---- public ---------------------------------------------------------------------------

    def run(self, *, resume: bool = False) -> RunResult:
        state = self._journal.state
        if len(state.pauses) > state.resumes:
            if not resume:
                raise StillPaused(state.pauses[-1][1])
            self._journal.append_resume()
        while True:
            view = self._api.get_turn_view(self._agent, self._season)
            season = view["season"]
            if season["status"] != "IN_PROGRESS":
                final = FINISHED if season["status"] == "FINISHED" else STOPPED
                return self._result(final, season["status"])
            stop = self._play(view, season["currentTurnNo"])
            if stop is not None:
                return stop

    # ---- one turn -------------------------------------------------------------------------

    def _result(self, state: str, reason: str | None) -> RunResult:
        return RunResult(state, reason, self._decisions, self._sends)

    def _play(self, view: dict[str, Any], turn_no: int) -> RunResult | None:
        turn = self._journal.state.turn(turn_no)
        if turn.advanced:
            raise RunnerAbort("JOURNAL_AHEAD")
        if turn.decided is None:
            if turn.attempts:
                # A crash between the submit and its journal row: the API may already hold the decision.
                stored = self._stored_decision(turn_no)
                if stored is not None:
                    self._journal.append_decided(turn_no, stored)
            if turn.decided is None:
                stop = self._decide(view, turn_no, turn)
                if stop is not None:
                    return stop
        if self._journal.state.no_response_streak >= failure.NO_RESPONSE_STREAK_LIMIT:
            return self._pause("NO_RESPONSE_STREAK", turn_no)
        return self._advance(turn_no)

    def _stored_decision(self, turn_no: int) -> dict[str, Any] | None:
        try:
            return self._api.get_decision(self._agent, self._season, turn_no)
        except ApiProblem as problem:
            if problem.status == 404:
                return None
            raise

    def _advance(self, turn_no: int) -> RunResult | None:
        try:
            result = self._api.advance_turn(self._agent, self._season, turn_no)
        except ApiProblem as problem:
            if problem.status == 409 and problem.code == "TURN_MISMATCH":
                # The earlier advance landed and its answer was lost; the next view says where the season is.
                self._journal.append_advanced(turn_no)
                return None
            raise
        self._journal.append_advanced(turn_no)
        if result["status"] == "FINISHED":
            return self._result(FINISHED, "FINISHED")
        return None

    def _decide(self, view: dict[str, Any], turn_no: int, turn: TurnState) -> RunResult | None:
        base = build_messages(self._cfg.persona, view)
        while True:
            step = failure.next_step(_results(turn), barrier=turn.flushed, jitter=self._jitter())
            if isinstance(step, failure.Pause):
                return self._pause(step.reason, turn_no)
            if isinstance(step, failure.Finish):
                return self._finish(turn_no, step.outcome, turn)
            if step.wait_s:
                self._sleep(step.wait_s)
            refusal = self._refusal()
            if refusal is not None:
                return self._pause(refusal, turn_no)
            if self._gate is not None and self._gate.calls_left() < 1:
                return self._result(STOPPED, CALL_CAP)
            if self._cap is not None and not self._cap.take():
                return self._result(STOPPED, CALL_CAP)
            messages = base
            if step.reask:
                first = next(r for r in turn.attempts if r["status"] == failure.INVALID)
                messages = reask_messages(base, _violations(first))
            stop = self._send(turn_no, turn, messages)
            if stop is not None:
                return stop

    def _refusal(self) -> str | None:
        """A pause reason when the season budget forbids another attempt, before anything is sent."""
        cfg = self._cfg
        if self._journal.state.attempt_total >= cfg.budget_calls:
            return "BUDGET"
        if cfg.cost_mode == UNKNOWN:
            return "BUDGET"
        if cfg.cost_mode == KNOWN and _spent(self._journal) > cfg.budget_usd:
            return "BUDGET"
        return None

    def _send(self, turn_no: int, turn: TurnState, messages: Sequence[Any]) -> RunResult | None:
        cfg = self._cfg
        request = ModelRequest(
            model_id=cfg.model_requested,
            messages=tuple(messages),
            max_tokens=cfg.max_tokens,
            temperature=cfg.temperature,
            seed=cfg.seed,
            timeout_ms=min(cfg.turn_timeout_ms, default_timeout_ms(cfg.route)),
            output_schema=_output_schema(),
        )
        try:
            attempt: ModelAttempt = self._port.attempt(request)
        except LocalRefusal:
            return self._pause("LOCAL_CAP", turn_no)
        except BudgetRefusal:
            return self._pause("BUDGET", turn_no)
        except SendCapReached:
            return self._result(STOPPED, CALL_CAP)
        except SubscriptionRefusal as refusal:
            return self._subscription_stop(refusal.code, turn_no)
        self._sends += 1
        verdict = parse_reply(attempt.text) if attempt.status == failure.OK else None
        record = self._builder.build(attempt, len(turn.attempts) + 1, verdict)
        self._journal.append_attempt(turn_no, record, attempt.retry_after_s)
        return None

    def _subscription_stop(self, code: str, turn_no: int) -> RunResult:
        """A gate refusal before a spawn: nothing was sent, so nothing is journaled but the stop."""
        if code == CALL_CAP:
            return self._result(STOPPED, CALL_CAP)
        if code in USAGE_REFUSALS:
            return self._pause("USAGE_GUARD", turn_no)
        return self._pause("SUBSCRIPTION_REFUSED", turn_no)

    def _finish(self, turn_no: int, outcome: str, turn: TurnState) -> RunResult | None:
        records = self._journal.state.unflushed(turn_no)
        decision = None
        if outcome == "DECISION":
            verdict = parse_reply(records[-1]["responseText"])
            if not isinstance(verdict, Parsed):
                raise RunnerDefect("the stored reply no longer parses")
            decision = verdict.decision
        try:
            result = self._api.submit_decision(
                self._agent, self._season, turn_no, records, decision
            )
        except ApiProblem as problem:
            if problem.status == 409 and problem.code in ENGINE_CODES:
                return self._pause("ENGINE_ERROR", turn_no, keep_last=True)
            raise
        self._journal.append_decided(turn_no, result)
        self._decisions += 1
        return None

    def _pause(self, reason: str, turn_no: int, *, keep_last: bool = False) -> RunResult:
        """Store the attempts the API does not hold yet (all but the last when it still has to be final), then stop."""
        turn = self._journal.state.turn(turn_no)
        upto = len(turn.attempts) - (1 if keep_last else 0)
        pending = turn.attempts[turn.flushed : upto]
        if pending:
            try:
                self._api.flush_calls(self._agent, self._season, turn_no, pending)
            except ApiUnavailable:
                # The attempts stay in the journal and go out with the decision.
                pass
            else:
                self._journal.append_flushed(turn_no, upto)
        self._journal.append_pause(reason, turn_no)
        return self._result(PAUSED, reason)
