"""`panel-v1` worlds: determinism, path cells equal panel cells, generator honoured, no dates."""

import re
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
from antlab_engine.sim.model import SimShapeError
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
