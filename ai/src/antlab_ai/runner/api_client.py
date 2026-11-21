"""Client of the internal agent-runner API: the seven operations of docs/api/agent-runner.yaml."""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable, Mapping, Sequence
from decimal import Decimal
from typing import Any, Final, Protocol
from urllib import error, request

from antlab_ai.runner import contract, jsonenc
from antlab_ai.runner.contract import BASE_PATH, ContractError
from antlab_ai.runner.records import calls_request, decision_request
from antlab_ai.runner.settings import RunnerSettings

TOKEN_HEADER: Final = "X-Antlab-Runner-Token"
MAX_RESPONSE_BYTES: Final = 16 * 1024 * 1024
REQUEST_TIMEOUT_S: Final = 120
RETRY_DELAYS_S: Final = (1, 2, 4)
_CODE = re.compile(r"[A-Z][A-Z_]{0,39}")

# operationId -> (method, path template, success statuses, response schema or None, retried on 5xx)
_OPERATIONS: Final[dict[str, tuple[str, str, frozenset[int], str | None, bool]]] = {
    "registerAgent": ("POST", f"{BASE_PATH}/agents", frozenset({200, 201}), "AgentDto", True),
    "startAgentSeason": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons",
        frozenset({201}),
        "SeasonDto",
        # Not idempotent: a retry after a lost response could start a second season.
        False,
    ),
    "getAgentTurnView": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/turn-view",
        frozenset({200}),
        "AgentTurnViewDto",
        True,
    ),
    "flushAgentCalls": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/turns/{{turnNo}}/calls",
        frozenset({204}),
        None,
        True,
    ),
    "submitAgentDecision": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/turns/{{turnNo}}/decision",
        frozenset({200}),
        "DecisionResultDto",
        True,
    ),
    "getAgentDecision": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/turns/{{turnNo}}/decision",
        frozenset({200}),
        "DecisionResultDto",
        True,
    ),
    "advanceAgentTurn": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/turns",
        frozenset({201}),
        "TurnResultDto",
        True,
    ),
    # Season lifecycle (AG-02 A5). Listed so the table equals the contract; the loop does not call them yet.
    "pauseAgentSeason": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/pause",
        frozenset({200}),
        "SeasonDto",
        True,
    ),
    "resumeAgentSeason": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/resume",
        frozenset({200}),
        "SeasonDto",
        True,
    ),
    "voidAgentSeason": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/void",
        frozenset({200}),
        "SeasonDto",
        True,
    ),
    "abandonAgentSeason": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/abandon",
        frozenset({200}),
        "AbandonResultDto",
        True,
    ),
    # AG-04 B: the config the season was started with; `run` compares it before any model call.
    "getAgentSeasonConfig": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/config",
        frozenset({200}),
        "AgentSeasonConfigDto",
        True,
    ),
    # League operations (AG-04 C1); the runner's `league` command drives them.
    "createLeague": ("POST", f"{BASE_PATH}/leagues", frozenset({201}), "LeagueDto", False),
    "getLeague": ("GET", f"{BASE_PATH}/leagues/{{leagueId}}", frozenset({200}), "LeagueDto", True),
    "closeLeague": (
        "POST",
        f"{BASE_PATH}/leagues/{{leagueId}}/close",
        frozenset({200}),
        "LeagueDto",
        True,
    ),
    # AG-05 C1: memory reads; the memory commands (AG-05 E) drive them.
    "getAgentMemory": ("GET", f"{BASE_PATH}/agents/{{agentId}}/memory", frozenset({200}), "AgentMemoryStateDto", True),
    # AG-05 D: two memory versions on the same seeds; the query (versionA, versionB) is added by the compare command (AG-05 E).
    "getMemoryComparison": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/memory/comparison",
        frozenset({200}),
        "MemoryComparisonDto",
        True,
    ),
    "getAgentMemoryVersion": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/memory/{{version}}",
        frozenset({200}),
        "AgentMemoryVersionDto",
        True,
    ),
    "getReflectionView": (
        "GET",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/reflection-view",
        frozenset({200}),
        "ReflectionViewDto",
        True,
    ),
    # AG-05 C2: record the memory step of a season. Not retried: a lost answer is read back from the memory state.
    "submitSeasonMemory": (
        "POST",
        f"{BASE_PATH}/agents/{{agentId}}/seasons/{{seasonId}}/memory",
        frozenset({201}),
        "SubmitSeasonMemoryResultDto",
        False,
    ),
}
OPERATIONS: Final[dict[str, tuple[str, str]]] = {k: (v[0], v[1]) for k, v in _OPERATIONS.items()}


