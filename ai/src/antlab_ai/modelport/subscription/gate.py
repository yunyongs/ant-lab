"""The subscription gate: every condition that must hold before a CLI process may be spawned.

`begin` and `finish_start` run once when an owner starts a batch. `check_attempt` runs inside the
adapter before every spawn, so no caller (runner, detection, probe) can reach a CLI around it.
Each refusal raises `SubscriptionRefusal(code)` with a fixed code; nothing is spawned.
"""

from __future__ import annotations

import os
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Final, Protocol

from ..port import ModelAttempt, ModelPort, ModelRequest, SubscriptionRefusal
from .common import AttemptMeta
from .ledger import BatchLedger, BatchLock, LedgerError, check_batch_id
from .usage import UsageReader, UsageSnapshot, UsageUnavailable

# Names whose presence means an API-key, cloud, third-party billing or proxy path is set up in the
# shell that starts a batch. The CLI child would not inherit them (allowlisted environment), but the
# batch refuses loudly instead of working around a shell that is set up for another route.
FORBIDDEN_ENV: Final = (
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "CODEX_API_KEY",
    "CODEX_ACCESS_TOKEN",
    "OPENROUTER_API_KEY",
    "OLLAMA_API_KEY",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "AZURE_OPENAI_API_KEY",
    "AZURE_OPENAI_ENDPOINT",
    "AWS_BEARER_TOKEN_BEDROCK",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "http_proxy",
    "https_proxy",
    "all_proxy",
)
# Environment names set by CI systems and container runtimes.
CONTAINER_ENV: Final = (
    "CI",
    "GITHUB_ACTIONS",
    "ANTLAB_IN_CONTAINER",
    "TF_BUILD",
    "GITLAB_CI",
    "JENKINS_URL",
    "BUILDKITE",
    "CIRCLECI",
    "TRAVIS",
    "TEAMCITY_VERSION",
    "CODEBUILD_BUILD_ID",
    "KUBERNETES_SERVICE_HOST",
    "container",
)
CONTAINER_MARKERS: Final = (Path("/.dockerenv"), Path("/run/.containerenv"))

START_PCT: Final = Decimal(70)
STOP_PCT: Final = Decimal(85)


@dataclass(frozen=True)
class OwnerProof:
    """The server said this agent belongs to the member who holds the subscription."""

    agent_id: int
    route: str
    model_id: str
    owner_member_id: int


class RegisterApi(Protocol):
    def register_agent(
        self, persona_key: str, model_route: str, model_id: str, display_name: str
    ) -> tuple[dict[str, Any], bool]: ...


def verify_owner(
    api: RegisterApi,
    *,
    agent_id: int,
    persona_key: str,
    display_name: str,
    route: str,
    model_id: str,
    device_owner_id: int,
) -> OwnerProof:
    """Two independent statements must agree: the server's agent owner and the device's setting.

    The call is the idempotent registration of the run's own agent. It returns the agent when it
    exists; it would only create one when the given agent id does not match, which is refused.
    """
    agent, _ = api.register_agent(persona_key, route, model_id, display_name)
    owner = agent.get("ownerMemberId")
    if (
        type(owner) is not int
        or owner != device_owner_id
        or agent.get("agentId") != agent_id
        or agent.get("modelRoute") != route
        or agent.get("modelId") != model_id
    ):
        raise SubscriptionRefusal("NOT_OWNER_AGENT")
    return OwnerProof(agent_id, route, model_id, owner)


def _utcnow() -> datetime:
    return datetime.now(UTC)


