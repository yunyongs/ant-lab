from datetime import date

import pytest
from synthetic_months import BASE_DATE, EXT348_REPLAY_START, ext348
from synthetic_panel import REPLAY_MONTHS, asset_names, random_panel

from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.projection import derive_path_seeds, draw_paths, seeds

EDGE_SEEDS = [-(1 << 63), -1, 0, 1, (1 << 63) - 1]


def test_seeds_are_distinct_signed_and_never_the_season_seed():
    for season in [*EDGE_SEEDS, *range(100, 295)]:
        derived = derive_path_seeds(season, 2000)
        assert len(derived) == 2000 == len(set(derived))
        assert season not in derived
        assert all(-(1 << 63) <= s < (1 << 63) for s in derived)


def test_derivation_is_pinned():
    assert derive_path_seeds(12345, 3) == (
        2843186674318266724,
        4180088400766346357,
        8646849347189256505,
    )
    assert derive_path_seeds(12345, 5)[:3] == derive_path_seeds(12345, 3)
    assert derive_path_seeds(12346, 1) != derive_path_seeds(12345, 1)


MASK = (1 << 64) - 1


def independent_seeds(season, n):
    """SplitMix64 written out from its published steps, not the library's class."""
    state = (season ^ 0x50524F4A45435431) & MASK
    seen, result = {season}, []
    while len(result) < n:
        state = (state + 0x9E3779B97F4A7C15) & MASK
        z = ((state ^ (state >> 30)) * 0xBF58476D1CE4E5B9) & MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
        z ^= z >> 31
        value = z - (1 << 64) if z >= 1 << 63 else z
        if value not in seen:
            seen.add(value)
            result.append(value)
    return tuple(result)


PINNED = {
    -1: (-6382850330246812792, 4650425084360761319, 7854637892520682154),
    0: (-5360021140964493346, 3061852271991838701, 6644459793017362082),
    (1 << 63) - 1: (-149759637888837787, 8692628374039415730, -6194489839260847378),
    -(1 << 63): (-3429890229126037812, 1619211747727228793, 6684872872295925373),
}


def test_seeds_match_an_independent_derivation_and_pinned_vectors():
    for season, first in PINNED.items():
        assert derive_path_seeds(season, 3) == first == independent_seeds(season, 3)
    for season in (*EDGE_SEEDS, 12345, 987654321, -424242):
        assert derive_path_seeds(season, 300) == independent_seeds(season, 300)


def test_the_number_of_seeds_is_capped():
    from antlab_engine.projection.model import MAX_PATHS

    assert len(derive_path_seeds(1, MAX_PATHS)) == MAX_PATHS
    for bad in (MAX_PATHS + 1, 10**9, -1, True, 1.5):
        with pytest.raises(InvalidArgument):
            derive_path_seeds(1, bad)


def test_draw_paths_caps_the_number_of_paths_before_drawing():
    from antlab_engine.projection.model import MAX_PATHS

    panel = random_panel(REPLAY_MONTHS, ["KR:A"], 1)
    with pytest.raises(InvalidArgument):
        draw_paths(
            panel,
            BASE_DATE,
            range(MAX_PATHS + 1),
            12,
            ["KR:A"],
            EXT348_REPLAY_START,
            stationary.DEFAULT_PARAMS,
        )


def test_a_derived_value_equal_to_the_season_seed_is_skipped(monkeypatch):
    class Stub:
        def __init__(self, _seed):
            self.values = iter([7, 7, 8, 9, 8, 10])

        def next(self):
            return next(self.values)

    monkeypatch.setattr(seeds, "SplitMix64", Stub)
    assert derive_path_seeds(7, 3) == (8, 9, 10)


def test_season_path_is_never_one_of_the_projection_paths():
    months = ext348()
    for season in range(200):
        own = stationary.generate(
            months, BASE_DATE, 60, season, replay_start=EXT348_REPLAY_START
        ).months
        for seed in derive_path_seeds(season, 40):
            other = stationary.generate(
                months, BASE_DATE, 60, seed, replay_start=EXT348_REPLAY_START
            ).months
            assert other != own


def test_paths_differ_by_season_seed_and_ignore_the_asset_order():
    assets = asset_names(3)
    panel = random_panel(REPLAY_MONTHS, assets, 5)

    def paths(season, used):
        return draw_paths(
            panel,
            BASE_DATE,
            derive_path_seeds(season, 20),
            24,
            used,
            EXT348_REPLAY_START,
            stationary.DEFAULT_PARAMS,
        )

    assert (paths(1, assets) != paths(2, assets)).any()
    assert (paths(1, assets) == paths(1, assets)).all()
    assert (paths(1, assets) == paths(1, list(reversed(assets)))).all()


@pytest.mark.parametrize("bad", [True, 1.5, "1", 1 << 63, -(1 << 63) - 1])
def test_bad_seed_is_rejected(bad):
    with pytest.raises(InvalidArgument):
        derive_path_seeds(bad, 1)


def test_paths_are_row_indexes_inside_the_replay_range():
    panel = random_panel([date(1996, 12, 1), *REPLAY_MONTHS, date(2026, 1, 1)], ["KR:A"], 3)
    paths = draw_paths(
        panel,
        BASE_DATE,
        derive_path_seeds(3, 10),
        12,
        ["KR:A"],
        EXT348_REPLAY_START,
        stationary.DEFAULT_PARAMS,
    )
    assert paths.dtype.name == "int32" and paths.shape == (10, 12)
    assert paths.min() >= 1 and paths.max() <= len(REPLAY_MONTHS)
