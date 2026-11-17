import hashlib
import platform
import re
import subprocess
import sys
import warnings
from datetime import date
from decimal import Decimal
from fractions import Fraction
from importlib import metadata
from pathlib import Path

import numpy as np
import pytest
from synthetic_months import BASE_DATE, EXT348_REPLAY_START, sample_shape, tiny
from synthetic_panel import (
    REPLAY_MONTHS,
    asset_names,
    constant_panel,
    portfolio,
    random_panel,
    random_rows,
)

from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import InvalidArgument, PathTooLong
from antlab_engine.projection import Portfolio, Projection, ReturnPanel, ReturnRow, project
from antlab_engine.projection.evaluate import evaluate
from antlab_engine.projection.stats import quantiles, summarize

LIMIT = Decimal("-0.20")
TESTS_DIR = str(Path(__file__).parent)


def run(panel, portfolios, **kwargs) -> Projection:
    args = {
        "season_seed": 7,
        "horizon": 24,
        "risk_limit": LIMIT,
        "n": 100,
        "replay_start": EXT348_REPLAY_START,
    }
    return project(panel, BASE_DATE, portfolios=portfolios, **{**args, **kwargs})


def fingerprint(result: Projection) -> str:
    digest = hashlib.sha256()
    for p in result.portfolios:
        digest.update(p.key.encode())
        digest.update(p.bands.tobytes())
        digest.update(p.end_values.tobytes())
        for s in p.summaries:
            digest.update(s.end_quantiles.tobytes() + s.mdd_quantiles.tobytes())
            digest.update(str(s.breach_count).encode())
    return digest.hexdigest()


def environment_note() -> str:
    """Interpreter, numpy and wheel facts to read when a pinned digest differs."""
    wheel = [
        line
        for line in (metadata.distribution("numpy").read_text("WHEEL") or "").splitlines()
        if line.startswith("Tag:")
    ]
    return (
        f"python {platform.python_version()}, numpy {np.__version__}, "
        f"machine {platform.machine()}, system {platform.system()}, wheel {wheel[:1]}"
    )


def test_constant_history_gives_a_flat_growth_band_and_no_drawdown():
    panel = constant_panel(REPLAY_MONTHS, {"KR:A": "0.01"})
    result = run(panel, [Portfolio("TEAM", {"KR:A": Decimal(1)}, Decimal(0))], horizon=60)
    expected, value = [], 1.0
    for _ in range(60):
        value = value * 1.01
        expected.append(value)
    (team,) = result.portfolios
    assert team.bands.shape == (60, 5)
    for column in range(5):
        assert team.bands[:, column].tolist() == expected
    (summary,) = team.summaries
    assert summary.turn == 60 and summary.breach_count == 0
    assert summary.mdd_quantiles.tolist() == [0.0] * 6
    assert (result.projection_version, result.engine_version) == ("m2-pj-v1", "m2-sb6-v1")


def test_all_cash_stays_at_one():
    panel = constant_panel(REPLAY_MONTHS, {"KR:A": "0.5"})
    result = run(panel, [Portfolio("CASH", {}, Decimal(1))])
    (cash,) = result.portfolios
    assert (cash.bands == 1.0).all() and cash.summaries[0].breach_count == 0


def test_quantities_are_held_not_rebalanced():
    factors = np.array([[2.0, 1.0], [0.5, 1.0]])
    paths = np.array([[0, 1]], dtype=np.int32)
    held = (((0, 0.5), (1, 0.5)), 0.0)
    (values,) = evaluate(factors, paths, [held])
    assert values[0].tolist() == [1.0, 1.5, 1.0]


def test_max_drawdown_peak_includes_turn_zero():
    rising = summarize("A", np.array([[1.0, 1.5, 1.125]]), [2], -0.5)
    assert rising.summaries[0].mdd_quantiles.tolist() == [-0.25] * 6
    falling = summarize("A", np.array([[1.0, 0.75, 0.875]]), [1, 2], -0.5)
    assert [s.mdd_quantiles[0] for s in falling.summaries] == [-0.25, -0.25]


def test_breach_is_strictly_below_the_limit():
    values = np.array([[1.0, 2.0, 1.5]])
    assert summarize("A", values, [2], -0.25).summaries[0].breach_count == 0
    assert summarize("A", values, [2], -0.20).summaries[0].breach_count == 1