class BatchGate:
    """One batch session: startup state, the held lock, the reservation of every spawn.

    `check_attempt` is valid only inside a completed, active session: startup finished (`activate`
    after `begin`, the owner proof and `finish_start`), the lock still held, and not closed or
    invalidated by a refusal. Construction without a lock exists for unit tests only.
    """

    def __init__(
        self,
        *,
        route: str,
        model_id: str,
        batch_id: str,
        max_calls: int,
        usage: UsageReader,
        ledger: BatchLedger,
        lock: BatchLock | None = None,
        lockless_for_tests: bool = False,
        environ: Mapping[str, str] | None = None,
        container_markers: tuple[Path, ...] = CONTAINER_MARKERS,
    ) -> None:
        if route not in ("CLAUDE_CLI", "CODEX_CLI"):
            raise ValueError("a batch gate serves a subscription route")
        if type(max_calls) is not int or max_calls < 1:
            raise ValueError("max_calls must be a positive integer")
        if lock is None and not lockless_for_tests:
            raise ValueError("a batch gate needs the batch lock")
        self.route = route
        self.model_id = model_id
        self.batch_id = check_batch_id(batch_id)
        self._cap = max_calls
        self._usage = usage
        self._ledger = ledger
        self._lock = lock
        self._environ = environ
        self._markers = container_markers
        self._owner: OwnerProof | None = None
        self._blocked: str | None = None
        self._begun = False
        self._confirmed = False
        self._active = False
        self._dead = False
        self._rid: str | None = None
        self.calls = 0
        self.last_usage: UsageSnapshot | None = None

    def __repr__(self) -> str:
        return f"BatchGate(route={self.route!r}, calls={self.calls}, cap={self._cap})"

    @property
    def _env(self) -> Mapping[str, str]:
        return os.environ if self._environ is None else self._environ

    # ---- batch start ----------------------------------------------------------------------

    def begin(self, *, is_terminal: Callable[[], bool], reconcile: bool = False) -> None:
        """Rows 2, 3, 4 (terminal), 12, an exhausted cap and unfinished reservations.

        Nothing here needs the CLI, the API or the meter. Any refusal ends the session for good.
        """
        self._guard_start()
        try:
            self._check_environment()
            if any(self._env.get(name) for name in CONTAINER_ENV) or any(
                marker.exists() for marker in self._markers
            ):
                raise SubscriptionRefusal("NOT_OWNER_DEVICE")
            if not is_terminal():
                raise SubscriptionRefusal("NOT_OWNER_STARTED")
            if self._lock is not None:
                self._lock.acquire()
            try:
                self.calls = self._ledger.count(self.batch_id)
                pending = self._ledger.incomplete()
            except LedgerError as error:
                raise SubscriptionRefusal(str(error)) from None
            if pending and not reconcile:
                # A reservation without a completion may be a CLI that is still running.
                raise SubscriptionRefusal("RECONCILE_REQUIRED")
            if pending:
                try:
                    self._ledger.reconcile()
                except LedgerError as error:
                    raise SubscriptionRefusal(str(error)) from None
            if self.calls >= self._cap:
                raise SubscriptionRefusal("CALL_CAP")
        except SubscriptionRefusal:
            self._dead = True
            raise
        self._begun = True

    def attach_owner(self, proof: OwnerProof) -> None:
        if proof.route != self.route or proof.model_id != self.model_id:
            self._dead = True
            raise SubscriptionRefusal("NOT_OWNER_AGENT")
        self._owner = proof

    def finish_start(
        self, *, ask: Callable[[str], str], say: Callable[[str], None]
    ) -> UsageSnapshot:
        """Rows 5, 8, 9 and the typed confirmation of row 4; usage is read again after the answer."""
        self._guard_start()
        if not self._begun:
            self._dead = True
            raise SubscriptionRefusal("NOT_OWNER_STARTED")
        try:
            self._require_owner()
            snapshot = self._read_usage()
            self._start_usage_rules(snapshot)
            say(
                f"route={self.route} model={self.model_id} batch={self.batch_id} "
                f"call_cap={self._cap} used_so_far={self.calls} "
                f"weekly={snapshot.weekly_used_pct}% session={snapshot.session_used_pct}%"
            )
            if ask(f"Type {self.route} to start this batch: ").strip() != self.route:
                raise SubscriptionRefusal("NOT_OWNER_STARTED")
            # Time-sensitive conditions are checked again after the interactive pause.
            self._check_environment()
            snapshot = self._read_usage()
            self._start_usage_rules(snapshot)
        except SubscriptionRefusal:
            self._dead = True
            raise
        self.last_usage = snapshot
        self._confirmed = True
        return snapshot

    def activate(self) -> None:
        """Open the session for attempts, after the CLI checks that follow the confirmation."""
        self._guard_start()
        if not (self._begun and self._confirmed and self._owner is not None):
            self._dead = True
            raise SubscriptionRefusal("NOT_OWNER_STARTED")
        self._active = True

    def invalidate(self) -> None:
        """A startup refusal outside the gate (CLI checks): no attempt is admitted afterwards."""
        self._dead = True
        self._active = False

    def close(self) -> None:
        self._dead = True
        self._active = False
        if self._lock is not None:
            self._lock.release()

    # ---- every attempt --------------------------------------------------------------------

    def calls_left(self) -> int:
        return max(0, self._cap - self.calls)

    def fail_closed(self, code: str) -> None:
        """After a ledger failure no further process may be spawned in this batch."""
        self._blocked = code

    def take_reservation(self) -> str | None:
        """The open reservation of the attempt in flight, handed over once for its completion."""
        rid, self._rid = self._rid, None
        return rid

    def check_attempt(self, route: str, model_id: str) -> None:
        if self._blocked is not None:
            raise SubscriptionRefusal(self._blocked)
        if self._dead or not self._active or (self._lock is not None and not self._lock.held):
            raise SubscriptionRefusal("BATCH_NOT_ACTIVE")
        if route != self.route or model_id != self.model_id:
            raise SubscriptionRefusal("NOT_OWNER_AGENT")
        self._check_environment()
        self._require_owner()
        if self.calls >= self._cap:
            raise SubscriptionRefusal("CALL_CAP")
        snapshot = self._read_usage()
        if snapshot.weekly_used_pct >= STOP_PCT or snapshot.session_used_pct >= STOP_PCT:
            raise SubscriptionRefusal("USAGE_STOP")
        # Reserved and flushed before the spawn: a call that fails, times out or crashes still
        # used the limits, and a crash cannot hand the allowance back.
        try:
            self._rid = self._ledger.reserve(
                batch_id=self.batch_id, route=self.route, model_id=self.model_id, usage=snapshot
            )
        except LedgerError as error:
            raise SubscriptionRefusal(str(error)) from None
        self.calls += 1
        self.last_usage = snapshot

    # ---- pieces ---------------------------------------------------------------------------

    def _guard_start(self) -> None:
        if self._dead:
            raise SubscriptionRefusal("BATCH_NOT_ACTIVE")

    def _start_usage_rules(self, snapshot: UsageSnapshot) -> None:
        if snapshot.weekly_used_pct >= START_PCT:
            raise SubscriptionRefusal("USAGE_START")
        if snapshot.session_used_pct >= STOP_PCT:
            raise SubscriptionRefusal("USAGE_STOP")

    def _check_environment(self) -> None:
        if any(self._env.get(name) for name in FORBIDDEN_ENV):
            raise SubscriptionRefusal("API_KEY_SET")

    def _require_owner(self) -> None:
        if self._owner is None:
            raise SubscriptionRefusal("NOT_OWNER_AGENT")

    def _read_usage(self) -> UsageSnapshot:
        try:
            return self._usage.read(self.route)
        except UsageUnavailable:
            raise SubscriptionRefusal("USAGE_UNKNOWN") from None


