"""CODEX_CLI route: one `codex exec` process per attempt, the owner's subscription login, read-only."""

from __future__ import annotations

import json
import os
import shutil
import stat
import tempfile
from pathlib import Path

from ..port import ERROR, LIMITED, OK, POLICY_REFUSED, TIMEOUT, UPSTREAM, ModelAttempt, ModelRequest
from .common import (
    LAST_MESSAGE_CAP,
    STDERR_CAP,
    STDOUT_CAP,
    AttemptMeta,
    CliConfig,
    Mapped,
    SubscriptionGate,
    build_env,
    check_model_id,
    classify,
    count,
    deadline_seconds,
    ensure_outside_repo,
    flatten,
    provider_text,
    request_text,
    require_gate,
    retry_after,
)
from .process import CliResult, run_cli

ROUTE = "CODEX_CLI"
PROVIDER_PREFIX = "codex-cli"
LAST_MESSAGE_FILE = "last_message.txt"
SCHEMA_FILE = "schema.json"

_EVENT_TYPES = frozenset(
    {
        "thread.started",
        "turn.started",
        "turn.completed",
        "turn.failed",
        "error",
        "item.started",
        "item.updated",
        "item.completed",
    }
)
# Anything else an item can be (command, file change, MCP call, web search) means a tool was used.
_PLAIN_ITEMS = frozenset({"agent_message", "reasoning"})


def codex_flags(
    model_id: str, *, work: str, schema: bool, display_work: str | None = None
) -> list[str]:
    root = work if display_work is None else display_work
    flags = [
        "exec",
        "-m",
        model_id,
        "-s",
        "read-only",
        "-C",
        root,
        "--skip-git-repo-check",
        "--ephemeral",
        "--ignore-user-config",
        "--ignore-rules",
        "--color",
        "never",
        "--json",
        "-o",
        f"{root}/{LAST_MESSAGE_FILE}",
    ]
    if schema:
        flags += ["--output-schema", f"{root}/{SCHEMA_FILE}"]
    flags.append("-")
    return flags


def _events(stdout: bytes) -> list[dict] | None:
    """Parse JSONL into events whose `type`, `item` and `usage` fields have the expected types."""
    try:
        lines = [ln for ln in stdout.decode("utf-8").splitlines() if ln.strip()]
        events = [json.loads(ln) for ln in lines]
    except (UnicodeDecodeError, ValueError):
        return None
    for event in events:
        if not isinstance(event, dict):
            return None
        kind = event.get("type")
        if not isinstance(kind, str) or kind not in _EVENT_TYPES:
            return None
        if kind.startswith("item."):
            item = event.get("item")
            if not isinstance(item, dict) or not isinstance(item.get("type"), str):
                return None
        if "usage" in event and not isinstance(event["usage"], dict):
            return None
        if "model" in event and not isinstance(event["model"], str):
            return None
    return events


def _order_problem(events: list[dict]) -> str | None:
    """Events are one stream of turns: nothing may follow the final completed turn."""
    if not events or events[-1]["type"] != "turn.completed":
        return (
            "events_after_completed" if any(e["type"] == "turn.completed" for e in events) else None
        )
    return None


def _messages(events: list[dict]) -> str:
    parts: list[str] = []
    for event in events:
        if event["type"] == "turn.failed":
            error = event.get("error")
            value = error.get("message") if isinstance(error, dict) else error
        elif event["type"] == "error":
            value = event.get("message")
        else:
            continue
        if isinstance(value, str):
            parts.append(value)
    return "\n".join(parts)


