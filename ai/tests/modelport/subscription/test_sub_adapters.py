"""One attempt = one process with the exact argv, a clean environment and a fresh empty directory."""

import json
import os
import time
from pathlib import Path

import pytest
from sub_fakes import RecordingGate, alive, claude_envelope, codex_stdout, request

from antlab_ai.modelport.subscription import ClaudeCliAdapter as Claude
from antlab_ai.modelport.subscription import CodexCliAdapter

HOSTILE = {
    "PATH": "",
    "HOME": "/home/x",
    "USER": "u",
    "LANG": "C",
    "OPENROUTER_API_KEY": "sk-or-v1-HOSTILE",
    "ANTLAB_AGENT_RUNNER_TOKEN": "hostile-token",
    "ANTLAB_ENGINE_TOKEN": "hostile-engine",
    "DATABASE_PASSWORD": "hostile-db",
    "ANTHROPIC_API_KEY": "hostile-anthropic",
    "OLLAMA_API_KEY": "hostile-ollama",
}
ALLOWED = {
    "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR",
    "NO_COLOR", "TERM", "CODEX_HOME", "CLAUDE_CONFIG_DIR",
}  # fmt: skip


def claude(fakes, gate, **kw):
    return Claude(fakes.config("claude", {**HOSTILE, "PATH": str(fakes.dir)}), gate, **kw)


def codex(fakes, gate, **kw):
    return CodexCliAdapter(fakes.config("codex", {**HOSTILE, "PATH": str(fakes.dir)}), gate, **kw)


def test_claude_exact_argv_and_reply_byte_for_byte(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope("  REPLY\n"))
    attempt = claude(fakes, gate).attempt(request())
    (call,) = fakes.calls()
    assert call["argv"] == [
        "-p", "--output-format", "json", "--model", "claude-x",
        "--system-prompt", "You are an agent.",
        "--tools", "", "--strict-mcp-config", "--safe-mode", "--disable-slash-commands",
        "--permission-prompts", "none", "--no-session-persistence",
    ]  # fmt: skip
    assert "--bare" not in call["argv"]
    assert call["stdin"] == '### USER\n{"turn":1}\n'
    assert attempt.status == "OK" and attempt.text == "  REPLY\n"
    assert attempt.cost_usd is None and attempt.ledger_call_id is None
    assert attempt.credit_refusal is False and attempt.http_status is None
    assert attempt.provider == "claude-cli/9.9.9"
    assert (attempt.tokens_in, attempt.tokens_out, attempt.model_actual) == (10, 4, "claude-x")


