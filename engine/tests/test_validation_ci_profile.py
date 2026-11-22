"""The experiment driver end to end on the `ci` profile: files, resume, workers and analysis."""

import ast
import json
import time
from dataclasses import fields, replace
from pathlib import Path

import pytest
from validation_support import counted_record, world_12

from antlab_engine.settings import (
    DB_HOST_VARIABLE,
    DB_NAME_VARIABLE,
    DB_USER_VARIABLE,
)
from antlab_engine.sim import money
from antlab_engine.validation import experiment
from antlab_engine.validation.__main__ import main
from antlab_engine.validation.checks import ROLE_EVAL, ROLE_NAMED, ROLE_REF
from antlab_engine.validation.components import (
    ComponentError,
    components_from_wire,
    components_of,
)
from antlab_engine.validation.composite import recompose_63_wire
from antlab_engine.validation.experiment import (
    Cell,
    ExperimentError,
    ProfileSkipped,
    analyze_run,
    profile_cells,
    roster,
    run_profile,
    scan_cell_file,
    validate_cell,
)
from antlab_engine.validation.output import MANIFEST_KEYS, MAX_SUMMARY_LINES, validate_result

COMMIT = "abc1234"
CELL = profile_cells("ci")[0]
CELL_FILE = Path("cells") / f"{CELL.id}.jsonl"


def run_ci(out: Path, *, workers: int = 1):
    return run_profile("ci", out, workers=workers, commit=COMMIT)


def read_lines(out: Path) -> list[dict]:
    text = (out / CELL_FILE).read_text(encoding="utf-8")
    return [json.loads(line) for line in text.splitlines()]


@pytest.fixture(scope="module")
def ci_run(tmp_path_factory):
    out = tmp_path_factory.mktemp("ci") / "run"
    started = time.monotonic()
    summary = run_ci(out)
    return out, summary, time.monotonic() - started


# ---------------------------------------------------------------------------- end to end


def test_the_ci_profile_runs_end_to_end_inside_ten_seconds(ci_run):
    out, summary, seconds = ci_run
    assert seconds <= 10
    assert summary.ran == {"ci12": [0, 1, 2]}
    assert (out / "result.json").exists() and (out / "summary.md").exists()


def test_the_result_matches_the_schema_and_the_manifest_rules(ci_run):
    out, _, _ = ci_run
    result = json.loads((out / "result.json").read_text(encoding="utf-8"))
    validate_result(result)
    assert result["schema"] == "validation-run/1"
    assert result["status"] == "provisional" and result["profile"] == "ci"
    manifest = result["manifest"]
    assert set(MANIFEST_KEYS) <= set(manifest)
    assert manifest["masterSeed"] == "303" and manifest["gitCommit"] == COMMIT
    assert all(isinstance(s, str) for s in manifest["worldSeeds"])
    assert len(manifest["worldSeeds"]) == 3
    assert manifest["scoreVersion"] == "s63-v3"
    assert manifest["botVersions"]["newsmix"] == 1 and manifest["botVersions"]["redeclare"] == 1
    assert manifest["catalogSha256"] and all(
        len(h) == 64 for h in manifest["catalogSha256"].values()
    )
    (cell,) = result["cells"]
    assert cell["cell"]["id"] == "ci12" and cell["worlds"] == {"total": 3, "train": 2, "test": 1}
    assert all(f["worlds"] == "all" for f in cell["formulas"])
    summary = (out / "summary.md").read_text(encoding="utf-8")
    assert len(summary.splitlines()) <= MAX_SUMMARY_LINES
    assert "profile ci" in summary


def test_every_world_holds_the_whole_roster_once(ci_run):
    out, _, _ = ci_run
    lines = read_lines(out)
    assert len(lines) == 3 * len(roster(CELL)) == 198
    assert [line["worldIndex"] for line in lines] == sorted(line["worldIndex"] for line in lines)
    scan = scan_cell_file(out / CELL_FILE, CELL)
    assert sorted(scan.complete) == [0, 1, 2] and not scan.unfinished and scan.bad_lines == 0


def test_the_recomposed_s63_score_equals_the_stored_score_of_every_line(ci_run):
    out, _, _ = ci_run
    for line in read_lines(out):
        comp = components_from_wire(line, line["components"])
        assert recompose_63_wire(comp) == line["score63"], (line["kind"], line["index"])


