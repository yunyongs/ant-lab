"""`overlap analyze` end to end on a `ci` profile run created inside the test's directory."""

import json
import re
import shutil
from pathlib import Path

import pytest

from antlab_engine.validation import overlap as overlap_module
from antlab_engine.validation.__main__ import main
from antlab_engine.validation.experiment import (
    ExperimentError,
    analyze_overlap_run,
    profile_cells,
    run_profile,
)
from antlab_engine.validation.overlap import FLAG_KEYS, ITEMS, PAIRS, TRAITS, validate_overlap

COMMIT = "abc1234"
CELL = profile_cells("ci")[0]
CELL_FILE = Path("cells") / f"{CELL.id}.jsonl"
OVERLAP_DIR = Path("overlap")


@pytest.fixture(scope="module")
def ci_run(tmp_path_factory):
    out = tmp_path_factory.mktemp("overlap") / "run"
    run_profile("ci", out, commit=COMMIT)
    return out


def copy_of(source: Path, target: Path) -> Path:
    shutil.copytree(source, target)
    return target


def cell_lines(out: Path) -> list[str]:
    return (out / CELL_FILE).read_text(encoding="utf-8").splitlines()


def test_analyze_writes_the_json_and_a_short_summary(ci_run, capsys):
    assert main(["overlap", "analyze", str(ci_run)]) == 0
    shown = capsys.readouterr().out
    assert "overlap.json" in shown and "overlap_summary.md" in shown
    doc = json.loads((ci_run / OVERLAP_DIR / "overlap.json").read_text(encoding="utf-8"))
    validate_overlap(doc)
    assert doc["schema"] == "process-overlap/1" and doc["status"] == "provisional"
    assert doc["source"]["masterSeed"] == "303" and doc["source"]["gitCommit"] == COMMIT
    assert doc["primaryCell"] == CELL.id and doc["cells"][0]["worlds"] == {
        "total": 3,
        "analysed": 3,
    }
    random_pop = doc["cells"][0]["populations"]["random"]
    assert [(p["x"], p["y"]) for p in random_pop["pairs"]] == list(PAIRS)
    assert random_pop["bots"] == 3 * 2 * CELL.reference
    assert "trait" not in doc["cells"][0]["populations"]
    assert set(doc["items"]) == {*ITEMS, "consistency"}
    assert doc["items"]["consistency"]["verdict"] == "NOT_ASSESSABLE"
    summary = (ci_run / OVERLAP_DIR / "overlap_summary.md").read_text(encoding="utf-8")
    assert len(summary.splitlines()) <= 40 and summary.endswith("\n")
    text = json.dumps(doc)
    for number in re.findall(r"-?\d+(?:\.\d+)?", summary):
        assert number in text, number


def test_the_nudge_item_is_constant_among_random_bots_so_not_assessable(ci_run):
    assert main(["overlap", "analyze", str(ci_run)]) == 0
    doc = json.loads((ci_run / OVERLAP_DIR / "overlap.json").read_text(encoding="utf-8"))
    pairs = doc["cells"][0]["populations"]["random"]["pairs"]
    nudge = [p for p in pairs if "nudge" in (p["x"], p["y"])]
    assert nudge and all(
        p["verdict"] == "NOT_ASSESSABLE" and p["reason"] == "constant" for p in nudge
    )


def test_two_analyses_of_one_run_are_byte_identical(ci_run, tmp_path):
    assert main(["overlap", "analyze", str(ci_run)]) == 0
    first = [
        (ci_run / OVERLAP_DIR / n).read_bytes() for n in ("overlap.json", "overlap_summary.md")
    ]
    twin = copy_of(ci_run, tmp_path / "twin")
    shutil.rmtree(twin / OVERLAP_DIR, ignore_errors=True)
    assert main(["overlap", "analyze", str(twin)]) == 0
    second = [(twin / OVERLAP_DIR / n).read_bytes() for n in ("overlap.json", "overlap_summary.md")]
    assert first == second
    assert not list((ci_run / OVERLAP_DIR).glob("*.tmp"))


def test_an_unfinished_cell_is_refused_without_partial(ci_run, tmp_path, capsys):
    run = copy_of(ci_run, tmp_path / "half")
    kept = [text for text in cell_lines(run) if json.loads(text)["worldIndex"] != 2]
    (run / CELL_FILE).write_text("".join(t + "\n" for t in kept), encoding="utf-8")
    shutil.rmtree(run / OVERLAP_DIR, ignore_errors=True)
    assert main(["overlap", "analyze", str(run)]) == 2
    assert "unfinished" in capsys.readouterr().err
    assert not (run / OVERLAP_DIR).exists()
    with pytest.raises(ExperimentError, match="--partial"):
        analyze_overlap_run(run)