def map_codex(result: CliResult, last_message: str | None, *, requested_model: str) -> Mapped:
    """Draft event names until the probe; unknown output or any tool item is ERROR, never retried.

    An `error` event strictly before the final completed turn (a reconnect notice) is counted and
    tolerated; anything after the final completion, a failed turn, or a malformed event is not.
    """
    if result.pipes_open:
        return Mapped(ERROR, pattern_id="escaped_descendant")
    if result.timed_out:
        return Mapped(TIMEOUT)
    if result.over_cap:
        return Mapped(ERROR, pattern_id="over_cap")
    events = _events(result.stdout)
    stderr = result.stderr.decode("utf-8", "replace")
    if events is not None:
        for event in events:
            item = event.get("item")
            if event["type"].startswith("item.") and item["type"] not in _PLAIN_ITEMS:
                return Mapped(ERROR, pattern_id="tool_item")
        if _order_problem(events) is not None:
            return Mapped(ERROR, pattern_id="events_after_completed")
    if events is None:
        # Malformed output is never classified by wording: that could retry or relabel a bad stream.
        return Mapped(ERROR, pattern_id="unknown_output")
    completed = [e for e in events if e["type"] == "turn.completed"]
    failed = (
        result.exit_code != 0 or not completed or any(e["type"] == "turn.failed" for e in events)
    )
    if failed:
        text = "\n".join([_messages(events), stderr])
        pid, status = classify(text)
        if status == LIMITED:
            return Mapped(LIMITED, retry_after_s=retry_after(text), pattern_id=pid)
        if status in (UPSTREAM, POLICY_REFUSED):
            return Mapped(status, pattern_id=pid)
        return Mapped(ERROR, pattern_id=pid or "unknown_output")
    if last_message is None or not last_message.strip():
        return Mapped(ERROR, pattern_id="no_last_message")
    actual = next((e["model"] for e in events if e.get("model")), None)
    if actual is not None and actual != requested_model:
        return Mapped(ERROR, model_actual=actual, pattern_id="model_mismatch")
    usage = completed[-1].get("usage")
    tokens_in = tokens_out = None
    if isinstance(usage, dict):
        tokens_in = count(usage.get("input_tokens"))
        tokens_out = count(usage.get("output_tokens"))
    error_events = sum(1 for e in events if e["type"] == "error")
    return Mapped(
        OK,
        text=last_message,
        model_actual=actual,
        tokens_in=tokens_in,
        tokens_out=tokens_out,
        pattern_id="error_event_before_completed" if error_events else None,
        error_events=error_events,
    )


def read_last_message(path: Path) -> str | None:
    """Read the reply file without following links, from a regular file only, at most cap bytes."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return None
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            return None
        data = b""
        while len(data) <= LAST_MESSAGE_CAP:
            chunk = os.read(fd, min(65536, LAST_MESSAGE_CAP + 1 - len(data)))
            if not chunk:
                break
            data += chunk
        if len(data) > LAST_MESSAGE_CAP:
            return None
        return data.decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return None
    finally:
        os.close(fd)


class CodexCliAdapter:
    def __init__(
        self, config: CliConfig, gate: SubscriptionGate, *, structured_output: bool = False
    ):
        require_gate(gate)
        self._config = config
        self._gate = gate
        self._structured_output = structured_output
        self.last_meta: AttemptMeta | None = None

    def __repr__(self) -> str:
        return f"CodexCliAdapter(version={self._config.version!r})"

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        self.last_meta = None
        check_model_id(req.model_id)
        self._gate.check_attempt(ROUTE, req.model_id)
        _, stdin_text = flatten(req.messages, system_flag=False)
        use_schema = self._structured_output and req.output_schema is not None
        root = ensure_outside_repo(self._config.work_root)
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(dir=root))
        try:
            if use_schema:
                (work / SCHEMA_FILE).write_text(json.dumps(req.output_schema), encoding="utf-8")
            flags = codex_flags(req.model_id, work=str(work), schema=use_schema)
            shown = codex_flags(
                req.model_id, work=str(work), schema=use_schema, display_work="<work>"
            )
            result = run_cli(
                [self._config.binary, *flags],
                stdin_bytes=stdin_text.encode("utf-8"),
                env=build_env(self._config),
                cwd=work,
                deadline_s=deadline_seconds(req.timeout_ms),
                stdout_cap=STDOUT_CAP,
                stderr_cap=STDERR_CAP,
            )
            clean = not (result.timed_out or result.over_cap or result.pipes_open)
            last = (
                read_last_message(work / LAST_MESSAGE_FILE)
                if clean and result.exit_code == 0
                else None
            )
        finally:
            shutil.rmtree(work, ignore_errors=True)
        mapped = map_codex(result, last, requested_model=req.model_id)
        self.last_meta = AttemptMeta.of(mapped)
        ok = mapped.status == OK
        return ModelAttempt(
            status=mapped.status,
            http_status=None,
            text=mapped.text if ok else None,
            model_actual=mapped.model_actual,
            provider=provider_text(PROVIDER_PREFIX, self._config.version),
            tokens_in=mapped.tokens_in,
            tokens_out=mapped.tokens_out,
            cost_usd=None,
            latency_ms=result.latency_ms,
            ledger_call_id=None,
            retry_after_s=mapped.retry_after_s,
            credit_refusal=False,
            request_text=request_text(ROUTE, shown, None, stdin_text),
        )
