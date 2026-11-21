"""The memory step of one finished season: reflection, operating-note rewrite, one submit.

Order (PLAN 4.16): reflection call, Python leak pre-check, one re-ask, then the playbook rewrite
from the base note and the new reflection, pre-check, one re-ask, then one submit. The API filter is
authoritative: a 422 marks the submitted reply as rejected and the step goes on with what is left
of its two tries. Every attempt is journaled right away, so a restart never repeats a finished
step. Nothing of a rejected reply goes into a re-ask, the journal or the console, only fixed
pointers and codes.
"""

from __future__ import annotations

import json
import random
import re
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from decimal import Decimal
from functools import cache
from typing import Any, Final, Protocol

from antlab_ai.memory.messages import playbook_messages, reask_messages, reflection_messages
from antlab_ai.memory.replies import (
    NOTE_SCHEMA_PATH,
    REFLECTION_SCHEMA_PATH,
    SCHEMA_CODE,
    Pairs,
    Verdict,
    discard_codes,
    judge_note,
    judge_reflection,
    nfc_tree,
)
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
from antlab_ai.runner.config import KNOWN, UNKNOWN, RunnerConfig, default_timeout_ms
from antlab_ai.runner.journal import Journal
from antlab_ai.runner.records import MAX_CALLS, RecordBuilder, RunnerDefect

ACCEPTED: Final = "ACCEPTED"
DISCARDED: Final = "DISCARDED"
DONE: Final = "DONE"  # the API already holds an outcome for this season
PENDING: Final = "PENDING"  # stopped without an outcome; run again later
NOT_OFFERED: Final = "NOT_OFFERED"  # the API does not offer this season now
STOPPED: Final = "STOPPED"  # the send cap of the command was reached
CALL_CAP: Final = "CALL_CAP"
NOTE_SCHEMA_ID: Final = "operating-note/1"
_YEAR_MONTH: Final = re.compile(r"(?<!\d)(19|20)\d{2}[-/]\d{2}")
# Refusals of the reflection view and of the submit that mean "not now"; the code is fixed text.
NOT_NOW: Final = frozenset(
    {
        "SEASON_NOT_FINISHED",
        "MEMORY_NOT_ELIGIBLE",
        "LEAGUE_OPEN",
        "MEMORY_ORDER",
        "POOL_NOT_LOADED",
    }
)


class Api(Protocol):
    def get_reflection_view(self, agent_id: int, season_id: int) -> dict[str, Any]: ...
    def submit_season_memory(
        self, agent_id: int, season_id: int, body: dict[str, Any]
    ) -> dict[str, Any]: ...


@dataclass(frozen=True)
class StepResult:
    state: str
    reason: str | None = None
    version: int | None = None
    sends: int = 0
    dropped: int = 0  # playbook sends that were cleared and so are not in the submit


@dataclass(frozen=True)
class _Accepted:
    doc: dict[str, Any]


@dataclass(frozen=True)
class _Failed:
    violations: Pairs


@dataclass(frozen=True)
class _Stopped:
    result: StepResult


@cache
def _schema(path_name: str) -> dict[str, Any]:
    path = REFLECTION_SCHEMA_PATH if path_name == "reflection" else NOTE_SCHEMA_PATH
    return json.loads(path.read_text(encoding="utf-8"))


def _result_of(record: dict[str, Any], retry: int | None) -> failure.Result:
    status = record["status"]
    return failure.Result(
        status, retry, credit_refusal=status == failure.LIMITED and record.get("httpStatus") == 402
    )


