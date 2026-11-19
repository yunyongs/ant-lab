"""A scripted season on a subscription route: fake CLI executables, a fake API, a real gate and ledger."""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "modelport" / "subscription"))

from gate_helpers import FakeUsage, make_gate  # noqa: E402
from loop_helpers import MODEL, Harness  # noqa: E402
from runner_helpers import reply  # noqa: E402
from sub_fakes import FakeClis, claude_envelope, codex_stdout  # noqa: E402

from antlab_ai.modelport import SubscriptionRefusal  # noqa: E402
from antlab_ai.modelport.subscription import (  # noqa: E402
    BatchLedger,
    ClaudeCliAdapter,
    CodexCliAdapter,
    LedgeredPort,
)
from antlab_ai.runner.config import FLAT_FEE, RunnerConfig, season_config  # noqa: E402
from antlab_ai.runner.loop import PAUSED, STOPPED, SeasonRunner  # noqa: E402
from antlab_ai.runner.persona import load_persona  # noqa: E402
from antlab_ai.runner.records import RunnerDefect  # noqa: E402


class Season:
    """One subscription season: the harness, the fake CLIs, the gate and the ledger in one place."""

    def __init__(self, tmp_path, *, route="CLAUDE_CLI", cap=15, usage=None, **config):
        self.tmp_path = tmp_path
        self.route = route
        self.fakes = FakeClis(tmp_path)
        self.usage = usage or FakeUsage()
        self.gate = make_gate(
            tmp_path, usage=self.usage, route=route, model_id=MODEL, max_calls=cap
        )
        self.ledger = BatchLedger(tmp_path / "state" / "subscription" / "calls.jsonl")
        name = "claude" if route == "CLAUDE_CLI" else "codex"
        cli = self.fakes.config(name)
        adapter = (
            ClaudeCliAdapter(cli, self.gate)
            if route == "CLAUDE_CLI"
            else CodexCliAdapter(cli, self.gate)
        )
        self.port = LedgeredPort(adapter, self.gate, self.ledger)
        self.harness = Harness(tmp_path, route=route, cost_mode=FLAT_FEE, **config)
        self.name = name

    def say(self, **fields):
        if self.route == "CLAUDE_CLI":
            self.fakes.scenario("claude", **fields)
        else:
            self.fakes.scenario("codex", **fields)

    def ok(self, text=None):
        text = reply() if text is None else text
        if self.route == "CLAUDE_CLI":
            self.say(stdout=claude_envelope(text, MODEL))
        else:
            self.say(stdout=codex_stdout(MODEL), last_message=text)

    def run(self, **kw):
        return self.harness.run(port=self.port, gate=self.gate, **kw)

    def calls(self):
        return self.fakes.calls()

    def ledger_rows(self):
        path = self.tmp_path / "state" / "subscription" / "calls.jsonl"
        rows = [json.loads(x) for x in path.read_text().splitlines()] if path.exists() else []
        merged: dict[str, dict] = {}
        for row in rows:
            merged.setdefault(row["rid"], {}).update(row)
        return [{k: v for k, v in r.items() if k not in ("ev", "rid")} for r in merged.values()]


@pytest.fixture(params=["CLAUDE_CLI", "CODEX_CLI"])
def season(request, tmp_path):
    return Season(tmp_path, route=request.param, path_length=2)


def test_a_season_finishes_with_one_process_per_turn_and_null_costs(season):
    season.ok()
    result = season.run()
    assert (result.state, result.decisions, result.sends) == ("FINISHED", 2, 2)
    assert len(season.calls()) == 2
    stored = [record for turn in season.harness.spring.calls.values() for record in turn]
    assert len(stored) == 2
    for record in stored:
        assert record["costUsd"] is None and record["ledgerCallId"] is None
        assert record["httpStatus"] != 402
        assert record["provider"] == f"{season.name}-cli/9.9.9"
        assert record["status"] == "OK" and record["modelRequested"] == MODEL
    assert len(season.ledger_rows()) == 2


