"""The failure table of one turn as a pure function: what to do after the attempts made so far.

A logical request is the original prompt or the single re-ask. Provider failures are retried or
waited on within a logical request; a reply that fails the shape check earns exactly one re-ask.
Nothing here sends, sleeps or writes: the loop acts on the step it is given.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Final

from antlab_ai.openrouter.retry import Retry, decide

OK: Final = "OK"
INVALID: Final = "INVALID"
UPSTREAM: Final = "UPSTREAM"
TIMEOUT: Final = "TIMEOUT"
LIMITED: Final = "LIMITED"
POLICY_REFUSED: Final = "POLICY_REFUSED"
ERROR: Final = "ERROR"

# Two logical requests, each with up to 3 transport tries and 3 limit waits.
MAX_ATTEMPTS_PER_TURN: Final = 12
NO_RESPONSE_STREAK_LIMIT: Final = 3


@dataclass(frozen=True)
class Result:
    """One attempt as the table sees it: INVALID is an OK reply the runner rejected."""

    status: str
    retry_after_s: int | None = None
    credit_refusal: bool = False


@dataclass(frozen=True)
class Send:
    """Send the next attempt, after waiting ``wait_s``; ``reask`` adds the fixed note to the prompt."""

    wait_s: float = 0.0
    reask: bool = False


@dataclass(frozen=True)
class Finish:
    outcome: str  # DECISION, INVALID or NO_RESPONSE


@dataclass(frozen=True)
class Pause:
    reason: str


Step = Send | Finish | Pause


def _logical_cap(step: Step, history: Sequence[Result], barrier: int) -> Step:
    # After a pause everything is stored already: a Finish would have no new final call to send.
    if isinstance(step, Send) and barrier < len(history) >= MAX_ATTEMPTS_PER_TURN:
        return Finish(INVALID if history[-1].status == INVALID else "NO_RESPONSE")
    return step


def next_step(history: Sequence[Result], *, barrier: int = 0, jitter: float = 0.0) -> Step:
    """The step after ``history`` (the attempts of this turn, in order).

    ``barrier`` is the number of attempts a pause had already handled: counters of the current
    logical request restart there, and a refusal at the barrier is retried rather than paused on again.
    """
    reask = False
    transport = 0
    limited = 0
    for index, result in enumerate(history):
        if index == barrier:
            transport = limited = 0
        if index == len(history) - 1 and index >= barrier:
            return _logical_cap(_after(result, reask, transport, limited, jitter), history, barrier)
        if result.status == INVALID:
            reask, transport, limited = True, 0, 0
        elif result.status in (UPSTREAM, TIMEOUT):
            transport += 1
        elif result.status == LIMITED:
            limited += 1
    return _logical_cap(Send(reask=reask), history, barrier)


def _after(result: Result, reask: bool, transport: int, limited: int, jitter: float) -> Step:
    status = result.status
    if status == OK:
        return Finish("DECISION")
    if status == INVALID:
        return Finish(INVALID) if reask else Send(reask=True)
    if status in (UPSTREAM, TIMEOUT):
        step = decide(status, transport + 1, None, jitter)
        return Send(step.delay_s, reask) if isinstance(step, Retry) else Finish("NO_RESPONSE")
    if status == LIMITED:
        if result.credit_refusal:
            return Pause("CREDIT")
        step = decide(LIMITED, limited + 1, result.retry_after_s)
        return Send(step.delay_s, reask) if isinstance(step, Retry) else Pause("LIMITED_DAILY")
    if status == POLICY_REFUSED:
        return Pause("POLICY")
    return Pause("CONFIG")
