"""AG-05 E: the memory commands, the start pin, the same-seed league option and the pinned turn screen."""

import json

import pytest
from api_helpers import TOKEN, contract_errors
from fake_spring import FakeSpring
from loop_helpers import MODEL, Harness, ok
from runner_helpers import reply
from step_helpers import YEAR, note, reflection

from antlab_ai.runner import __main__ as cli

ROUTE = "OLLAMA"


@pytest.fixture
def env(tmp_path):
    return {
        "ANTLAB_API_URL": "http://api.test:8080",
        "ANTLAB_AGENT_RUNNER_TOKEN": TOKEN,
        "ANTLAB_RUNNER_STATE_DIR": str(tmp_path / "state"),
    }


@pytest.fixture
def spring():
    return FakeSpring(path_length=3, model=MODEL, route=ROUTE)


def call(spring, env, *argv):
    return cli.main(list(argv), environ=env, transport=spring, sleep=lambda s: None)


def script_file(tmp_path, entries):
    path = tmp_path / "script.json"
    path.write_text(json.dumps(entries), encoding="utf-8")
    return path


def version(spring, rules, carried=False):
    spring.memory_versions.append(
        {"rules": rules, "reflection": reflection(), "carried": carried, "source": 90}
    )


def reflect_argv(script, *extra):
    return [
        "memory", "reflect", "--agent-id", "1", "--persona", "patient-value",
        "--model-id", MODEL, "--route", ROUTE, "--model-script", str(script), *extra,
    ]  # fmt: skip


# ---- start pins a memory version ---------------------------------------------------------


def start(spring, env, *extra):
    return call(
        spring, env, "start", "--agent-id", "11", "--persona", "patient-value",
        "--model-id", MODEL, *extra,
    )  # fmt: skip


def test_start_pins_the_latest_version_by_default(spring, env, capsys):
    version(spring, ["One."])
    version(spring, ["Two."])
    assert start(spring, env) == 0
    assert spring.started_with[-1]["config"]["memoryVersion"] == 2
    assert "memory_version=2" in capsys.readouterr().out
    assert spring.requests["getAgentMemory"] == 1


def test_start_without_any_memory_pins_none(spring, env, capsys):
    assert start(spring, env) == 0
    assert spring.started_with[-1]["config"]["memoryVersion"] is None
    assert "memory_version=none" in capsys.readouterr().out


def test_start_with_none_never_reads_the_memory(spring, env):
    version(spring, ["One."])
    assert start(spring, env, "--memory", "none") == 0
    assert spring.started_with[-1]["config"]["memoryVersion"] is None
    assert spring.requests["getAgentMemory"] == 0


def test_start_with_a_version_sends_it_as_it_is(spring, env):
    assert start(spring, env, "--memory", "1") == 0
    assert spring.started_with[-1]["config"]["memoryVersion"] == 1
    assert spring.requests["getAgentMemory"] == 0


@pytest.mark.parametrize("value", ["0", "-1", "x", "1.5", "", "٣"])
def test_start_refuses_a_bad_memory_option(spring, env, value):
    with pytest.raises(SystemExit) as stop:
        start(spring, env, "--memory", value)
    assert stop.value.code == 2
    assert spring.started_with == []


def test_league_create_passes_the_same_seed_root(spring, env, capsys):
    assert call(spring, env, "league", "create", "--name", "Twin", "--same-seed-as", "5") == 0
    assert spring.created_with[-1] == {"name": "Twin", "sameSeedAs": 5}
    call(spring, env, "league", "create", "--name", "Plain")
    assert "sameSeedAs" not in spring.created_with[-1]


# ---- the run checks the pin and builds the prompt from it ---------------------------------


def test_a_run_refuses_a_turn_screen_whose_memory_differs_from_the_pin(
    tmp_path, spring, env, capsys
):
    spring.config_override = {"memoryVersion": 2}
    version(spring, ["One."])
    path = script_file(tmp_path, [{"reply": json.loads(reply()), "repeat": 5}])
    code = call(
        spring, env, "run", "--agent-id", "1", "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--route", ROUTE, "--model-script", str(path),
    )  # fmt: skip
    assert code == 2
    assert "memory differs" in capsys.readouterr().err
    assert spring.requests["advanceAgentTurn"] == 0


def test_the_turn_prompt_carries_the_pinned_version_only(tmp_path):
    h = Harness(tmp_path, ok(), path_length=1, route=ROUTE)
    for n in (1, 2, 3):
        h.spring.memory_versions.append(
            {
                "rules": [f"Rule of version {n}."],
                "reflection": reflection(ruleCandidates=[f"Candidate of version {n}."]),
                "carried": False,
                "source": n,
            }
        )
    h.spring.configs[7] = {"memoryVersion": 2}
    h.run()
    user = h.port.requests[0].messages[1].content
    system = h.port.requests[0].messages[0].content
    assert "Rule of version 2." in user and "Rule of version 3." not in user
    assert "Candidate of version 1." in user and "Candidate of version 2." in user
    assert "Candidate of version 3." not in user
    assert "agent.memory" in system


