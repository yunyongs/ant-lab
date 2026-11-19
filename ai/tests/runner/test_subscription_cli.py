"""The runner command line on a subscription route: every start refusal, with fake CLIs on PATH."""

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "modelport" / "subscription"))

from api_helpers import TOKEN  # noqa: E402
from fake_spring import FakeSpring  # noqa: E402
from loop_helpers import MODEL  # noqa: E402
from runner_helpers import reply  # noqa: E402
from sub_fakes import FakeClis, claude_envelope  # noqa: E402

from antlab_ai.modelport.subscription import BatchLock  # noqa: E402
from antlab_ai.runner import __main__ as cli  # noqa: E402

MARKER = "MARKER-reply-text-91ab"
METER = """
import datetime, json, sys
now = datetime.datetime.now(datetime.UTC).isoformat()
entry = {"weekly_used_percent": sys.argv[1], "session_used_percent": sys.argv[2], "as_of": now}
print(json.dumps({"schema": "subscription-usage/1", "claude": entry, "codex": entry}))
"""


class Terminal:
    def __init__(self, typed="CLAUDE_CLI", tty=True):
        self.typed, self.tty, self.said, self.asked = typed, tty, [], []
        self.io = cli.Interaction(
            self.is_terminal,
            self.ask,
            self.said.append,
            container_markers=(Path("/nonexistent-marker"),),
        )

    def is_terminal(self):
        return self.tty

    def ask(self, prompt):
        self.asked.append(prompt)
        return self.typed


@pytest.fixture
def fakes(tmp_path):
    clis = FakeClis(tmp_path)
    clis.scenario("claude", stdout=claude_envelope(reply(reason=MARKER), MODEL))
    return clis


@pytest.fixture
def env(tmp_path, fakes):
    meter = tmp_path / "meter.py"
    meter.write_text(METER)
    return {
        "ANTLAB_API_URL": "http://api.test:8080",
        "ANTLAB_AGENT_RUNNER_TOKEN": TOKEN,
        "ANTLAB_RUNNER_STATE_DIR": str(tmp_path / "state"),
        "ANTLAB_MODEL_SUBSCRIPTION_ENABLED": "true",
        "ANTLAB_MODEL_SUBSCRIPTION_ROUTES": "CLAUDE_CLI",
        "ANTLAB_SUBSCRIPTION_OWNER_MEMBER_ID": "1",
        "ANTLAB_SUBSCRIPTION_USAGE_CMD": f"{sys.executable} {meter} 20 5",
        "ANTLAB_SUBSCRIPTION_WORK_ROOT": str(tmp_path / "work"),
        "PATH": str(fakes.dir),
        "HOME": str(tmp_path),
    }


@pytest.fixture
def spring():
    return FakeSpring(path_length=3, model=MODEL, route="CLAUDE_CLI")


def run(spring, env, terminal, *extra, agent="11"):
    argv = [
        "run", "--agent-id", agent, "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--route", "CLAUDE_CLI", "--live", "--max-calls", "10", *extra,
    ]  # fmt: skip
    return cli.main(argv, environ=env, transport=spring, sleep=lambda s: None, io=terminal.io)


def model_calls(fakes):
    return [c for c in fakes.calls() if "-p" in c["argv"]]


def test_an_owner_started_run_finishes_without_any_openrouter_setting(
    spring, env, fakes, tmp_path, capsys
):
    term = Terminal()
    assert run(spring, env, term) == 0
    out = capsys.readouterr()
    assert "state=FINISHED reason=FINISHED decisions=3 sends=3" in out.out
    assert MARKER not in out.out + out.err + "".join(term.said)
    assert len(model_calls(fakes)) == 3
    assert not any("OPENROUTER" in name for call in fakes.calls() for name in call["env"])
    rows = (tmp_path / "state" / "subscription" / "calls.jsonl").read_text().splitlines()
    assert len(rows) == 6  # a reservation and a completion per spawn
    assert term.asked == ["Type CLAUDE_CLI to start this batch: "]
    assert term.said[0].startswith("route=CLAUDE_CLI model=vendor/model:free batch=")
    assert "call_cap=10" in term.said[0] and "weekly=20%" in term.said[0]


def test_the_season_start_records_no_seed_and_the_cli_timeout(spring, env):
    seen = {}
    original = spring.__call__

    def spy(method, url, headers, body, timeout):
        if url.endswith("/seasons") and method == "POST":
            seen.update(json.loads(body))
        return original(method, url, headers, body, timeout)

    code = cli.main(
        ["start", "--agent-id", "11", "--persona", "patient-value", "--model-id", MODEL,
         "--route", "CLAUDE_CLI"],
        environ=env, transport=spy, sleep=lambda s: None,
    )  # fmt: skip
    assert code == 0
    config = seen["config"]
    assert config["modelSeed"] is None and config["budgetUsd"] == "0"
    assert config["turnTimeoutMs"] == 600_000 and config["modelRequested"] == MODEL


