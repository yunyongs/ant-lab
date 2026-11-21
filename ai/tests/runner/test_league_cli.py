"""AG-04 E: the C2 config check in run, and the league commands."""

import json

import pytest
from api_helpers import TOKEN
from fake_spring import FakeSpring
from loop_helpers import MODEL
from runner_helpers import reply

from antlab_ai.runner import __main__ as cli
from antlab_ai.runner.persona import load_persona

ROSTER = """\
[[entry]]
persona = "patient-value"
model_id = "vendor/model:free"

[[entry]]
persona = "trend-rider"
model_id = "vendor/model:free"
"""


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


def call(spring, env, *argv):
    return cli.main(list(argv), environ=env, transport=spring, sleep=lambda s: None)


def script_file(tmp_path):
    path = tmp_path / "script.json"
    path.write_text(json.dumps([{"reply": json.loads(reply()), "repeat": 5}]), encoding="utf-8")
    return path


def run_argv(script, **changes):
    values = {"--persona": "patient-value", "--model-id": MODEL}
    values.update(changes)
    argv = ["run", "--agent-id", "1", "--season-id", "7", "--model-script", str(script)]
    for name, value in values.items():
        argv += [name, value]
    return argv


# ---- C2: the recorded config is compared before any model call ---------------------------


def test_a_matching_config_runs_and_the_runner_version_may_differ(tmp_path, spring, env):
    spring.config_override = {"runnerVersion": "9.9.9"}
    assert call(spring, env, *run_argv(script_file(tmp_path))) == 0
    assert spring.requests["getAgentSeasonConfig"] == 1


@pytest.mark.parametrize(
    "field",
    [
        "personaKey",
        "personaVersion",
        "personaSha256",
        "modelRoute",
        "modelRequested",
        "promptTemplateVersion",
    ],  # fmt: skip
)
def test_each_recorded_field_that_differs_stops_the_run_before_any_send(
    tmp_path, spring, env, capsys, field
):
    base = call(spring, env, "status", "--agent-id", "1", "--season-id", "7")
    assert base == 0
    recorded = spring._getAgentSeasonConfig([1, 7], None)[1]
    other = {
        "personaKey": "trend-rider",
        "personaVersion": recorded["personaVersion"] + 1,
        "personaSha256": "0" * 64,
        "modelRoute": "OLLAMA",
        "modelRequested": "other/model:free",
        "promptTemplateVersion": "td1-p0",
    }[field]
    spring.config_override = {field: other}
    capsys.readouterr()
    code = call(spring, env, *run_argv(script_file(tmp_path)))
    err = capsys.readouterr().err
    assert code == 2 and "UsageError" in err and "differ" in err
    assert str(other) not in err and TOKEN not in err
    assert spring.requests["submitAgentDecision"] == 0
    assert spring.requests["getAgentTurnView"] == 1  # only the status call above; run stopped first


def test_a_mismatch_leaves_no_binding_file_and_no_model_call(tmp_path, spring, env, monkeypatch):
    def refuse(*args, **kwargs):
        raise AssertionError("a model port was built")

    monkeypatch.setattr(cli, "_port", refuse)
    spring.config_override = {"personaSha256": "0" * 64}
    assert call(spring, env, *run_argv(script_file(tmp_path))) == 2
    assert list((tmp_path / "state").glob("*.bind")) == []


def test_a_failing_config_read_stops_the_run_with_the_api_code(tmp_path, spring, env, capsys):
    spring.fail["getAgentSeasonConfig"] = (404, "NOT_FOUND")
    assert call(spring, env, *run_argv(script_file(tmp_path))) == 1
    assert "NOT_FOUND" in capsys.readouterr().err
    assert spring.requests["getAgentTurnView"] == 0


def test_the_local_binding_file_is_still_the_offline_second_check(tmp_path, spring, env, capsys):
    script = script_file(tmp_path)
    assert call(spring, env, *run_argv(script, **{"--model-id": MODEL}), "--max-calls", "1") == 3
    other = FakeSpring(path_length=3, model="other/model:free")
    other.config_override = {}
    code = call(other, env, *run_argv(script, **{"--model-id": "other/model:free"}))
    assert code == 2
    assert "differ" in capsys.readouterr().err
    assert other.requests["submitAgentDecision"] == 0


