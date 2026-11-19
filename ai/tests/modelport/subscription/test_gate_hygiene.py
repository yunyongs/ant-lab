"""No secret, account address or reply text in any repr, refusal text, ledger row or console line."""

import json

import pytest
from gate_helpers import FakeUsage, ledger_of, make_gate
from sub_fakes import claude_envelope, request

from antlab_ai.modelport import SubscriptionRefusal
from antlab_ai.modelport.subscription import (
    BatchLedger,
    BatchLock,
    ClaudeCliAdapter,
    CommandUsageReader,
    LedgeredPort,
    OwnerProof,
    SubscriptionSettings,
)

SECRET = "sk-ant-SECRETVALUE-9f3"
EMAIL = "owner.person@example.com"


def clean(text: str) -> None:
    assert SECRET not in text and EMAIL not in text


def test_reprs_and_refusal_texts_hold_no_value(tmp_path):
    gate = make_gate(tmp_path, environ={"ANTHROPIC_API_KEY": SECRET}, lock=True)
    with pytest.raises(SubscriptionRefusal) as caught:
        gate.begin(is_terminal=lambda: True)
    clean(str(caught.value) + repr(caught.value))
    assert str(caught.value) == "API_KEY_SET"
    for obj in (
        gate,
        ledger_of(tmp_path),
        BatchLock(tmp_path / "lock"),
        OwnerProof(1, "CLAUDE_CLI", "m", 1),
        CommandUsageReader(["meter", "--token", SECRET], max_age_s=1),
        SubscriptionSettings.from_env(
            {
                "ANTLAB_MODEL_SUBSCRIPTION_ENABLED": "true",
                "ANTLAB_MODEL_SUBSCRIPTION_ROUTES": "CLAUDE_CLI",
                "ANTLAB_SUBSCRIPTION_OWNER_MEMBER_ID": "1",
                "ANTLAB_SUBSCRIPTION_USAGE_CMD": f"meter --token {SECRET}",
            }
        ),
    ):
        clean(repr(obj) + str(obj))


def test_a_run_leaves_only_metadata_in_the_ledger_and_in_the_log(tmp_path, fakes, caplog):
    caplog.set_level("DEBUG")
    fakes.scenario("claude", stdout=claude_envelope(f"reply for {EMAIL}"), stderr=f"hi {EMAIL}")
    gate = make_gate(tmp_path, usage=FakeUsage())
    port = LedgeredPort(ClaudeCliAdapter(fakes.config("claude"), gate), gate, ledger_of(tmp_path))
    attempt = port.attempt(request())
    assert EMAIL in attempt.text  # the reply itself is returned unedited, for the runner
    clean(repr(attempt) + caplog.text)
    ledger = (tmp_path / "state" / "subscription" / "calls.jsonl").read_text()
    clean(ledger)
    reserve, done = (json.loads(x) for x in ledger.splitlines())
    assert set(reserve) == {
        "ev", "rid", "at", "batch", "route", "model", "weekly_pct", "session_pct", "usage_as_of",
    }  # fmt: skip
    assert set(done) == {
        "ev", "rid", "at", "batch", "status", "pattern", "tokens_in", "tokens_out",
        "latency_ms", "estimate_usd", "error_events",
    }  # fmt: skip


def test_the_ledger_object_has_no_way_to_store_free_text(tmp_path):
    ledger = BatchLedger(tmp_path / "c.jsonl")
    rid = ledger.reserve(batch_id="b", route="CLAUDE_CLI", model_id="m", usage=None)
    with pytest.raises(TypeError):
        ledger.complete(rid=rid, batch_id="b", status="OK", text="free text")  # type: ignore[call-arg]