def test_summary_mdd_stops_at_its_turn():
    values = np.ones((100, 121))
    values[:, 61:] = 0.5
    result = summarize("A", values, [60, 120], -0.2)
    first, last = result.summaries
    assert first.mdd_quantiles.tolist() == [0.0] * 6 and first.breach_count == 0
    assert last.mdd_quantiles.tolist() == [-0.5] * 6 and last.breach_count == 100
    assert first.end_quantiles.tolist() == result.bands[59].tolist()


def test_quantile_type_7_by_hand():
    sample = np.array([30.0, 10.0, 50.0, 20.0, 40.0])
    assert quantiles(sample, [5, 50, 95]).tolist() == [12.0, 30.0, 48.0]
    assert quantiles(np.array([4.0]), [5, 95]).tolist() == [4.0, 4.0]


def test_quantile_matches_an_exact_reference_and_numpy_linear():
    rng = np.random.default_rng(3)
    sample = rng.normal(1.0, 0.3, 2000)
    ordered = sorted(sample.tolist())
    for k in (5, 10, 25, 50, 75, 95):
        h = Fraction(1999 * k, 100)
        low = h.numerator // h.denominator
        exact = Fraction(ordered[low]) + (h - low) * (
            Fraction(ordered[low + 1]) - Fraction(ordered[low])
        )
        got = quantiles(sample, [k])[0]
        assert abs(Fraction(float(got)) - exact) <= Fraction(1, 10**15)
        assert got == pytest.approx(np.quantile(sample, k / 100), rel=1e-12)


def test_summary_turns_are_clipped_sorted_and_deduplicated():
    panel = constant_panel(REPLAY_MONTHS, {"KR:A": "0.01"})
    team = [Portfolio("TEAM", {"KR:A": Decimal(1)}, Decimal(0))]
    result = run(panel, team, horizon=60, summary_turns=(60, 24, 0, 61, 24, 120))
    assert [s.turn for s in result.portfolios[0].summaries] == [24, 60]
    assert [s.turn for s in run(panel, team, horizon=60).portfolios[0].summaries] == [60]


def build(assets):
    panel = random_panel(REPLAY_MONTHS, assets, 11)
    return panel, [portfolio("TEAM", assets), portfolio("MARKET", assets[:2])]


def test_same_inputs_give_identical_bytes_here_and_in_a_fresh_process():
    assets = asset_names(5)
    panel, books = build(assets)
    first = fingerprint(run(panel, books))
    assert first == fingerprint(run(panel, books))
    code = (
        "import sys\n"
        f"sys.path.insert(0, {TESTS_DIR!r})\n"
        "from test_projection_eval import build, fingerprint, run\n"
        "from synthetic_panel import asset_names\n"
        "panel, books = build(asset_names(5))\n"
        "print(fingerprint(run(panel, books)))\n"
    )
    out = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", code], capture_output=True, text=True, check=True
    )
    assert out.stdout.strip() == first


def test_request_order_never_changes_bits():
    assets = asset_names(5)
    panel, books = build(assets)
    forward = run(panel, books)
    flipped = [
        Portfolio(p.key, dict(reversed(list(p.weights.items()))), p.cash) for p in books[::-1]
    ]
    backward = run(panel, flipped)
    by_key = {p.key: p for p in backward.portfolios}
    for p in forward.portfolios:
        assert p.bands.tobytes() == by_key[p.key].bands.tobytes()
        assert p.end_values.tobytes() == by_key[p.key].end_values.tobytes()


def test_team_result_does_not_depend_on_the_other_portfolios():
    assets = asset_names(4)
    panel, books = build(assets)
    alone = run(panel, books[:1])
    together = run(panel, books)
    assert alone.portfolios[0].end_values.tobytes() == together.portfolios[0].end_values.tobytes()


def test_different_season_seeds_give_different_results():
    panel, books = build(asset_names(3))
    assert fingerprint(run(panel, books, season_seed=1)) != fingerprint(
        run(panel, books, season_seed=2)
    )


