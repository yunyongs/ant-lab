"""The Python-owned `m2-sb6-v1` price vectors: one writer (make_m2_price_vectors.py).

This file drift-tests them. The Java test `M2PriceVectorsTest` reads the same file and recomputes
every won with MoneyMath.priceAt.
"""

import numpy as np
from make_m2_price_vectors import LENGTHS, SEEDS, render
from parity_prices import (
    BASE_DATE,
    M2_FILE,
    PRICES_HEADER,
    exact_factor,
    exact_value,
    load_inputs,
    paths_of,
    read_price_rows,
    won,
)

from antlab_engine.bootstrap import rules
from antlab_engine.bootstrap.stationary import ENGINE_VERSION, generate
from antlab_engine.projection import ReturnPanel, ReturnRow
from antlab_engine.projection.evaluate import evaluate

INPUTS = load_inputs()


def test_file_equals_a_fresh_run_of_the_generator():
    assert M2_FILE.read_text(encoding="utf-8") == render()


def test_file_shape():
    lines = M2_FILE.read_text(encoding="utf-8").splitlines()
    assert lines[0] == PRICES_HEADER
    rows = read_price_rows(M2_FILE)
    assert len(rows) == len(SEEDS) * sum(LENGTHS) * len(INPUTS.assets)
    assert {(seed, length) for seed, length, *_ in rows} == {
        (seed, length) for seed in SEEDS for length in LENGTHS
    }
    assert ENGINE_VERSION == "m2-sb6-v1"


def test_months_follow_the_m2_rules_and_come_from_the_inputs():
    for (seed, length), path in paths_of(read_price_rows(M2_FILE)).items():
        assert set(path) <= set(INPUTS.months)
        full = generate(INPUTS.months, BASE_DATE, length, seed)
        assert list(full.months) == path
        broken = rules.violations(
            path, BASE_DATE, length, engine_version=ENGINE_VERSION, blocks=full.blocks
        )
        assert broken == [], (seed, length)


def test_every_price_is_the_exact_product_rounded_half_up_once():
    paths = paths_of(read_price_rows(M2_FILE))
    for seed, length, turn, asset, _, price in read_price_rows(M2_FILE):
        assert won(exact_value(INPUTS, asset, paths[(seed, length)][:turn])) == price


def test_float64_multiple_of_the_projection_library_tracks_the_exact_factor():
    rows = [
        ReturnRow(asset, month, INPUTS.local[(asset, month)], INPUTS.fx[(asset, month)])
        for asset in INPUTS.assets
        for month in INPUTS.months
    ]
    panel = ReturnPanel.from_rows(INPUTS.months, INPUTS.assets, rows)
    row_of = {m: i for i, m in enumerate(panel.months)}
    worst = 0
    for (_, length), path in paths_of(read_price_rows(M2_FILE)).items():
        if length != 60:
            continue
        paths = np.array([[row_of[m] for m in path]], dtype=np.int32)
        for column, asset in enumerate(panel.assets):
            (values,) = evaluate(panel.factors, paths, [(((column, 1.0),), 0.0)])
            for turn in (1, 12, 30, 60):
                exact = exact_factor(INPUTS, asset, path[:turn])
                worst = max(worst, abs(values[0, turn] - float(exact)) / float(exact))
    assert worst <= 1e-9
