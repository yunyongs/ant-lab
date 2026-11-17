"""Month labels (TA8, TA9, TA10): sampling, subset invariance, carried months, look-ahead."""

import random
import time
from datetime import date, datetime, timedelta
from decimal import Decimal

import pytest
from regime_synthetic import month_fingerprint, raw_panel

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import (
    SCHEDULE_START,
    MonthRegime,
    MonthStatus,
    RawObservation,
    SourceSeries,
    evaluate,
    label_months,
    publication_floor,
    to_observations,
    weekly_schedule,
)
from antlab_engine.regime.adapter import SOURCE_TO_CODE
from antlab_engine.regime.model import Axis, Direction, Phase, Pool
from antlab_engine.regime.monthly import ALL_INDICATORS, INDICATOR_KEYS

D = date
SRC = SourceSeries
ROWS = raw_panel(9, 11)
OBS = to_observations(ROWS)
MONTHS = [D(2022, m, 1) for m in range(1, 13)]
FULL = label_months(OBS, MONTHS)
LAST_DAY = {
    m: (m.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1) for m in MONTHS
}


def last_saturday(month: date) -> date:
    last = LAST_DAY[month]
    return last - timedelta(days=(last.weekday() - 5) % 7)


# --- TA8 -----------------------------------------------------------------------------------


def test_the_schedule_starts_on_a_saturday_in_1994():
    assert D(1994, 1, 1) == SCHEDULE_START and SCHEDULE_START.weekday() == 5


def test_each_month_takes_the_last_saturday_step_inside_it():
    steps = weekly_schedule(SCHEDULE_START, D(2022, 12, 31))
    by_day = {s.as_of: s for s in evaluate(OBS, steps)}
    for month, result in zip(MONTHS, FULL, strict=True):
        snap = by_day[last_saturday(month)]
        assert result.status is MonthStatus.EVALUATED
        assert result.p == snap.p and result.label == snap.label and result.phase == snap.phase
        assert dict(result.indicator_z) == {k: snap.indicator_z[k] for k in INDICATOR_KEYS}
        for axis in Axis:
            assert result.axes[axis] == snap.display[axis]
        assert (result.stale, result.unavailable) == (snap.stale, snap.unavailable)
    saturday_end = [m for m in MONTHS if LAST_DAY[m].weekday() == 5]
    assert saturday_end  # April 2022 ends on a Saturday: that step belongs to April


def test_the_result_has_16_indicator_keys_and_three_state_axes():
    assert len(INDICATOR_KEYS) == 16 and set(ALL_INDICATORS) <= set(INDICATOR_KEYS)
    for result in FULL:
        assert list(result.indicator_z) == list(INDICATOR_KEYS)
        assert set(result.axes) == set(Axis)
        for display in result.axes.values():
            assert display is None or display.direction in set(Direction)
        assert result.p is not None and abs(sum(result.p) - 1.0) < 1e-12
        assert result.label in set(Pool) and result.phase in set(Phase)


@pytest.mark.parametrize("index", [0, 3, 7, 11])
def test_a_month_alone_equals_the_same_month_in_the_full_list(index):
    alone = label_months(OBS, [MONTHS[index]])
    assert [month_fingerprint(m) for m in alone] == [month_fingerprint(FULL[index])]


def test_a_subset_and_any_input_row_order_give_the_same_months():
    subset = [MONTHS[2], MONTHS[5], MONTHS[9]]
    picked = label_months(OBS, subset)
    assert [month_fingerprint(m) for m in picked] == [month_fingerprint(FULL[i]) for i in (2, 5, 9)]
    rows = list(ROWS)
    random.Random(5).shuffle(rows)  # noqa: S311
    shuffled = label_months(to_observations(rows), MONTHS)
    assert [month_fingerprint(m) for m in shuffled] == [month_fingerprint(m) for m in FULL]


def test_a_month_before_the_schedule_or_the_data_is_no_data():
    early = label_months(OBS, [D(1993, 6, 1), D(1994, 1, 1), D(2010, 5, 1)])
    assert [m.status for m in early] == [MonthStatus.NO_DATA] * 3
    for m in early:
        assert m.p is None and m.label is None and m.phase is None
        assert all(v is None for v in m.indicator_z.values())
    before_schedule = early[0]
    assert not before_schedule.quality_ok and before_schedule.unavailable == ALL_INDICATORS
    assert before_schedule.stale == () and set(before_schedule.axes.values()) == {None}


def test_a_month_without_data_keeps_no_fake_rate_or_growth_values():
    month = label_months(OBS, [D(2010, 5, 1)])[0]
    assert all(display is None for display in month.axes.values())


@pytest.mark.parametrize(
    "months",
    [
        [MONTHS[3], MONTHS[1]],  # unsorted
        [MONTHS[1], MONTHS[1]],  # duplicate
        [D(2022, 3, 2)],  # not the first of the month
        [datetime(2022, 3, 1)],  # a datetime
        ["2022-03-01"],  # text
        [D(1899, 12, 1)],  # outside the supported years
        [D(2101, 1, 1)],
    ],
)
def test_bad_month_lists_are_rejected(months):
    with pytest.raises(InvalidArgument) as error:
        label_months(OBS, months)
    assert "2022" not in str(error.value)


