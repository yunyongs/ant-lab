"""`panel-v1` worlds: determinism, path cells equal panel cells, generator honoured, no dates."""

import re
from datetime import date
from decimal import Decimal

import pytest

from antlab_engine.bootstrap import simple, stationary
from antlab_engine.bots import io
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import canonical
from antlab_engine.bots.world_panel import (
    BASE_DATE,
    panel_months,
    panel_world,
    path_months,
    path_seed,
)
from antlab_engine.projection import ReturnRow
from antlab_engine.sim import measure, report
from antlab_engine.sim.decisions import apply_unit
from antlab_engine.sim.model import Position, SimShapeError
from antlab_engine.sim.season import Season
from antlab_engine.validation.counts_cache import CachedEngineCounts


def _world(index=0, generator="simple", length=12, assets=12, master=1):
    return panel_world(master, index, length=length, assets=assets, generator=generator)


def test_panel_months_run_from_1997_01_to_2025_12():
    months = panel_months()
    assert len(months) == 348
    assert (months[0].year, months[0].month) == (1997, 1)
    assert (months[-1].year, months[-1].month) == (2025, 12)


@pytest.mark.parametrize("generator", ["simple", "stationary"])
def test_same_inputs_give_the_same_bytes(generator):
    w1, p1 = _world(generator=generator)
    w2, p2 = _world(generator=generator)
    assert canonical(w1.to_doc()) == canonical(w2.to_doc())
    assert p1.months == p2.months and p1.assets == p2.assets
    assert p1.factors.tobytes() == p2.factors.tobytes()


def test_master_seed_index_and_generator_change_the_world():
    ids = {
        _world(0)[0].world_id,
        _world(1)[0].world_id,
        _world(0, master=2)[0].world_id,
        _world(0, generator="stationary")[0].world_id,
    }
    assert len(ids) == 4


@pytest.mark.parametrize("generator", ["simple", "stationary"])
@pytest.mark.parametrize("length", [12, 24])
def test_every_path_cell_is_the_panel_cell_of_the_drawn_month(generator, length):
    world, panel = _world(2, generator, length)
    months = path_months(panel, world.seed, length, generator)
    assert len(world.inputs.path) == length
    row_of = {m: i for i, m in enumerate(panel.months)}
    column = {a: i for i, a in enumerate(panel.assets)}
    for step, month in zip(world.inputs.path, months, strict=True):
        assert set(step.local_returns) == set(panel.assets)
        for asset, local in step.local_returns.items():
            fx = step.fx_change["USD"] if asset.startswith("US:") else Decimal(0)
            cell = ReturnRow(asset, month, local, fx).factor()
            assert cell == panel.factors[row_of[month], column[asset]]


def test_the_simple_generator_walks_the_java_default_months():
    world, panel = _world(3, "simple", 24)
    expected = simple.generate_months(list(panel.months), BASE_DATE, 24, path_seed(world.seed))
    assert path_months(panel, world.seed, 24, "simple") == expected
    assert expected[0].month == 1 and expected[6].month == 7


def test_the_stationary_generator_walks_the_engine_months():
    world, panel = _world(3, "stationary", 24)
    expected = stationary.generate(list(panel.months), BASE_DATE, 24, path_seed(world.seed)).months
    assert tuple(path_months(panel, world.seed, 24, "stationary")) == expected
    other = simple.generate_months(list(panel.months), BASE_DATE, 24, path_seed(world.seed))
    assert list(expected) != other


def test_path_months_stay_inside_the_replay_range():
    for generator in ("simple", "stationary"):
        world, panel = _world(4, generator, 24)
        for month in path_months(panel, world.seed, 24, generator):
            assert simple.REPLAY_START <= month < BASE_DATE


def test_feed_facts_are_synthetic_and_cover_every_turn():
    world, _ = _world(0, length=12)
    assert world.feed is not None
    assert all(a["name"].startswith("Asset ") for a in world.feed["assets"])
    assert all(e["key"].startswith("synthetic-") for e in world.feed["events"])
    assert [row["turn"] for row in world.feed["attention"]] == list(range(13))
    assert world.feed["reviewed"] == list(range(1, 13))
    assert all(v is None for v in world.feed["attention"][0]["values"].values())
    assert any(v is not None for v in world.feed["attention"][1]["values"].values())


@pytest.mark.parametrize("generator", ["simple", "stationary"])
def test_attention_rows_of_the_first_turns_need_only_those_turns_months(generator):
    from antlab_engine.bots.world_panel import _asset_names, _attention, _build_panel

    for index in range(6):
        world, panel = _world(index, generator, 24, 6)
        names = _asset_names(6)
        _, cells = _build_panel(world.seed, names)
        path = path_months(panel, world.seed, 24, generator)
        stored = world.feed["attention"]
        for t in (1, 5, 12):
            assert _attention(names, cells, path[:t]) == stored[: t + 1]