def test_the_ledger_rows_are_metadata_only(season):
    season.ok("SECRET-REPLY-TEXT")
    season.run()
    text = (season.tmp_path / "state" / "subscription" / "calls.jsonl").read_text()
    assert "SECRET-REPLY-TEXT" not in text and "turn-decision" not in text
    first = season.ledger_rows()[0]
    assert first["batch"] == "batch-1" and first["route"] == season.route
    assert first["status"] == "OK" and first["weekly_pct"] == "10"


def test_usage_guard_pause_after_two_calls_and_resume_after_the_meter_recovers(tmp_path):
    usage = FakeUsage()
    season = Season(tmp_path, usage=usage, path_length=3)
    season.ok()
    healthy = usage.current
    usage.script = [healthy, healthy]
    from gate_helpers import snapshot

    usage.current = snapshot("90", "5")
    result = season.run()
    assert (result.state, result.reason, result.decisions) == (PAUSED, "USAGE_GUARD", 2)
    assert len(season.calls()) == 2
    usage.current = healthy
    resumed = season.harness.run(port=season.port, gate=season.gate, resume=True)
    assert resumed.state == "FINISHED"
    assert len(season.calls()) == 3


def test_an_unreadable_meter_pauses_with_usage_guard_before_any_process(tmp_path):
    usage = FakeUsage()
    usage.current = None
    season = Season(tmp_path, usage=usage)
    season.ok()
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "USAGE_GUARD")
    assert season.calls() == [] and season.ledger_rows() == []


def test_the_batch_cap_stops_the_run_with_call_cap_and_spawns_nothing_more(tmp_path):
    season = Season(tmp_path, cap=2, path_length=5)
    season.ok()
    result = season.run()
    assert (result.state, result.reason) == (STOPPED, "CALL_CAP")
    assert len(season.calls()) == 2 and len(season.ledger_rows()) == 2


def test_the_cap_still_binds_after_a_restart_inside_the_same_batch(tmp_path):
    season = Season(tmp_path, cap=2, path_length=5)
    season.ok()
    season.run()
    again = make_gate(tmp_path, usage=season.usage, model_id=MODEL, max_calls=2, start=False)
    with pytest.raises(SubscriptionRefusal) as caught:
        again.begin(is_terminal=lambda: True)
    assert caught.value.code == "CALL_CAP"
    assert len(season.calls()) == 2


@pytest.mark.parametrize("route", ["CLAUDE_CLI", "CODEX_CLI"])
def test_a_limit_message_pauses_with_limited_daily_after_exactly_one_process(tmp_path, route):
    season = Season(tmp_path, route=route)
    season.say(stderr="Claude usage limit reached for your plan", exit=1, stdout="")
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "LIMITED_DAILY")
    assert len(season.calls()) == 1


@pytest.mark.parametrize("route", ["CLAUDE_CLI", "CODEX_CLI"])
def test_an_unrecognised_failure_pauses_with_config_after_exactly_one_process(tmp_path, route):
    season = Season(tmp_path, route=route)
    season.say(stderr="something nobody has seen before", exit=2, stdout="")
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "CONFIG")
    assert len(season.calls()) == 1


def test_a_timeout_makes_at_most_three_processes_then_no_response(tmp_path):
    season = Season(tmp_path, path_length=1, turn_timeout_ms=1000)
    season.say(sleep=60)
    result = season.run()
    assert result.state == "FINISHED"
    assert len(season.calls()) == 3
    assert [r["status"] for r in season.ledger_rows()] == ["TIMEOUT"] * 3
    stored = [r for turn in season.harness.spring.calls.values() for r in turn]
    assert [r["status"] for r in stored] == ["TIMEOUT"] * 3


def test_a_tool_item_in_the_codex_events_is_never_submitted(tmp_path):
    season = Season(tmp_path, route="CODEX_CLI")
    season.say(stdout=codex_stdout(MODEL, tool=True), last_message=reply())
    result = season.run()
    assert (result.state, result.reason, result.decisions) == (PAUSED, "CONFIG", 0)
    assert season.harness.spring.requests["submitAgentDecision"] == 0
    assert len(season.calls()) == 1


def test_a_process_that_cannot_start_pauses_with_a_fixed_reason_not_a_crash(tmp_path):
    season = Season(tmp_path)
    season.ok()
    (season.fakes.dir / "claude").unlink()
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "SUBSCRIPTION_REFUSED")