def test_an_empty_month_list_gives_an_empty_result():
    assert label_months(OBS, []) == ()


def test_a_month_result_is_internally_consistent():
    good = FULL[0]
    kwargs = {
        "month": good.month,
        "status": MonthStatus.EVALUATED,
        "axes": good.axes,
        "p": good.p,
        "label": good.label,
        "phase": good.phase,
        "indicator_z": good.indicator_z,
        "quality_ok": True,
        "stale": (),
        "unavailable": (),
    }
    assert MonthRegime(**kwargs)
    with pytest.raises(InvalidArgument):
        MonthRegime(**{**kwargs, "label": None})
    with pytest.raises(InvalidArgument):
        MonthRegime(**{**kwargs, "status": MonthStatus.CARRIED})
    assert "2022" not in repr(good)


def test_a_month_result_can_be_hashed_and_put_in_a_set():
    assert len({*FULL}) == len(FULL)


# --- TA9: carried months ---------------------------------------------------------------------

FX_AND_SENTIMENT = {SRC.KR_USDKRW, SRC.US_DTWEXBGS, SRC.US_VIX}
KOREAN = {
    SRC.KR_KTB3Y,
    SRC.KR_BASE_RATE,
    SRC.KR_CPI,
    SRC.KR_CLI,
    SRC.KR_EXPORTS,
    SRC.KR_CORP_AA,
}
CUTOFF = D(2022, 6, 30)


def stopped_after(sources_to_stop) -> list:
    return [r for r in ROWS if r.source not in sources_to_stop or r.ref_date <= CUTOFF]


def test_when_rate_and_growth_inputs_stop_later_months_are_carried_not_evaluated():
    stop = set(SRC) - FX_AND_SENTIMENT - {SRC.FRED_DTWEXM}
    months = [D(2022, 5, 1), D(2022, 6, 1), D(2023, 1, 1), D(2023, 6, 1)]
    result = label_months(to_observations(stopped_after(stop)), months)
    assert [m.status for m in result] == [
        MonthStatus.EVALUATED,
        MonthStatus.EVALUATED,
        MonthStatus.CARRIED,
        MonthStatus.CARRIED,
    ]
    for carried in result[2:]:
        assert carried.p is None and carried.label is None and carried.phase is None
        assert carried.axes[Axis.RATE] is None and carried.axes[Axis.GROWTH] is None
        assert carried.axes[Axis.FX] is not None and carried.axes[Axis.SENTIMENT] is not None
        assert carried.indicator_z["13"] is not None and carried.indicator_z["1"] is None
        assert not carried.quality_ok
    assert result[1].p is not None


def test_a_gap_in_one_region_keeps_the_month_evaluated():
    result = label_months(to_observations(stopped_after(KOREAN)), [D(2023, 1, 1), D(2023, 6, 1)])
    assert [m.status for m in result] == [MonthStatus.EVALUATED] * 2
    assert all(m.p is not None and m.label is not None for m in result)
    assert result[0].indicator_z["3"] is None and result[0].indicator_z["1"] is not None


GROWTH_ONLY_SOURCES = {
    SRC.US_ICSA,
    SRC.US_SAHM,
    SRC.US_T10Y3M,
    SRC.KR_CLI,
    SRC.KR_EXPORTS,
    SRC.US_BAA10Y,
    SRC.KR_CORP_AA,
}


def test_a_month_with_rate_data_but_no_growth_data_is_no_data():
    rows = [r for r in ROWS if r.source not in GROWTH_ONLY_SOURCES]
    (month,) = label_months(to_observations(rows), [D(2022, 6, 1)])
    assert month.status is MonthStatus.NO_DATA
    assert month.p is None and month.axes[Axis.RATE] is None and month.axes[Axis.GROWTH] is None
    assert month.indicator_z["1"] is not None  # the rate values are still reported


# --- TA10: no look-ahead through the adapter -----------------------------------------------------

LAST_MONTH = D(2022, 2, 1)
CUTOFF_DAY = D(2022, 2, 28)
LOOK_MONTHS = [D(2021, m, 1) for m in range(8, 13)] + [D(2022, m, 1) for m in range(1, 6)]
UP_TO = LOOK_MONTHS.index(LAST_MONTH) + 1


def late(row: RawObservation) -> bool:
    return publication_floor(SOURCE_TO_CODE[row.source], row.ref_date) > CUTOFF_DAY


def prints(rows) -> list:
    return [month_fingerprint(m) for m in label_months(to_observations(rows), LOOK_MONTHS)]


LOOK_BASE = prints(ROWS)


def test_nothing_published_after_the_month_changes_that_month_or_earlier_ones():
    late_rows = [r for r in ROWS if late(r)]
    assert late_rows and len(late_rows) < len(ROWS)
    deleted = prints([r for r in ROWS if not late(r)])
    rewritten = prints(
        [
            RawObservation(r.source, r.ref_date, r.value * 7 + 13, r.published_on) if late(r) else r
            for r in ROWS
        ]
    )
    for variant in (deleted, rewritten):
        assert variant[:UP_TO] == LOOK_BASE[:UP_TO]
        assert variant[UP_TO:] != LOOK_BASE[UP_TO:]  # the later months do see the change


