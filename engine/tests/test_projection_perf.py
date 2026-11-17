"""Timing of the projection stages for M2-05; run with -m slow -s."""

import platform
import resource
import sys
import time
from decimal import Decimal

import numpy as np
import pytest
from synthetic_months import BASE_DATE, EXT348_REPLAY_START
from synthetic_panel import REPLAY_MONTHS, asset_names, random_panel

from antlab_engine.bootstrap import stationary
from antlab_engine.projection import Portfolio, derive_path_seeds, draw_paths, project
from antlab_engine.projection.evaluate import evaluate
from antlab_engine.projection.stats import summarize

pytestmark = pytest.mark.slow

ASSETS = 180
N = 2000
HORIZON = 120


def books(assets: list[str]) -> list[Portfolio]:
    share = Decimal("0.005")
    team = Portfolio("TEAM", dict.fromkeys(assets, share), Decimal(1) - share * len(assets))
    market = Portfolio("MARKET", {assets[0]: Decimal("0.9")}, Decimal("0.1"))
    mixed = Portfolio(
        "MIXED", {assets[1]: Decimal("0.5"), assets[2]: Decimal("0.3")}, Decimal("0.2")
    )
    return [team, market, mixed]


def test_two_thousand_paths_over_120_turns_for_180_assets_and_three_portfolios():
    assets = asset_names(ASSETS)
    panel = random_panel(REPLAY_MONTHS, assets, 1)
    portfolios = books(assets)
    held = [
        (tuple((panel.assets.index(a), float(w)) for a, w in p.active), float(p.cash))
        for p in portfolios
    ]

    start = time.perf_counter()
    seeds = derive_path_seeds(1, N)
    paths = draw_paths(
        panel, BASE_DATE, seeds, HORIZON, assets, EXT348_REPLAY_START, stationary.DEFAULT_PARAMS
    )
    drawn = time.perf_counter()
    values = evaluate(panel.factors, paths, held)
    evaluated = time.perf_counter()
    for p, v in zip(portfolios, values, strict=True):
        summarize(p.key, v, [60, 120], -0.2)
    done = time.perf_counter()
    print(
        f"stages: paths {drawn - start:.3f} s, evaluation {evaluated - drawn:.3f} s, "
        f"statistics {done - evaluated:.3f} s"
    )

    begin = time.perf_counter()
    result = project(
        panel,
        BASE_DATE,
        season_seed=1,
        horizon=HORIZON,
        portfolios=portfolios,
        risk_limit=Decimal("-0.2"),
        n=N,
        replay_start=EXT348_REPLAY_START,
    )
    total = time.perf_counter() - begin
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak_mb = peak / 1e6 if sys.platform == "darwin" else peak / 1e3
    print(f"project(): {total:.3f} s; process peak RSS {peak_mb:.0f} MB")
    print(
        f"environment: python {platform.python_version()}, numpy {np.__version__}, "
        f"{platform.machine()}"
    )
    assert len(result.portfolios) == 3
    assert total < 2