def test_registering_a_subscription_agent_needs_no_gate(spring, env, capsys):
    argv = ["register", "--persona", "patient-value", "--model-id", MODEL, "--route", "CODEX_CLI"]
    assert cli.main(argv, environ={}, transport=spring) == 2  # no API settings: usage error
    env = {k: v for k, v in env.items() if not k.startswith("ANTLAB_MODEL_SUB")}
    assert cli.main(argv, environ=env, transport=spring) == 0
    assert "agent_id=11" in capsys.readouterr().out


def refused(spring, env, terminal, capsys, code, *extra, agent="11"):
    assert run(spring, env, terminal, *extra, agent=agent) == 2
    err = capsys.readouterr().err
    assert code in err
    assert MARKER not in err and TOKEN not in err
    return err


def test_a_route_that_is_not_switched_on_is_disabled(spring, env, fakes, capsys):
    env["ANTLAB_MODEL_SUBSCRIPTION_ENABLED"] = "false"
    refused(spring, env, Terminal(), capsys, "AdapterDisabled CLAUDE_CLI")
    assert fakes.calls() == []


def test_a_route_that_is_not_listed_is_disabled(spring, env, fakes, capsys):
    env["ANTLAB_MODEL_SUBSCRIPTION_ROUTES"] = "CODEX_CLI"
    refused(spring, env, Terminal(), capsys, "AdapterDisabled CLAUDE_CLI")
    assert fakes.calls() == []


def test_the_default_state_is_off(spring, env, fakes, capsys):
    for name in [n for n in env if n.startswith("ANTLAB_MODEL_SUB")]:
        del env[name]
    refused(spring, env, Terminal(), capsys, "AdapterDisabled CLAUDE_CLI")


def test_a_bad_switch_value_is_a_usage_error(spring, env, capsys):
    env["ANTLAB_MODEL_SUBSCRIPTION_ENABLED"] = "yes"
    refused(spring, env, Terminal(), capsys, "true or false")


def test_a_forbidden_environment_name_refuses_before_any_process(spring, env, fakes, capsys):
    env["ANTHROPIC_API_KEY"] = "sk-ant-SECRETVALUE"
    err = refused(spring, env, Terminal(), capsys, "SubscriptionRefusal API_KEY_SET")
    assert "SECRETVALUE" not in err
    assert fakes.calls() == [] and spring.requests["registerAgent"] == 0


def test_a_ci_environment_refuses_before_any_process(spring, env, fakes, capsys):
    env["CI"] = "true"
    refused(spring, env, Terminal(), capsys, "NOT_OWNER_DEVICE")
    assert fakes.calls() == []


def test_a_start_without_a_terminal_refuses_before_any_process(spring, env, fakes, capsys):
    term = Terminal(tty=False)
    refused(spring, env, term, capsys, "NOT_OWNER_STARTED")
    assert fakes.calls() == [] and term.asked == []


def test_a_wrong_confirmation_refuses_and_spawns_no_model_call(spring, env, fakes, capsys):
    refused(spring, env, Terminal(typed="yes"), capsys, "NOT_OWNER_STARTED")
    assert fakes.calls() == []


def test_another_members_agent_refuses_before_the_confirmation(spring, env, fakes, capsys):
    env["ANTLAB_SUBSCRIPTION_OWNER_MEMBER_ID"] = "2"
    term = Terminal()
    refused(spring, env, term, capsys, "NOT_OWNER_AGENT")
    assert fakes.calls() == [] and term.asked == []


def test_an_agent_id_that_the_server_does_not_return_refuses(spring, env, fakes, capsys):
    refused(spring, env, Terminal(), capsys, "NOT_OWNER_AGENT", agent="12")
    assert fakes.calls() == []


def test_a_missing_cli_refuses(spring, env, fakes, tmp_path, capsys):
    env["PATH"] = str(tmp_path / "empty")
    refused(spring, env, Terminal(), capsys, "CLI_MISSING")


def test_a_login_that_is_not_a_subscription_refuses(spring, env, fakes, capsys):
    fakes.scenario("claude:login", stdout=json.dumps({"loggedIn": False}))
    refused(spring, env, Terminal(), capsys, "NOT_LOGGED_IN")
    assert model_calls(fakes) == []


def test_a_batch_already_running_refuses(spring, env, fakes, tmp_path, capsys):
    (tmp_path / "state").mkdir(mode=0o700)
    held = BatchLock(tmp_path / "state" / "subscription" / "batch.lock")
    held.acquire()
    try:
        refused(spring, env, Terminal(), capsys, "BATCH_RUNNING")
    finally:
        held.release()
    assert fakes.calls() == []


@pytest.mark.parametrize(("weekly", "ok"), [("69.9", True), ("70", False), ("90", False)])
def test_the_start_is_refused_from_seventy_percent_weekly(
    spring, env, fakes, tmp_path, capsys, weekly, ok
):
    env["ANTLAB_SUBSCRIPTION_USAGE_CMD"] = f"{sys.executable} {tmp_path / 'meter.py'} {weekly} 5"
    if ok:
        assert run(spring, env, Terminal()) == 0
    else:
        refused(spring, env, Terminal(), capsys, "USAGE_START")
        assert fakes.calls() == []