def test_results_are_ordered_finite_and_read_only():
    panel, books = build(asset_names(6))
    result = run(panel, books, horizon=60, n=200)
    for p in result.portfolios:
        assert (np.diff(p.bands, axis=1) >= 0).all()
        assert np.isfinite(p.bands).all() and np.isfinite(p.end_values).all()
        assert (p.end_values[:, 0] == 1.0).all()
        for s in p.summaries:
            assert (np.diff(s.mdd_quantiles) >= 0).all() and s.mdd_quantiles.max() <= 0
            assert 0 <= s.breach_count <= 200
        for array in (p.bands, p.end_values, p.summaries[0].end_quantiles):
            with pytest.raises(ValueError, match="read-only"):
                array[...] = 0


def test_a_missing_cell_removes_the_month_for_everyone():
    assets = asset_names(2)
    hole = date(2005, 6, 1)
    rows = random_rows(REPLAY_MONTHS, assets, 4)
    with_hole = ReturnPanel.from_rows(
        REPLAY_MONTHS, assets, [r for r in rows if (r.asset, r.month) != (assets[1], hole)]
    )
    without = ReturnPanel.from_rows(
        [m for m in REPLAY_MONTHS if m != hole], assets, [r for r in rows if r.month != hole]
    )
    books = [portfolio("TEAM", assets)]
    assert fingerprint(run(with_hole, books)) == fingerprint(run(without, books))
    assert run(with_hole, books).eligible_months == len(REPLAY_MONTHS) - 1


def test_a_zero_weight_asset_does_not_restrict_months():
    assets = asset_names(2)
    hole = date(2005, 6, 1)
    rows = random_rows(REPLAY_MONTHS, assets, 4)
    panel = ReturnPanel.from_rows(
        REPLAY_MONTHS, assets, [r for r in rows if (r.asset, r.month) != (assets[1], hole)]
    )
    team = Portfolio("TEAM", {assets[0]: Decimal(1), assets[1]: Decimal(0)}, Decimal(0))
    assert run(panel, [team]).eligible_months == len(REPLAY_MONTHS)


def test_too_few_eligible_months_is_path_too_long():
    assets = asset_names(1)
    short = random_panel(REPLAY_MONTHS[:20], assets, 2)
    with pytest.raises(PathTooLong):
        run(short, [portfolio("TEAM", assets)], horizon=24)
    sample = random_panel(sample_shape(), assets, 2)
    kwargs = {"replay_start": date(2007, 1, 1)}
    with pytest.raises(PathTooLong):
        run(sample, [portfolio("TEAM", assets)], horizon=120, **kwargs)
    ok = run(sample, [portfolio("TEAM", assets)], horizon=60, **kwargs)
    assert ok.eligible_months == 100


def test_months_outside_the_replay_range_cannot_change_any_output():
    assets = asset_names(3)
    base_rows = random_rows(REPLAY_MONTHS, assets, 9)
    outside = [date(1990, 3, 1), date(1996, 12, 1), date(2026, 1, 1), date(2026, 2, 1)]
    extra = [ReturnRow(a, m, Decimal(99), Decimal(0)) for m in outside for a in assets]
    plain = ReturnPanel.from_rows(REPLAY_MONTHS, assets, base_rows)
    wider = ReturnPanel.from_rows([*outside, *REPLAY_MONTHS], assets, [*extra, *base_rows])
    books = [portfolio("TEAM", assets)]
    a, b = run(plain, books, horizon=60), run(wider, books, horizon=60)
    assert fingerprint(a) == fingerprint(b) and a.eligible_months == b.eligible_months


@pytest.mark.parametrize(
    ("weight", "cash"),
    [
        ("0.25", "0.24999999"),
        ("0.25", "0.250000001"),
        ("0.25", "0.2499999999999"),
        ("-0.1", "0.6"),
        ("0.75", "-0.25"),
        ("NaN", "0.5"),
        ("Infinity", "0"),
        ("sNaN", "0.5"),
    ],
)
def test_bad_portfolio_sums_and_values_are_rejected(weight, cash):
    with pytest.raises(InvalidArgument):
        Portfolio("TEAM", {"KR:A": Decimal(weight), "US:B": Decimal("0.5")}, Decimal(cash))


@pytest.mark.parametrize(
    "weights",
    [{"KR:A": 0.5, "US:B": Decimal("0.5")}, {"kr:a": Decimal(1)}, {"KR:A": "1"}],
)
def test_bad_portfolio_types_and_keys_are_rejected(weights):
    with pytest.raises(InvalidArgument):
        Portfolio("TEAM", weights, Decimal(0))


