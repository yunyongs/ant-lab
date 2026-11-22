"""Season throughput of the simulator core. The light test keeps a generous ceiling for CI;
the slow one measures the 20 seasons/s target with `-m slow -s`."""

import statistics
import time
from decimal import Decimal

import pytest
from sim_support import KR_A, KR_B, US_X, inputs

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


def seasons_per_second(count: int) -> tuple[float, float]:
    times = []
    for _ in range(count):
        start = time.perf_counter()
        play_season(60, 10)
        times.append(time.perf_counter() - start)
    return count / sum(times), statistics.median(times)


def test_a_sixty_turn_season_with_ten_orders_a_turn_stays_far_below_the_ceiling():
    season = play_season(60, 10)
    assert len(season.turns) == 60 and len(season.executions) > 300
    season.check_invariants()
    _, median = seasons_per_second(5)
    assert median < 0.5, f"median {median:.3f} s per season"


@pytest.mark.slow
def test_throughput_meets_twenty_seasons_per_second():
    rate, median = seasons_per_second(100)
    print(f"60 turns x 10 orders: {rate:.0f} seasons/s, median {median * 1000:.1f} ms")
    assert rate >= 20