def test_a_season_started_on_p1_keeps_the_p1_prompt(tmp_path):
    h = Harness(tmp_path, ok(), path_length=1, route=ROUTE, template_id="td1-p1")
    h.run()
    assert "agent.memory" not in h.port.requests[0].messages[0].content


def test_a_run_on_a_p1_season_matches_and_an_unknown_template_stops(tmp_path, spring, env):
    path = script_file(tmp_path, [{"reply": json.loads(reply()), "repeat": 5}])
    argv = [
        "run", "--agent-id", "1", "--season-id", "7", "--persona", "patient-value",
        "--model-id", MODEL, "--route", ROUTE, "--model-script", str(path),
    ]  # fmt: skip
    spring.config_override = {"promptTemplateVersion": "td1-p1"}
    assert call(spring, env, *argv) == 0
    spring.config_override = {"promptTemplateVersion": "td9-p9"}
    assert call(spring, env, *argv) == 2


# ---- memory pending, show, compare ----------------------------------------------------------


def test_pending_lists_ids_and_counts_only(spring, env, capsys):
    version(spring, ["Rule."])
    spring.memory_seasons = {7: "pending", 8: "pending", 9: "held", 10: "done"}
    assert call(spring, env, "memory", "pending", "--agent-id", "1") == 0
    out = capsys.readouterr().out.splitlines()
    assert out[0] == "latest_version=1 pending=2 held=1"
    assert out[1:] == ["pending season_id=7", "pending season_id=8"]


def test_show_prints_the_filtered_memory(spring, env, capsys):
    version(spring, ["Write the reason first."], carried=True)
    assert call(spring, env, "memory", "show", "--agent-id", "1") == 0
    out = capsys.readouterr().out
    assert "version=1 carried=true" in out
    assert "rule 1: Write the reason first." in out
    assert "good: Kept cash for the turns that needed it." in out
    assert "mistake OVERTRADE:" in out and "candidate:" in out
    assert call(spring, env, "memory", "show", "--agent-id", "1", "--version", "9") == 1


def test_show_without_any_memory_says_so(spring, env, capsys):
    assert call(spring, env, "memory", "show", "--agent-id", "1") == 0
    assert capsys.readouterr().out.strip() == "latest_version=none"


def side(version, multiple, persona=1):
    return {
        "memoryVersion": version,
        "personaVersion": persona,
        "valuationMultiple": multiple,
        "score": None,
        "failedTurns": 0,
        "experiencedSeed": False,
    }


def comparison():
    return {
        "versionA": 0,
        "versionB": 2,
        "groups": [
            {"groupLabel": "G1", "a": side(None, "1.01000000"), "b": side(2, "1.04000000", 2)}
        ],
        "summary": {
            "groups": 1,
            "bWins": 1,
            "anecdotal": True,
            "meanDiff": {
                "valuationMultiple": "0.03000000",
                "score": None,
                "resultScore": None,
                "processScore": None,
                "items": {
                    k: None
                    for k in (
                        "risk",
                        "discipline",
                        "diversification",
                        "turnover",
                        "consistency",
                        "formation",
                        "role",
                        "noise",
                        "nudge",
                    )  # fmt: skip
                },
            },
        },
    }


def test_compare_asks_with_both_versions_and_prints_markdown(spring, env, capsys, tmp_path):
    spring.comparison = comparison()
    assert contract_errors("MemoryComparisonDto", comparison()) == []
    assert (
        call(
            spring,
            env,
            "memory",
            "compare",
            "--agent-id",
            "1",
            "--version-a",
            "0",
            "--version-b",
            "2",
        )
        == 0
    )
    assert spring.last_query == "versionA=0&versionB=2"
    out = capsys.readouterr().out
    assert out.startswith("# Memory comparison: version 0 (A) and version 2 (B)")
    assert "anecdotal" in out and "| G1 | A |" in out and "| G1 | B | v2 | p2 |" in out
    assert "persona version differs between the sides in: G1" in out
    target = tmp_path / "report.md"
    assert call(
        spring, env, "memory", "compare", "--agent-id", "1", "--version-a", "0",
        "--version-b", "2", "--out", str(target),
    ) == 0  # fmt: skip
    assert target.read_text(encoding="utf-8") == out
    assert capsys.readouterr().out.strip() == "written=report.md"


# ---- memory reflect ---------------------------------------------------------------------------