def test_the_counts_are_filled_from_the_requested_path_count(ci_run):
    out, _, _ = ci_run
    for line in read_lines(out):
        assert line["counts"]["sharpeDiff"]["n"] == CELL.paths


def test_both_guard_variants_keep_their_roster_names(ci_run):
    out, _, _ = ci_run
    kinds = {line["kind"] for line in read_lines(out)}
    assert {"disciplined", "disciplined-noguard", "news", "newsmix-50", "redeclare"} <= kinds


# ---------------------------------------------------------------------------- run == analyze


def test_analyze_gives_the_same_bytes_as_run(ci_run):
    out, _, _ = ci_run
    first = (out / "result.json").read_bytes(), (out / "summary.md").read_bytes()
    analyze_run(out)
    assert ((out / "result.json").read_bytes(), (out / "summary.md").read_bytes()) == first


def test_run_analyses_the_parsed_file_not_the_values_in_memory(tmp_path, monkeypatch):
    parsed = []
    original = experiment.row_of

    def spy(line):
        parsed.append(line["worldIndex"])
        return original(line)

    monkeypatch.setattr(experiment, "row_of", spy)
    run_ci(tmp_path / "run")
    assert len(parsed) == 198


def test_the_rows_given_to_the_analysis_are_the_parsed_file_values(tmp_path, monkeypatch):
    seen = []
    original = experiment.analyze_cell

    def spy(rows, master_seed, **kwargs):
        seen.append(list(rows))
        return original(rows, master_seed, **kwargs)

    monkeypatch.setattr(experiment, "analyze_cell", spy)
    out = tmp_path / "run"
    run_ci(out)
    (rows,) = seen
    parsed = [json.loads(line) for line in (out / CELL_FILE).read_text("utf-8").splitlines()]
    assert len(rows) == len(parsed) == 198
    for row, line in zip(rows, parsed, strict=True):
        assert row.components == components_from_wire(line, line["components"])


def test_the_cli_runs_and_analyzes_to_the_same_result(tmp_path, capsys):
    out = tmp_path / "cli"
    assert main(["run", "--profile", "ci", "--out", str(out)]) == 0
    first = (out / "result.json").read_bytes()
    assert main(["analyze", str(out)]) == 0
    assert (out / "result.json").read_bytes() == first
    assert "result" in capsys.readouterr().out


def test_analyze_refuses_an_unfinished_run(tmp_path, capsys):
    out = tmp_path / "run"
    run_ci(out)
    path = out / CELL_FILE
    kept = path.read_text(encoding="utf-8").splitlines(keepends=True)[:-1]
    path.write_text("".join(kept), encoding="utf-8")
    with pytest.raises(ExperimentError, match="unfinished"):
        analyze_run(out)
    assert main(["analyze", str(out)]) == 2
    assert "unfinished" in capsys.readouterr().err
    with pytest.raises(ExperimentError, match="not a validation run"):
        analyze_run(tmp_path / "empty")


# ---------------------------------------------------------------------------- the line format


def test_components_survive_the_line_format(ci_run):
    out, _, _ = ci_run
    world = world_12()
    for kind, index in (("random", 3), ("disciplined-noguard", 0), ("news", 0), ("static", 1)):
        record = counted_record(world, kind, index)
        original = components_of(record)
        line = json.loads(
            experiment.component_line("c", 4, ROLE_NAMED, record, replace(original, kind=kind))
        )
        row = experiment.row_of(line)
        assert (row.world, row.role) == (4, ROLE_NAMED)
        back = row.components
        assert (back.world_id, back.path_length, back.kind, back.index) == (
            original.world_id,
            original.path_length,
            kind,
            original.index,
        )
        for f in fields(original):
            if f.name in ("items", "result", "world_id", "path_length", "kind", "index"):
                continue
            assert getattr(back, f.name) == money.round12(getattr(original, f.name)), f.name
        assert back.items == {
            name: None if value is None else money.round12(value)
            for name, value in original.items.items()
        }
        assert back.result == (None if original.result is None else money.round12(original.result))
        assert back.to_wire() == original.to_wire()


