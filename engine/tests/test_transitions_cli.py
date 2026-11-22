"""python -m antlab_engine.regime transitions: counts only, never a month (TB8)."""

import json
import re
import subprocess
import sys
from datetime import date

import pytest
from synthetic_labels import label_fixture
from synthetic_months import BASE_DATE, month_range

from antlab_engine.regime.__main__ import _mean, main, transitions_report

START = date(2007, 1, 1)
YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
POOL_ROW = re.compile(r"\bP[1-4]\b")


def hand_rows() -> list[tuple[date, str | None]]:
    """2010-01..2010-12: P1 P1 P2 P2 P2 -, P3 P3 P1 P1 P1 P2 (the sixth month has no label)."""
    labels = ["P1", "P1", "P2", "P2", "P2", None, "P3", "P3", "P1", "P1", "P1", "P2"]
    rows: list[tuple[date, str | None]] = list(
        zip(month_range(date(2010, 1, 1), date(2010, 12, 1)), labels, strict=True)
    )
    rows += [(m, "P4") for m in month_range(date(2011, 1, 1), date(2012, 1, 1))]
    return rows


def run_cli(rows, capsys, *extra):
    code = main(
        ["transitions", "--base-date", BASE_DATE.isoformat(), *extra], month_source=lambda _: rows
    )
    captured = capsys.readouterr()
    return code, captured.out, captured.err


def test_tb8_a_hand_history_gives_the_exact_counts(capsys):
    code, out, err = run_cli(hand_rows(), capsys)
    assert code == 0, err
    report = json.loads(out)
    assert report["pools"] == ["P1", "P2", "P3", "P4"]
    assert report["counts"] == [[3, 2, 0, 0], [0, 2, 0, 1], [1, 0, 1, 0], [0, 0, 0, 12]]
    assert report["labelledMonths"] == 24
    assert report["pairs"] == 22
    assert report["rowTotals"] == [sum(row) for row in report["counts"]]


def test_tb8_runs_and_mean_run_length(capsys):
    code, out, _ = run_cli(hand_rows(), capsys)
    report = json.loads(out)
    # P1: 2 + 3 months in 2 runs; P2: 3 + 1 months in 2 runs; P3: 2 in 1; P4: 13 in 1
    assert report["runs"] == {"P1": 2, "P2": 2, "P3": 1, "P4": 1}
    assert report["monthsPerPool"] == {"P1": 5, "P2": 4, "P3": 2, "P4": 13}
    assert report["meanRunLength"] == {"P1": "2.50", "P2": "2.00", "P3": "2.00", "P4": "13.00"}


def test_tb8_the_output_has_no_month_and_no_label_per_month(capsys):
    _, out, _ = run_cli(hand_rows(), capsys)
    assert not YEAR_MONTH.search(out)
    # pool names appear only as the keys of the aggregate tables
    assert "2010" not in out
    keys = set(json.loads(out))
    assert keys == {
        "labelledMonths",
        "pairs",
        "pools",
        "counts",
        "rowTotals",
        "runs",
        "monthsPerPool",
        "meanRunLength",
    }


def test_tb8_too_few_labels_exits_1_with_counts_only(capsys):
    rows = [(m, None) for m, _ in hand_rows()]
    code, out, err = run_cli(rows, capsys)
    assert code == 1
    assert out == ""
    assert "REGIME_LABELS_MISSING" in err
    assert not YEAR_MONTH.search(err)


def test_tb8_a_base_year_label_is_not_counted(capsys):
    months, start, labels = label_fixture("persist")
    rows = [(m, labels.get(m)) for m in months]
    inside = main(
        ["transitions", "--base-date", BASE_DATE.isoformat()], month_source=lambda _: rows
    )
    out_with = capsys.readouterr().out
    assert inside == 0
    flipped = [(m, "P4" if m >= date(2026, 1, 1) else lab) for m, lab in rows]
    main(["transitions", "--base-date", BASE_DATE.isoformat()], month_source=lambda _: flipped)
    out_flipped = capsys.readouterr().out
    assert out_with == out_flipped


def test_tb8_the_command_needs_a_base_date(capsys):
    with pytest.raises(SystemExit) as caught:
        main(["transitions"], month_source=lambda _: [])
    assert caught.value.code == 2


def test_tb8_the_report_function_matches_the_cli_json(capsys):
    rows = hand_rows()
    expected = transitions_report(rows, BASE_DATE, START)
    _, out, _ = run_cli(rows, capsys)
    assert json.loads(out) == expected


@pytest.mark.parametrize(
    ("months", "runs", "text"),
    [(5, 2, "2.50"), (1, 3, "0.33"), (2, 3, "0.67"), (7, 0, None), (12, 1, "12.00")],
)
def test_the_mean_is_rounded_half_up_in_integers(months, runs, text):
    assert _mean(months, runs) == text


def test_the_module_command_is_wired_to_the_transitions_choice():
    result = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-m", "antlab_engine.regime", "transitions"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 2
    assert "--base-date" in result.stderr
