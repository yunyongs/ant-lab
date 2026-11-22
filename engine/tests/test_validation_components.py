"""Score components: hand values, edge rules, record facts, reference sets and purity."""

import ast
from decimal import Decimal

import pytest
from conftest import ENGINE_DIR
from test_sim_source_rules import float_uses
from validation_support import comp, counted_record, roster, world_12, world_24

from antlab_engine.bots.io import RUN_SCHEMA
from antlab_engine.sim import money
from antlab_engine.validation import components as components_module
from antlab_engine.validation.components import (
    RATIO_CAP,
    ComponentError,
    ReferenceSet,
    cagr,
    calmar,
    capped_ratio,
    components_of,
    compound,
    max_drawdown,
    mid_rank_percentile,
    rank_counts_of,
    series_of,
    sortino,
)

D = Decimal


def dec(*values: str) -> list[Decimal]:
    return [D(v) for v in values]


# ---------------------------------------------------------------------------- CAGR


@pytest.mark.parametrize(
    ("initial", "final", "turns", "expected"),
    [
        ("100", "110", 12, "0.1"),
        ("100", "121", 24, "0.1"),
        ("100", "161.051", 60, "0.1"),
        ("100", "90", 12, "-0.1"),
        ("100", "81", 24, "-0.1"),
        ("100", "100", 12, "0"),
        ("100", "100", 60, "0"),
        ("100", "121", 6, "0.4641"),
        ("100", "0", 12, "-1"),
    ],
)
def test_cagr_hand_values(initial, final, turns, expected):
    assert cagr(D(initial), D(final), turns) == D(expected)


def test_cagr_is_rounded_to_twelve_places():
    value = cagr(D(100), D(103), 24)
    assert value == money.round12(value)
    assert value == D("0.014889156509")


def test_cagr_refuses_unusable_inputs():
    for args in ((D(0), D(1), 12), (D(1), D(-1), 12), (D(1), D(1), 0)):
        with pytest.raises(ComponentError):
            cagr(*args)


# ---------------------------------------------------------------------------- drawdown


def test_max_drawdown_starts_its_peak_at_the_initial_value():
    assert max_drawdown(D(100), dec("90", "80")) == D("-0.2")
    assert max_drawdown(D(100), dec("90", "95")) == D("-0.1")


def test_max_drawdown_follows_a_rising_peak():
    assert max_drawdown(D(100), dec("120", "90", "100")) == D("-0.25")
    assert max_drawdown(D(100), dec("110", "121", "121")) == 0


def test_max_drawdown_takes_the_deepest_fall_not_the_last():
    assert max_drawdown(D(100), dec("80", "100", "90")) == D("-0.2")


def test_compound_multiplies_returns():
    assert compound(dec("0.1", "-0.1")) == (D("1.1"), D("0.99"))
    assert max_drawdown(D(1), compound(dec("0.1", "-0.1"))) == D("-0.1")


# ---------------------------------------------------------------------------- Sortino, Calmar


def test_sortino_uses_only_negative_returns_for_the_deviation():
    # downside deviation sqrt((0.04^2 + 0.04^2) / 2) = 0.04, mean 0.01
    assert sortino(dec("0.11", "-0.04", "-0.04")) == D("0.25")


def test_sortino_with_one_negative_month():
    # downside deviation sqrt(0.06^2 / 3) = 0.0346410161513775..., mean 0.015
    returns = dec("0.05", "0.04", "0.03", "-0.06")
    expected = D("0.015") / (D("0.0036") / 3).sqrt()
    assert abs(sortino(returns) - expected) < D("1e-20")


def test_sortino_without_a_negative_month_is_capped():
    assert sortino(dec("0.01", "0.02", "0.03")) == RATIO_CAP
    assert sortino(dec("0", "0", "0")) == 0


def test_sortino_needs_two_returns():
    with pytest.raises(ComponentError):
        sortino(dec("0.01"))


