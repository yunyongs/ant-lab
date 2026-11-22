"""Rank-count sources: the recorded answer and the in-process engine library."""

from decimal import Decimal

import pytest
from score_support import AAA, KR_BENCH, US_BENCH
from synthetic_months import BASE_DATE, EXT348_REPLAY_START
from synthetic_panel import REPLAY_MONTHS, random_panel

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection import Portfolio, rank
from antlab_engine.sim.counts import (
    EngineCounts,
    FixedCounts,
    PercentileCounts,
    PercentileRequest,
    RankCounts,
    WirePortfolio,
)
from antlab_engine.sim.model import SimReportError, SimShapeError

ASSETS = [KR_BENCH, US_BENCH, AAA]
PANEL = random_panel(REPLAY_MONTHS, ASSETS, 21)


def request(n: int = 100, end: str = "1.000000000000", diff: str = "0.000000000000"):
    team = WirePortfolio("0.200000000000", ((AAA, "0.500000000000"), (US_BENCH, "0.300000000000")))
    bench = WirePortfolio("0", ((KR_BENCH, "0.5"), (US_BENCH, "0.5")))
    return PercentileRequest(77, BASE_DATE, 12, n, team, bench, end, diff)


def test_engine_counts_equal_the_library_call_with_the_same_portfolios():
    source = EngineCounts(PANEL, EXT348_REPLAY_START)
    got = source.counts(request())
    want = rank(
        PANEL,
        BASE_DATE,
        season_seed=77,
        horizon=12,
        team=Portfolio("TEAM", {AAA: Decimal("0.5"), US_BENCH: Decimal("0.3")}, Decimal("0.2")),
        benchmark=Portfolio(
            "BENCHMARK", {KR_BENCH: Decimal("0.5"), US_BENCH: Decimal("0.5")}, Decimal(0)
        ),
        observed_end_multiple=Decimal(1),
        observed_sharpe_diff=Decimal(0),
        n=100,
        replay_start=EXT348_REPLAY_START,
    )
    assert got.sharpe_diff == RankCounts(want.sharpe_diff.below, want.sharpe_diff.equal, 100)
    assert got.end_multiple == RankCounts(want.end_multiple.below, want.end_multiple.equal, 100)
    assert got == source.counts(request())  # deterministic for a seed


def test_engine_counts_move_with_the_observed_values():
    source = EngineCounts(PANEL, EXT348_REPLAY_START)
    low = source.counts(request(end="0", diff="-999999"))
    high = source.counts(request(end="999999999999", diff="999999"))
    assert (low.end_multiple.below, low.sharpe_diff.below) == (0, 0)
    assert (high.end_multiple.below, high.sharpe_diff.below) == (100, 100)


def test_the_library_refusals_surface_unchanged():
    source = EngineCounts(PANEL, EXT348_REPLAY_START)
    stranger = WirePortfolio("0.5", (("KR:ZZZ", "0.5"),))
    bad = PercentileRequest(77, BASE_DATE, 12, 100, stranger, request().benchmark, "1", "0")
    with pytest.raises(InvalidArgument):
        source.counts(bad)


def test_fixed_counts_replay_a_recorded_answer_for_its_path_number_only():
    answer = PercentileCounts(RankCounts(10, 2, 100), RankCounts(4, 0, 100))
    assert FixedCounts(answer).counts(request()) == answer
    with pytest.raises(SimReportError):
        FixedCounts(answer).counts(request(n=200))


def test_count_shapes_are_checked_at_construction():
    with pytest.raises(SimShapeError):
        PercentileCounts(RankCounts(1, 0, 10), RankCounts(1, 0, 11))
    with pytest.raises(SimShapeError):
        RankCounts(5, 6, 10)


def test_engine_counts_turn_path_too_long_into_no_distribution_and_rethrow_the_rest():
    from dataclasses import replace

    from synthetic_months import tiny

    months = tiny()  # twelve months cannot carry a 120-turn horizon
    short = random_panel(months, ASSETS, 3)
    assert EngineCounts(short, months[0]).counts(replace(request(), horizon_turns=120)) is None
    with pytest.raises(InvalidArgument):
        stranger = WirePortfolio("0.5", (("KR:ZZZ", "0.5"),))
        bad = PercentileRequest(77, BASE_DATE, 12, 100, stranger, request().benchmark, "1", "0")
        EngineCounts(PANEL, EXT348_REPLAY_START).counts(bad)


def test_fixed_counts_can_replay_a_recorded_refusal():
    assert FixedCounts(None).counts(request(n=100)) is None
