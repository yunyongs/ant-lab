from decimal import Decimal

import numpy as np
import projection_reference as reference
from synthetic_months import BASE_DATE, EXT348_REPLAY_START
from synthetic_panel import REPLAY_MONTHS, asset_names, random_rows

from antlab_engine.bootstrap import stationary
from antlab_engine.projection import Portfolio, ReturnPanel, derive_path_seeds, project

LIMIT = Decimal("-0.05")


def independent_factors(rows, assets):
    """Factors per month and asset, computed without the library's ReturnRow."""
    month_row = {m: i for i, m in enumerate(REPLAY_MONTHS)}
    table = [[0.0] * len(assets) for _ in REPLAY_MONTHS]
    for r in rows:
        table[month_row[r.month]][assets.index(r.asset)] = float(
            (1 + r.local_return) * (1 + r.fx_change)
        )
    return table


def test_projection_matches_an_independent_pure_python_evaluator_bit_for_bit():
    assets = sorted(asset_names(5))
    rows = random_rows(REPLAY_MONTHS, assets, 21)
    panel = ReturnPanel.from_rows(REPLAY_MONTHS, assets, rows)
    wide = Portfolio(
        "WIDE",
        {
            assets[0]: Decimal("0.3"),
            assets[1]: Decimal("0.2"),
            assets[2]: Decimal("0.1"),
            assets[3]: Decimal("0.15"),
            assets[4]: Decimal("0.05"),
        },
        Decimal("0.2"),
    )
    narrow = Portfolio(
        "NARROW", {assets[4]: Decimal("0.7"), assets[1]: Decimal("0.1")}, Decimal("0.2")
    )
    result = project(
        panel,
        BASE_DATE,
        season_seed=-77,
        horizon=24,
        portfolios=[wide, narrow],
        risk_limit=LIMIT,
        n=100,
        summary_turns=(12, 24),
        replay_start=EXT348_REPLAY_START,
    )

    factors = independent_factors(rows, assets)
    month_row = {m: i for i, m in enumerate(REPLAY_MONTHS)}
    paths = [
        [
            month_row[m]
            for m in stationary.generate(
                REPLAY_MONTHS, BASE_DATE, 24, seed, replay_start=EXT348_REPLAY_START
            ).months
        ]
        for seed in derive_path_seeds(-77, 100)
    ]
    for book, mine in zip((wide, narrow), result.portfolios, strict=True):
        held = [(assets.index(a), float(w)) for a, w in book.active]
        values = reference.values(factors, paths, sorted(held), float(book.cash))
        bands, summaries = reference.summarize(values, (12, 24), float(LIMIT))
        assert mine.end_values.tobytes() == np.array(values).tobytes()
        assert mine.bands.tobytes() == np.array(bands).tobytes()
        for ours, theirs in zip(mine.summaries, summaries, strict=True):
            assert ours.turn == theirs["turn"]
            assert ours.end_quantiles.tobytes() == np.array(theirs["end"]).tobytes()
            assert ours.mdd_quantiles.tobytes() == np.array(theirs["mdd"]).tobytes()
            assert ours.breach_count == theirs["breach"]