def test_an_api_key_name_appearing_mid_season_pauses_with_subscription_refused(tmp_path):
    env: dict[str, str] = {}
    season = Season(tmp_path, path_length=3)
    season.gate._environ = env  # noqa: SLF001 - the live environment the gate re-reads
    season.ok()
    env["ANTHROPIC_API_KEY"] = "x"
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "SUBSCRIPTION_REFUSED")
    assert season.calls() == []


# ---- construction rules ------------------------------------------------------------------


def config(route="CLAUDE_CLI", **kw):
    return RunnerConfig(
        model_requested=MODEL,
        route=route,
        persona=load_persona("patient-value"),
        path_length=1,
        turn_timeout_ms=1000,
        **kw,
    )


def runner(tmp_path, cfg, gate):
    h = Harness(tmp_path, route=cfg.route)
    from antlab_ai.runner.journal import Journal

    journal = Journal.open(h.state_dir, 1, 7)
    try:
        SeasonRunner(
            api=h.api(),
            journal=journal,
            port=h.port,
            config=cfg,
            agent_id=1,
            season_id=7,
            gate=gate,
        )
    finally:
        journal.close()


def test_a_flat_fee_season_without_a_gate_is_refused_at_construction(tmp_path):
    with pytest.raises(RunnerDefect):
        runner(tmp_path, config(cost_mode=FLAT_FEE), None)


def test_a_gate_without_flat_fee_is_refused_too(tmp_path):
    with pytest.raises(RunnerDefect):
        runner(tmp_path, config(), make_gate(tmp_path))


def test_flat_fee_is_only_for_a_subscription_route(tmp_path):
    with pytest.raises(RunnerDefect):
        runner(tmp_path, config("OPENROUTER", cost_mode=FLAT_FEE), make_gate(tmp_path))


def test_the_season_config_records_no_seed_and_no_dollars_for_a_subscription_route():
    body = season_config(config(cost_mode=FLAT_FEE))
    assert body["modelSeed"] is None and body["budgetUsd"] == "0"
    assert body["turnTimeoutMs"] == 1000
    assert season_config(config("OPENROUTER"))["modelSeed"] == 1


def test_default_timeouts_are_six_hundred_seconds_for_both_clis():
    from antlab_ai.runner.config import default_timeout_ms

    assert default_timeout_ms("CLAUDE_CLI") == default_timeout_ms("CODEX_CLI") == 600_000


def test_the_season_call_budget_still_binds_a_flat_fee_season(tmp_path):
    season = Season(tmp_path, path_length=3, budget_calls=1)
    season.ok()
    result = season.run()
    assert (result.state, result.reason) == (PAUSED, "BUDGET")
    assert len(season.calls()) == 1


class RefusingPort:
    def __init__(self, code):
        self.code = code

    def attempt(self, req):
        raise SubscriptionRefusal(self.code)


class OpenGate:
    def calls_left(self):
        return 1


@pytest.mark.parametrize(
    ("code", "state", "reason"),
    [
        ("CALL_CAP", STOPPED, "CALL_CAP"),
        ("USAGE_UNKNOWN", PAUSED, "USAGE_GUARD"),
        ("USAGE_STOP", PAUSED, "USAGE_GUARD"),
        ("USAGE_START", PAUSED, "USAGE_GUARD"),
        ("API_KEY_SET", PAUSED, "SUBSCRIPTION_REFUSED"),
        ("NOT_OWNER_AGENT", PAUSED, "SUBSCRIPTION_REFUSED"),
        ("CLI_MISSING", PAUSED, "SUBSCRIPTION_REFUSED"),
        ("LEDGER_UNSAFE", PAUSED, "SUBSCRIPTION_REFUSED"),
    ],
)
def test_every_gate_refusal_code_maps_to_one_stop_or_pause(tmp_path, code, state, reason):
    h = Harness(tmp_path, route="CLAUDE_CLI", cost_mode=FLAT_FEE)
    result = h.run(port=RefusingPort(code), gate=OpenGate())
    assert (result.state, result.reason, result.sends) == (state, reason, 0)
    assert h.spring.requests["submitAgentDecision"] == 0
