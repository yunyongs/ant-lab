"""`overlap run` end to end on a `ci` profile run: probe lines, resume, workers, refusals."""

import json
import shutil
from pathlib import Path

import pytest

from antlab_engine.validation import overlap_run
from antlab_engine.validation.__main__ import main
from antlab_engine.validation.experiment import (
    ExperimentError,
    analyze_overlap_run,
    profile_cells,
    run_profile,
)
from antlab_engine.validation.overlap import TRAITS, validate_overlap
from antlab_engine.validation.overlap_run import run_overlap

COMMIT = "abc1234"
CELL = profile_cells("ci")[0]
MAIN_FILE = Path("cells") / f"{CELL.id}.jsonl"
PROBE_FILE = Path("overlap") / "cells" / f"{CELL.id}.jsonl"
OVERLAP_JSON = Path("overlap") / "overlap.json"
PER_WORLD = 2 * CELL.reference


@pytest.fixture(scope="module")
def ci_run(tmp_path_factory):
    out = tmp_path_factory.mktemp("overlap-run") / "run"
    run_profile("ci", out, commit=COMMIT)
    return out


@pytest.fixture(scope="module")
def probed(ci_run, tmp_path_factory):
    out = tmp_path_factory.mktemp("probed") / "run"
    shutil.copytree(ci_run, out)
    run_overlap(out)
    return out


def copy_of(source: Path, target: Path) -> Path:
    shutil.copytree(source, target)
    return target


def lines(path: Path) -> list[dict]:
    return [json.loads(t) for t in path.read_text(encoding="utf-8").splitlines()]


def test_the_probe_file_holds_the_probe_roster_of_every_world(probed):
    docs = lines(probed / PROBE_FILE)
    assert len(docs) == CELL.worlds * PER_WORLD
    for world in range(CELL.worlds):
        mine = [d for d in docs if d["worldIndex"] == world]
        assert sorted((d["kind"], d["index"]) for d in mine) == sorted(
            [("trait", i) for i in range(CELL.reference)]
            + [("random", CELL.reference + i) for i in range(CELL.reference)]
        )
        assert all(d["schema"] == "process-overlap-trait/1" and d["cell"] == CELL.id for d in mine)
    sample = next(d for d in docs if d["kind"] == "trait")
    assert set(sample["traits"]) == set(TRAITS)
    assert set(sample["raw"]) == {
        "turnoverAnnual",
        "panicShare",
        "chaseShare",
        "noiseShare",
        "nudgeShare",
    }
    assert set(sample["flags"]) >= {"traded", "noise", "discipline", "nudge", "both"}
    assert next(d for d in docs if d["kind"] == "random")["traits"] == {}


def test_the_world_ids_equal_the_world_ids_of_the_main_cell(probed):
    main = {}
    for d in lines(probed / MAIN_FILE):
        main.setdefault(d["worldIndex"], d["worldId"])
    assert len(main) == CELL.worlds
    assert all(d["worldId"] == main[d["worldIndex"]] for d in lines(probed / PROBE_FILE))


def test_the_random_bots_of_the_probe_have_the_items_of_their_main_cell_lines(probed):
    main = {
        (d["worldIndex"], d["kind"], d["index"]): d["components"]["items"]
        for d in lines(probed / MAIN_FILE)
    }
    checked = 0
    for d in lines(probed / PROBE_FILE):
        if d["kind"] == "random":
            assert d["components"]["items"] == main[(d["worldIndex"], "random", d["index"])]
            checked += 1
    assert checked == CELL.worlds * CELL.reference


def test_run_analyses_the_probe_into_a_trait_population_and_trade_shares(probed):
    doc = json.loads((probed / OVERLAP_JSON).read_text(encoding="utf-8"))
    validate_overlap(doc)
    assert doc["status"] == "provisional"
    cell = doc["cells"][0]
    assert cell["populations"]["trait"]["bots"] == CELL.worlds * CELL.reference
    assert cell["eventResponse"]["verdict"] == "MECHANICS_ONLY"
    shares = cell["tradeShares"]
    assert set(shares) == {"random", "trait"}
    assert int(shares["trait"]["tradedWon"]) > 0 and int(shares["trait"]["noiseWon"]) > 0
    assert (probed / "overlap" / "run.json").exists()


def test_the_main_cell_file_is_not_touched(ci_run, probed):
    assert (probed / MAIN_FILE).read_bytes() == (ci_run / MAIN_FILE).read_bytes()


def test_a_killed_run_resumes_to_the_same_bytes_and_skips_finished_worlds(
    probed, tmp_path, monkeypatch
):
    run = copy_of(probed, tmp_path / "resume")
    whole = (run / PROBE_FILE).read_bytes()
    kept = (run / PROBE_FILE).read_text(encoding="utf-8").splitlines(keepends=True)
    cut = PER_WORLD + PER_WORLD // 2
    (run / PROBE_FILE).write_text("".join(kept[:cut]), encoding="utf-8")
    ran = []
    original = overlap_run.run_probe_world

    def counting(job):
        ran.append(job.index)
        return original(job)

    monkeypatch.setattr(overlap_run, "run_probe_world", counting)
    run_overlap(run)
    assert ran == [1, 2]
    assert (run / PROBE_FILE).read_bytes() == whole
    ran.clear()
    run_overlap(run)
    assert ran == []
    assert (run / PROBE_FILE).read_bytes() == whole


