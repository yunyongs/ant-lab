"""`validation-run/1` result, fixed-template summary and the files written for a run."""

import copy
import json
import re

import pytest
from validation_support import comp

from antlab_engine.validation.checks import ROLE_EVAL, ROLE_NAMED, ROLE_REF, BotRow
from antlab_engine.validation.output import (
    MAX_SUMMARY_LINES,
    OutputError,
    build_result,
    render_summary,
    validate_result,
    write_run,
)
from antlab_engine.validation.search import analyze_cell

SEED = 31
MANIFEST = {
    "masterSeed": str(SEED),
    "worldSeeds": ["1", "2", "3", "4", "5", "6"],
    "botVersions": {"random": 1, "static": 1},
    "catalogSha256": "0" * 64,
    "scoreVersion": "s63-v3",
    "engineVersion": "0.1.0",
    "gitCommit": "b8a2206",
}


def level_row(world, role, kind, index, level, *, process):
    return BotRow(
        world,
        role,
        comp(
            kind,
            index,
            world_id=f"w{world}",
            excess_cagr=str(level),
            sharpe_diff=str(level),
            sortino_diff=str(level),
            calmar_diff=str(level),
            risk=str(level),
            mdd=str(level - 100),
            process=str(process),
            result=str(level),
        ),
    )


def cell_rows(worlds=6):
    rows = []
    for w in range(worlds):
        for i in range(6):
            rows.append(level_row(w, ROLE_REF, "random", i, 40 + 2 * i, process=50))
        for i in range(6, 12):
            rows.append(level_row(w, ROLE_EVAL, "random", i, 40 + (3 * i + w) % 20, process=50))
        for i in range(2):
            rows.append(level_row(w, ROLE_EVAL, "static", i, 42 + 4 * i + w, process=50))
        rows.append(level_row(w, ROLE_NAMED, "disciplined", 0, 80, process=80))
        rows.append(level_row(w, ROLE_NAMED, "disciplined-noguard", 0, 75, process=70))
        rows.append(level_row(w, ROLE_NAMED, "news", 0, 20, process=20))
        rows.append(level_row(w, ROLE_NAMED, "newsmix-50", 0, 50, process=50))
        rows.append(level_row(w, ROLE_EVAL, "redeclare", 6, 55, process=50))
    return rows


def analysis():
    return analyze_cell(cell_rows(), SEED)


def result_doc():
    return build_result(MANIFEST, [({"id": "P12-test"}, analysis())], status="provisional")


def leaves(value):
    if isinstance(value, dict):
        for item in value.values():
            yield from leaves(item)
    elif isinstance(value, list):
        for item in value:
            yield from leaves(item)
    else:
        yield str(value)


# ---------------------------------------------------------------------------- analysis


def test_analyze_cell_covers_both_formulas_and_every_check():
    doc = analysis()
    assert doc["worlds"] == {"total": 6, "train": 3, "test": 3}
    names = {entry["name"] for entry in doc["formulas"]}
    assert {"s63-draft", "s-draft"} <= names
    assert set(doc["search"]) == {"s63", "s"}
    for entry in doc["formulas"]:
        assert set(entry["checks"]) == {f"C{i}" for i in range(1, 9)}
    assert doc["correlation"]["draft"]["n"] == 6 * (6 + 2 + 5)
    assert doc["calibration"]["available"] is True


def test_analyze_cell_is_deterministic():
    assert analysis() == analysis()


def test_analyze_cell_scores_the_reference_bots_out_of_the_evaluation():
    rows = cell_rows()
    rows.append(level_row(0, ROLE_EVAL, "random", 1, 40, process=50))
    with pytest.raises(ValueError, match="reference"):
        analyze_cell(rows, SEED)


# ---------------------------------------------------------------------------- schema


def test_a_built_result_validates_and_has_string_numbers():
    result = result_doc()
    validate_result(result)
    assert result["schema"] == "validation-run/1" and result["status"] == "provisional"
    c1 = result["cells"][0]["formulas"][0]["checks"]["C1"]
    assert isinstance(c1["mean"], str) and all(isinstance(x, str) for x in c1["ci"])
    assert result["cells"][0]["cell"] == {"id": "P12-test"}