def test_a_vintage_published_after_the_month_changes_nothing_up_to_it():
    extra = [
        RawObservation(r.source, r.ref_date, r.value * 3, D(2022, 3, 20))
        for r in ROWS
        if r.source in (SRC.KR_CPI, SRC.US_CPILFESL) and r.ref_date <= D(2022, 1, 1)
    ]
    assert extra
    changed = prints([*ROWS, *extra])
    assert changed[:UP_TO] == LOOK_BASE[:UP_TO]
    assert changed[UP_TO:] != LOOK_BASE[UP_TO:]


def test_a_value_available_before_the_last_step_of_the_month_does_change_it():
    # the step of February 2022 is the Saturday the 26th; Thursday the 24th is visible to it
    rows = [
        RawObservation(r.source, r.ref_date, r.value * 3, r.published_on)
        if r.source is SRC.US_VIX and r.ref_date == D(2022, 2, 24)
        else r
        for r in ROWS
    ]
    changed = prints(rows)
    assert changed[UP_TO - 1] != LOOK_BASE[UP_TO - 1]
    assert changed[: UP_TO - 1] == LOOK_BASE[: UP_TO - 1]


# --- TA10 with the legacy dollar index in the panel (audit C1, C2, C5) ---------------------------

OTHER_ROWS = [r for r in ROWS if r.source is not SRC.US_DTWEXBGS]
DOLLAR = [r for r in ROWS if r.source is SRC.US_DTWEXBGS]


def old_index(last: date) -> list[RawObservation]:
    return [
        RawObservation(SRC.FRED_DTWEXM, r.ref_date, r.value * Decimal("0.8"), None)
        for r in DOLLAR
        if r.ref_date <= last
    ]


def usable_by(row: RawObservation, cutoff: date) -> bool:
    floor = publication_floor(SOURCE_TO_CODE[row.source], row.ref_date)
    return max(row.published_on or floor, floor) <= cutoff


DOLLAR_MONTHS = [D(2020, m, 1) for m in (3, 6, 9, 12)] + [D(2021, m, 1) for m in range(1, 9)]


def dollar_prints(rows) -> list:
    return [month_fingerprint(m) for m in label_months(to_observations(rows), DOLLAR_MONTHS)]


def test_an_old_index_that_the_new_one_joins_later_changes_nothing_before_the_join():
    new = [r for r in DOLLAR if r.ref_date >= D(2021, 6, 1)]
    rows = [*OTHER_ROWS, *old_index(D(2021, 12, 31)), *new]
    cutoff, upto = D(2021, 5, 31), DOLLAR_MONTHS.index(D(2021, 5, 1)) + 1
    full = dollar_prints(rows)
    cut = dollar_prints([r for r in rows if usable_by(r, cutoff)])
    assert full[:upto] == cut[:upto]
    month = label_months(to_observations(rows), [D(2021, 3, 1)])[0]
    assert month.indicator_z["11"] is not None  # the old index carries the dollar signal alone


def test_a_new_index_published_late_leaves_the_dollar_signal_in_place():
    late = D(2022, 6, 1)
    new = [
        RawObservation(r.source, r.ref_date, r.value, late)
        for r in DOLLAR
        if D(2019, 1, 2) <= r.ref_date <= D(2022, 5, 31)
    ]
    rows = [*OTHER_ROWS, *old_index(D(2022, 12, 31)), *new]
    months = [D(2020, 6, 1), D(2021, 6, 1)]
    both = label_months(to_observations(rows), months)
    alone = label_months(to_observations([*OTHER_ROWS, *old_index(D(2022, 12, 31))]), months)
    for linked, plain in zip(both, alone, strict=True):
        assert linked.indicator_z["11"] is not None and "11" not in linked.unavailable
        assert month_fingerprint(linked) == month_fingerprint(plain)


# --- timing (slow) -------------------------------------------------------------------------------

BUDGET_SECONDS = 6.0


@pytest.mark.slow
def test_29_years_through_the_adapter_and_month_labels_stay_inside_the_budget():
    rows = raw_panel(29, 2)
    started = time.perf_counter()
    items = to_observations(rows)
    adapt_seconds = time.perf_counter() - started
    months = [D(y, m, 1) for y in range(1997, 2026) for m in range(1, 13) if (y, m) <= (2025, 8)]
    started = time.perf_counter()
    result = label_months(items, months)
    label_seconds = time.perf_counter() - started
    print(  # noqa: T201
        f"\nregime adapter: {len(rows)} rows {adapt_seconds:.2f} s; "
        f"label_months: {len(months)} months, {label_seconds:.2f} s"
    )
    assert len(result) == len(months)
    assert result[-1].status is MonthStatus.EVALUATED
    assert label_seconds < BUDGET_SECONDS
