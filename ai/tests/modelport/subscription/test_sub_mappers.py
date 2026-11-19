"""Every row of the failure mapping (plan section 4) as a fixture case; unknown output is ERROR."""

import json
from decimal import Decimal

import pytest
from sub_fakes import FIXTURES

from antlab_ai.modelport.subscription.claude_cli import map_claude
from antlab_ai.modelport.subscription.codex_cli import map_codex
from antlab_ai.modelport.subscription.process import CliResult

CLAUDE = json.loads((FIXTURES / "claude_cases.json").read_text())
CODEX = json.loads((FIXTURES / "codex_cases.json").read_text())


def _result(exit_code: int, stdout: str, stderr: str, **flags) -> CliResult:
    return CliResult(
        exit_code=exit_code,
        stdout=stdout.encode(),
        stderr=stderr.encode(),
        timed_out=flags.get("timed_out", False),
        over_cap=flags.get("over_cap", False),
        latency_ms=5,
    )


def _check(mapped, expect: dict) -> None:
    for key, value in expect.items():
        actual = getattr(mapped, key)
        if key == "estimate_usd":
            value = Decimal(value)
        assert actual == value, key


def _claude_stdout(case: dict) -> str:
    stdout = case["stdout"]
    return stdout if isinstance(stdout, str) else json.dumps(stdout)


@pytest.mark.parametrize("case", CLAUDE, ids=[c["name"] for c in CLAUDE])
def test_claude_rows(case):
    result = _result(case["exit"], _claude_stdout(case), case["stderr"])
    mapped = map_claude(result, requested_model="claude-x")
    _check(mapped, case["expect"])
    if mapped.status != "OK":
        assert mapped.text is None


@pytest.mark.parametrize("case", CODEX, ids=[c["name"] for c in CODEX])
def test_codex_rows(case):
    if case["events"] is not None:
        stdout = "\n".join(json.dumps(e) for e in case["events"]) + "\n"
    else:
        stdout = case["raw"] or ""
    result = _result(case["exit"], stdout, case["stderr"])
    mapped = map_codex(result, case["last_message"], requested_model="gpt-x")
    _check(mapped, case["expect"])
    if mapped.status != "OK":
        assert mapped.text is None


@pytest.mark.parametrize("mapper", ["claude", "codex"])
def test_deadline_is_timeout_whatever_was_printed(mapper):
    result = _result(None, "garbage", "usage limit", timed_out=True)
    mapped = (
        map_claude(result, requested_model="m")
        if mapper == "claude"
        else map_codex(result, "x", requested_model="m")
    )
    assert mapped.status == "TIMEOUT"


@pytest.mark.parametrize("mapper", ["claude", "codex"])
def test_a_crossed_output_cap_is_error(mapper):
    result = _result(0, "x", "", over_cap=True)
    mapped = (
        map_claude(result, requested_model="m")
        if mapper == "claude"
        else map_codex(result, "x", requested_model="m")
    )
    assert (mapped.status, mapped.pattern_id) == ("ERROR", "over_cap")


def test_claude_extra_turns_are_allowed_only_with_a_schema():
    case = next(c for c in CLAUDE if c["name"] == "tool_or_extra_turns")
    result = _result(0, _claude_stdout(case), "")
    assert map_claude(result, requested_model="claude-x").status == "ERROR"
    assert map_claude(result, requested_model="claude-x", allow_extra_turns=True).status == "OK"


def test_claude_reply_is_the_envelope_string_unedited():
    text = '  {"a": 1}\n\n'
    stdout = json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": text})
    mapped = map_claude(_result(0, stdout, ""), requested_model="m")
    assert mapped.text == text and mapped.tokens_in is None


def test_the_dollar_estimate_is_kept_apart_from_cost():
    case = CLAUDE[0]
    mapped = map_claude(_result(0, _claude_stdout(case), ""), requested_model="claude-x")
    assert mapped.estimate_usd == Decimal("0.0123")
    assert not hasattr(mapped, "cost_usd")


def test_http_402_is_never_reported():
    for status in (402, 99, 600, True, "500"):
        stdout = json.dumps(
            {"type": "result", "is_error": True, "result": "x", "api_error_status": status}
        )
        assert map_claude(_result(1, stdout, ""), requested_model="m").http_status is None