def test_a_malformed_line_is_refused():
    wire = components_of(counted_record(world_12(), "random", 0)).to_wire()
    identity = {"worldId": "w", "pathLength": 12, "kind": "random", "index": 0}
    assert components_from_wire(identity, wire).kind == "random"
    with pytest.raises(ComponentError):
        components_from_wire({**identity, "index": "0"}, wire)
    with pytest.raises(ComponentError):
        components_from_wire({**identity, "kind": None}, wire)
    with pytest.raises(ComponentError):
        components_from_wire(identity, {**wire, "cagr": 1.5})
    with pytest.raises(ComponentError):
        components_from_wire(identity, {k: v for k, v in wire.items() if k != "items"})


# ---------------------------------------------------------------------------- workers


def test_one_and_two_workers_write_identical_files(tmp_path):
    one, two = tmp_path / "one", tmp_path / "two"
    run_ci(one, workers=1)
    run_ci(two, workers=2)
    for name in (CELL_FILE, Path("result.json"), Path("summary.md")):
        assert (one / name).read_bytes() == (two / name).read_bytes(), name


def test_workers_are_capped_for_the_light_resource_mode(tmp_path):
    with pytest.raises(ExperimentError, match="workers"):
        run_profile("ci", tmp_path / "x", workers=3, commit=COMMIT)
    with pytest.raises(ExperimentError, match="workers"):
        run_profile("ci", tmp_path / "x", workers=0, commit=COMMIT)
    with pytest.raises(SystemExit):
        main(["run", "--profile", "ci", "--workers", "3", "--out", str(tmp_path / "y")])
    assert not (tmp_path / "x").exists()


def test_a_world_is_one_unit_of_work_with_one_counts_cache(tmp_path, monkeypatch):
    made = []
    original = experiment.CachedEngineCounts

    class Spy(original):
        def __init__(self, panel):
            super().__init__(panel)
            made.append(self)

    monkeypatch.setattr(experiment, "CachedEngineCounts", Spy)
    run_ci(tmp_path / "run")
    assert len(made) == 3
    assert [c.draws for c in made] == [1, 1, 1]


# ---------------------------------------------------------------------------- resume


def test_a_finished_run_is_not_run_again(tmp_path):
    out = tmp_path / "run"
    run_ci(out)
    first = (out / CELL_FILE).read_bytes(), (out / "result.json").read_bytes()
    again = run_ci(out)
    assert again.ran == {"ci12": []}
    assert ((out / CELL_FILE).read_bytes(), (out / "result.json").read_bytes()) == first


def test_only_the_missing_worlds_are_run_on_resume(tmp_path):
    out = tmp_path / "run"
    run_ci(out)
    path = out / CELL_FILE
    first = path.read_bytes(), (out / "result.json").read_bytes()
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    kept = [line for line in lines if json.loads(line)["worldIndex"] != 1]
    path.write_text("".join(kept), encoding="utf-8")
    resumed = run_ci(out)
    assert resumed.ran == {"ci12": [1]}
    assert (path.read_bytes(), (out / "result.json").read_bytes()) == first


def test_a_half_written_world_is_run_again_and_a_torn_line_is_dropped(tmp_path):
    out = tmp_path / "run"
    run_ci(out)
    path = out / CELL_FILE
    first = path.read_bytes()
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    torn = "".join(lines[:-30]) + lines[-30][:40]
    path.write_text(torn, encoding="utf-8")
    scan = scan_cell_file(path, CELL)
    assert scan.unfinished == [2] and sorted(scan.complete) == [0, 1] and scan.bad_lines == 1
    resumed = run_ci(out)
    assert resumed.ran == {"ci12": [2]}
    assert path.read_bytes() == first


def test_a_directory_of_another_run_is_refused(tmp_path):
    out = tmp_path / "run"
    run_ci(out)
    with pytest.raises(ExperimentError, match="another run"):
        run_profile("ci", out, master_seed=7, commit=COMMIT)
    with pytest.raises(ExperimentError, match="another run"):
        run_profile("ci", out, commit="def5678")


# ---------------------------------------------------------------------------- roster and profiles