def test_partial_analyses_the_finished_worlds_and_says_so(ci_run, tmp_path):
    run = copy_of(ci_run, tmp_path / "half")
    kept = [text for text in cell_lines(run) if json.loads(text)["worldIndex"] != 2]
    (run / CELL_FILE).write_text("".join(t + "\n" for t in kept), encoding="utf-8")
    shutil.rmtree(run / OVERLAP_DIR, ignore_errors=True)
    assert main(["overlap", "analyze", str(run), "--partial"]) == 0
    doc = json.loads((run / OVERLAP_DIR / "overlap.json").read_text(encoding="utf-8"))
    assert doc["status"] == "partial"
    assert doc["cells"][0]["worlds"] == {"total": 3, "analysed": 2}
    assert doc["cells"][0]["assessableBase"] == "analysed"
    summary = (run / OVERLAP_DIR / "overlap_summary.md").read_text(encoding="utf-8")
    assert "Status: partial" in summary


def test_partial_on_a_finished_run_keeps_the_normal_status(ci_run, tmp_path):
    run = copy_of(ci_run, tmp_path / "whole")
    assert main(["overlap", "analyze", str(run), "--partial"]) == 0
    doc = json.loads((run / OVERLAP_DIR / "overlap.json").read_text(encoding="utf-8"))
    assert doc["status"] == "provisional"


def test_a_directory_that_is_not_a_run_is_an_error(tmp_path, capsys):
    assert main(["overlap", "analyze", str(tmp_path)]) == 2
    assert "not a validation run directory" in capsys.readouterr().err


def trait_cell_lines(run: Path) -> list[str]:
    ids = {}
    for text in cell_lines(run):
        doc = json.loads(text)
        ids.setdefault(doc["worldIndex"], doc["worldId"])
    lines = []
    for world in range(CELL.worlds):
        for i in range(2 * CELL.reference):
            is_trait = i < CELL.reference
            level = {t: (i * (k + 2) + i // (k + 2) + world) % 5 for k, t in enumerate(TRAITS)}
            items = {name: (i * (j + 5) + world) % 100 for j, name in enumerate(ITEMS)}
            items["noise"] = 100 - 20 * level["follow"] + i % 7
            lines.append(
                {
                    "schema": "process-overlap-trait/1",
                    "cell": CELL.id,
                    "worldIndex": world,
                    "worldId": ids[world],
                    "kind": "trait" if is_trait else "random",
                    "index": i,
                    "role": "eval",
                    "components": {
                        "items": {**{k: str(v) for k, v in items.items()}, "consistency": None}
                    },
                    "traits": {t: str(v / 4) for t, v in level.items()} if is_trait else {},
                    "raw": {
                        n: "0.5"
                        for n in (
                            "panicShare",
                            "chaseShare",
                            "turnoverAnnual",
                            "noiseShare",
                            "nudgeShare",
                        )
                    },
                    "flags": {
                        **{k: "0" for k in FLAG_KEYS},
                        "traded": "100",
                        "eventDirection": "10",
                        "eventCaught": "4",
                    },
                }
            )
    return [json.dumps(doc) for doc in lines]


def test_trait_cell_lines_in_the_overlap_folder_form_a_trait_population(
    ci_run, tmp_path, monkeypatch
):
    monkeypatch.setattr(overlap_module, "MIN_BOTS", 10)  # the ci probe has 20 trait bots a world
    run = copy_of(ci_run, tmp_path / "traits")
    cells = run / OVERLAP_DIR / "cells"
    cells.mkdir(parents=True, exist_ok=True)
    (cells / f"{CELL.id}.jsonl").write_text(
        "".join(t + "\n" for t in trait_cell_lines(run)), encoding="utf-8"
    )
    assert main(["overlap", "analyze", str(run)]) == 0
    doc = json.loads((run / OVERLAP_DIR / "overlap.json").read_text(encoding="utf-8"))
    trait = doc["cells"][0]["populations"]["trait"]
    assert trait["bots"] == 3 * CELL.reference
    assert trait["tracking"]["noise"]["tracks"] is True
    assert doc["items"]["noise"]["verdict"] == "KEEP"


def test_a_foreign_line_in_the_trait_file_is_refused(ci_run, tmp_path, capsys):
    run = copy_of(ci_run, tmp_path / "foreign")
    cells = run / OVERLAP_DIR / "cells"
    cells.mkdir(parents=True, exist_ok=True)
    (cells / f"{CELL.id}.jsonl").write_text(cell_lines(run)[0] + "\n", encoding="utf-8")
    assert main(["overlap", "analyze", str(run)]) == 2
    assert "probe lines" in capsys.readouterr().err