class MemoryStep:
    def __init__(
        self,
        *,
        api: Api,
        journal: Journal,
        port: ModelPort,
        config: RunnerConfig,
        agent_id: int,
        season_id: int,
        terms: Any = None,
        sleep: Callable[[float], None] = time.sleep,
        jitter: Callable[[], float] = lambda: random.uniform(0, 0.5),
        send_cap: SendCap | None = None,
        discard_on_budget: bool = False,
    ) -> None:
        self._api = api
        self._journal = journal
        self._port = port
        self._cfg = config
        self._agent = agent_id
        self._season = season_id
        self._terms = terms
        self._sleep = sleep
        self._jitter = jitter
        self._cap = send_cap
        self._discard_on_budget = discard_on_budget
        self._builder = RecordBuilder(config.route, config.model_requested)
        self._sends = 0

    # ---- public ---------------------------------------------------------------------------

    def run(self) -> StepResult:
        memory = self._journal.state.memory
        if memory.done is not None:
            return self._result(DONE)
        try:
            view = self._api.get_reflection_view(self._agent, self._season)
        except ApiProblem as problem:
            return self._refused_view(problem)
        if not memory.based or memory.base != view["baseVersion"]:
            self._journal.append_memory_base(view["baseVersion"])
        if any(turn["reason"] and _YEAR_MONTH.search(turn["reason"]) for turn in view["turns"]):
            # The agent's own reason holds a year-month: stored history nobody can edit, so no safe reflection exists.
            return self._submit_discard(["YEAR_MONTH"])
        first = reflection_messages(self._cfg.persona, view)  # any other guard trip stops the run
        while True:
            reflection = self._phase("REFLECTION", first, judge_reflection)
            if isinstance(reflection, _Stopped):
                return self._stopped(reflection.result)
            if isinstance(reflection, _Failed):
                return self._submit_discard(discard_codes(reflection.violations))
            doc = reflection.doc
            note = self._phase(
                "PLAYBOOK",
                playbook_messages(self._cfg.persona, view["basePlaybook"], doc),
                judge_note,
            )
            if isinstance(note, _Stopped):
                return self._stopped(note.result)
            carried = isinstance(note, _Failed)
            if carried:
                carry = self._carried(view, doc)
                if isinstance(carry, StepResult):
                    return carry
                note_doc = carry
            else:
                note_doc = note.doc
            answered = self._submit_accepted(view["baseVersion"], doc, note_doc, carried)
            if answered is not None:
                return answered

    # ---- phases ---------------------------------------------------------------------------

    def _phase(
        self,
        step: str,
        base: tuple[Any, ...],
        judge: Callable[..., Verdict],
    ) -> _Accepted | _Failed | _Stopped:
        memory = self._journal.state.memory
        while True:
            results = [
                _result_of(record, retry)
                for record, retry in zip(
                    memory.attempts[step], memory.retry_after[step], strict=True
                )
            ]
            plan = failure.next_step(results, barrier=memory.barrier[step], jitter=self._jitter())
            if isinstance(plan, failure.Pause):
                return _Stopped(self._pending(plan.reason))
            if isinstance(plan, failure.Finish):
                if plan.outcome == "NO_RESPONSE":
                    return _Stopped(self._pending("NO_RESPONSE"))
                if plan.outcome == failure.INVALID:
                    return _Failed(tuple(memory.violations[step][-1]))
                verdict = judge(memory.attempts[step][-1].get("responseText"), self._terms)
                if verdict.ok:
                    return _Accepted(verdict.doc)
                # The pre-check no longer agrees with an accepted reply (the terms file changed).
                self._journal.append_memory_reject(step, verdict.violations)
                continue
            if plan.wait_s:
                self._sleep(plan.wait_s)
            refusal = self._refusal()
            if refusal is not None:
                return _Stopped(self._pending(refusal))
            if self._cap is not None and not self._cap.take():
                return _Stopped(self._result(STOPPED, CALL_CAP))
            messages = base
            if plan.reask:
                first = next(
                    v
                    for r, v in zip(memory.attempts[step], memory.violations[step], strict=True)
                    if r["status"] == failure.INVALID
                )
                messages = reask_messages(base, first)
            stop = self._send(step, messages, judge)
            if stop is not None:
                return _Stopped(stop)

    def _refusal(self) -> str | None:
        cfg = self._cfg
        state = self._journal.state
        if state.attempt_total + state.memory.sent >= cfg.budget_calls:
            return "BUDGET"
        if cfg.cost_mode == UNKNOWN:
            return "BUDGET"
        if cfg.cost_mode == KNOWN and self._spent() > cfg.budget_usd:
            return "BUDGET"
        return None

    def _spent(self) -> Decimal:
        state = self._journal.state
        records = [r for turn in state.turns.values() for r in turn.attempts]
        records += [r for step in state.memory.attempts.values() for r in step]
        return sum(
            (Decimal(r["costUsd"]) for r in records if r.get("costUsd") is not None), Decimal(0)
        )

    def _send(
        self, step: str, messages: Sequence[Any], judge: Callable[..., Verdict]
    ) -> StepResult | None:
        cfg = self._cfg
        schema = _schema("reflection" if step == "REFLECTION" else "note")
        request = ModelRequest(
            model_id=cfg.model_requested,
            messages=tuple(messages),
            max_tokens=cfg.max_tokens,
            temperature=cfg.temperature,
            seed=cfg.seed,
            timeout_ms=min(cfg.turn_timeout_ms, default_timeout_ms(cfg.route)),
            output_schema=schema,
        )
        try:
            attempt: ModelAttempt = self._port.attempt(request)
        except LocalRefusal:
            return self._pending("LOCAL_CAP")
        except BudgetRefusal:
            return self._pending("BUDGET")
        except SendCapReached:
            return self._result(STOPPED, CALL_CAP)
        except SubscriptionRefusal:
            return self._pending("SUBSCRIPTION_REFUSED")
        self._sends += 1
        memory = self._journal.state.memory
        verdict = judge(attempt.text, self._terms) if attempt.status == failure.OK else None
        if verdict is None:
            status, violations = attempt.status, ()
        else:
            status, violations = (failure.OK if verdict.ok else failure.INVALID), verdict.violations
        record = self._builder.build_as(attempt, len(memory.attempts[step]) + 1, status, step)
        if status == failure.INVALID:
            # A reply the filter or the shape check refused is never stored, so no leaked text is kept.
            record["responseText"] = None
        self._journal.append_memory_attempt(step, record, violations, attempt.retry_after_s)
        return None

    # ---- outcomes -------------------------------------------------------------------------

    def _carried(self, view: dict[str, Any], doc: dict[str, Any]) -> dict[str, Any] | StepResult:
        """The rewrite failed twice: the previous rules stay (the reflection's candidates for the first version)."""
        rules = (
            list(view["basePlaybook"])
            if view["baseVersion"] is not None
            else list(doc["ruleCandidates"])
        )
        note = {"schema": NOTE_SCHEMA_ID, "rules": rules}
        if not rules:
            return self._submit_discard([SCHEMA_CODE])
        verdict = judge_note(json.dumps(note, ensure_ascii=False), self._terms)
        if not verdict.ok:
            return self._submit_discard(discard_codes(verdict.violations))
        return nfc_tree(note)

    def _calls(self) -> list[dict[str, Any]]:
        memory = self._journal.state.memory
        calls = [
            {**record, "responseText": None} if record["status"] == failure.INVALID else record
            for record in (*memory.attempts["REFLECTION"], *memory.attempts["PLAYBOOK"])
        ]
        if len(calls) > MAX_CALLS:
            raise RunnerDefect("a memory step carries at most 20 calls")
        return calls

    def _submit_discard(self, codes: list[str]) -> StepResult:
        body = {
            "baseVersion": None,
            "outcome": DISCARDED,
            "discardCodes": codes,
            "calls": self._calls(),
        }
        answered = self._send_body(body)
        if answered is None:
            raise RunnerDefect("a discard is never refused for its documents")
        return answered

    def _submit_accepted(
        self, base: int | None, reflection: dict[str, Any], note: dict[str, Any], carried: bool
    ) -> StepResult | None:
        """The step's result, or None when the API refused a document and the loop must go on."""
        body = {
            "baseVersion": base,
            "outcome": ACCEPTED,
            "reflection": reflection,
            "playbook": note,
            "playbookCarried": carried,
            "calls": self._calls(),
        }
        return self._send_body(body, carried=carried)

    def _send_body(self, body: dict[str, Any], *, carried: bool = False) -> StepResult | None:
        try:
            answer = self._api.submit_season_memory(self._agent, self._season, body)
        except ApiUnavailable:
            return self._pending("API_UNAVAILABLE")
        except ApiProblem as problem:
            return self._refused_submit(problem, body["outcome"], carried)
        self._journal.append_memory_done(answer["outcome"], answer["version"])
        return self._result(answer["outcome"], None, answer["version"])

    def _refused_submit(
        self, problem: ApiProblem, outcome: str, carried: bool
    ) -> StepResult | None:
        if problem.status == 409 and problem.code == "MEMORY_DONE":
            self._journal.append_memory_done("UNKNOWN", None)
            return self._result(DONE)
        if problem.status == 409 and problem.code in NOT_NOW | {"MEMORY_BASE_STALE"}:
            return self._pending(problem.code)
        if problem.status == 422 and outcome == ACCEPTED:
            return self._reject_submitted(problem, carried)
        raise problem

    def _reject_submitted(self, problem: ApiProblem, carried: bool) -> StepResult | None:
        """The API refused a document the pre-check let through: count it as that reply's failure."""
        found = {"REFLECTION": [], "PLAYBOOK": []}
        for pointer, word in problem.details:
            for step, prefix in (("REFLECTION", "/reflection"), ("PLAYBOOK", "/playbook")):
                if pointer == prefix or pointer.startswith((prefix + "/", prefix + "#")):
                    found[step].append((pointer[len(prefix) :] or "/", word))
        if found["REFLECTION"]:
            self._journal.append_memory_reject("REFLECTION", tuple(found["REFLECTION"][:20]))
            return None
        if found["PLAYBOOK"] and carried:
            return self._submit_discard(discard_codes(tuple(found["PLAYBOOK"])))
        if found["PLAYBOOK"]:
            self._journal.append_memory_reject("PLAYBOOK", tuple(found["PLAYBOOK"][:20]))
            return None
        return self._pending(problem.code or "REJECTED")

    def _refused_view(self, problem: ApiProblem) -> StepResult:
        if problem.status == 409 and problem.code == "MEMORY_DONE":
            self._journal.append_memory_done("UNKNOWN", None)
            return self._result(DONE)
        if problem.status == 409 and problem.code in NOT_NOW:
            return self._result(NOT_OFFERED, problem.code)
        if problem.status == 503 and problem.code == "SCORE_PENDING":
            return self._result(PENDING, "SCORE_PENDING")
        raise problem

    # ---- stops ----------------------------------------------------------------------------

    def _result(
        self, state: str, reason: str | None = None, version: int | None = None
    ) -> StepResult:
        memory = self._journal.state.memory
        held = sum(len(attempts) for attempts in memory.attempts.values())
        return StepResult(state, reason, version, self._sends, max(0, memory.sent - held))

    def _pending(self, reason: str) -> StepResult:
        return self._result(PENDING, reason)

    def _stopped(self, result: StepResult) -> StepResult:
        """Record a stop so a restart does not repeat the same refusal; the owner may turn a budget stop into a discard."""
        if result.state == PENDING and result.reason is not None:
            self._journal.append_memory_stop(result.reason)
            if result.reason == "BUDGET" and self._discard_on_budget:
                return self._submit_discard(["BUDGET"])
        return result