def test_portfolio_key_and_size_limits():
    for key in ("team", "1TEAM", "", "T" * 33):
        with pytest.raises(InvalidArgument):
            Portfolio(key, {}, Decimal(1))
    many = {f"KR:{i:03d}": Decimal(0) for i in range(201)}
    with pytest.raises(InvalidArgument):
        Portfolio("TEAM", many, Decimal(1))
    assert Portfolio("TEAM", dict(list(many.items())[:200]), Decimal(1)).active == ()


@pytest.mark.parametrize(
    "changes",
    [
        {"n": 99},
        {"n": 5001},
        {"n": 100.0},
        {"horizon": 61},
        {"risk_limit": Decimal(0)},
        {"risk_limit": Decimal(-1)},
        {"risk_limit": Decimal("0.1")},
        {"risk_limit": Decimal("NaN")},
        {"risk_limit": -0.2},
        {"season_seed": True},
        {"season_seed": 1 << 63},
        {"summary_turns": (True,)},
        {"portfolios": []},
    ],
)
def test_invalid_requests_are_rejected_without_months(changes):
    assets = asset_names(2)
    panel = random_panel(REPLAY_MONTHS, assets, 1)
    arguments = {"portfolios": [portfolio("TEAM", assets)], **changes}
    with pytest.raises(InvalidArgument) as caught:
        run(panel, **arguments)
    assert not re.search(r"\d{4}-\d{2}", str(caught.value))


def test_portfolio_list_and_asset_checks():
    assets = asset_names(2)
    panel = random_panel(REPLAY_MONTHS, assets, 1)
    team = portfolio("TEAM", assets)
    for books in ([team, team], [team] * 7, [portfolio("TEAM", ["KR:ZZZ"])]):
        with pytest.raises(InvalidArgument):
            run(panel, books)
    six = [portfolio(f"P{i}", assets) for i in range(6)]
    assert len(run(panel, six).portfolios) == 6


def test_overflowing_compounding_is_an_invalid_argument_not_nan():
    panel = constant_panel(REPLAY_MONTHS, {"KR:A": "1E+100"})
    team = [Portfolio("TEAM", {"KR:A": Decimal(1)}, Decimal(0))]
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        with pytest.raises(InvalidArgument, match="overflow") as caught:
            run(panel, team, horizon=12)
    assert not re.search(r"\d{4}-\d{2}", str(caught.value))


def test_evaluate_rejects_overflow_and_the_nan_that_a_later_zero_would_make():
    held = (((0, 1.0),), 0.0)
    paths = np.array([[0, 1, 2]], dtype=np.int32)
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        for column in ([1e200, 1e200, 1e200], [1e200, 1e200, 0.0]):
            with pytest.raises(InvalidArgument):
                evaluate(np.array(column).reshape(3, 1), paths, [held])
        big = evaluate(np.array([[1e100], [1e100], [1.0]]), paths, [held])
    assert np.isfinite(big[0]).all() and big[0][0, -1] == 1e200


def test_a_huge_weighted_value_cannot_hide_behind_a_second_book():
    factors = np.array([[1e200], [1e200]])
    paths = np.array([[0, 1]], dtype=np.int32)
    cheap = (((0, 1e-300),), 1.0)
    with pytest.raises(InvalidArgument):
        evaluate(factors, paths, [cheap, (((0, 1.0),), 0.0)])


@pytest.mark.parametrize(
    ("weights", "cash"),
    [
        ({"KR:A": Decimal("1E-201")}, Decimal(1)),
        ({"KR:A": Decimal("1E-1000")}, Decimal(1)),
        ({"KR:A": Decimal("1E-300"), "US:B": Decimal("0.5")}, Decimal("0.5")),
        ({"KR:A": Decimal("1E+999999")}, Decimal(0)),
        ({"KR:A": Decimal(1)}, Decimal("1E-201")),
    ],
)
def test_sums_that_need_rounding_are_rejected_not_accepted(weights, cash):
    with pytest.raises(InvalidArgument):
        Portfolio("TEAM", weights, cash)