def test_every_reference_bot_plays_a_panel_world_and_records_hold_no_dates():
    world, panel = _world(5, length=12)
    counts = CachedEngineCounts(panel)
    pattern = re.compile(r"\b(19|20)\d\d-\d\d(-\d\d)?\b")
    for kind in ("random", "static", "disciplined", "news"):
        record = run_bot(world, make_bot(kind, 1, 7, world.seed), counts_source=counts).record
        assert record["verdicts"]["rejected"] == {}
        assert record["percentileRequest"]["baseDate"] == BASE_DATE.isoformat()
        record = dict(record)
        record["percentileRequest"] = None
        assert not pattern.search(io.record_line(record))


def test_a_world_document_does_not_expose_panel_months_in_its_inputs():
    world, _ = _world(0)
    body = canonical(world.to_doc()["inputs"])
    assert not re.search(r"(19|20)\d\d-\d\d", body)


def test_bad_arguments_are_refused():
    with pytest.raises(SimShapeError):
        _world(generator="other")
    with pytest.raises(SimShapeError):
        _world(assets=2)


def _season_returns(world):
    return {
        a.asset: [
            measure.krw_return(
                a.currency == "KRW", s.local_returns[a.asset], s.fx_change.get(a.currency)
            )
            for s in world.inputs.path
        ]
        for a in world.inputs.pool
    }


def _agreement(world, master_seed):
    """Value share of the disciplined bot whose system position is the season-measured one."""
    run = run_bot(world, make_bot("disciplined", 0, master_seed, world.seed))
    by_turn = dict(run.units_by_turn)
    season = Season(world.inputs)
    measured = report._measure_pool(len(world.inputs.path), _season_returns(world))
    system = {a.asset: a.system_position for a in world.inputs.pool}
    same = total = Decimal(0)
    for turn in range(world.inputs.settings.path_length):
        for unit in by_turn.get(turn, ()):
            apply_unit(season, unit)
        for asset, (quantity, _) in season.holdings.items():
            if quantity:
                value = season.turn_prices[turn][asset] * quantity
                total += value
                if measured[asset] == system[asset]:
                    same += value
        season.advance()
    return same / total


def test_system_positions_follow_the_season_measurement_far_more_than_chance():
    # Measured on 20 worlds: mean 0.50 (asset count 0.59); positions drawn at random gave 0.33.
    shares = [_agreement(_world(i, length=60, assets=30, master=303)[0], 303) for i in range(20)]
    assert sum(shares) / len(shares) >= Decimal("0.42")
    world, _ = _world(0, length=60, assets=30, master=303)
    measured = report._measure_pool(60, _season_returns(world))
    agree = sum(1 for a in world.inputs.pool if measured[a.asset] == a.system_position)
    assert agree >= 15


def test_every_pool_position_is_the_measured_one_benchmarks_included():
    from antlab_engine.bots.world_panel import _asset_names, _build_panel, base_positions

    for index in range(4):
        world, _ = _world(index, length=12, assets=20)
        names = _asset_names(20)
        _, cells = _build_panel(world.seed, names)
        expected = base_positions(names, cells)
        assert {a.asset: a.system_position for a in world.inputs.pool} == expected


def test_system_positions_read_only_the_36_months_before_the_base_date():
    from antlab_engine.bots.world_panel import (
        BASE_WINDOW,
        _asset_names,
        _build_panel,
        base_positions,
        world_seed,
    )

    names = _asset_names(20)
    for index in range(4):
        _, cells = _build_panel(world_seed(1, index), names)
        window = [m for m in panel_months() if m < BASE_DATE][-BASE_WINDOW:]
        before = base_positions(names, cells)
        assert set(before.values()) - {Position.MIDFIELDER}
        changed = {a: dict(months) for a, months in cells.items()}
        for months in changed.values():
            for month in months:
                if month not in window:
                    months[month] = (Decimal("0.9"), Decimal("0.3"))
            months[BASE_DATE] = (Decimal("-0.5"), Decimal("0.2"))
            months[date(2026, 6, 1)] = (Decimal("0.7"), Decimal("0"))
        assert base_positions(names, changed) == before


def test_panel_v2_world_ids_are_pinned():
    # panel-v2: system positions are measured on the pre-base window; v1 ids must not come back.
    assert (
        _world(0)[0].world_id == "2589fa7d2fe179308f2dd4566bfbff9d0287a083d509a2ca043a4727bab392d5"
    )
    assert (
        _world(0, generator="stationary")[0].world_id
        == "4ba4e8421e25068ce3537c5a87d80f002cd7d64aa16be453fa935d18b8b70bda"
    )
