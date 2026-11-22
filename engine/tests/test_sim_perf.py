"""Season throughput of the simulator core. The light test keeps a generous ceiling for CI;
the slow one measures the 20 seasons/s target with `-m slow -s`."""

import statistics
import time
from datetime import date
from decimal import Decimal

import pytest
from score_support import inputs as scored_inputs
from sim_support import KR_A, KR_B, US_X, inputs

from antlab_engine.sim import score
from antlab_engine.sim.counts import FixedCounts, PercentileCounts, RankCounts
from antlab_engine.sim.model import Order, OrderSource, Position, Side
from antlab_engine.sim.season import Season

ASSETS = (KR_A, KR_B, US_X)
POSITIONS = (Position.DEFENDER, Position.MIDFIELDER, Position.FORWARD_TARGET)


def play_season(length: int, orders_per_turn: int) -> Season:
    """A fixed order stream (no randomness): alternating small buys and partial sells."""
    season = Season(inputs(length=length, single_asset_limit=Decimal("0.6")))
    counter = 0
    for _ in range(length):
        for _ in range(orders_per_turn):
            asset = ASSETS[counter % 3]
            if counter % 4 == 3 and asset in season.holdings:
                season.place_order(Order(asset, Side.SELL, 1 + counter % 7))
            else:
                season.place_order(
                    Order(asset, Side.BUY, 1 + counter % 9, POSITIONS[counter % 3], OrderSource())
                )
            counter += 1
        season.advance()
    return season


def seasons_per_second(count: int, play=None) -> tuple[float, float]:
    play = play or (lambda: play_season(60, 10))
    times = []
    for _ in range(count):
        start = time.perf_counter()
        play()
        times.append(time.perf_counter() - start)
    return count / sum(times), statistics.median(times)


COUNTS = FixedCounts(PercentileCounts(RankCounts(1200, 100, 2000), RankCounts(900, 0, 2000)))


def play_scored(length: int, orders_per_turn: int, pool_extra: int = 10) -> score.ScoredSeason:
    """A season on a 14-asset pool, then its report and `s63-v3` score (rank counts recorded)."""
    season = Season(
        scored_inputs(length, flat=False, extra=pool_extra, single_asset_limit=Decimal("0.6"))
    )
    assets = [a.asset for a in season.inputs.pool]
    counter = 0
    for _ in range(length):
        for _ in range(orders_per_turn):
            asset = assets[counter % len(assets)]
            if counter % 4 == 3 and asset in season.holdings:
                season.place_order(Order(asset, Side.SELL, 1 + counter % 7))
            else:
                season.place_order(
                    Order(asset, Side.BUY, 1 + counter % 9, POSITIONS[counter % 3], OrderSource())
                )
            counter += 1
        season.advance()
    return score.score_season(
        season, COUNTS, season_seed=7, base_date=date(2026, 1, 2), path_count=2000
    )


def test_a_sixty_turn_season_with_ten_orders_a_turn_stays_far_below_the_ceiling():
    season = play_season(60, 10)
    assert len(season.turns) == 60 and len(season.executions) > 300
    season.check_invariants()
    _, median = seasons_per_second(5)
    assert median < 0.5, f"median {median:.3f} s per season"


def test_a_scored_sixty_turn_season_stays_far_below_the_ceiling():
    scored = play_scored(60, 10)
    assert scored.score.score is not None and scored.report.report_turns == 60
    _, median = seasons_per_second(3, lambda: play_scored(60, 10))
    assert median < 1.0, f"median {median:.3f} s per scored season"


@pytest.mark.slow
def test_throughput_meets_twenty_seasons_per_second():
    rate, median = seasons_per_second(100)
    print(f"60 turns x 10 orders (core only): {rate:.0f} seasons/s, median {median * 1000:.1f} ms")
    assert rate >= 20


@pytest.mark.slow
def test_throughput_with_report_and_score_meets_twenty_seasons_per_second():
    rate, median = seasons_per_second(40, lambda: play_scored(60, 10))
    print(f"60 turns, report + score: {rate:.1f} seasons/s, median {median * 1000:.1f} ms")
    assert rate >= 20


@pytest.mark.slow
def test_twelve_turn_season_with_report_and_score_stays_within_ten_milliseconds():
    _, median = seasons_per_second(60, lambda: play_scored(12, 10))
    print(f"12 turns x 10 orders + report + score: median {median * 1000:.1f} ms")
    assert median <= 0.010
