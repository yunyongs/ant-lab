"""An in-process stand-in for the agent-runner API: a transport for ``ApiClient`` with the reconcile rules.

It follows the rules of the real service that the runner depends on: attempt numbers follow one another,
an identical resend is skipped, a different one under a stored number is ``DECISION_CONFLICT``, a decided
turn answers an identical resubmit with ``replayed = true``, and a turn advances only after a final decision.
Hooks simulate a crash (``Crash``, not caught by anything) or a lost answer at a chosen operation.
"""

import copy
import json
import re
from collections import Counter

from api_helpers import fixture

PREFIX = "/internal/agent-runner/v1/agents"
_ROUTES = (
    ("registerAgent", "POST", re.compile(rf"{PREFIX}$")),
    ("startAgentSeason", "POST", re.compile(rf"{PREFIX}/(\d+)/seasons$")),
    ("getAgentTurnView", "GET", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/turn-view$")),
    ("flushAgentCalls", "POST", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/turns/(\d+)/calls$")),
    (
        "submitAgentDecision",
        "POST",
        re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/turns/(\d+)/decision$"),
    ),
    (
        "getAgentDecision",
        "GET",
        re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/turns/(\d+)/decision$"),
    ),
    ("advanceAgentTurn", "POST", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/turns$")),
)
ENGINE_CODES = ("LEDGER_MISMATCH", "LINEUP_MISMATCH", "INVALID_FORMATION", "EVENT_TIME_CONFLICT")


def _route(method, path):
    for operation, verb, pattern in _ROUTES:
        match = pattern.match(path) if verb == method else None
        if match is not None:
            return operation, match
    raise AssertionError(f"unknown route {method} {path}")


class Crash(BaseException):
    """The runner process dies here. A BaseException, so no ``except Exception`` of the runner sees it."""


def problem(status: int, code: str) -> tuple[int, dict]:
    body = fixture("problem")
    body.update(status=status, code=code)
    return status, body


class FakeSpring:
    def __init__(self, path_length: int = 12, model: str = "vendor/model:free", route="OPENROUTER"):
        self.path_length = path_length
        self.model = model
        self.route = route
        self.status = "IN_PROGRESS"
        self.turn = 0
        self.calls: dict[int, list[dict]] = {}
        self.decided: dict[int, dict] = {}
        self.decision_hash: dict[int, str] = {}
        self.requests: Counter[str] = Counter()
        self.log: list[tuple[str, int | None]] = []
        self.engine_error: str | None = None
        self.crash_before: Counter[str] = Counter()  # operation -> Nth call (1-based) to crash on
        self.crash_after: Counter[str] = Counter()
        self.lose_answer: Counter[str] = Counter()
        self.token = None
        self.fail: dict[str, tuple[int, str]] = {}

    # ---- transport ------------------------------------------------------------------------

    def __call__(self, method, url, headers, body, timeout):
        self.token = headers.get("X-Antlab-Runner-Token")
        path = url[url.index("/internal") :]
        operation, match = _route(method, path)
        self.requests[operation] += 1
        count = self.requests[operation]
        ids = [int(g) for g in match.groups()]
        payload = None if body is None else json.loads(body)
        if self.crash_before[operation] == count:
            raise Crash(operation)
        if operation in self.fail:
            status, answer = problem(*self.fail[operation])
        else:
            status, answer = getattr(self, f"_{operation}")(ids, payload)
        self.log.append((operation, status))
        if self.crash_after[operation] == count:
            raise Crash(operation)
        if self.lose_answer[operation] == count:
            raise OSError("answer lost")
        raw = b"" if answer is None else json.dumps(answer, ensure_ascii=False).encode()
        return status, {"Content-Type": "application/json"}, raw

    # ---- operations -----------------------------------------------------------------------

    def _registerAgent(self, ids, body):  # noqa: N802
        agent = fixture("agent")
        agent.update(modelId=body["modelId"], modelRoute=body["modelRoute"])
        return 201, agent

    def _startAgentSeason(self, ids, body):  # noqa: N802
        if body["config"]["modelRequested"] != self.model:
            return problem(400, "INVALID_ARGUMENT")
        return 201, self._season()

    def _season(self):
        season = fixture("season")
        season.update(currentTurnNo=self.turn, status=self.status, pathLength=self.path_length)
        return season

    def _result(self, turn_no):
        result = fixture("turn_result")
        result.update(turnNo=turn_no, status=self.status, pathLength=self.path_length)
        return result

    def _getAgentTurnView(self, ids, body):  # noqa: N802
        view = fixture("turn_view")
        view["season"] = self._season()
        view["lastTurn"] = None if self.turn == 0 else self._result(self.turn)
        view["holdings"]["turnNo"] = self.turn
        return 200, view

    def _check_turn(self, turn_no):
        if self.status != "IN_PROGRESS":
            return problem(409, "SEASON_NOT_ACTIVE")
        if turn_no != self.turn:
            return problem(409, "TURN_MISMATCH")
        return None

    def _reconcile(self, turn_no, sent, needs_new_last):
        stored = self.calls.setdefault(turn_no, [])
        first = sent[0]["attemptNo"]
        for offset, call in enumerate(sent):
            if call["attemptNo"] != first + offset:
                return None, problem(400, "INVALID_ARGUMENT")
        if first > len(stored) + 1:
            return None, problem(400, "INVALID_ARGUMENT")
        fresh = []
        for call in sent:
            if call["attemptNo"] <= len(stored):
                if stored[call["attemptNo"] - 1] != call:
                    return None, problem(409, "DECISION_CONFLICT")
            else:
                fresh.append(call)
        if needs_new_last and (not fresh or fresh[-1] is not sent[-1]):
            return None, problem(400, "INVALID_ARGUMENT")
        for call in sent:
            if call["modelRequested"] != self.model:
                return None, problem(400, "INVALID_ARGUMENT")
            if self.route == "OPENROUTER" and call.get("ledgerCallId") is None:
                return None, problem(400, "INVALID_ARGUMENT")
        return fresh, None

    def _flushAgentCalls(self, ids, body):  # noqa: N802
        turn_no = ids[2]
        bad = self._check_turn(turn_no)
        if bad:
            return bad
        fresh, error = self._reconcile(turn_no, body["calls"], False)
        if error:
            return error
        if turn_no in self.decided and fresh:
            return problem(409, "DECISION_CONFLICT")
        self.calls[turn_no].extend(copy.deepcopy(fresh))
        return 204, None

    def _submitAgentDecision(self, ids, body):  # noqa: N802
        turn_no = ids[2]
        digest = json.dumps(body, sort_keys=True)
        if turn_no in self.decided:
            if self.decision_hash[turn_no] != digest:
                return problem(409, "DECISION_CONFLICT")
            return 200, {**self.decided[turn_no], "replayed": True}
        bad = self._check_turn(turn_no)
        if bad:
            return bad
        last = body["calls"][-1]["status"]
        fits = {
            "DECISION": last == "OK",
            "INVALID": last in ("OK", "INVALID"),
            "NO_RESPONSE": last not in ("OK", "INVALID"),
        }[body["outcome"]]
        if not fits or (body["outcome"] == "DECISION") != (body["decision"] is not None):
            return problem(400, "INVALID_ARGUMENT")
        fresh, error = self._reconcile(turn_no, body["calls"], True)
        if error:
            return error
        if self.engine_error:
            return problem(409, self.engine_error)
        self.calls[turn_no].extend(copy.deepcopy(fresh))
        result = self._decision_result(turn_no, body)
        self.decided[turn_no] = result
        self.decision_hash[turn_no] = digest
        return 200, {**result, "replayed": False}

    def _decision_result(self, turn_no, body):
        decision = body["decision"] or {}
        change = decision.get("formation_change")
        formation = {"status": "NONE", "code": None, "formationChangeId": None}
        if change is not None:
            if turn_no == 0:
                formation = {
                    "status": "REJECTED",
                    "code": "TURN_ZERO_FORMATION",
                    "formationChangeId": None,
                }
            else:
                formation = {"status": "APPLIED", "code": None, "formationChangeId": 1}
        orders = []
        for index, order in enumerate(decision.get("orders", [])):
            if order.get("quantity", 0) <= 0:
                orders.append(
                    {
                        "index": index,
                        "status": "REJECTED",
                        "code": "QUANTITY_INVALID",
                        "orderId": None,
                    }
                )
            else:
                orders.append(
                    {"index": index, "status": "APPLIED", "code": None, "orderId": 100 + index}
                )
        return {
            "seasonId": 7,
            "turnNo": turn_no,
            "outcome": body["outcome"],
            "replayed": False,
            "formationChange": formation,
            "orders": orders,
            "theses": {"status": "NONE", "code": None},
            "schemaViolations": [],
        }

    def _getAgentDecision(self, ids, body):  # noqa: N802
        turn_no = ids[2]
        if turn_no > self.turn:
            return problem(409, "TURN_NOT_REACHED")
        if turn_no not in self.decided:
            return problem(404, "NOT_FOUND")
        return 200, {**self.decided[turn_no], "replayed": True}

    def _advanceAgentTurn(self, ids, body):  # noqa: N802
        if self.status != "IN_PROGRESS":
            return problem(409, "SEASON_NOT_ACTIVE")
        if body["expectedTurnNo"] != self.turn:
            return problem(409, "TURN_MISMATCH")
        if self.turn not in self.decided:
            return problem(409, "DECISION_MISSING")
        self.turn += 1
        if self.turn >= self.path_length:
            self.status = "FINISHED"
        return 201, self._result(self.turn)

    # ---- inspection -----------------------------------------------------------------------

    def stored(self, turn_no: int) -> list[dict]:
        return self.calls.get(turn_no, [])

    def outcome(self, turn_no: int) -> str:
        return self.decided[turn_no]["outcome"]