def test_a_started_season_records_the_config_the_runner_then_matches(tmp_path, spring, env):
    assert call(spring, env, "register", "--persona", "patient-value", "--model-id", MODEL) == 0
    assert call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value", "--model-id", MODEL
    ) == 0  # fmt: skip
    persona = load_persona("patient-value")
    recorded = spring.configs[7]
    assert recorded["personaSha256"] == persona.sha256
    argv = run_argv(script_file(tmp_path))
    argv[argv.index("1")] = "11"
    assert call(spring, env, *argv) == 0


# ---- the league commands -----------------------------------------------------------------


def test_league_create_show_and_close(spring, env, capsys):
    assert call(spring, env, "league", "create", "--name", "Cup", "--path-length", "24") == 0
    assert (
        "league_id=5 status=OPEN path_length=24 entries=0 max_entries=8" in capsys.readouterr().out
    )
    assert call(spring, env, "league", "show", "--league", "5") == 0
    assert "status=OPEN" in capsys.readouterr().out
    assert call(spring, env, "league", "close", "--league", "5") == 0
    assert "status=CLOSED" in capsys.readouterr().out


def test_league_create_sends_only_the_allowed_fields(spring, env):
    seen = []
    original = spring.__call__

    def spy(method, url, headers, body, timeout):
        if body is not None:
            seen.append(json.loads(body))
        return original(method, url, headers, body, timeout)

    cli.main(
        ["league", "create", "--name", "Cup"], environ=env, transport=spy, sleep=lambda s: None
    )
    assert seen == [{"name": "Cup"}]


def test_a_missing_league_is_an_api_refusal(spring, env, capsys):
    assert call(spring, env, "league", "show", "--league", "99") == 1
    assert "NOT_FOUND" in capsys.readouterr().err


def make_roster(tmp_path, text=ROSTER):
    path = tmp_path / "roster.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_league_enter_registers_and_starts_each_entry_with_the_league_id(
    tmp_path, spring, env, capsys
):
    call(spring, env, "league", "create", "--name", "Cup", "--path-length", "12")
    capsys.readouterr()
    roster = make_roster(tmp_path)
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 0
    out = capsys.readouterr().out
    assert "entered=2 already_seated=0" in out
    assert [b["leagueId"] for b in spring.started_with] == [5, 5]
    assert all("pathLength" not in b for b in spring.started_with)
    assert len(spring.leagues[5]["entries"]) == 2
    assert TOKEN not in out


def test_league_enter_is_idempotent_a_second_run_restarts_nobody(tmp_path, spring, env, capsys):
    call(spring, env, "league", "create", "--name", "Cup")
    roster = make_roster(tmp_path)
    call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster))
    capsys.readouterr()
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 0
    assert "entered=0 already_seated=2" in capsys.readouterr().out
    assert len(spring.leagues[5]["entries"]) == 2


def test_a_rerun_skips_seated_agents_even_when_their_start_would_hit_a_limit(
    tmp_path, spring, env, capsys
):
    call(spring, env, "league", "create", "--name", "Cup")
    roster = make_roster(tmp_path)
    call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster))
    spring.season_limit_agents = {
        11
    }  # the first agent's season is paused: the real start says SEASON_LIMIT
    starts = spring.requests["startAgentSeason"]
    capsys.readouterr()
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 0
    assert "entered=0 already_seated=2" in capsys.readouterr().out
    assert spring.requests["startAgentSeason"] == starts


def test_an_unseated_agent_at_the_season_limit_still_stops_the_enter(tmp_path, spring, env, capsys):
    call(spring, env, "league", "create", "--name", "Cup")
    spring.season_limit_agents = {11}
    roster = make_roster(tmp_path)
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 1
    assert "SEASON_LIMIT" in capsys.readouterr().err


