"""`CachedEngineCounts` equals `rank()`; the cache key holds the eligible rows."""

from datetime import date
from decimal import Decimal

from synthetic_months import month_range, tiny
from synthetic_panel import asset_names, random_panel

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.projection import Portfolio, rank
from antlab_engine.sim.counts import (
    EngineCounts,
    PercentileRequest,
    WirePortfolio,
)
from antlab_engine.validation.counts_cache import CachedEngineCounts

BASE = date(2026, 1, 1)
MONTHS = month_range(date(1997, 1, 1), date(2025, 12, 1))


def _wire(rng: SplitMix64, assets: list[str]) -> WirePortfolio:
    chosen = [a for a in assets if rng.next_int(3) == 0] or assets[:1]
    weights = tuple((a, "0.1") for a in chosen[:5])
    cash = Decimal(1) - Decimal("0.1") * len(weights)
    return WirePortfolio(format(cash, "f"), weights)


def _request(rng: SplitMix64, assets: list[str], seed: int, horizon: int, n: int):
    end = Decimal(rng.next_int(2000)) / 1000
    diff = Decimal(rng.next_int(2000) - 1000) / 1000
    return PercentileRequest(
        seed,
        BASE,
        horizon,
        n,
        _wire(rng, assets),
        _wire(rng, assets[:2]),
        format(end, "f"),
        format(diff, "f"),
    )


def _gap_panel():
    assets = asset_names(6)
    skip = frozenset((assets[1], m) for m in MONTHS[100:200])
    return assets, random_panel(MONTHS, assets, 5, skip)


def test_equals_rank_on_sixty_requests_with_and_without_gaps():
    rng = SplitMix64(99)
    checked = 0
    for gaps in (False, True):
        assets = asset_names(6)
        panel = _gap_panel()[1] if gaps else random_panel(MONTHS, assets, 5)
        cached = CachedEngineCounts(panel)
        oracle = EngineCounts(panel)
        for i in range(30):
            request = _request(rng, assets, 1 + i % 3, (12, 24)[i % 2], 100)
            got = cached.counts(request)
            assert got is not None
            assert got == oracle.counts(request)
            checked += 1
    assert checked == 60


def test_matches_the_rank_library_directly():
    assets = asset_names(6)
    panel = random_panel(MONTHS, assets, 8)
    request = _request(SplitMix64(4), assets, 11, 12, 100)
    direct = rank(
        panel,
        BASE,
        season_seed=11,
        horizon=12,
        team=Portfolio(
            "TEAM", {a: Decimal(w) for a, w in request.team.weights}, Decimal(request.team.cash)
        ),
        benchmark=Portfolio(
            "BENCHMARK",
            {a: Decimal(w) for a, w in request.benchmark.weights},
            Decimal(request.benchmark.cash),
        ),
        observed_end_multiple=Decimal(request.end_multiple),
        observed_sharpe_diff=Decimal(request.sharpe_diff),
        n=100,
    )
    got = CachedEngineCounts(panel).counts(request)
    assert (got.sharpe_diff.below, got.sharpe_diff.equal) == (
        direct.sharpe_diff.below,
        direct.sharpe_diff.equal,
    )
    assert (got.end_multiple.below, got.end_multiple.equal) == (
        direct.end_multiple.below,
        direct.end_multiple.equal,
    )


def test_the_cache_key_holds_the_eligible_rows():
    assets, panel = _gap_panel()
    cached = CachedEngineCounts(panel)
    oracle = EngineCounts(panel)
    full = PercentileRequest(
        3,
        BASE,
        12,
        100,
        WirePortfolio("0.5", ((assets[0], "0.5"),)),
        WirePortfolio("0.5", ((assets[2], "0.5"),)),
        "1.1",
        "0.1",
    )
    gappy = PercentileRequest(
        3,
        BASE,
        12,
        100,
        WirePortfolio("0.5", ((assets[1], "0.5"),)),
        WirePortfolio("0.5", ((assets[2], "0.5"),)),
        "1.1",
        "0.1",
    )
    assert cached.counts(full) == oracle.counts(full)
    assert cached.counts(gappy) == oracle.counts(gappy)
    assert cached.draws == 2


def test_teams_with_the_same_eligible_rows_share_one_draw():
    assets = asset_names(6)
    cached = CachedEngineCounts(random_panel(MONTHS, assets, 5))
    rng = SplitMix64(1)
    for _ in range(10):
        assert cached.counts(_request(rng, assets, 7, 12, 100)) is not None
    assert cached.draws == 1
    assert cached.counts(_request(rng, assets, 8, 12, 100)) is not None
    assert cached.counts(_request(rng, assets, 7, 24, 100)) is not None
    assert cached.counts(_request(rng, assets, 7, 12, 150)) is not None
    assert cached.draws == 4


def test_a_history_that_cannot_carry_the_horizon_answers_none_like_the_engine():
    assets = asset_names(3)
    panel = random_panel(tiny(), assets, 2)
    request = _request(SplitMix64(2), assets, 5, 24, 100)
    assert CachedEngineCounts(panel).counts(request) is None
    assert EngineCounts(panel).counts(request) is None


def test_the_cache_keeps_a_bounded_number_of_draws():
    assets = asset_names(4)
    cached = CachedEngineCounts(random_panel(MONTHS, assets, 6))
    rng = SplitMix64(3)
    for seed in range(20):
        cached.counts(_request(rng, assets, seed, 12, 100))
    assert len(cached._paths) <= 8


def test_equals_rank_at_horizon_60_with_two_thousand_paths_and_a_zero_weight_entry():
    assets = asset_names(6)
    panel = random_panel(MONTHS, assets, 12)
    team = WirePortfolio("0.5", ((assets[0], "0.3"), (assets[1], "0"), (assets[2], "0.2")))
    bench = WirePortfolio("0.5", ((assets[3], "0.5"),))
    request = PercentileRequest(21, BASE, 60, 2000, team, bench, "1.05", "0.05")
    got = CachedEngineCounts(panel).counts(request)
    assert got is not None and got.sharpe_diff.n == 2000
    assert got == EngineCounts(panel).counts(request)
