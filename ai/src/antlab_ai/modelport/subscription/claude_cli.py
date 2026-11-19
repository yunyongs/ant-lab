"""CLAUDE_CLI route: one `claude -p` process per attempt, the owner's subscription login, no tools."""

from __future__ import annotations

import json
import shutil
import tempfile
from decimal import Decimal, InvalidOperation
from pathlib import Path

from ..port import ERROR, LIMITED, OK, POLICY_REFUSED, TIMEOUT, UPSTREAM, ModelAttempt, ModelRequest
from .common import (
    STDERR_CAP,
    STDOUT_CAP,
    AttemptMeta,
    CliConfig,
    Mapped,
    SubscriptionGate,
    api_status,
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

ROUTE = "CLAUDE_CLI"
PROVIDER_PREFIX = "claude-cli"


def claude_flags(model_id: str, *, system: str | None, schema_json: str | None) -> list[str]:
    # Never `--bare`: it reads no OAuth or keychain login and would bill an API key.
    flags = ["-p", "--output-format", "json", "--model", model_id]
    if system is not None:
        flags += ["--system-prompt", system]
    flags += [
        "--tools",
        "",
        "--strict-mcp-config",
        "--safe-mode",
        "--disable-slash-commands",
        "--permission-prompts",
        "none",
        "--no-session-persistence",
    ]
    if schema_json is not None:
        flags += ["--json-schema", schema_json]
    return flags


def _model_actual(envelope: dict) -> str | None:
    usage = envelope.get("modelUsage")
    if not isinstance(usage, dict) or not usage:
        return None
    best: tuple[int, str] | None = None
    for name, entry in usage.items():
        out = count(entry.get("outputTokens")) if isinstance(entry, dict) else None
        if isinstance(name, str) and out is not None and (best is None or out > best[0]):
            best = (out, name)
    return best[1] if best else None


def _estimate(value: object) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, int | float | str):
        return None
    try:
        parsed = Decimal(str(value))
    except InvalidOperation:
        return None
    return parsed if parsed.is_finite() and parsed >= 0 else None


def _error_text(envelope: dict | None, result: CliResult) -> str:
    parts = [result.stderr.decode("utf-8", "replace")]
    if envelope is not None:
        for key in ("result", "error", "subtype"):
            value = envelope.get(key)
            if isinstance(value, str):
                parts.append(value)
    else:
        parts.append(result.stdout.decode("utf-8", "replace"))
    return "\n".join(parts)