def test_a_seat_taken_between_the_read_and_the_start_is_reported_not_fatal(
    tmp_path, spring, env, capsys
):
    call(spring, env, "league", "create", "--name", "Cup")
    original = spring.__call__
    state = {"raced": False}

    def racing(method, url, headers, body, timeout):
        if method == "POST" and url.endswith("/seasons") and not state["raced"]:
            state["raced"] = True
            spring.leagues[5]["entries"].append(
                {
                    "memberId": 11,
                    "memberKind": "AGENT",
                    "displayName": "x",
                    "seasonId": 300,
                    "status": "IN_PROGRESS",
                    "currentTurnNo": 0,
                }
            )
        return original(method, url, headers, body, timeout)

    roster = make_roster(tmp_path)
    code = cli.main(
        ["league", "enter", "--league", "5", "--roster", str(roster)],
        environ=env,
        transport=racing,
        sleep=lambda s: None,
    )
    assert code == 0
    assert "entered=1 already_seated=1" in capsys.readouterr().out


def test_a_closed_or_missing_league_is_refused_before_any_registration(tmp_path, spring, env):
    roster = make_roster(tmp_path)
    assert call(spring, env, "league", "enter", "--league", "99", "--roster", str(roster)) == 1
    call(spring, env, "league", "create", "--name", "Cup")
    call(spring, env, "league", "close", "--league", "5")
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 2
    assert spring.requests["registerAgent"] == 0


def test_close_with_an_unfinished_seat_is_refused_with_the_code(tmp_path, spring, env, capsys):
    call(spring, env, "league", "create", "--name", "Cup")
    call(spring, env, "league", "enter", "--league", "5", "--roster", str(make_roster(tmp_path)))
    capsys.readouterr()
    assert call(spring, env, "league", "close", "--league", "5") == 1
    assert "LEAGUE_NOT_SETTLED" in capsys.readouterr().err


def test_other_start_refusals_stop_league_enter(tmp_path, spring, env, capsys):
    call(spring, env, "league", "create", "--name", "Cup")
    call(spring, env, "league", "close", "--league", "5")
    capsys.readouterr()
    roster = make_roster(tmp_path)
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 2
    assert "closed" in capsys.readouterr().err


def test_a_bad_roster_is_refused_before_any_registration(tmp_path, spring, env):
    call(spring, env, "league", "create", "--name", "Cup")
    roster = make_roster(tmp_path, ROSTER + "seed = 7\n")
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 2
    assert spring.requests["registerAgent"] == 0


def test_an_unknown_persona_in_the_roster_is_refused_before_any_registration(tmp_path, spring, env):
    call(spring, env, "league", "create", "--name", "Cup")
    roster = make_roster(tmp_path, ROSTER.replace("trend-rider", "no-such-persona"))
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 2
    assert spring.requests["registerAgent"] == 0


def test_start_with_a_league_uses_the_league_path_and_omits_the_length(spring, env):
    call(spring, env, "league", "create", "--name", "Cup", "--path-length", "24")
    call(spring, env, "register", "--persona", "patient-value", "--model-id", MODEL)
    code = call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value",
        "--model-id", MODEL, "--league", "5",
    )  # fmt: skip
    assert code == 0
    body = spring.started_with[-1]
    assert body["leagueId"] == 5 and "pathLength" not in body
    assert body["config"]["budgetCalls"] == 78  # 3 calls per turn over 24 turns, 6 kept for memory


def test_start_with_a_league_refuses_another_path_length(spring, env, capsys):
    call(spring, env, "league", "create", "--name", "Cup", "--path-length", "24")
    code = call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value",
        "--model-id", MODEL, "--league", "5", "--path-length", "12",
    )  # fmt: skip
    assert code == 2
    assert spring.requests["startAgentSeason"] == 0


def test_start_without_a_league_sends_no_league_id_and_keeps_the_default_length(spring, env):
    call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value", "--model-id", MODEL
    )
    body = spring.started_with[-1]
    assert "leagueId" not in body and body["pathLength"] == 12


def test_league_enter_pins_the_latest_memory_of_each_agent(tmp_path, spring, env):
    spring.memory_versions.append(
        {
            "rules": ["One."],
            "reflection": {"schema": "season-reflection/1"},
            "carried": False,
            "source": 1,
        }
    )
    call(spring, env, "league", "create", "--name", "Cup")
    roster = make_roster(tmp_path, ROSTER)
    assert call(spring, env, "league", "enter", "--league", "5", "--roster", str(roster)) == 0
    assert [b["config"]["memoryVersion"] for b in spring.started_with] == [1, 1]