def test_an_unreadable_meter_refuses_the_start(spring, env, fakes, capsys):
    env["ANTLAB_SUBSCRIPTION_USAGE_CMD"] = f"{sys.executable} -c pass"
    refused(spring, env, Terminal(), capsys, "USAGE_UNKNOWN")
    assert fakes.calls() == []


def test_an_env_file_is_not_used_on_a_subscription_route(spring, env, capsys):
    refused(spring, env, Terminal(), capsys, "--env-file", "--env-file", "x.env")


def test_a_bad_batch_id_is_a_usage_error(spring, env, capsys):
    refused(spring, env, Terminal(), capsys, "batch id", "--batch-id", "../x")


def test_a_cap_above_sixty_is_a_startup_error(spring, env, capsys):
    env["ANTLAB_SUBSCRIPTION_MAX_CALLS_PER_BATCH"] = "61"
    refused(spring, env, Terminal(), capsys, "ANTLAB_SUBSCRIPTION_MAX_CALLS_PER_BATCH")


def test_the_environment_cap_lowers_the_command_cap(spring, env, fakes, capsys):
    env["ANTLAB_SUBSCRIPTION_MAX_CALLS_PER_BATCH"] = "2"
    assert run(spring, env, Terminal()) == 3
    assert "state=STOPPED reason=CALL_CAP decisions=2 sends=2" in capsys.readouterr().out
    assert len(model_calls(fakes)) == 2


def test_the_call_cap_of_a_batch_id_survives_a_second_command(spring, env, fakes, capsys):
    argv_cap = ["--batch-id", "b1"]
    env["ANTLAB_SUBSCRIPTION_MAX_CALLS_PER_BATCH"] = "2"
    assert run(spring, env, Terminal(), *argv_cap) == 3
    first = capsys.readouterr().out
    assert "decisions=2 sends=2" in first
    # the same batch id has used its two calls: the start is refused before any process runs,
    # not even the version or login check
    before = len(fakes.calls())
    assert run(spring, env, Terminal(), *argv_cap) == 2
    assert "SubscriptionRefusal CALL_CAP" in capsys.readouterr().err
    assert len(fakes.calls()) == before
    assert len(model_calls(fakes)) == 2
    # a new batch id gets a fresh count
    assert run(spring, env, Terminal(), "--batch-id", "b2") == 0
    assert len(model_calls(fakes)) == 3


def test_a_server_that_denies_the_agent_refuses_as_not_owner(spring, env, fakes, capsys):
    spring.fail["registerAgent"] = (404, "NOT_FOUND")
    refused(spring, env, Terminal(), capsys, "NOT_OWNER_AGENT")
    assert fakes.calls() == []


def test_a_server_outage_during_the_owner_check_is_a_run_error_not_a_refusal(
    spring, env, fakes, capsys
):
    spring.fail["registerAgent"] = (500, "INTERNAL")
    assert run(spring, env, Terminal()) == 1
    assert fakes.calls() == []


def test_c4_a_paused_season_without_resume_launches_no_process(
    spring, env, fakes, tmp_path, capsys
):
    fakes.scenario("claude", stderr="Claude usage limit reached", exit=1, stdout="")
    assert run(spring, env, Terminal()) == 3
    capsys.readouterr()
    before = len(fakes.calls())
    assert run(spring, env, Terminal()) == 2
    assert "StillPaused" in capsys.readouterr().err
    assert len(fakes.calls()) == before


def test_c1_an_unfinished_reservation_blocks_the_start_until_it_is_reconciled(
    spring, env, fakes, tmp_path, capsys
):
    from antlab_ai.modelport.subscription import BatchLedger

    (tmp_path / "state").mkdir(mode=0o700, exist_ok=True)
    ledger = BatchLedger(tmp_path / "state" / "subscription" / "calls.jsonl")
    ledger.reserve(batch_id="old", route="CLAUDE_CLI", model_id=MODEL, usage=None)
    refused(spring, env, Terminal(), capsys, "RECONCILE_REQUIRED")
    assert fakes.calls() == []
    assert run(spring, env, Terminal(), "--reconcile") == 0
    assert ledger.incomplete() == []


@pytest.mark.parametrize("flag", [["--batch-id", "b1"], ["--reconcile"]])
def test_r3_subscription_only_flags_are_refused_on_other_routes(
    spring, env, tmp_path, flag, capsys
):
    argv = [
        "run", "--agent-id", "11", "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--model-script", str(tmp_path / "s.json"), *flag,
    ]  # fmt: skip
    code = cli.main(argv, environ=env, transport=spring, sleep=lambda s: None)
    assert code == 2 and "subscription route" in capsys.readouterr().err
