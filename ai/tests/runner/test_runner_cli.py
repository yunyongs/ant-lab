"""F9: the command line: offline by default, hard call cap, exit codes, console hygiene."""

import json

import pytest
from api_helpers import TOKEN
from fake_spring import FakeSpring
from loop_helpers import MODEL
from runner_helpers import reply

from antlab_ai.runner import __main__ as cli
from antlab_ai.runner.config import ScriptError, load_script

MARKER = "MARKER-reply-text-7c1d"


@pytest.fixture
def env(tmp_path):
    return {
        "ANTLAB_API_URL": "http://api.test:8080",
        "ANTLAB_AGENT_RUNNER_TOKEN": TOKEN,
        "ANTLAB_RUNNER_STATE_DIR": str(tmp_path / "state"),
    }


@pytest.fixture
def spring():
    return FakeSpring(path_length=3, model=MODEL)


def script_file(tmp_path, entries):
    path = tmp_path / "script.json"
    path.write_text(json.dumps(entries), encoding="utf-8")
    return path


def call(spring, env, *argv):
    return cli.main(list(argv), environ=env, transport=spring, sleep=lambda s: None)


def run_args(script, *extra, season=7):
    return [
        "run", "--agent-id", "1", "--season-id", str(season), "--persona", "patient-value",
        "--model-id", MODEL, "--model-script", str(script), *extra,
    ]  # fmt: skip


def hold(**kwargs):
    return {"reply": json.loads(reply(**kwargs))}


def test_register_and_start_print_the_new_ids(spring, env, capsys):
    assert call(spring, env, "register", "--persona", "patient-value", "--model-id", MODEL) == 0
    assert "agent_id=11 created=true" in capsys.readouterr().out
    code = call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value", "--model-id", MODEL
    )
    assert code == 0
    assert "season_id=7" in capsys.readouterr().out


def test_start_sends_the_persona_defaults_and_the_fixed_season_config(spring, env):
    seen = {}
    original = spring.__call__

    def spy(method, url, headers, body, timeout):
        if url.endswith("/seasons") and method == "POST":
            seen.update(json.loads(body))
        return original(method, url, headers, body, timeout)

    code = cli.main(
        ["start", "--agent-id", "11", "--persona", "patient-value", "--model-id", MODEL],
        environ=env,
        transport=spy,
        sleep=lambda s: None,
    )
    assert code == 0
    assert seen["riskLimit"] == "-0.20"
    assert seen["formation"] == {"goalkeeper": 10, "defender": 30, "midfielder": 30, "forward": 30}
    assert seen["pathLength"] == 12
    config = seen["config"]
    assert config["temperature"] == "0" and config["modelSeed"] == 1
    assert config["maxTokens"] == 2048 and config["turnTimeoutMs"] == 120000
    assert config["promptTemplateVersion"] == "td1-p2"
    assert config["runnerVersion"] == "0.2.0"
    assert config["budgetCalls"] == 42 and config["budgetUsd"] == "0"
    assert config["modelRequested"] == MODEL and config["memoryVersion"] is None


def test_a_fake_run_finishes_with_exit_zero_and_prints_counts_only(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [hold(reason=MARKER), {**hold(), "repeat": 2}])
    assert call(spring, env, *run_args(script)) == 0
    out = capsys.readouterr()
    assert "state=FINISHED reason=FINISHED decisions=3 sends=3" in out.out
    assert MARKER not in out.out + out.err
    assert TOKEN not in out.out + out.err


def test_a_run_without_live_never_builds_a_network_adapter(tmp_path, spring, env, monkeypatch):
    def refuse(*args, **kwargs):
        raise AssertionError("a network adapter was built")

    monkeypatch.setattr(cli, "build_registry", refuse)
    monkeypatch.setattr(cli, "OpenRouterClient", refuse)
    script = script_file(tmp_path, [{**hold(), "repeat": 3}])
    assert call(spring, env, *run_args(script)) == 0


@pytest.mark.parametrize("extra", [["--live"], []])
def test_run_needs_exactly_one_model_source(tmp_path, spring, env, extra, capsys):
    argv = run_args(script_file(tmp_path, [hold()]))
    if not extra:
        argv = argv[: argv.index("--model-script")]
    assert call(spring, env, *argv, *extra) == 2
    assert "UsageError" in capsys.readouterr().err


def test_live_needs_a_call_cap(spring, env, monkeypatch, tmp_path):
    monkeypatch.setenv("OPENROUTER_API_KEY", "k" * 40)
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(tmp_path / "ledger.jsonl"))
    argv = [
        "run", "--agent-id", "1", "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--live",
    ]  # fmt: skip
    assert call(spring, env, *argv) == 2
    assert spring.requests["registerAgent"] == 0
    assert not (tmp_path / "ledger.jsonl").exists()


def test_live_needs_the_key_and_an_absolute_ledger_path(spring, env, monkeypatch, tmp_path):
    argv = [
        "run", "--agent-id", "1", "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--live", "--max-calls", "2",
    ]  # fmt: skip
    monkeypatch.delenv("OPENROUTER_API_KEY", raising=False)
    monkeypatch.delenv("ANTLAB_OPENROUTER_LEDGER", raising=False)
    assert call(spring, env, *argv) == 2
    monkeypatch.setenv("OPENROUTER_API_KEY", "k" * 40)
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", "relative/ledger.jsonl")
    assert call(spring, env, *argv) == 2


def test_the_call_cap_is_a_hard_stop_across_the_run(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [{**hold(), "repeat": 10}])
    assert call(spring, env, *run_args(script, "--max-calls", "2")) == 3
    assert "state=STOPPED reason=CALL_CAP decisions=2 sends=2" in capsys.readouterr().out
    # the run can go on, and the cap counts again from zero
    assert call(spring, env, *run_args(script, "--max-calls", "5")) == 0