def test_one_and_two_workers_write_the_same_files(probed, ci_run, tmp_path):
    twin = copy_of(ci_run, tmp_path / "twin")
    run_overlap(twin, workers=2)
    assert (twin / PROBE_FILE).read_bytes() == (probed / PROBE_FILE).read_bytes()
    assert (twin / OVERLAP_JSON).read_bytes() == (probed / OVERLAP_JSON).read_bytes()
    assert (twin / "overlap" / "overlap_summary.md").read_bytes() == (
        probed / "overlap" / "overlap_summary.md"
    ).read_bytes()


def test_workers_beyond_the_light_mode_cap_are_refused(ci_run, tmp_path):
    with pytest.raises(ExperimentError, match="workers"):
        run_overlap(copy_of(ci_run, tmp_path / "w"), workers=3)


def test_an_unfinished_main_cell_is_refused(ci_run, tmp_path):
    run = copy_of(ci_run, tmp_path / "half")
    kept = [
        t
        for t in (run / MAIN_FILE).read_text(encoding="utf-8").splitlines()
        if json.loads(t)["worldIndex"] != 2
    ]
    (run / MAIN_FILE).write_text("".join(t + "\n" for t in kept), encoding="utf-8")
    with pytest.raises(ExperimentError, match="unfinished"):
        run_overlap(run)
    assert not (run / PROBE_FILE).exists()


def test_an_unknown_cell_is_refused(ci_run, tmp_path):
    with pytest.raises(ExperimentError, match="unknown cell"):
        run_overlap(copy_of(ci_run, tmp_path / "c"), cells=["nope"])


def test_a_probe_file_with_an_unfinished_world_is_refused_unless_partial(probed, tmp_path):
    run = copy_of(probed, tmp_path / "short")
    kept = (run / PROBE_FILE).read_text(encoding="utf-8").splitlines(keepends=True)
    (run / PROBE_FILE).write_text("".join(kept[: 2 * PER_WORLD - 1]), encoding="utf-8")
    with pytest.raises(ExperimentError, match="probe lines"):
        analyze_overlap_run(run)
    analyze_overlap_run(run, partial=True)
    doc = json.loads((run / OVERLAP_JSON).read_text(encoding="utf-8"))
    assert doc["status"] == "partial"
    assert doc["cells"][0]["populations"]["trait"]["worlds"] == 1


def test_a_probe_line_with_another_world_id_is_refused(probed, tmp_path):
    run = copy_of(probed, tmp_path / "foreign")
    kept = (run / PROBE_FILE).read_text(encoding="utf-8").splitlines()
    doc = json.loads(kept[0])
    doc["worldId"] = "0" * len(doc["worldId"])
    kept[0] = json.dumps(doc)
    (run / PROBE_FILE).write_text("".join(t + "\n" for t in kept), encoding="utf-8")
    with pytest.raises(ExperimentError, match="probe lines"):
        analyze_overlap_run(run)


def test_a_probe_line_of_a_world_outside_the_cell_is_refused(probed, tmp_path):
    run = copy_of(probed, tmp_path / "range")
    kept = (run / PROBE_FILE).read_text(encoding="utf-8").splitlines()
    doc = json.loads(kept[0])
    doc["worldIndex"] = CELL.worlds
    kept[0] = json.dumps(doc)
    (run / PROBE_FILE).write_text("".join(t + "\n" for t in kept), encoding="utf-8")
    with pytest.raises(ExperimentError, match="probe lines"):
        analyze_overlap_run(run)


def test_the_cli_runs_the_probe_and_reports_cost(ci_run, tmp_path, capsys):
    run = copy_of(ci_run, tmp_path / "cli")
    assert main(["overlap", "run", str(run), "--workers", "1"]) == 0
    shown = capsys.readouterr().out
    assert "wall" in shown and "peak rss" in shown and "overlap.json" in shown
    assert main(["overlap", "run", str(run), "--cells", "nope"]) == 2
    assert "unknown cell" in capsys.readouterr().err


PROBE_JSON = Path("overlap") / "cells" / "probe.json"


def test_the_probe_provenance_is_written_and_reaches_the_overlap_source(probed):
    probe = json.loads((probed / PROBE_JSON).read_text(encoding="utf-8"))
    assert probe["botVersions"] == {"random": 1, "trait": 1}
    assert probe["gitCommit"] and probe["engineVersion"] and probe["scoreVersion"]
    doc = json.loads((probed / OVERLAP_JSON).read_text(encoding="utf-8"))
    assert doc["source"]["botVersions"]["trait"] == 1
    assert doc["source"]["probe"]["gitCommit"] == probe["gitCommit"]


@pytest.mark.parametrize(
    "change",
    [
        lambda d: d["botVersions"].update(trait=2),
        lambda d: d.update(gitCommit="0000000"),
        lambda d: d.update(engineVersion="other"),
    ],
)
def test_a_resume_with_other_code_or_versions_is_refused(probed, tmp_path, change):
    run = copy_of(probed, tmp_path / "stale")
    path = run / PROBE_JSON
    doc = json.loads(path.read_text(encoding="utf-8"))
    change(doc)
    path.write_text(json.dumps(doc), encoding="utf-8")
    before = (run / PROBE_FILE).read_bytes()
    with pytest.raises(ExperimentError, match="other code or bot versions"):
        run_overlap(run)
    assert (run / PROBE_FILE).read_bytes() == before


def test_probe_lines_without_a_provenance_file_are_refused(probed, tmp_path):
    run = copy_of(probed, tmp_path / "unknown")
    (run / PROBE_JSON).rename(run / "overlap" / "elsewhere.json")
    with pytest.raises(ExperimentError, match="no provenance"):
        run_overlap(run)


def test_a_matching_provenance_resumes(probed, tmp_path):
    run = copy_of(probed, tmp_path / "same")
    run_overlap(run)
    assert (run / PROBE_FILE).read_bytes() == (probed / PROBE_FILE).read_bytes()