def test_capped_ratio_edges():
    assert capped_ratio(D("0.1"), D(0)) == RATIO_CAP
    assert capped_ratio(D("-0.1"), D(0)) == -RATIO_CAP
    assert capped_ratio(D(0), D(0)) == 0
    assert capped_ratio(D(10**9), D("0.001")) == RATIO_CAP


def test_calmar_hand_values_and_edges():
    assert calmar(D("0.1"), D("-0.2")) == D("0.5")
    assert calmar(D("-0.1"), D("-0.2")) == D("-0.5")
    assert calmar(D(0), D("-0.2")) == 0
    assert calmar(D("0.1"), D(0)) == RATIO_CAP
    assert calmar(D(0), D(0)) == 0


# ---------------------------------------------------------------------------- percentile


@pytest.mark.parametrize(
    ("value", "reference", "expected"),
    [
        ("0", ["1", "2", "3", "4"], "0"),
        ("5", ["1", "2", "3", "4"], "100"),
        ("2", ["2", "2", "2", "2"], "50"),
        ("2.5", ["1", "2", "3", "4"], "50"),
        ("2", ["1", "2", "2", "3"], "50"),
        ("1", ["1", "2", "3", "4"], "12.5"),
        ("4", ["1", "2", "3", "4"], "87.5"),
        ("3", ["4", "1", "3", "2"], "62.5"),
    ],
)
def test_mid_rank_percentile(value, reference, expected):
    assert mid_rank_percentile(D(value), dec(*reference)) == D(expected)


def test_percentile_of_an_empty_reference_is_refused():
    with pytest.raises(ComponentError):
        mid_rank_percentile(D(1), [])


# ---------------------------------------------------------------------------- record facts


@pytest.fixture(scope="module")
def records12():
    return roster(world_12())


@pytest.fixture(scope="module")
def records24():
    return roster(world_24())


def test_record_facts_equal_the_stored_report_and_score(records12, records24):
    for record in [*records12, *records24]:
        c = components_of(record)
        stored = record["score"]
        report = record["report"]
        assert money.round8(c.sharpe) == D(stored["sharpe"])
        assert money.round8(c.bench_sharpe) == D(stored["benchSharpe"])
        assert money.round8(c.sharpe_diff) == D(stored["sharpeDiff"])
        assert money.round8(c.mdd) == D(report["maxDrawdown"])
        assert money.round2(c.process) == D(stored["processScore"])
        assert c.result is not None
        assert money.round2(c.result) == D(stored["resultScore"])
        for name, value in c.items.items():
            column = stored[f"{name}Score"]
            assert (None if value is None else money.round2(value)) == (
                None if column is None else D(column)
            )


def test_a_twelve_turn_cagr_is_the_total_return(records12):
    for record in records12:
        c = components_of(record)
        assert money.round8(c.cagr) == D(record["report"]["totalReturn"])


def test_a_longer_season_cagr_annualises_the_total_return(records24):
    for record in records24:
        c = components_of(record)
        final = D(record["totals"][-1]) / D(record["report"]["initialCash"])
        assert abs((1 + c.cagr) ** 2 - final) < D("1e-10")


def test_calmar_divides_by_the_bots_own_drawdown(records12):
    seen_different = False
    for record in records12:
        c = components_of(record)
        assert c.calmar == calmar(c.cagr, c.mdd)
        assert c.bench_calmar == calmar(c.bench_cagr, c.bench_mdd)
        seen_different |= c.mdd != c.bench_mdd
    assert seen_different


def test_the_drawdown_counts_the_initial_cash_as_the_first_peak(records12, records24):
    first_below_initial = 0
    for record in [*records12, *records24]:
        series = series_of(record)
        if series.totals[1] < series.initial_cash:
            first_below_initial += 1
    assert first_below_initial > 0


