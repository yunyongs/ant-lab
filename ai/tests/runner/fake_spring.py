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

from api_helpers import REPO_ROOT, fixture
from jsonschema import Draft202012Validator

from antlab_ai.memory.filter import check_note, check_reflection, load_terms
from antlab_ai.runner.persona import load_persona
from antlab_ai.runner.prompt import PROMPT_TEMPLATE_VERSION

PREFIX = "/internal/agent-runner/v1/agents"
LEAGUES = "/internal/agent-runner/v1/leagues"
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
    ("getAgentSeasonConfig", "GET", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/config$")),
    ("createLeague", "POST", re.compile(rf"{LEAGUES}$")),
    ("getLeague", "GET", re.compile(rf"{LEAGUES}/(\d+)$")),
    ("closeLeague", "POST", re.compile(rf"{LEAGUES}/(\d+)/close$")),
    ("getAgentMemory", "GET", re.compile(rf"{PREFIX}/(\d+)/memory$")),
    ("getMemoryComparison", "GET", re.compile(rf"{PREFIX}/(\d+)/memory/comparison$")),
    ("getAgentMemoryVersion", "GET", re.compile(rf"{PREFIX}/(\d+)/memory/(\d+)$")),
    ("getReflectionView", "GET", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/reflection-view$")),
    ("submitSeasonMemory", "POST", re.compile(rf"{PREFIX}/(\d+)/seasons/(\d+)/memory$")),
)
SCHEMAS = {
    "reflection": REPO_ROOT / "docs/api/season-reflection-1.schema.json",
    "note": REPO_ROOT / "docs/api/operating-note-1.schema.json",
}
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
        self.agents: dict[int, dict] = {}
        self.configs: dict[int, dict] = {}
        self.config_override: dict = {}  # fields of the recorded config to change, for mismatch tests
        self.leagues: dict[int, dict] = {}
        self.season_limit_agents: set[int] = set()  # agents whose start answers SEASON_LIMIT
        self.next_league_season = 100
        self.started_with: list[dict] = []  # request bodies of every startAgentSeason
        # AG-05 memory: versions are {"rules", "reflection", "carried", "source"}, oldest first.
        self.memory_versions: list[dict] = []
        # season id -> pending, held, done, void, abandoned or unfinished; pending ones are in finish order.
        self.memory_seasons: dict[int, str] = {}
        self.memory_submits: list[dict] = []
        self.memory_outcomes: dict[int, dict] = {}
        self.memory_forced: list[tuple[int, str, dict]] = []  # answers for the next submits
        self.score_pending = False
        self.event_titles_loaded = True
        self.comparison: dict | None = None
        self.last_query = ""
        self.reflection_view: dict | None = None  # replaces the fixture view when set
        self.created_with: list[dict] = []  # request bodies of every createLeague
        self._memory_terms = load_terms()

    # ---- transport ------------------------------------------------------------------------

    def __call__(self, method, url, headers, body, timeout):
        self.token = headers.get("X-Antlab-Runner-Token")
        path, _, self.last_query = url[url.index("/internal") :].partition("?")
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
        key = (body["personaKey"], body["modelRoute"], body["modelId"])
        for agent in self.agents.values():
            if (agent["personaKey"], agent["modelRoute"], agent["modelId"]) == key:
                return 200, copy.deepcopy(agent)
        agent = fixture("agent")
        agent.update(
            agentId=11 + len(self.agents),
            personaKey=body["personaKey"],
            displayName=body["displayName"],
            modelId=body["modelId"],
            modelRoute=body["modelRoute"],
        )
        self.agents[agent["agentId"]] = agent
        return 201, copy.deepcopy(agent)

    def _startAgentSeason(self, ids, body):  # noqa: N802
        self.started_with.append(body)
        agent = self.agents.get(ids[0])
        expected_model = self.model if agent is None else agent["modelId"]
        if body["config"]["modelRequested"] != expected_model:
            return problem(400, "INVALID_ARGUMENT")
        league_id = body.get("leagueId")
        season = self._season()
        if ids[0] in self.season_limit_agents:  # the real service checks limits before the seat
            return problem(409, "SEASON_LIMIT")
        if league_id is not None:
            league = self.leagues.get(league_id)
            if league is None:
                return problem(404, "NOT_FOUND")
            if "pathLength" in body and body["pathLength"] != league["pathLength"]:
                return problem(400, "INVALID_ARGUMENT")
            if league["status"] == "CLOSED":
                return problem(409, "LEAGUE_CLOSED")
            if any(e["memberId"] == ids[0] for e in league["entries"]):
                return problem(409, "LEAGUE_SEAT_TAKEN")
            if len(league["entries"]) >= league["maxEntries"]:
                return problem(409, "LEAGUE_FULL")
            self.next_league_season += 1
            season.update(id=self.next_league_season, pathLength=league["pathLength"])
            league["entries"].append(
                {
                    "memberId": ids[0],
                    "memberKind": "AGENT",
                    "displayName": "agent",
                    "seasonId": season["id"],
                    "status": "IN_PROGRESS",
                    "currentTurnNo": 0,
                }
            )
        persona_key = "patient-value" if agent is None else agent["personaKey"]
        self.configs[season["id"]] = self._recorded(body["config"], persona_key, agent)
        return 201, season

    def _recorded(self, config, persona_key, agent):
        return {
            "personaKey": persona_key,
            "personaVersion": config["personaVersion"],
            "personaSha256": config["personaSha256"],
            "modelRoute": self.route if agent is None else agent["modelRoute"],
            "modelRequested": config["modelRequested"],
            "promptTemplateVersion": config["promptTemplateVersion"],
            "runnerVersion": config["runnerVersion"],
            "memoryVersion": config["memoryVersion"],
            "temperature": config["temperature"],
            "modelSeed": config["modelSeed"],
            "maxTokens": config["maxTokens"],
            "turnTimeoutMs": config["turnTimeoutMs"],
            "budgetCalls": config["budgetCalls"],
            "budgetUsd": config["budgetUsd"],
        }

    def _getAgentSeasonConfig(self, ids, body):  # noqa: N802
        config = self.configs.get(ids[1])
        if config is None:
            persona = load_persona("patient-value")
            config = {
                "personaKey": persona.key,
                "personaVersion": persona.version,
                "personaSha256": persona.sha256,
                "modelRoute": self.route,
                "modelRequested": self.model,
                "promptTemplateVersion": PROMPT_TEMPLATE_VERSION,
                "runnerVersion": "0.0.1",
                "memoryVersion": None,
                "temperature": "0",
                "modelSeed": 1,
                "maxTokens": 2048,
                "turnTimeoutMs": 120000,
                "budgetCalls": 36,
                "budgetUsd": "0",
            }
        return 200, {**config, **self.config_override}

    def _createLeague(self, ids, body):  # noqa: N802
        self.created_with.append(body)
        league_id = 5 + len(self.leagues)
        league = {
            "id": league_id,
            "name": body["name"],
            "status": "OPEN",
            "pathLength": body.get("pathLength", 12),
            "maxEntries": body.get("maxEntries", 8),
            "entries": [],
        }
        self.leagues[league_id] = league
        return 201, copy.deepcopy(league)

    def _getLeague(self, ids, body):  # noqa: N802
        league = self.leagues.get(ids[0])
        return (200, copy.deepcopy(league)) if league else problem(404, "NOT_FOUND")

    def _closeLeague(self, ids, body):  # noqa: N802
        league = self.leagues.get(ids[0])
        if league is None:
            return problem(404, "NOT_FOUND")
        if any(e["status"] not in ("FINISHED", "VOID") for e in league["entries"]):
            return problem(409, "LEAGUE_NOT_SETTLED")
        league["status"] = "CLOSED"
        return 200, copy.deepcopy(league)

    def _season(self):
        season = fixture("season")
        season.update(currentTurnNo=self.turn, status=self.status, pathLength=self.path_length)
        return season

    def _result(self, turn_no):
        result = fixture("turn_result")
        result.update(turnNo=turn_no, status=self.status, pathLength=self.path_length)
        for position, item in enumerate(result["feed"] + result["alerts"], start=1):
            item.update(turnNo=turn_no, seq=position)
        return result

    def _getAgentTurnView(self, ids, body):  # noqa: N802
        view = fixture("turn_view")
        view["season"] = self._season()
        view["lastTurn"] = None if self.turn == 0 else self._result(self.turn)
        view["holdings"]["turnNo"] = self.turn
        pinned = self.configs.get(ids[1], {}).get("memoryVersion")
        view["agent"]["memory"] = None if pinned is None else self._memory_dto(pinned)
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

    # ---- memory (AG-05) -------------------------------------------------------------------

    def _memory_dto(self, version):
        first = max(1, version - 2)
        return {
            "version": version,
            "playbook": list(self.memory_versions[version - 1]["rules"]),
            "reflections": [
                copy.deepcopy(self.memory_versions[n - 1]["reflection"])
                for n in range(first, version + 1)
            ],
        }

    def _getAgentMemory(self, ids, body):  # noqa: N802
        pending = [s for s, state in self.memory_seasons.items() if state == "pending"]
        held = sum(1 for state in self.memory_seasons.values() if state == "held")
        latest = len(self.memory_versions) or None
        return 200, {"latestVersion": latest, "pendingSeasonIds": pending, "heldSeasonCount": held}

    def _getAgentMemoryVersion(self, ids, body):  # noqa: N802
        version = ids[1]
        if not 1 <= version <= len(self.memory_versions):
            return problem(404, "NOT_FOUND")
        return 200, {
            "memory": self._memory_dto(version),
            "playbookCarried": self.memory_versions[version - 1]["carried"],
            "createdAt": "2026-11-20T00:00:00Z",
        }

    def _getMemoryComparison(self, ids, body):  # noqa: N802
        return 200, copy.deepcopy(self.comparison)

    def _memory_gate(self, season_id):
        state = self.memory_seasons.get(season_id)
        if state is None:
            return problem(404, "NOT_FOUND")
        pending = [s for s, st in self.memory_seasons.items() if st == "pending"]
        codes = {
            "unfinished": "SEASON_NOT_FINISHED",
            "void": "MEMORY_NOT_ELIGIBLE",
            "abandoned": "MEMORY_NOT_ELIGIBLE",
            "done": "MEMORY_DONE",
            "held": "LEAGUE_OPEN",
        }
        if state in codes:
            return problem(409, codes[state])
        if pending[0] != season_id:
            return problem(409, "MEMORY_ORDER")
        return None

    def _getReflectionView(self, ids, body):  # noqa: N802
        bad = self._memory_gate(ids[1])
        if bad:
            return bad
        if self.score_pending:
            return problem(503, "SCORE_PENDING")
        view = (
            copy.deepcopy(self.reflection_view)
            if self.reflection_view
            else fixture("reflection_view")
        )
        if self.memory_versions:
            view["baseVersion"] = len(self.memory_versions)
            view["basePlaybook"] = list(self.memory_versions[-1]["rules"])
        return 200, view

    def _submitSeasonMemory(self, ids, body):  # noqa: N802
        season = ids[1]
        self.memory_submits.append(copy.deepcopy(body))
        bad = self._memory_gate(season)
        if bad:
            return bad
        error = self._memory_envelope(body)
        if error:
            return error
        if self.memory_forced:
            status, code, extra = self.memory_forced.pop(0)
            status, answer = problem(status, code)
            return status, {**answer, **extra}
        if not self.event_titles_loaded and body["outcome"] == "ACCEPTED":
            return problem(409, "POOL_NOT_LOADED")
        if body["outcome"] == "DISCARDED":
            self.memory_seasons[season] = "done"
            self.memory_outcomes[season] = {"outcome": "DISCARDED", "codes": body["discardCodes"]}
            return 201, {"outcome": "DISCARDED", "version": None}
        latest = len(self.memory_versions) or None
        if body["baseVersion"] != latest:
            return problem(409, "MEMORY_BASE_STALE")
        rejected = self._memory_refused(body)
        if rejected:
            return rejected
        self.memory_versions.append(
            {
                "rules": list(body["playbook"]["rules"]),
                "reflection": copy.deepcopy(body["reflection"]),
                "carried": body["playbookCarried"],
                "source": season,
            }
        )
        self.memory_seasons[season] = "done"
        self.memory_outcomes[season] = {"outcome": "ACCEPTED"}
        return 201, {"outcome": "ACCEPTED", "version": len(self.memory_versions)}

    def _memory_envelope(self, body):
        accepted = body["outcome"] == "ACCEPTED"
        if accepted and not (body.get("reflection") and body.get("playbook")):
            return problem(400, "INVALID_ARGUMENT")
        if not accepted and (not body.get("discardCodes") or body.get("baseVersion") is not None):
            return problem(400, "INVALID_ARGUMENT")
        if not accepted and (
            body.get("reflection") or body.get("playbook") or body.get("playbookCarried")
        ):
            return problem(400, "INVALID_ARGUMENT")
        seen: Counter[str] = Counter()
        for call in body["calls"]:
            seen[call["kind"]] += 1
            if (
                call["kind"] not in ("REFLECTION", "PLAYBOOK")
                or call["attemptNo"] != seen[call["kind"]]
            ):
                return problem(400, "INVALID_ARGUMENT")
            if call["modelRequested"] != self.model:
                return problem(400, "INVALID_ARGUMENT")
            if self.route == "OPENROUTER" and call.get("ledgerCallId") is None:
                return problem(400, "INVALID_ARGUMENT")
        if accepted and not seen["REFLECTION"]:
            return problem(400, "INVALID_ARGUMENT")
        return None

    def _memory_refused(self, body):
        violations = []
        for name, key in (("reflection", "reflection"), ("playbook", "note")):
            validator = Draft202012Validator(json.loads(SCHEMAS[key].read_text(encoding="utf-8")))
            violations += [
                {"pointer": f"/{name}", "keyword": str(e.validator)}
                for e in validator.iter_errors(body[name])
            ]
        if violations:
            status, answer = problem(422, "MEMORY_SCHEMA_INVALID")
            return status, {**answer, "violations": violations}
        leaks = [
            {"pointer": "/reflection" + hit.pointer, "code": hit.code}
            for hit in check_reflection(body["reflection"], self._memory_terms).hits
        ]
        leaks += [
            {"pointer": "/playbook" + hit.pointer, "code": hit.code}
            for hit in check_note(body["playbook"], self._memory_terms).hits
        ]
        if leaks:
            status, answer = problem(422, "MEMORY_LEAK")
            return status, {**answer, "leaks": leaks}
        if body["playbookCarried"]:
            previous = (
                self.memory_versions[-1]["rules"]
                if self.memory_versions
                else body["reflection"]["ruleCandidates"]
            )
            if body["playbook"]["rules"] != previous:
                return problem(400, "INVALID_ARGUMENT")
        return None

    # ---- inspection -----------------------------------------------------------------------

    def stored(self, turn_no: int) -> list[dict]:
        return self.calls.get(turn_no, [])

    def outcome(self, turn_no: int) -> str:
        return self.decided[turn_no]["outcome"]
