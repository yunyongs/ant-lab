"""No stderr text, account address, secret or environment value escapes into any record or text."""

import json
import logging
from dataclasses import fields
from pathlib import Path

import pytest
from sub_fakes import RecordingGate, claude_envelope, codex_stdout, request

from antlab_ai.modelport import (
    AdapterDisabled,
    ModelAttempt,
    ModelPortSettings,
    ModelRequest,
    SubscriptionWiring,
    build_registry,
)
from antlab_ai.modelport.subscription import ClaudeCliAdapter, CodexCliAdapter
from antlab_ai.modelport.subscription.process import CliResult
from antlab_ai.runner.decision import MAX_TEXT_BYTES
from antlab_ai.runner.persona import load_persona
from antlab_ai.runner.prompt import build_messages

ACCOUNT = "owner.person@example.com"
SECRET = "sk-ant-SECRETVALUE"
STDERR = f"Error for {ACCOUNT}: usage limit reached (token {SECRET})"


def assert_clean(text: str) -> None:
    assert ACCOUNT not in text and SECRET not in text and "usage limit reached" not in text


@pytest.mark.parametrize("name", ["claude", "codex"])
def test_stderr_never_reaches_a_record_log_or_exception(fakes, gate, caplog, name):
    caplog.set_level(logging.DEBUG)
    fakes.scenario(name, stderr=STDERR, exit=1)
    source = {"PATH": str(fakes.dir), "ANTHROPIC_API_KEY": SECRET}
    config = fakes.config(name, source)
    adapter = (ClaudeCliAdapter if name == "claude" else CodexCliAdapter)(config, gate)
    attempt = adapter.attempt(request("claude-x" if name == "claude" else "gpt-x"))
    assert attempt.status == "LIMITED"
    for text in (repr(attempt), attempt.request_text, repr(adapter), repr(config), caplog.text):
        assert_clean(text)
    assert_clean(repr(adapter.last_meta))


def test_cli_result_repr_shows_sizes_only():
    result = CliResult(1, b"out " + ACCOUNT.encode(), STDERR.encode(), False, False, 3)
    assert_clean(repr(result))
    assert "stderr=" in repr(result)


def test_exceptions_hold_only_the_fixed_code(fakes):
    adapter = ClaudeCliAdapter(fakes.config("claude"), RecordingGate("API_KEY_SET"))
    with pytest.raises(Exception) as caught:
        adapter.attempt(request())
    assert (
        str(caught.value) == "API_KEY_SET"
        and repr(caught.value) == "SubscriptionRefusal('API_KEY_SET')"
    )
    with pytest.raises(ValueError) as bad:
        adapter.attempt(request(f"--{SECRET}"))
    assert SECRET not in str(bad.value)


def test_request_text_holds_no_environment_value(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope())
    config = fakes.config("claude", {"PATH": str(fakes.dir), "HOME": "/home/unique-home-dir"})
    attempt = ClaudeCliAdapter(config, gate).attempt(request())
    assert "unique-home-dir" not in attempt.request_text
    assert str(fakes.dir) not in attempt.request_text


def test_registry_builds_cli_adapters_only_from_a_wiring(fakes, gate):
    assert build_registry(ModelPortSettings()).routes == frozenset()
    wiring = SubscriptionWiring(gate, claude=fakes.config("claude"))
    registry = build_registry(ModelPortSettings(), subscription=wiring)
    assert registry.routes == {"CLAUDE_CLI"}
    assert isinstance(registry.get("CLAUDE_CLI"), ClaudeCliAdapter)
    with pytest.raises(AdapterDisabled):
        registry.get("CODEX_CLI")
    both = SubscriptionWiring(gate, fakes.config("claude"), fakes.config("codex"))
    assert build_registry(ModelPortSettings(), subscription=both).routes == {
        "CLAUDE_CLI",
        "CODEX_CLI",
    }


def test_the_stored_request_fits_for_the_largest_turn_view(fakes, gate):
    view = json.loads(
        (Path(__file__).parents[2] / "runner/fixtures/turn_view_pool180.json").read_text()
    )
    messages = build_messages(load_persona("patient-value"), view)
    fakes.scenario("claude", stdout=claude_envelope())
    fakes.scenario("codex", stdout=codex_stdout(), last_message="r")
    for adapter, model in (
        (ClaudeCliAdapter(fakes.config("claude"), gate), "claude-x"),
        (CodexCliAdapter(fakes.config("codex"), gate), "gpt-x"),
    ):
        req = ModelRequest(model, messages, 1000, request().temperature, None, 30_000)
        attempt = adapter.attempt(req)
        assert attempt.status == "OK"
        assert len(attempt.request_text.encode("utf-8")) <= MAX_TEXT_BYTES


def test_last_meta_exposes_ledger_metadata_only(fakes, gate):
    fakes.scenario("claude", stdout=claude_envelope(f"SECRET REPLY {ACCOUNT}", model="claude-x"))
    adapter = ClaudeCliAdapter(fakes.config("claude"), gate)
    attempt = adapter.attempt(request())
    fakes.scenario("codex", stdout=codex_stdout(), last_message=f"SECRET REPLY {ACCOUNT}")
    other = CodexCliAdapter(fakes.config("codex"), gate)
    other_attempt = other.attempt(request("gpt-x"))
    for meta, att in ((adapter.last_meta, attempt), (other.last_meta, other_attempt)):
        assert {f.name for f in fields(meta)} == {
            "status",
            "pattern_id",
            "estimate_usd",
            "error_events",
        }
        for value in vars(meta).values():
            assert "SECRET" not in str(value) and ACCOUNT not in str(value)
        assert "SECRET REPLY" in att.text  # the reply travels only in the attempt itself
        for text in (repr(att), str(att)):
            assert "SECRET REPLY" not in text and ACCOUNT not in text and "### " not in text


def test_model_attempt_repr_redacts_reply_and_request():
    attempt = ModelAttempt(
        "OK", None, "REPLY-TEXT", "m", "p", 1, 1, None, 3, None, None, False, "PROMPT-TEXT"
    )
    assert "REPLY-TEXT" not in repr(attempt) and "PROMPT-TEXT" not in repr(attempt)
    assert "status='OK'" in repr(attempt)


@pytest.mark.parametrize("adapter_cls", [ClaudeCliAdapter, CodexCliAdapter])
def test_constructors_refuse_a_missing_gate(fakes, adapter_cls):
    with pytest.raises(ValueError):
        adapter_cls(fakes.config("claude"), None)
    with pytest.raises(ValueError):
        adapter_cls(fakes.config("claude"), object())