class LedgeredPort:
    """Wraps a CLI adapter: completes the reservation of every spawn, metadata only.

    A `Popen` failure (binary removed, no descriptors) becomes a fixed refusal instead of a crash.
    """

    def __init__(self, adapter: ModelPort, gate: BatchGate, ledger: BatchLedger) -> None:
        self._adapter = adapter
        self._gate = gate
        self._ledger = ledger

    def __repr__(self) -> str:
        return f"LedgeredPort(route={self._gate.route!r})"

    @property
    def gate(self) -> BatchGate:
        return self._gate

    def _close(self, rid: str | None, status: str, **fields: Any) -> None:
        if rid is None:
            return
        try:
            self._ledger.complete(rid=rid, batch_id=self._gate.batch_id, status=status, **fields)
        except LedgerError as error:
            # The reply is still returned (the call is paid for); the batch ends here.
            self._gate.fail_closed(str(error))

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        try:
            result = self._adapter.attempt(req)
        except SubscriptionRefusal:
            raise
        except OSError:
            self._close(self._gate.take_reservation(), "SPAWN_FAILED")
            raise SubscriptionRefusal("CLI_MISSING") from None
        except Exception:
            self._close(self._gate.take_reservation(), "ABORTED")
            raise
        meta: AttemptMeta | None = getattr(self._adapter, "last_meta", None)
        self._close(
            self._gate.take_reservation(),
            result.status,
            meta=meta,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
            latency_ms=result.latency_ms,
        )
        return result