def test_the_reference_and_evaluation_bots_never_share_an_index():
    specs = roster(CELL)
    ref = [s.index for s in specs if s.kind == "random" and s.role == ROLE_REF]
    evaluation = [s.index for s in specs if s.kind == "random" and s.role == ROLE_EVAL]
    assert ref == list(range(20)) and evaluation == list(range(20, 40))
    assert [s.index for s in specs if s.kind == "static"] == list(range(20))
    assert {s.role for s in specs if s.kind == "static"} == {ROLE_EVAL}


def test_redeclare_bots_pair_with_evaluation_indices_not_reference_indices():
    for cell in profile_cells("ci") + profile_cells("offline"):
        specs = roster(cell)
        evaluation = {s.index for s in specs if s.kind == "random" and s.role == ROLE_EVAL}
        reference = {s.index for s in specs if s.kind == "random" and s.role == ROLE_REF}
        redeclared = [s.index for s in specs if s.kind == "redeclare"]
        assert len(redeclared) == cell.redeclare > 0
        assert set(redeclared) <= evaluation and not set(redeclared) & reference
        assert redeclared == [cell.reference + i for i in range(cell.redeclare)]


def test_the_offline_roster_is_the_planned_656_bots():
    cell = profile_cells("offline")[0]
    specs = roster(cell)
    assert len(specs) == 656
    kinds = [s.kind for s in specs]
    assert kinds.count("random") == 400 and kinds.count("static") == 200
    assert kinds.count("redeclare") == 50
    assert {"disciplined", "disciplined-noguard", "news"} <= set(kinds)
    assert {f"newsmix-{q}" for q in (25, 50, 75)} <= set(kinds)
    assert len(set((s.kind, s.index) for s in specs)) == 656


def test_the_profiles_have_the_planned_sizes():
    offline = {c.id: c for c in profile_cells("offline")}
    assert list(offline) == ["p60s", "p60e", "p24s", "p12s"]
    assert [(c.worlds, c.turns, c.generator) for c in offline.values()] == [
        (100, 60, "simple"),
        (60, 60, "stationary"),
        (60, 24, "simple"),
        (60, 12, "simple"),
    ]
    for cell in offline.values():
        assert (cell.reference, cell.paths, cell.assets, cell.redeclare) == (200, 2000, 30, 50)
    assert [(c.id, c.worlds, c.turns, c.source) for c in profile_cells("sample-db")] == [
        ("sdb24", 20, 24, "db")
    ]
    assert [(c.id, c.worlds, c.turns, c.source) for c in profile_cells("post-rl")] == [
        ("rl60", 100, 60, "db"),
        ("rl24", 60, 24, "db"),
    ]
    assert (CELL.worlds, CELL.turns, CELL.assets, CELL.reference, CELL.paths) == (
        3,
        12,
        12,
        20,
        100,
    )
    for name in experiment.PROFILES:
        assert len(profile_cells(name)) <= 5
    with pytest.raises(ExperimentError, match="unknown profile"):
        profile_cells("nope")


def test_a_roster_without_every_dose_point_fails_before_anything_runs(tmp_path, monkeypatch):
    offline = profile_cells("offline")[0]
    for newsmix in ((50,), (25, 75), ()):
        with pytest.raises(ExperimentError, match="dose"):
            validate_cell(replace(offline, newsmix=newsmix))
    short = replace(offline, newsmix=(25, 75))
    monkeypatch.setattr(experiment, "profile_cells", lambda _name: (short,))
    with pytest.raises(ExperimentError, match="dose"):
        run_profile("offline", tmp_path / "run", commit=COMMIT)
    assert not (tmp_path / "run").exists()


def test_invalid_cells_are_refused():
    base = CELL
    for bad in (
        replace(base, worlds=1),
        replace(base, paths=99),
        replace(base, reference=1),
        replace(base, redeclare=base.reference + 1),
        replace(base, generator="other"),
        replace(base, assets=3),
        replace(base, id="Bad Id"),
        replace(base, newsmix=(50, 50)),
        replace(base, newsmix=(0,), dose_required=()),
        replace(base, source="file"),
    ):
        with pytest.raises(ExperimentError):
            validate_cell(bad)
    assert Cell.from_descriptor(base.descriptor()) == base
    with pytest.raises(ExperimentError):
        Cell.from_descriptor({"id": "x"})


# ---------------------------------------------------------------------------- database profiles