def test_excess_return_and_differences_are_team_minus_benchmark(records12):
    c = components_of(records12[0])
    sub = money.EXACT.subtract
    assert c.excess_cagr == sub(c.cagr, c.bench_cagr)
    assert c.sharpe_diff == sub(c.sharpe, c.bench_sharpe)
    assert c.sortino_diff == sub(c.sortino, c.bench_sortino)
    assert c.calmar_diff == sub(c.calmar, c.bench_calmar)


def test_the_wire_form_is_strings_and_repeatable(records12):
    first = components_of(records12[3]).to_wire()
    assert first == components_of(records12[3]).to_wire()
    assert {
        "benchCagr",
        "excessCagr",
        "sharpeDiff",
        "sortinoDiff",
        "calmarDiff",
        "benchMdd",
    } <= set(first)
    assert not [k for k in first if "_" in k]
    assert all(isinstance(v, str) for k, v in first.items() if k not in ("items", "result"))
    assert set(first["items"]) == {
        "risk",
        "discipline",
        "diversification",
        "turnover",
        "consistency",
        "formation",
        "role",
        "noise",
        "nudge",
    }
    assert first["items"]["consistency"] is None


def test_a_record_without_counts_has_no_result():
    record = counted_record(world_12(), "random", 0, with_counts=False)
    assert record["counts"] is None
    c = components_of(record)
    assert c.result is None
    assert c.process > 0


def test_record_refusals(records12):
    good = records12[0]
    for bad_turns in ("0", 0.0, True):
        with pytest.raises(ComponentError):
            components_of({**good, "report": {**good["report"], "riskBreachTurns": bad_turns}})
    assert RUN_SCHEMA == "bot-run/1"
    with pytest.raises(ComponentError):
        series_of({**good, "schema": "bot-run/2"})
    with pytest.raises(ComponentError):
        series_of({**good, "status": "ACTIVE"})
    with pytest.raises(ComponentError):
        series_of({**good, "teamReturns": good["teamReturns"][:-1]})
    with pytest.raises(ComponentError):
        series_of({**good, "totals": good["totals"][:-1]})
    with pytest.raises(ComponentError):
        series_of({**good, "benchmarkReturns": [*good["benchmarkReturns"][:-1], "NaN"]})


def test_counts_must_come_from_the_requested_number_of_paths(records12):
    good = records12[0]
    assert rank_counts_of(good, "sharpeDiff") is not None
    wrong = dict(good["counts"])
    wrong["sharpeDiff"] = {**wrong["sharpeDiff"], "n": 1000}
    with pytest.raises(ComponentError):
        rank_counts_of({**good, "counts": wrong}, "sharpeDiff")
    with pytest.raises(ComponentError):
        components_of({**good, "counts": wrong})
    with pytest.raises(ComponentError):
        rank_counts_of({**good, "counts": {"endMultiple": wrong["endMultiple"]}}, "sharpeDiff")
    broken = {"sharpeDiff": {"below": 5, "equal": 0, "n": 2000}, "endMultiple": "x"}
    with pytest.raises(ComponentError):
        rank_counts_of({**good, "counts": broken}, "endMultiple")
    impossible = {**wrong, "sharpeDiff": {"below": 2000, "equal": 5, "n": 2000}}
    with pytest.raises(ComponentError):
        rank_counts_of({**good, "counts": impossible}, "sharpeDiff")


# ---------------------------------------------------------------------------- reference set


def test_reference_set_sorts_each_metric_and_ranks_against_it():
    bots = [
        comp(index=0, excess_cagr="0.03", mdd="-0.1"),
        comp(index=1, excess_cagr="0.01", mdd="-0.3"),
        comp(index=2, excess_cagr="0.02", mdd="-0.2"),
    ]
    reference = ReferenceSet.from_components(bots)
    assert reference.size == 3
    assert reference.values["excess_cagr"] == tuple(dec("0.01", "0.02", "0.03"))
    assert reference.values["mdd"] == tuple(dec("-0.3", "-0.2", "-0.1"))
    assert abs(reference.percentile("mdd", D("-0.1")) - D("83.333333333333333333333333333")) < D(
        "1e-20"
    )


