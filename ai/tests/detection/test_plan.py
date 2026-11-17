import re

from sample_helpers import FIXTURES, write_sample

from antlab_ai.detection import plan as plan_module
from antlab_ai.detection.plan import (
    CallPlan,
    Unit,
    build_units,
    plan_hash,
    unit_months,
    units_for_segments,
)
from antlab_ai.detection.sample import load_sample

FIXTURE_PLAN_HASH = "9b760883e40df3642bda239ae06f1cc125974744a25837a0646aac55212f7a7b"


def test_fixture_units_follow_segment_shape(sample):
    units = build_units(sample)
    assert len(units) == 10
    by_window = CallPlan(units, 1, 100).units_by_window
    assert by_window == {1: 7, 3: 3, 6: 0}


def test_real_sample_shape_gives_280_units():
    units = units_for_segments([64, 36])
    assert len(units) == 280
    assert CallPlan(units, 1, 100).units_by_window == {1: 98, 3: 94, 6: 88}


def test_units_are_ordered_by_segment_window_target():
    units = units_for_segments([5, 4])
    keys = [(u.segment, u.window, u.target) for u in units]
    assert keys == sorted(keys)
    assert units[0] == Unit(0, 1, 0)


def test_windows_and_next_month_never_cross_the_gap(sample):
    for unit in build_units(sample):
        months = unit_months(sample, unit)
        assert len(months) == unit.window + 1
        steps = [
            (b.year * 12 + b.month) - (a.year * 12 + a.month)
            for a, b in zip(months, months[1:], strict=False)
        ]
        assert steps == [1] * unit.window
        start, end = sample.segments[unit.segment]
        assert start <= sample.months.index(months[0]) and sample.months.index(months[-1]) < end


def test_no_target_has_its_next_month_in_the_other_segment(sample):
    targets = {(u.segment, u.target) for u in build_units(sample) if u.window == 1}
    assert targets == {(0, 0), (0, 1), (0, 2), (0, 3), (1, 0), (1, 1), (1, 2)}


def test_plan_hash_is_pinned_and_repeatable(sample):
    units = build_units(sample)
    assert plan_hash(units) == plan_hash(build_units(load_sample(FIXTURES)))
    assert plan_hash(units) == FIXTURE_PLAN_HASH
    assert re.fullmatch(r"[0-9a-f]{64}", plan_hash(units))


def test_plan_hash_ignores_sample_values_but_not_units_or_versions(tmp_path, sample, monkeypatch):
    units = build_units(sample)
    text = (FIXTURES / "asset_month_return.csv").read_text(encoding="utf-8")
    other = load_sample(
        write_sample(tmp_path / "s", **{"asset_month_return.csv": text.replace("0.0", "0.1")})
    )
    assert plan_hash(build_units(other)) == plan_hash(units)
    assert plan_hash(units[:-1]) != plan_hash(units)
    assert plan_hash(units[::-1]) != plan_hash(units)
    monkeypatch.setattr(plan_module, "RENDER_VERSION", "render/2")
    assert plan_hash(units) != FIXTURE_PLAN_HASH


def test_call_limit_boundary():
    plan = CallPlan(units_for_segments([5, 4]), 2, 100)
    assert plan.total_calls == 20
    assert plan.fits(20)
    assert not plan.fits(19)
    assert plan.worst_output_tokens == 2000