class ApiProblem(Exception):
    """The API answered with an error. Only the status and the fixed ``code`` are kept."""

    def __init__(self, status: int, code: str | None) -> None:
        super().__init__(f"API answered {status} {code or '-'}")
        self.status = status
        self.code = code


class ApiUnavailable(Exception):
    """The API could not be reached (after the retries of the operation)."""


class Transport(Protocol):
    def __call__(
        self, method: str, url: str, headers: Mapping[str, str], body: bytes | None, timeout: int
    ) -> tuple[int, Mapping[str, str], bytes]: ...


class _NoRedirect(request.HTTPRedirectHandler):
    """A redirect is an answer, not a second request: the token never leaves for another URL."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = request.build_opener(_NoRedirect())


def _read(response) -> bytes:
    data = response.read(MAX_RESPONSE_BYTES + 1)
    if len(data) > MAX_RESPONSE_BYTES:
        raise OSError("response body too large")
    return data


def urllib_transport(
    method: str, url: str, headers: Mapping[str, str], body: bytes | None, timeout: int
) -> tuple[int, Mapping[str, str], bytes]:
    req = request.Request(url, data=body, headers=dict(headers), method=method)
    try:
        with _OPENER.open(req, timeout=timeout) as response:
            return response.status, dict(response.headers), _read(response)
    except error.HTTPError as exc:
        return exc.code, dict(exc.headers), _read(exc)


def _refuse_constant(name: str) -> Any:
    raise ValueError(f"constant {name} is not JSON")


def _dumps(body: Any) -> bytes:
    # Amounts travel as strings. The one exception is the model's own reply (``decision``): its numbers
    # were parsed as Decimal and go out as the same numbers.
    return jsonenc.dumps(body, decimals_under="decision").encode()


def _id(value: Any, name: str, minimum: int = 1) -> int:
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer of at least {minimum}")
    return value


def _decimal_text(value: Decimal | str, name: str) -> str:
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError(f"{name} must be finite")
        return format(value, "f")
    if isinstance(value, str):
        return value
    raise ValueError(f"{name} must be a Decimal or text, never a float")


class ApiClient:
    def __init__(
        self,
        settings: RunnerSettings,
        *,
        transport: Transport = urllib_transport,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._base = settings.api_url
        self._token = settings.runner_token
        self._transport = transport
        self._sleep = sleep

    def __repr__(self) -> str:
        return f"ApiClient(base={self._base!r}, token=set)"

    # ---- the seven operations -------------------------------------------------------------

    def register_agent(
        self, persona_key: str, model_route: str, model_id: str, display_name: str
    ) -> tuple[dict[str, Any], bool]:
        """The agent, and whether this call created it (201) rather than found it (200)."""
        body = {
            "personaKey": persona_key,
            "modelRoute": model_route,
            "modelId": model_id,
            "displayName": display_name,
        }
        contract.check("RegisterAgentRequest", body)
        status, payload = self._call("registerAgent", {}, body)
        return payload, status == 201

    def start_season(
        self,
        agent_id: int,
        risk_limit: Decimal | str,
        formation: Mapping[str, int],
        path_length: int | None,
        config: Mapping[str, Any],
        league_id: int | None = None,
    ) -> dict[str, Any]:
        """With ``league_id`` the league fixes the path: pass ``path_length=None`` to omit it."""
        body: dict[str, Any] = {
            "riskLimit": _decimal_text(risk_limit, "risk_limit"),
            "formation": dict(formation),
            "config": dict(config),
        }
        if path_length is not None:
            body["pathLength"] = path_length
        if league_id is not None:
            body["leagueId"] = _id(league_id, "league_id")
        contract.check("StartAgentSeasonRequest", body)
        return self._call("startAgentSeason", {"agentId": _id(agent_id, "agent_id")}, body)[1]

    def get_turn_view(self, agent_id: int, season_id: int) -> dict[str, Any]:
        return self._call("getAgentTurnView", self._season(agent_id, season_id))[1]

    def flush_calls(
        self, agent_id: int, season_id: int, turn_no: int, calls: Sequence[dict[str, Any]]
    ) -> None:
        body = calls_request(calls)
        contract.check("CallsRequest", body)
        self._call("flushAgentCalls", self._turn(agent_id, season_id, turn_no), body)

    def submit_decision(
        self,
        agent_id: int,
        season_id: int,
        turn_no: int,
        calls: Sequence[dict[str, Any]],
        decision: dict[str, Any] | None,
    ) -> dict[str, Any]:
        """The outcome follows from the last call, the way the API reads it."""
        body = decision_request(calls, decision)
        contract.check("DecisionRequest", body)
        return self._call("submitAgentDecision", self._turn(agent_id, season_id, turn_no), body)[1]

    def get_decision(self, agent_id: int, season_id: int, turn_no: int) -> dict[str, Any]:
        return self._call("getAgentDecision", self._turn(agent_id, season_id, turn_no))[1]

    def advance_turn(self, agent_id: int, season_id: int, expected_turn_no: int) -> dict[str, Any]:
        body = {"expectedTurnNo": expected_turn_no}
        contract.check("AdvanceTurnRequest", body)
        return self._call("advanceAgentTurn", self._season(agent_id, season_id), body)[1]

    def get_season_config(self, agent_id: int, season_id: int) -> dict[str, Any]:
        return self._call("getAgentSeasonConfig", self._season(agent_id, season_id))[1]

    def create_league(
        self, name: str, path_length: int | None = None, max_entries: int | None = None
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"name": name}
        if path_length is not None:
            body["pathLength"] = path_length
        if max_entries is not None:
            body["maxEntries"] = max_entries
        contract.check("CreateLeagueRequest", body)
        return self._call("createLeague", {}, body)[1]

    def get_league(self, league_id: int) -> dict[str, Any]:
        return self._call("getLeague", {"leagueId": _id(league_id, "league_id")})[1]

    def close_league(self, league_id: int) -> dict[str, Any]:
        return self._call("closeLeague", {"leagueId": _id(league_id, "league_id")})[1]

    # ---- plumbing -------------------------------------------------------------------------

    @staticmethod
    def _season(agent_id: int, season_id: int) -> dict[str, int]:
        return {"agentId": _id(agent_id, "agent_id"), "seasonId": _id(season_id, "season_id")}

    @classmethod
    def _turn(cls, agent_id: int, season_id: int, turn_no: int) -> dict[str, int]:
        return {**cls._season(agent_id, season_id), "turnNo": _id(turn_no, "turn_no", 0)}

    def _call(
        self, operation: str, path_ids: Mapping[str, int], body: Any = None
    ) -> tuple[int, Any]:
        method, template, ok, schema, retried = _OPERATIONS[operation]
        url = self._base + template.format(**path_ids)
        headers = {
            TOKEN_HEADER: self._token,
            "Accept": "application/json, application/problem+json",
        }
        data = None
        if body is not None:
            data = _dumps(body)
            headers["Content-Type"] = "application/json"
        retries = RETRY_DELAYS_S if retried else ()
        for delay in (*retries, None):
            try:
                status, _headers, raw = self._transport(
                    method, url, headers, data, REQUEST_TIMEOUT_S
                )
            except (OSError, TimeoutError):
                # urllib wraps connection failures in URLError (an OSError); no text is kept.
                if delay is None:
                    raise ApiUnavailable("the API could not be reached") from None
                self._sleep(delay)
                continue
            if status >= 500 and delay is not None:
                self._sleep(delay)
                continue
            return status, self._result(status, raw, ok, schema)
        raise ApiUnavailable("the API could not be reached")  # pragma: no cover

    @staticmethod
    def _result(status: int, raw: bytes, ok: frozenset[int], schema: str | None) -> Any:
        if status not in ok:
            if 200 <= status < 300:
                raise ContractError(f"unexpected success status {status}")
            raise ApiProblem(status, _problem_code(raw))
        if schema is None:
            return None
        try:
            payload = json.loads(raw, parse_float=Decimal, parse_constant=_refuse_constant)
        except ValueError:
            raise ContractError(f"response is not JSON for {schema}") from None
        contract.check(schema, payload)
        return payload


def _problem_code(raw: bytes) -> str | None:
    """The fixed ``code`` of a problem body; the free-text ``detail`` is never kept."""
    try:
        payload = json.loads(raw)
    except ValueError:
        return None
    code = payload.get("code") if isinstance(payload, dict) else None
    return code if isinstance(code, str) and _CODE.fullmatch(code) else None