def test_reference_set_size_must_match_the_profile():
    bots = [comp(index=i) for i in range(20)]
    assert ReferenceSet.from_components(bots, expected_size=20).size == 20
    with pytest.raises(ComponentError):
        ReferenceSet.from_components(bots, expected_size=200)
    with pytest.raises(ComponentError):
        ReferenceSet.from_components(bots, expected_size=19)
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([])


def test_reference_set_refuses_foreign_bots():
    bots = [comp(index=0), comp(index=1)]
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([*bots, comp("static", 2)])
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([*bots, comp(index=1)])
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([*bots, comp(index=2, world_id="other")])
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([*bots, comp(index=2, path_length=24)])


def test_a_bot_without_a_risk_item_cannot_join_a_reference_set():
    without = comp(index=1)
    without = type(without)(**{**without.__dict__, "items": {"risk": None}})
    with pytest.raises(ComponentError):
        ReferenceSet.from_components([comp(index=0), without])


# ---------------------------------------------------------------------------- purity

VALIDATION_DIR = ENGINE_DIR / "src" / "antlab_engine" / "validation"
PURE_MODULES = ("__init__.py", "components.py", "composite.py")
ALLOWED_ROOTS = {"__future__", "bisect", "collections", "dataclasses", "decimal", "typing"}
ALLOWED_FIRST_PARTY = {
    "antlab_engine.sim",
    "antlab_engine.sim.counts",
    "antlab_engine.sim.model",
    "antlab_engine.sim.report",
}
ALLOWED_RELATIVE = {".components"}
IO_NAMES = {"open", "print", "input", "Path", "exec", "eval", "compile", "__import__"}


def import_names(tree: ast.AST) -> list[str]:
    names = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names += [a.name for a in node.names]
        elif isinstance(node, ast.ImportFrom):
            names.append("." * node.level + (node.module or ""))
    return names


def violations(tree: ast.AST) -> list[str]:
    found = []
    for name in import_names(tree):
        if name in ALLOWED_RELATIVE or name in ALLOWED_FIRST_PARTY:
            continue
        if not name.startswith(".") and name.split(".")[0] in ALLOWED_ROOTS:
            continue
        found.append(name)
    found += [n.id for n in ast.walk(tree) if isinstance(n, ast.Name) and n.id in IO_NAMES]
    found += [
        n.attr
        for n in ast.walk(tree)
        if isinstance(n, ast.Attribute) and n.attr in {"read_text", "write_text", "open"}
    ]
    return found


def test_purity_scanner_flags_io_network_and_float():
    bad = ast.parse(
        "import psycopg\nfrom pathlib import Path\nimport json\n"
        "x = open('f')\ny = 1.5\nz = a / b\nw = p.read_text()\n"
        "from antlab_engine.bots import io\nfrom antlab_engine.sim import money\n"
        "from ..bots import io\nfrom . import experiment\nfrom .search import x\n"
    )
    assert violations(bad) == [
        "psycopg",
        "pathlib",
        "json",
        "antlab_engine.bots",
        "..bots",
        ".",
        ".search",
        "open",
        "read_text",
    ]
    assert float_uses(bad) == [5, 6]
    ok = ast.parse(
        "from bisect import bisect_left\nfrom decimal import Decimal\n"
        "from antlab_engine.sim import money\nfrom .components import x\n"
    )
    assert violations(ok) == [] and float_uses(ok) == []


def test_validation_components_are_pure_and_float_free():
    for name in PURE_MODULES:
        tree = ast.parse((VALIDATION_DIR / name).read_text(encoding="utf-8"))
        assert violations(tree) == [], name
        assert float_uses(tree) == [], name


def test_the_local_schema_name_matches_the_runner():
    assert components_module.RUN_SCHEMA == RUN_SCHEMA