def test_reflect_one_season_prints_counts_and_codes_only(tmp_path, spring, env, capsys):
    spring.memory_seasons = {7: "pending"}
    script = script_file(tmp_path, [{"reply": reflection()}, {"reply": note()}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 0
    out = capsys.readouterr()
    assert out.out.strip() == "season_id=7 state=ACCEPTED version=1 sends=2"
    assert out.err == "" and TOKEN not in out.out
    assert len(spring.memory_versions) == 1


def test_reflect_all_pending_goes_in_order_and_stops_at_the_cap_of_seasons(
    tmp_path, spring, env, capsys
):
    spring.memory_seasons = {7: "pending", 8: "pending", 9: "pending"}
    entries = [{"reply": reflection()}, {"reply": note()}] * 3
    script = script_file(tmp_path, entries)
    assert call(spring, env, *reflect_argv(script, "--all-pending", "--max-seasons", "2")) == 0
    lines = capsys.readouterr().out.splitlines()
    assert [line.split()[0] for line in lines] == ["season_id=7", "season_id=8"]
    assert spring.memory_seasons == {7: "done", 8: "done", 9: "pending"}
    assert len(spring.memory_versions) == 2 and spring.memory_versions[1]["source"] == 8


def test_reflect_stops_at_the_first_season_that_cannot_finish(tmp_path, spring, env, capsys):
    spring.memory_seasons = {7: "pending", 8: "pending"}
    script = script_file(tmp_path, [{"status": "POLICY_REFUSED", "http_status": 400}])
    assert call(spring, env, *reflect_argv(script, "--all-pending")) == 3
    lines = capsys.readouterr().out.splitlines()
    assert lines == ["season_id=7 state=PENDING reason=POLICY sends=1"]


def test_reflect_prints_discard_without_any_reply_text(tmp_path, spring, env, capsys):
    spring.memory_seasons = {7: "pending"}
    bad = reflection(good=[YEAR, "b line", "c line"])
    script = script_file(tmp_path, [{"reply": bad}, {"reply": bad}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 0
    out = capsys.readouterr()
    assert "state=DISCARDED" in out.out and "2008" not in out.out + out.err


def test_reflect_with_nothing_pending_says_so(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [{"reply": reflection()}])
    assert call(spring, env, *reflect_argv(script, "--all-pending")) == 0
    assert capsys.readouterr().out.strip() == "pending=0"


@pytest.mark.parametrize("route", ["CLAUDE_CLI", "CODEX_CLI"])
def test_reflect_never_runs_on_a_subscription_route(tmp_path, spring, env, capsys, route):
    spring.memory_seasons = {7: "pending"}
    argv = reflect_argv(script_file(tmp_path, [{"reply": reflection()}]), "--season-id", "7")
    argv[argv.index("--route") + 1] = route
    assert call(spring, env, *argv) == 2
    assert "subscription" in capsys.readouterr().err
    assert spring.requests["getReflectionView"] == 0


def test_reflect_needs_a_season_or_all_pending(tmp_path, spring, env, capsys):
    script = script_file(tmp_path, [{"reply": reflection()}])
    assert call(spring, env, *reflect_argv(script)) == 2


def test_reflect_needs_one_model_source(spring, env):
    spring.memory_seasons = {7: "pending"}
    argv = [
        "memory", "reflect", "--agent-id", "1", "--persona", "patient-value", "--model-id",
        MODEL, "--route", ROUTE, "--season-id", "7",
    ]  # fmt: skip
    assert call(spring, env, *argv) == 2
    assert spring.requests["getReflectionView"] == 0


def test_reflect_checks_the_recorded_config_before_any_call(tmp_path, spring, env):
    spring.memory_seasons = {7: "pending"}
    spring.config_override = {"modelRequested": "other/model"}
    script = script_file(tmp_path, [{"reply": reflection()}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 2
    assert spring.requests["getReflectionView"] == 0


def test_reflect_uses_the_recorded_budget(tmp_path, spring, env, capsys):
    spring.memory_seasons = {7: "pending"}
    spring.config_override = {"budgetCalls": 1}
    script = script_file(tmp_path, [{"reply": reflection()}, {"reply": note()}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 3
    assert "state=PENDING reason=BUDGET sends=1" in capsys.readouterr().out
    assert call(spring, env, *reflect_argv(script, "--season-id", "7", "--discard-on-budget")) == 0
    assert spring.memory_outcomes[7] == {"outcome": "DISCARDED", "codes": ["BUDGET"]}


def test_reflect_proceeds_when_the_persona_text_changed_since_the_season(
    tmp_path, spring, env, capsys
):
    spring.memory_seasons = {7: "pending"}
    spring.config_override = {"personaVersion": 99, "personaSha256": "0" * 64}
    script = script_file(tmp_path, [{"reply": reflection()}, {"reply": note()}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 0
    assert "state=ACCEPTED" in capsys.readouterr().out
    journal = next((tmp_path / "state").glob("*-season-7.jsonl")).read_text(encoding="utf-8")
    base = next(json.loads(x) for x in journal.splitlines() if '"mbase"' in x)
    assert base["personaVersion"] == 1 and len(base["personaSha256"]) == 64


@pytest.mark.parametrize(
    "override",
    [
        {"personaKey": "trend-rider"},
        {"modelRoute": "OPENROUTER"},
        {"modelRequested": "other/model"},
    ],
)
def test_reflect_refuses_a_changed_persona_key_route_or_model(
    tmp_path, spring, env, capsys, override
):
    spring.memory_seasons = {7: "pending"}
    spring.config_override = override
    script = script_file(tmp_path, [{"reply": reflection()}])
    assert call(spring, env, *reflect_argv(script, "--season-id", "7")) == 2
    assert "differ" in capsys.readouterr().err
    assert spring.requests["getReflectionView"] == 0