@pytest.mark.parametrize("profile", ["sample-db", "post-rl"])
def test_database_profiles_skip_cleanly_without_settings(profile, tmp_path, monkeypatch, capsys):
    for name in (DB_HOST_VARIABLE, DB_NAME_VARIABLE, DB_USER_VARIABLE):
        monkeypatch.delenv(name, raising=False)
    out = tmp_path / "run"
    with pytest.raises(ProfileSkipped, match="no database settings"):
        run_profile(profile, out, commit=COMMIT)
    assert main(["run", "--profile", profile, "--out", str(out)]) == 0
    assert "skipped" in capsys.readouterr().out
    assert not out.exists()


# ---------------------------------------------------------------------------- source rules

VALIDATION_DIR = Path(experiment.__file__).parent
BANNED = ("psycopg", "httpx", "requests", "urllib", "socket", "antlab_engine.history")


def test_no_validation_module_reaches_a_database_or_the_network_directly():
    for path in sorted(VALIDATION_DIR.glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            for name in names:
                assert not name.startswith(BANNED), (path.name, name)


def test_the_database_world_reader_is_imported_only_inside_a_function():
    tree = ast.parse((VALIDATION_DIR / "experiment.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.ImportFrom):
            assert "world_pg" not in (node.module or "") + ",".join(a.name for a in node.names)
    inside = [
        n
        for fn in ast.walk(tree)
        if isinstance(fn, ast.FunctionDef)
        for n in ast.walk(fn)
        if isinstance(n, ast.ImportFrom) and any(a.name == "world_pg" for a in n.names)
    ]
    assert inside


# ---------------------------------------------------------------------------- git commit

SHA = "0123456789abcdef0123456789abcdef01234567"


def test_the_commit_is_read_from_the_checkout_or_unknown(tmp_path):
    assert experiment.git_commit() == "unknown" or len(experiment.git_commit()) == 7
    assert experiment.read_git_commit(tmp_path) == "unknown"
    repo = tmp_path / "plain"
    (repo / ".git" / "refs" / "heads").mkdir(parents=True)
    (repo / ".git" / "HEAD").write_text("ref: refs/heads/main\n", encoding="utf-8")
    assert experiment.read_git_commit(repo) == "unknown"
    (repo / ".git" / "refs" / "heads" / "main").write_text(SHA + "\n", encoding="utf-8")
    inner = repo / "engine" / "src"
    inner.mkdir(parents=True)
    assert experiment.read_git_commit(inner) == SHA[:7]
    (repo / ".git" / "HEAD").write_text(SHA + "\n", encoding="utf-8")
    assert experiment.read_git_commit(repo) == SHA[:7]


def test_the_commit_is_found_through_packed_refs_and_a_linked_worktree(tmp_path):
    main = tmp_path / "main" / ".git"
    (main / "worktrees" / "w").mkdir(parents=True)
    (main / "packed-refs").write_text(f"# pack\n{SHA} refs/heads/feat\n", encoding="utf-8")
    linked = main / "worktrees" / "w"
    (linked / "HEAD").write_text("ref: refs/heads/feat\n", encoding="utf-8")
    (linked / "commondir").write_text("../..\n", encoding="utf-8")
    checkout = tmp_path / "work"
    checkout.mkdir()
    (checkout / ".git").write_text(f"gitdir: {linked}\n", encoding="utf-8")
    assert experiment.read_git_commit(checkout) == SHA[:7]
    (linked / "HEAD").write_text("ref: refs/heads/other\n", encoding="utf-8")
    assert experiment.read_git_commit(checkout) == "unknown"


# ---------------------------------------------------------------------------- review round 2


def test_a_changed_profile_cell_set_is_refused(tmp_path, monkeypatch):
    out = tmp_path / "run"
    run_ci(out)
    monkeypatch.setattr(experiment, "profile_cells", lambda _name: (replace(CELL, worlds=4),))
    with pytest.raises(ExperimentError, match="another run"):
        run_ci(out)


def _db_env(monkeypatch, replay_start):
    monkeypatch.setenv(DB_HOST_VARIABLE, "localhost")
    monkeypatch.setenv(DB_NAME_VARIABLE, "x")
    monkeypatch.setenv(DB_USER_VARIABLE, "x")
    monkeypatch.setenv("ANTLAB_HISTORY_REPLAY_START", replay_start)


def test_a_changed_replay_start_is_refused_before_any_database_call(tmp_path, monkeypatch):
    calls = []

    def stop(*args):
        calls.append(args)
        raise ExperimentError("stop here")

    monkeypatch.setattr(experiment, "_db_jobs", stop)
    out = tmp_path / "run"
    _db_env(monkeypatch, "2007-01-01")
    with pytest.raises(ExperimentError, match="stop here"):
        run_profile("sample-db", out, commit=COMMIT)
    assert len(calls) == 1
    _db_env(monkeypatch, "2010-01-01")
    with pytest.raises(ExperimentError, match="another run"):
        run_profile("sample-db", out, commit=COMMIT)
    assert len(calls) == 1
    _db_env(monkeypatch, "2007-01-01")
    with pytest.raises(ExperimentError, match="stop here"):
        run_profile("sample-db", out, commit=COMMIT)
    assert len(calls) == 2
    _db_env(monkeypatch, "2007-01-15")
    with pytest.raises(ExperimentError, match="replay start"):
        run_profile("sample-db", tmp_path / "other", commit=COMMIT)


def test_panel_read_failures_become_clear_refusals(tmp_path, monkeypatch, capsys):
    from antlab_engine.bots import world_pg
    from antlab_engine.engine_errors import HistoryUnavailable

    def fail(*args):
        raise HistoryUnavailable("down")

    monkeypatch.setattr(world_pg, "read_panel", fail)
    _db_env(monkeypatch, "2007-01-01")
    with pytest.raises(ExperimentError, match="HISTORY_UNAVAILABLE"):
        run_profile("sample-db", tmp_path / "run", commit=COMMIT)
    assert main(["run", "--profile", "sample-db", "--out", str(tmp_path / "run2")]) == 2
    assert "HISTORY_UNAVAILABLE" in capsys.readouterr().err


def test_a_torn_manifest_is_refused_with_a_message_and_writes_leave_no_temp_file(tmp_path):
    out = tmp_path / "run"
    run_ci(out)
    assert not list(out.glob("*.tmp")) and not list((out / "cells").glob("*.tmp"))
    (out / "manifest.json").write_text('{"schema": "validation-dr', encoding="utf-8")
    with pytest.raises(ExperimentError, match="unreadable"):
        run_ci(out)


def test_the_manifest_records_analysis_time_and_the_peak_memory(ci_run):
    out, _, _ = ci_run
    timing = json.loads((out / "manifest.json").read_text(encoding="utf-8"))["timing"]
    assert {
        "wallSeconds",
        "cpuSeconds",
        "analysisWallSeconds",
        "analysisCpuSeconds",
        "peakRssMbLargestProcess",
    } <= set(timing)


def test_foreign_lines_with_odd_types_are_dropped_not_crashed_on(tmp_path):
    path = tmp_path / "c.jsonl"
    good = {"schema": "validation-component/1", "cell": "ci12", "worldIndex": 0}
    lines = [
        {**good, "kind": ["x"], "index": 0, "role": "ref"},
        {**good, "kind": "random", "index": "0", "role": "ref"},
        {**good, "kind": "random", "index": 0, "role": ["ref"]},
        {**good, "kind": "random", "index": 0, "role": "boss"},
    ]
    path.write_text("".join(json.dumps(d) + "\n" for d in lines), encoding="utf-8")
    scan = scan_cell_file(path, CELL)
    assert scan.bad_lines == 4 and not scan.complete and not scan.unfinished


def test_every_roster_bot_records_its_own_kind_and_a_mismatch_is_refused(tmp_path, monkeypatch):
    from antlab_engine.bots.runner import make_bot

    for spec in roster(profile_cells("offline")[0]):
        assert make_bot(spec.kind, spec.index, 1, 2).kind == spec.kind
    original = experiment.make_bot

    def wrong(kind, index, master, seed):
        return original("random" if kind == "news" else kind, index, master, seed)

    monkeypatch.setattr(experiment, "make_bot", wrong)
    with pytest.raises(ExperimentError, match="recorded kind"):
        run_ci(tmp_path / "run")