def map_claude(
    result: CliResult, *, requested_model: str, allow_extra_turns: bool = False
) -> Mapped:
    """Draft shapes until the probe: one JSON object on stdout with `result`, `usage`, `modelUsage`."""
    if result.pipes_open:
        return Mapped(ERROR, pattern_id="escaped_descendant")
    if result.timed_out:
        return Mapped(TIMEOUT)
    if result.over_cap:
        return Mapped(ERROR, pattern_id="over_cap")
    envelope: dict | None = None
    try:
        parsed = json.loads(result.stdout.decode("utf-8"))
        if isinstance(parsed, dict):
            envelope = parsed
    except (UnicodeDecodeError, ValueError):
        envelope = None
    status_code = api_status(envelope.get("api_error_status")) if envelope else None
    failed = (
        result.exit_code != 0
        or envelope is None
        or envelope.get("is_error") is not False
        or envelope.get("type") != "result"
    )
    if envelope is not None:
        denied = envelope.get("permission_denials")
        turns = envelope.get("num_turns")
        if (isinstance(denied, list) and denied) or (
            isinstance(turns, int) and turns > 1 and not allow_extra_turns
        ):
            return Mapped(ERROR, status_code, pattern_id="tool_or_turns")
    if envelope is not None and envelope.get("stop_reason") == "refusal":
        return Mapped(POLICY_REFUSED, http_status=status_code, pattern_id="stop_refusal")
    if failed:
        denials = envelope.get("permission_denials") if envelope else None
        if isinstance(denials, list) and denials:
            return Mapped(ERROR, status_code, pattern_id="tool_or_turns")
        text = _error_text(envelope, result)
        # Explicit status evidence outranks any wording.
        if status_code == 429:
            return Mapped(
                LIMITED, status_code, retry_after_s=retry_after(text), pattern_id="api_status"
            )
        if status_code in (401, 403):
            return Mapped(ERROR, status_code, pattern_id="auth")
        pid, status = classify(text)
        if status == LIMITED:
            return Mapped(LIMITED, status_code, retry_after_s=retry_after(text), pattern_id=pid)
        if status in (UPSTREAM, POLICY_REFUSED):
            return Mapped(status, status_code, pattern_id=pid)
        if pid is None and status_code is not None and status_code >= 500:
            return Mapped(UPSTREAM, status_code, pattern_id="api_status")
        return Mapped(ERROR, status_code, pattern_id=pid or "unknown_output")
    assert envelope is not None
    turns = envelope.get("num_turns")
    denials = envelope.get("permission_denials")
    if (
        (isinstance(turns, int) and turns > 1 and not allow_extra_turns)
        or (isinstance(denials, list) and denials)
        or envelope.get("subtype", "success") != "success"
    ):
        return Mapped(ERROR, pattern_id="tool_or_turns")
    reply = envelope.get("result")
    if not isinstance(reply, str):
        return Mapped(ERROR, pattern_id="unknown_output")
    actual = _model_actual(envelope)
    if actual is not None and actual != requested_model:
        return Mapped(ERROR, model_actual=actual, pattern_id="model_mismatch")
    usage = envelope.get("usage")
    tokens_in = tokens_out = None
    if isinstance(usage, dict):
        parts = [
            count(usage.get("input_tokens")),
            count(usage.get("cache_creation_input_tokens", 0)),
            count(usage.get("cache_read_input_tokens", 0)),
        ]
        tokens_in = None if None in parts else sum(p for p in parts if p is not None)
        tokens_out = count(usage.get("output_tokens"))
    return Mapped(
        OK,
        http_status=None,
        text=reply,
        model_actual=actual,
        tokens_in=tokens_in,
        tokens_out=tokens_out,
        estimate_usd=_estimate(envelope.get("total_cost_usd")),
    )


class ClaudeCliAdapter:
    def __init__(
        self, config: CliConfig, gate: SubscriptionGate, *, structured_output: bool = False
    ):
        require_gate(gate)
        self._config = config
        self._gate = gate
        self._structured_output = structured_output
        self.last_meta: AttemptMeta | None = None

    def __repr__(self) -> str:
        return f"ClaudeCliAdapter(version={self._config.version!r})"

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        self.last_meta = None
        check_model_id(req.model_id)
        self._gate.check_attempt(ROUTE, req.model_id)
        system, stdin_text = flatten(req.messages, system_flag=True)
        schema = (
            json.dumps(req.output_schema)
            if self._structured_output and req.output_schema is not None
            else None
        )
        flags = claude_flags(req.model_id, system=system, schema_json=schema)
        shown = list(flags)
        if system is not None:
            shown[shown.index("--system-prompt") + 1] = "<system>"
        argv = [self._config.binary, *flags]
        work = Path(tempfile.mkdtemp(dir=self._ensure_root()))
        try:
            result = run_cli(
                argv,
                stdin_bytes=stdin_text.encode("utf-8"),
                env=build_env(self._config),
                cwd=work,
                deadline_s=deadline_seconds(req.timeout_ms),
                stdout_cap=STDOUT_CAP,
                stderr_cap=STDERR_CAP,
            )
        finally:
            shutil.rmtree(work, ignore_errors=True)
        mapped = map_claude(
            result, requested_model=req.model_id, allow_extra_turns=schema is not None
        )
        self.last_meta = AttemptMeta.of(mapped)
        ok = mapped.status == OK
        return ModelAttempt(
            status=mapped.status,
            http_status=mapped.http_status,
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
            request_text=request_text(ROUTE, shown, system, stdin_text),
        )

    def _ensure_root(self) -> Path:
        root = ensure_outside_repo(self._config.work_root)
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        return root