def test_exact_sums_with_many_digits_are_still_accepted():
    third = Decimal("0." + "3" * 100)
    cash = Decimal("0." + "6" * 99 + "7")
    assert len(cash.as_tuple().digits) == 100
    assert Portfolio("TEAM", {"KR:A": third}, cash).cash == cash
    with pytest.raises(InvalidArgument):
        Portfolio("TEAM", {"KR:A": third}, cash + Decimal("1E-100"))


def test_a_history_that_allows_one_path_is_rejected_not_reported_as_many():
    panel = random_panel(tiny(), ["KR:A"], 3)
    team = [Portfolio("TEAM", {"KR:A": Decimal(1)}, Decimal(0))]
    kwargs = {"horizon": 12, "replay_start": date(2010, 1, 1), "n": 100}
    forced = stationary.BootstrapParams(mean_block_length=1, max_block_length=1, min_candidates=1)
    with pytest.raises(PathTooLong, match="only one projection path") as caught:
        run(panel, team, params=forced, **kwargs)
    assert not re.search(r"\d{4}-\d{2}", str(caught.value))
    default = run(panel, team, **kwargs)
    assert default.eligible_months == 12
    assert len({row.tobytes() for row in default.portfolios[0].end_values}) > 1


def test_quantile_ends_and_two_samples_by_hand():
    assert quantiles(np.array([3.0, 1.0, 2.0]), [0, 100]).tolist() == [1.0, 3.0]
    two = np.array([[1.0, 8.0], [3.0, 4.0]])
    assert quantiles(two, [0, 25, 50, 75, 100]).tolist() == [
        [1.0, 4.0],
        [1.5, 5.0],
        [2.0, 6.0],
        [2.5, 7.0],
        [3.0, 8.0],
    ]


def test_two_paths_summary_by_hand():
    values = np.array([[1.0, 0.5, 0.25], [1.0, 2.0, 4.0]])
    result = summarize("A", values, [1, 2], -0.5)
    assert result.bands[0].tolist()[1:4] == [0.875, 1.25, 1.625]
    assert result.bands[1].tolist()[1:4] == [1.1875, 2.125, 3.0625]
    first, last = result.summaries
    assert first.mdd_quantiles[3] == -0.25 and first.breach_count == 0
    assert last.mdd_quantiles[3] == -0.375 and last.breach_count == 1


def test_a_total_loss_stays_zero_and_is_a_full_drawdown():
    factors = np.array([[0.0, 1.0], [2.0, 1.0]])
    paths = np.array([[0, 1, 0]], dtype=np.int32)
    (values,) = evaluate(factors, paths, [(((0, 1.0),), 0.0)])
    assert values[0].tolist() == [1.0, 0.0, 0.0, 0.0]
    (half,) = evaluate(factors, paths, [(((0, 0.5),), 0.5)])
    assert half[0].tolist() == [1.0, 0.5, 0.5, 0.5]
    lost = summarize("A", values, [1, 3], -0.9)
    assert [s.mdd_quantiles[0] for s in lost.summaries] == [-1.0, -1.0]
    assert [s.breach_count for s in lost.summaries] == [1, 1]
    assert summarize("A", values, [3], -1.0).summaries[0].breach_count == 0


def test_repeated_equal_peaks_keep_the_running_minimum():
    values = np.array([[1.0, 2.0, 1.0, 2.0, 1.0]])
    result = summarize("A", values, [1, 2, 3, 4], -0.4)
    assert [s.mdd_quantiles[0] for s in result.summaries] == [0.0, -0.5, -0.5, -0.5]
    assert [s.breach_count for s in result.summaries] == [0, 1, 1, 1]
    flat = summarize("A", np.ones((3, 4)), [3], -0.01).summaries[0]
    assert flat.mdd_quantiles.tolist() == [0.0] * 6 and flat.breach_count == 0


PINNED_DIGEST = "012a938d774f06dbf898b8aa151cb73680a9e78b5cedaed0510a21c61b21a040"


def test_fixed_input_gives_the_pinned_digest_on_every_platform():
    panel, books = build(asset_names(5))
    assert fingerprint(run(panel, books)) == PINNED_DIGEST, environment_note()


def test_environment_note_names_python_numpy_and_the_wheel():
    note = environment_note()
    assert np.__version__ in note and platform.python_version() in note and "Tag:" in note