def test_a_pause_exits_three_with_its_reason_and_needs_resume(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [{"status": "LIMITED", "http_status": 429}, hold()])
    assert call(spring, env, *run_args(script)) == 3
    assert "state=PAUSED reason=LIMITED_DAILY" in capsys.readouterr().out
    assert call(spring, env, *run_args(script)) == 2  # not resumed
    assert "StillPaused" in capsys.readouterr().err
    script = script_file(tmp_path, [{**hold(), "repeat": 5}])
    assert call(spring, env, *run_args(script, "--resume")) == 0


def test_an_api_refusal_exits_one_with_a_fixed_code(tmp_path, spring, env, capsys):
    spring.fail["getAgentTurnView"] = (404, "NOT_FOUND")
    code = call(spring, env, *run_args(script_file(tmp_path, [hold()])))
    err = capsys.readouterr().err
    assert code == 1
    assert "ApiProblem" in err and "NOT_FOUND" in err and TOKEN not in err


def test_an_unexpected_error_prints_only_its_class(tmp_path, spring, env, capsys, monkeypatch):
    def boom(*args, **kwargs):
        raise RuntimeError(f"secret detail {TOKEN}")

    monkeypatch.setattr(cli, "load_persona", boom)
    code = call(spring, env, *run_args(script_file(tmp_path, [hold()])))
    err = capsys.readouterr().err
    assert code == 1
    assert "RuntimeError" in err and "secret detail" not in err and TOKEN not in err


def test_missing_settings_and_an_unknown_persona_are_usage_errors(tmp_path, spring, env):
    assert call(spring, {}, *run_args(script_file(tmp_path, [hold()]))) == 2
    argv = run_args(script_file(tmp_path, [hold()]))
    argv[argv.index("patient-value")] = "no-such-persona"
    assert call(spring, env, *argv) == 2


def test_status_reads_the_journal_and_the_season(tmp_path, spring, env, capsys):
    call(spring, env, *run_args(script_file(tmp_path, [{**hold(), "repeat": 3}])))
    capsys.readouterr()
    assert call(spring, env, "status", "--agent-id", "1", "--season-id", "7") == 0
    out = capsys.readouterr().out
    assert "attempts=3 decided_turns=3 advanced_turns=3" in out
    assert "paused=false" in out and "season_status=FINISHED" in out


def test_a_second_runner_on_the_same_season_is_refused(tmp_path, spring, env):
    from antlab_ai.runner.journal import Journal

    with Journal.open(tmp_path / "state", 1, 7):
        assert call(spring, env, *run_args(script_file(tmp_path, [hold()]))) == 2


@pytest.mark.parametrize(
    "entries",
    [
        [],
        {"reply": {}},
        [{"status": "WRONG"}],
        [{"reply": {}, "text": "x"}],
        [{"unknown": 1}],
        [{"repeat": 0}],
        [{"text": 5}],
        ["x"],
    ],
)
def test_bad_scripts_are_refused(tmp_path, entries):
    with pytest.raises(ScriptError):
        load_script(script_file(tmp_path, entries))


def test_a_script_that_is_not_json_is_refused(tmp_path):
    path = tmp_path / "script.json"
    path.write_text("{not json", encoding="utf-8")
    with pytest.raises(ScriptError):
        load_script(path)


def live_env(monkeypatch, tmp_path, base_url):
    monkeypatch.setenv("OPENROUTER_API_KEY", "k" * 40)
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(tmp_path / "ledger.jsonl"))
    return {"OLLAMA_BASE_URL": base_url, "OLLAMA_API_KEY": "o" * 40}


def test_a_remote_ollama_address_is_refused_before_anything_is_built(
    env, monkeypatch, tmp_path, capsys
):
    spring = FakeSpring(path_length=3, model="llama3", route="OLLAMA")
    env.update(live_env(monkeypatch, tmp_path, "http://remote.example:11434"))

    def refuse(*args, **kwargs):
        raise AssertionError("an adapter was built")

    monkeypatch.setattr(cli, "build_registry", refuse)
    argv = [
        "run", "--agent-id", "1", "--season-id", "7", "--persona", "patient-value",
        "--model-id", "llama3", "--route", "OLLAMA", "--live", "--max-calls", "2",
    ]  # fmt: skip
    assert call(spring, env, *argv) == 2
    assert "remote Ollama" in capsys.readouterr().err


def test_a_season_is_bound_to_its_persona_model_and_route(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [{**hold(), "repeat": 5}])
    assert call(spring, env, *run_args(script, "--max-calls", "1")) == 3
    sends = spring.requests["submitAgentDecision"]
    argv = run_args(script)
    argv[argv.index(MODEL)] = "other/model:free"
    assert call(spring, env, *argv) == 2
    assert "differ" in capsys.readouterr().err
    assert spring.requests["submitAgentDecision"] == sends
    assert call(spring, env, *run_args(script)) == 0


def test_a_changed_persona_file_is_refused_on_resume(tmp_path, spring, env, monkeypatch):
    script = script_file(tmp_path, [{**hold(), "repeat": 5}])
    assert call(spring, env, *run_args(script, "--max-calls", "1")) == 3
    real = cli.load_persona("patient-value")
    monkeypatch.setattr(
        cli, "load_persona", lambda key: type(real)(**{**real.__dict__, "sha256": "0" * 64})
    )
    assert call(spring, env, *run_args(script)) == 2


def test_scripted_credit_refusal_needs_status_402(tmp_path):
    with pytest.raises(ScriptError):
        load_script(script_file(tmp_path, [{"status": "LIMITED", "credit_refusal": True}]))