def test_claude_without_a_system_message_omits_the_flag(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    claude(fakes, gate).attempt(request(system=False))
    assert "--system-prompt" not in fakes.calls()[0]["argv"]


def test_claude_structured_output_flag_is_off_by_default(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    schema = {"type": "object"}
    claude(fakes, gate).attempt(request(output_schema=schema))
    claude(fakes, gate, structured_output=True).attempt(request(output_schema=schema))
    first, second = (c["argv"] for c in fakes.calls())
    assert "--json-schema" not in first
    assert second[-2:] == ["--json-schema", json.dumps(schema)]


def test_codex_exact_argv_reply_and_empty_work_dir(fakes, gate):
    fakes.scenario("codex", stdout=codex_stdout(), last_message="  raw reply\n")
    attempt = codex(fakes, gate).attempt(request("gpt-x"))
    (call,) = fakes.calls()
    work = call["cwd"]
    assert call["argv"] == [
        "exec", "-m", "gpt-x", "-s", "read-only", "-C", work, "--skip-git-repo-check",
        "--ephemeral", "--ignore-user-config", "--ignore-rules", "--color", "never", "--json",
        "-o", f"{work}/last_message.txt", "-",
    ]  # fmt: skip
    assert call["stdin"] == '### SYSTEM\nYou are an agent.\n### USER\n{"turn":1}\n'
    assert call["cwd_listing"] == []
    assert attempt.status == "OK" and attempt.text == "  raw reply\n"
    assert (attempt.tokens_in, attempt.tokens_out, attempt.model_actual) == (9, 3, "gpt-x")
    assert attempt.provider == "codex-cli/9.9.9" and attempt.cost_usd is None
    assert not Path(work).exists()


def test_codex_schema_file_only_with_the_switch(fakes, gate):
    fakes.scenario("codex", stdout=codex_stdout(), last_message="r")
    schema = {"type": "object"}
    codex(fakes, gate).attempt(request("gpt-x", output_schema=schema))
    codex(fakes, gate, structured_output=True).attempt(request("gpt-x", output_schema=schema))
    off, on = fakes.calls()
    assert "--output-schema" not in off["argv"] and off["cwd_listing"] == []
    flag = on["argv"].index("--output-schema")
    assert on["argv"][flag + 1] == f"{on['cwd']}/schema.json"
    assert on["cwd_listing"] == ["schema.json"]


def test_environment_is_the_allowlist_only(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    claude(fakes, gate).attempt(request())
    (call,) = fakes.calls()
    assert set(call["env"]) - {"LC_CTYPE", "__CF_USER_TEXT_ENCODING"} <= ALLOWED
    assert call["env_values"]["NO_COLOR"] == "1" and call["env_values"]["TERM"] == "dumb"
    blob = json.dumps(call["env_values"])
    for secret in ("HOSTILE", "hostile"):
        assert secret not in blob


def test_config_directory_names_pass_only_when_set_by_the_owner(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    config = fakes.config("claude", dict(HOSTILE, CODEX_HOME="/leak", CLAUDE_CONFIG_DIR="/leak2"))
    Claude(config, gate).attempt(request())
    assert "CODEX_HOME" not in fakes.calls()[0]["env"]
    owned = fakes.config("claude", {"PATH": "/x"}, CLAUDE_CONFIG_DIR="/owner/dir")
    Claude(owned, gate).attempt(request())
    assert fakes.calls()[1]["env_values"]["CLAUDE_CONFIG_DIR"] == "/owner/dir"


def test_work_directory_is_fresh_private_outside_the_repo_and_removed(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    claude(fakes, gate).attempt(request())
    claude(fakes, gate).attempt(request())
    first, second = fakes.calls()
    assert first["cwd"] != second["cwd"]
    assert Path(first["cwd"]).parent == fakes.work_root.resolve()
    assert first["cwd_listing"] == []
    assert not Path(first["cwd"]).exists()
    repo = Path(__file__).resolve().parents[4]
    assert repo not in Path(first["cwd"]).parents


def test_stdin_is_a_closed_pipe_not_a_terminal(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    claude(fakes, gate).attempt(request())
    assert fakes.calls()[0]["stdin_tty"] is False


def test_binary_swapped_on_path_after_start_is_not_used(fakes, gate, tmp_path):
    fakes.scenario("claude", stdout=claude_envelope())
    adapter = claude(fakes, gate)
    other = tmp_path / "other"
    other.mkdir()
    (other / "claude").write_text("#!/bin/sh\nexit 9\n")
    (other / "claude").chmod(0o700)
    os.environ["PATH"] = f"{other}{os.pathsep}{os.environ['PATH']}"
    try:
        attempt = adapter.attempt(request())
    finally:
        os.environ["PATH"] = os.environ["PATH"].split(os.pathsep, 1)[1]
    assert attempt.status == "OK" and len(fakes.calls()) == 1


@pytest.mark.parametrize("model", ["--bare", "-x", "", "a b", "a;b", "a\nb", "x" * 101])
@pytest.mark.parametrize("adapter", [claude, codex])
def test_a_model_id_that_could_be_a_flag_never_spawns(fakes, gate, adapter, model):
    with pytest.raises(ValueError):
        adapter(fakes, gate).attempt(request(model))
    assert fakes.calls() == [] and gate.checks == []


@pytest.mark.parametrize("name", ["claude", "codex"])
def test_a_refusing_gate_spawns_nothing(fakes, name):
    gate = RecordingGate("USAGE_STOP")
    adapter = (claude if name == "claude" else codex)(fakes, gate)
    with pytest.raises(Exception) as caught:
        adapter.attempt(request("claude-x" if name == "claude" else "gpt-x"))
    assert caught.value.code == "USAGE_STOP" and str(caught.value) == "USAGE_STOP"
    assert fakes.calls() == []
    assert gate.checks == [(name.upper() + "_CLI", "claude-x" if name == "claude" else "gpt-x")]


def test_gate_is_asked_once_per_attempt_with_route_and_model(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    adapter = claude(fakes, gate)
    adapter.attempt(request())
    adapter.attempt(request())
    assert gate.checks == [("CLAUDE_CLI", "claude-x")] * 2


def test_a_tool_item_in_codex_events_drops_the_reply(fakes, gate):
    fakes.scenario("codex", stdout=codex_stdout(tool=True), last_message="decision")
    adapter = codex(fakes, gate)
    attempt = adapter.attempt(request("gpt-x"))
    assert attempt.status == "ERROR" and attempt.text is None
    assert adapter.last_meta.pattern_id == "tool_item"


def test_unknown_output_is_error_after_exactly_one_process(fakes, gate):
    fakes.scenario("claude", stdout="not json", exit=0)
    attempt = claude(fakes, gate).attempt(request())
    assert attempt.status == "ERROR" and attempt.text is None
    assert len(fakes.calls()) == 1


def test_a_deadline_kills_the_process_and_its_child(fakes, gate):
    fakes.scenario("claude", sleep=60, child=True, stdout=claude_envelope())
    started = time.monotonic()
    attempt = claude(fakes, gate).attempt(request(timeout_ms=1000))
    elapsed = time.monotonic() - started
    assert attempt.status == "TIMEOUT" and attempt.text is None
    assert elapsed < 8
    pid = int((fakes.dir / "child.pid").read_text())
    for _ in range(60):
        if not alive(pid):
            break
        time.sleep(0.1)
    assert not alive(pid)


def test_output_over_the_cap_is_killed_and_error(fakes, gate):
    fakes.scenario("claude", flood=3 * 1024 * 1024, sleep=30)
    started = time.monotonic()
    attempt = claude(fakes, gate).attempt(request())
    assert attempt.status == "ERROR" and attempt.text is None
    assert time.monotonic() - started < 20


def test_request_text_is_the_flat_record_without_paths_or_environment(fakes, gate):
    fakes.scenario("codex", stdout=codex_stdout(), last_message="r")
    attempt = codex(fakes, gate).attempt(request("gpt-x"))
    record = json.loads(attempt.request_text)
    assert record["format"] == "cli-flat/1" and record["route"] == "CODEX_CLI"
    assert record["system"] is None and "### SYSTEM" in record["stdin"]
    assert "-C" in record["argv"] and "<work>" in record["argv"]
    assert str(fakes.dir) not in attempt.request_text
    assert str(fakes.work_root) not in attempt.request_text
    fakes.scenario("claude", stdout=claude_envelope())
    shown = json.loads(claude(fakes, gate).attempt(request()).request_text)
    assert shown["system"] == "You are an agent."
    assert shown["argv"][shown["argv"].index("--system-prompt") + 1] == "<system>"
    assert shown["stdin"] == '### USER\n{"turn":1}\n'
    assert "--bare" not in shown["argv"]


def test_a_helper_left_behind_after_a_normal_exit_is_killed(fakes, gate):
    fakes.scenario("claude", child=True, stdout=claude_envelope())
    started = time.monotonic()
    attempt = claude(fakes, gate).attempt(request())
    assert attempt.status == "OK"
    pid = int((fakes.dir / "child.pid").read_text())
    for _ in range(30):
        if not alive(pid):
            break
        time.sleep(0.1)
    assert not alive(pid)
    assert time.monotonic() - started < 4