def test_results_without_the_required_parts_are_refused():
    result = result_doc()
    bad = copy.deepcopy(result)
    bad["status"] = "draft"
    with pytest.raises(OutputError):
        validate_result(bad)
    bad = copy.deepcopy(result)
    del bad["manifest"]["gitCommit"]
    with pytest.raises(OutputError):
        validate_result(bad)
    bad = copy.deepcopy(result)
    del bad["cells"][0]["calibration"]
    with pytest.raises(OutputError):
        validate_result(bad)
    bad = copy.deepcopy(result)
    del bad["cells"][0]["formulas"][0]["checks"]["C5"]
    with pytest.raises(OutputError):
        validate_result(bad)
    bad = copy.deepcopy(result)
    bad["schema"] = "validation-run/2"
    with pytest.raises(OutputError):
        validate_result(bad)
    bad = copy.deepcopy(result)
    bad["cells"] = []
    with pytest.raises(OutputError):
        validate_result(bad)


def test_floats_are_refused_anywhere_in_the_result():
    result = result_doc()
    result["cells"][0]["calibration"]["mean"] = 50.0
    with pytest.raises(OutputError, match="float"):
        validate_result(result)


def test_only_json_values_are_allowed_and_seeds_are_strings():
    from decimal import Decimal

    import numpy as np

    for leaf in (Decimal(1), np.int64(3), np.bool_(True), (1, 2)):
        result = result_doc()
        result["cells"][0]["calibration"]["mean"] = leaf
        with pytest.raises(OutputError):
            validate_result(result)
    result = result_doc()
    result["manifest"]["masterSeed"] = 31
    with pytest.raises(OutputError, match="strings"):
        validate_result(result)
    result = result_doc()
    result["manifest"]["worldSeeds"] = [1, 2]
    with pytest.raises(OutputError, match="strings"):
        validate_result(result)


def test_the_interval_rule_is_recorded():
    rule = result_doc()["intervalRule"]
    assert rule["unit"] == "world" and rule["resamples"] == 2000
    assert rule["orderStatistics"] == [51, 1950]


def test_build_result_rejects_an_unknown_status():
    with pytest.raises(OutputError):
        build_result(MANIFEST, [({"id": "x"}, analysis())], status="done")


# ---------------------------------------------------------------------------- summary


def test_summary_is_short_english_and_uses_only_numbers_of_the_result():
    result = result_doc()
    text = render_summary(result)
    lines = text.splitlines()
    assert len(lines) <= MAX_SUMMARY_LINES and text.endswith("\n") and not text.endswith("\n\n")
    known = set(leaves(result))
    for token in re.findall(r"(?<![\w.§])-?\d+(?:\.\d+)?(?!\w|\.\d)", text):
        assert token in known, token
    assert text.isascii()


def test_summary_states_the_status_and_every_formula():
    text = render_summary(result_doc())
    assert "Status: provisional" in text
    for name in ("s63-draft", "s-draft"):
        assert f"- {name} (" in text
    assert "s63 search:" in text and "S search:" in text


def test_summary_refuses_more_cells_than_it_has_room_for():
    result = result_doc()
    result["cells"] = result["cells"] * 6
    with pytest.raises(OutputError):
        render_summary(result)


def test_five_cells_fit_in_sixty_lines():
    result = result_doc()
    result["cells"] = result["cells"] * 5
    assert len(render_summary(result).splitlines()) <= MAX_SUMMARY_LINES


# ---------------------------------------------------------------------------- files


def test_write_run_writes_the_two_files_under_the_given_directory(tmp_path):
    result = result_doc()
    target = tmp_path / "out" / "validation" / "run-1"
    result_path, summary_path = write_run(target, result)
    assert result_path == target / "result.json" and summary_path == target / "summary.md"
    assert sorted(p.name for p in target.iterdir()) == ["result.json", "summary.md"]
    assert json.loads(result_path.read_text(encoding="utf-8")) == result
    assert result_path.read_text(encoding="utf-8").endswith("}\n")
    assert summary_path.read_text(encoding="utf-8") == render_summary(result)
    assert [p for p in tmp_path.rglob("*") if p.is_file()] == [result_path, summary_path]


def test_write_run_is_byte_deterministic(tmp_path):
    first = write_run(tmp_path / "a", result_doc())
    second = write_run(tmp_path / "b", result_doc())
    for one, two in zip(first, second, strict=True):
        assert one.read_bytes() == two.read_bytes()


def test_write_run_leaves_no_files_when_the_result_is_invalid(tmp_path):
    result = result_doc()
    del result["manifest"]["gitCommit"]
    with pytest.raises(OutputError):
        write_run(tmp_path / "bad", result)
    assert not (tmp_path / "bad").exists()
