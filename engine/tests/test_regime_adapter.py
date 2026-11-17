"""Adapter (TA2–TA7): publication dating, rejection of bad rows, Korean vintages, dollar link."""

import math
import re
from collections import Counter
from datetime import date, datetime, timedelta
from decimal import Decimal

import pytest
from regime_synthetic import fingerprint, raw, raw_panel, weekdays

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import (
    Observation,
    PointInTime,
    RawObservation,
    SeriesCode,
    SourceSeries,
    adapt,
    evaluate,
    publication_floor,
    to_observations,
    weekly_schedule,
)
from antlab_engine.regime.adapter import SOURCE_TO_CODE
from antlab_engine.regime.model import Market

D = date
SRC = SourceSeries
S = SeriesCode
MARKER = "987654.321"  # a value that must never show up in a message


def visible(items, code, day) -> list[float]:
    return PointInTime(items).snapshot(code, day).values.tolist()


# --- TA6 -----------------------------------------------------------------------------------


def test_the_source_mapping_covers_every_series_code_once_and_links_the_old_index():
    counts = Counter(SOURCE_TO_CODE.values())
    assert set(counts) == set(SeriesCode)
    assert {code for code, n in counts.items() if n == 2} == {S.US_DTWEXBGS}
    assert set(SOURCE_TO_CODE) == set(SourceSeries)
    assert len(SourceSeries) == 16
    assert SOURCE_TO_CODE[SRC.FRED_DTWEXM] is S.US_DTWEXBGS
    for source, code in SOURCE_TO_CODE.items():
        if source is not SRC.FRED_DTWEXM:
            assert source.value == code.value


# --- TA2 (C6): the eight US series are never usable on their reference date ----------------

US_EIGHT = [
    (SRC.US_DGS2, D(2024, 3, 8)),
    (SRC.US_T10Y3M, D(2024, 3, 8)),
    (SRC.US_BAA10Y, D(2024, 3, 8)),
    (SRC.US_VIX, D(2024, 3, 8)),
    (SRC.US_DTWEXBGS, D(2024, 3, 8)),
    (SRC.US_ICSA, D(2024, 3, 9)),
    (SRC.US_CPILFESL, D(2024, 2, 1)),
    (SRC.US_SAHM, D(2024, 2, 1)),
]


@pytest.mark.parametrize(("source", "ref"), US_EIGHT)
def test_a_us_row_is_never_usable_on_its_reference_date(source, ref):
    floor = publication_floor(SOURCE_TO_CODE[source], ref)
    assert floor > ref
    for published in (None, ref, floor - timedelta(days=1)):
        (item,) = to_observations([raw(source, ref, "1.5", published)])
        assert item.available_on > ref
        assert item.available_on == floor


@pytest.mark.parametrize(("source", "ref"), US_EIGHT)
def test_a_later_publication_date_wins_over_the_floor(source, ref):
    floor = publication_floor(SOURCE_TO_CODE[source], ref)
    later = floor + timedelta(days=9)
    (item,) = to_observations([raw(source, ref, "1.5", later)])
    assert item.available_on == later
    (on_the_floor,) = to_observations([raw(source, ref, "1.5", floor)])
    assert on_the_floor.available_on == floor


def test_a_us_calendar_moves_the_adapted_date():
    friday = D(2024, 3, 8)
    row = raw(SRC.US_DGS2, friday, "4.1")
    (plain,) = to_observations([row])
    assert plain.available_on == D(2024, 3, 11)
    (shifted,) = to_observations([row], {Market.US: frozenset({D(2024, 3, 11)})})
    assert shifted.available_on == D(2024, 3, 12)


# --- TA3: the Saturday step is blind to Friday ---------------------------------------------

STEP = D(2022, 3, 5)  # a Saturday
FRIDAY = D(2022, 3, 4)
THURSDAY = D(2022, 3, 3)
STEPS = weekly_schedule(D(2021, 1, 2), D(2022, 4, 2))
INDEX = STEPS.index(STEP)
BASE_ROWS = raw_panel(9, 11)
US_DAILY_SOURCES = {SRC.US_DGS2, SRC.US_T10Y3M, SRC.US_BAA10Y, SRC.US_VIX}


def bumped(row: RawObservation) -> RawObservation:
    return RawObservation(row.source, row.ref_date, row.value * 2 + 1, row.published_on)


def run(rows) -> list:
    return [fingerprint(s) for s in evaluate(to_observations(rows), STEPS)]


def friday_data(row: RawObservation) -> bool:
    if row.source in US_DAILY_SOURCES:
        return row.ref_date == FRIDAY
    if row.source is SRC.US_ICSA:
        return row.ref_date == STEP  # the week ending on the step
    if row.source is SRC.US_DTWEXBGS:
        return D(2022, 2, 28) <= row.ref_date <= FRIDAY
    return False


BASE_RUN = run(BASE_ROWS)


def test_the_saturday_panel_has_the_rows_the_test_changes():
    assert STEP.weekday() == 5 and FRIDAY.weekday() == 4 and THURSDAY.weekday() == 3
    hit = {row.source for row in BASE_ROWS if friday_data(row)}
    assert hit == {*US_DAILY_SOURCES, SRC.US_ICSA, SRC.US_DTWEXBGS}
    assert sum(1 for row in BASE_ROWS if friday_data(row)) == 4 + 1 + 5


def test_changing_friday_us_values_and_the_claims_of_the_week_leaves_the_step_unchanged():
    changed = run([bumped(r) if friday_data(r) else r for r in BASE_ROWS])
    assert changed[: INDEX + 1] == BASE_RUN[: INDEX + 1]
    assert changed[INDEX + 1] != BASE_RUN[INDEX + 1]  # the next step does see them


def test_each_kind_of_late_value_alone_leaves_the_step_unchanged_and_moves_the_next():
    for kind in (*US_DAILY_SOURCES, SRC.US_ICSA, SRC.US_DTWEXBGS):
        rows = [bumped(r) if friday_data(r) and r.source is kind else r for r in BASE_ROWS]
        changed = run(rows)
        assert changed[: INDEX + 1] == BASE_RUN[: INDEX + 1], kind
        assert changed[INDEX + 1] != BASE_RUN[INDEX + 1], kind


def test_a_thursday_value_does_change_the_step():
    for kind in US_DAILY_SOURCES:
        rows = [bumped(r) if r.source is kind and r.ref_date == THURSDAY else r for r in BASE_ROWS]
        assert run(rows)[INDEX] != BASE_RUN[INDEX], kind


def test_korean_values_of_the_friday_are_visible_to_the_step():
    for kind in (SRC.KR_KTB3Y, SRC.KR_USDKRW):
        rows = [bumped(r) if r.source is kind and r.ref_date == FRIDAY else r for r in BASE_ROWS]
        assert run(rows)[INDEX] != BASE_RUN[INDEX], kind


# --- TA4: rejected rows --------------------------------------------------------------------


def bad_rows() -> dict:
    ref = D(2024, 3, 8)
    return {
        "published before reference": lambda: to_observations(
            [raw(SRC.US_DGS2, ref, MARKER, D(2024, 3, 7))]
        ),
        "float value": lambda: RawObservation(SRC.US_DGS2, ref, 1.5),  # type: ignore[arg-type]
        "nan": lambda: RawObservation(SRC.US_DGS2, ref, Decimal("NaN")),
        "infinity": lambda: RawObservation(SRC.US_DGS2, ref, Decimal("Infinity")),
        "does not fit float64": lambda: to_observations([raw(SRC.US_DGS2, ref, "1e400")]),
        "monthly not day 1": lambda: to_observations(
            [raw(SRC.US_CPILFESL, D(2024, 2, 15), MARKER)]
        ),
        "claims not on a Saturday": lambda: to_observations([raw(SRC.US_ICSA, ref, MARKER)]),
        "duplicate daily row": lambda: to_observations(
            [raw(SRC.US_DGS2, ref, MARKER), raw(SRC.US_DGS2, ref, "1")]
        ),
        "duplicate non-revisable monthly": lambda: to_observations(
            [
                raw(SRC.US_SAHM, D(2024, 2, 1), MARKER, D(2024, 3, 20)),
                raw(SRC.US_SAHM, D(2024, 2, 1), "1", D(2024, 3, 25)),
            ]
        ),
        "same vintage twice": lambda: to_observations(
            [
                raw(SRC.US_CPILFESL, D(2024, 2, 1), MARKER, D(2024, 3, 20)),
                raw(SRC.US_CPILFESL, D(2024, 2, 1), "1", D(2024, 3, 20)),
            ]
        ),
        "back-fill and a row dated on the floor": lambda: to_observations(
            [
                raw(SRC.KR_CPI, D(2024, 1, 1), MARKER),
                raw(SRC.KR_CPI, D(2024, 1, 1), "1", D(2024, 2, 7)),
            ]
        ),
        "unknown source": lambda: RawObservation("NOPE", ref, Decimal(MARKER)),  # type: ignore[arg-type]
        "source as plain text": lambda: RawObservation("US_DGS2", ref, Decimal(MARKER)),  # type: ignore[arg-type]
        "not a raw row": lambda: to_observations(["US_DGS2"]),  # type: ignore[list-item]
        "datetime reference": lambda: RawObservation(
            SRC.US_DGS2, datetime(2024, 3, 8), Decimal(MARKER)
        ),
        "datetime publication": lambda: RawObservation(
            SRC.US_DGS2, ref, Decimal(MARKER), datetime(2024, 3, 11)
        ),
        "zero index at the link date": lambda: to_observations(
            [raw(SRC.FRED_DTWEXM, D(2024, 3, 8), MARKER), raw(SRC.US_DTWEXBGS, D(2024, 3, 8), "0")]
        ),
    }


@pytest.mark.parametrize("case", sorted(bad_rows()))
def test_bad_rows_are_rejected_without_echoing_dates_or_values(case):
    with pytest.raises(InvalidArgument) as error:
        bad_rows()[case]()
    text = str(error.value)
    assert text
    assert not re.search(r"\d{4}-\d{2}", text)
    assert "987654" not in text and "2024" not in text


def test_a_raw_row_repr_hides_dates_and_values():
    text = repr(raw(SRC.US_DGS2, D(2024, 3, 8), MARKER, D(2024, 3, 11)))
    assert "2024" not in text and "987654" not in text


def test_a_float_value_that_does_not_fit_is_not_accepted_even_if_finite_as_decimal():
    assert not math.isfinite(float(Decimal("1e400")))
    with pytest.raises(InvalidArgument):
        to_observations([raw(SRC.US_DGS2, D(2024, 3, 8), "1e400")])


# --- TA5: Korean monthly vintages -----------------------------------------------------------


@pytest.mark.parametrize(
    ("source", "code", "first_day"),
    [
        (SRC.KR_CPI, S.KR_CPI, D(2024, 2, 7)),  # month end + 7
        (SRC.KR_EXPORTS, S.KR_EXPORTS, D(2024, 2, 15)),  # + 15
        (SRC.KR_CLI, S.KR_CLI, D(2024, 3, 6)),  # + 35
    ],
)
def test_a_korean_back_fill_is_held_back_by_its_lag(source, code, first_day):
    items = to_observations([raw(source, D(2024, 1, 1), "101.5")])
    assert visible(items, code, first_day - timedelta(days=1)) == []
    assert visible(items, code, first_day) == [101.5]


def test_a_later_collection_of_the_same_month_is_used_only_from_its_own_date():
    items = to_observations(
        [
            raw(SRC.KR_CPI, D(2024, 1, 1), "100", D(2024, 2, 7)),
            raw(SRC.KR_CPI, D(2024, 1, 1), "101", D(2024, 3, 15)),
        ]
    )
    assert len(items) == 2
    assert visible(items, S.KR_CPI, D(2024, 3, 14)) == [100.0]
    assert visible(items, S.KR_CPI, D(2024, 3, 15)) == [101.0]


def test_two_collections_before_the_floor_keep_the_later_one_in_any_order():
    early = raw(SRC.KR_CPI, D(2024, 1, 1), "100", D(2024, 2, 2))
    late = raw(SRC.KR_CPI, D(2024, 1, 1), "101", D(2024, 2, 4))
    for rows in ([early, late], [late, early]):
        (item,) = to_observations(rows)
        assert item.value == Decimal("101") and item.available_on == D(2024, 2, 7)
        assert visible([item], S.KR_CPI, D(2024, 2, 7)) == [101.0]
    kept = to_observations([early, raw(SRC.KR_CPI, D(2024, 1, 1), "102", D(2024, 3, 15))])
    assert [(o.value, o.available_on) for o in kept] == [
        (Decimal("100"), D(2024, 2, 7)),
        (Decimal("102"), D(2024, 3, 15)),
    ]


def test_a_publication_date_after_the_floor_wins_for_korean_monthly_series():
    (item,) = to_observations([raw(SRC.KR_CPI, D(2024, 1, 1), "100", D(2024, 2, 20))])
    assert item.available_on == D(2024, 2, 20)


def test_a_korean_daily_value_of_the_friday_is_visible_on_the_saturday():
    items = to_observations([raw(SRC.KR_KTB3Y, D(2024, 3, 8), "3.25")])
    assert visible(items, S.KR_KTB3Y, D(2024, 3, 9)) == [3.25]
    assert visible(items, S.KR_KTB3Y, D(2024, 3, 7)) == []


# --- TA7: link of the legacy dollar index ---------------------------------------------------

D0 = D(2005, 1, 3)  # first date both indexes have


def level(day: date) -> float:
    return 100.0 + 5.0 * math.sin(day.toordinal() / 11.0) + 0.002 * (day.toordinal() - 730000)


def index_rows(source, start: date, end: date, scale: float, published=None) -> list:
    days = [d for d in weekdays(start, 4000) if d <= end]
    return [raw(source, d, scale * level(d), published) for d in days]


OLD = index_rows(SRC.FRED_DTWEXM, D(2003, 1, 1), D(2008, 12, 31), 0.8)
NEW = index_rows(SRC.US_DTWEXBGS, D0, D(2013, 12, 31), 1.25)


def test_the_linked_series_is_continuous_across_the_common_date():
    adapted = adapt(OLD + NEW)
    assert adapted.dtwex_linked
    days = [d for d in weekdays(D(2003, 1, 1), 5000) if d <= D(2013, 12, 31)]
    assert [o.ref_date for o in adapted.observations] == days
    assert {o.series for o in adapted.observations} == {S.US_DTWEXBGS}
    for item in adapted.observations:
        assert float(item.value) == pytest.approx(0.8 * level(item.ref_date), rel=1e-12)


def test_old_rows_on_or_after_the_common_date_are_dropped_and_new_ones_rescaled():
    adapted = adapt(OLD + NEW)
    by_day = {o.ref_date: o for o in adapted.observations}
    old_value = next(r.value for r in OLD if r.ref_date == D0)
    assert float(by_day[D0].value) == pytest.approx(float(old_value), rel=1e-12)
    before = next(r.value for r in OLD if r.ref_date == D(2004, 12, 31))
    assert by_day[D(2004, 12, 31)].value == before  # raw, not rescaled


def dwex_signal(rows, day):
    snap = evaluate(to_observations(rows), [day])[0]
    return next(s for s in snap.signals if s.term_id == "11")


def test_the_dollar_signal_far_after_the_link_equals_the_unlinked_one():
    day = D(2012, 6, 2)
    linked, plain = dwex_signal(OLD + NEW, day), dwex_signal(NEW, day)
    assert linked.status.value == "OK" and plain.status.value == "OK"
    assert linked.z is not None and plain.z is not None
    assert abs(linked.z - plain.z) < 1e-9


def changed_at_link_date(rows: list) -> list:
    return [bumped(r) if r.source is SRC.US_DTWEXBGS and r.ref_date == D0 else r for r in rows]


def test_changing_the_new_index_at_the_link_date_changes_nothing_before_the_link_is_usable():
    first_use = publication_floor(S.US_DTWEXBGS, D0)  # Monday after the reference date
    a, b = adapt(OLD + NEW).observations, adapt(OLD + changed_at_link_date(NEW)).observations
    assert [o for o in a if o.available_on < first_use] == [
        o for o in b if o.available_on < first_use
    ]
    assert [o for o in a if o.available_on > first_use] != [
        o for o in b if o.available_on > first_use
    ]
    steps = weekly_schedule(D(2004, 6, 5), first_use - timedelta(days=1))
    run_a = [fingerprint(s) for s in evaluate(a, steps)]
    run_b = [fingerprint(s) for s in evaluate(b, steps)]
    assert steps and run_a == run_b


def test_without_a_common_date_the_old_index_is_ignored():
    new = index_rows(SRC.US_DTWEXBGS, D(2006, 1, 2), D(2007, 12, 31), 1.25)
    adapted = adapt(OLD[:300] + new)
    assert not adapted.dtwex_linked
    assert [o.ref_date for o in adapted.observations] == [r.ref_date for r in new]
    assert adapted.observations[0].value == new[0].value  # no rescale


def test_the_old_index_alone_is_ignored():
    adapted = adapt(OLD)
    assert adapted.observations == () and not adapted.dtwex_linked


def test_the_old_index_is_dated_by_the_same_rule_as_the_new_one():
    adapted = adapt(OLD + NEW)
    friday = next(o for o in adapted.observations if o.ref_date == D(2004, 12, 31))
    assert friday.available_on == D(2005, 1, 3)  # the Monday that follows
    tuesday = next(o for o in adapted.observations if o.ref_date == D(2004, 12, 28))
    assert tuesday.available_on == D(2005, 1, 3)


def test_the_link_is_not_usable_before_both_rows_of_the_common_date_are():
    published_late = [
        RawObservation(r.source, r.ref_date, r.value, D(2005, 2, 1)) if r.ref_date == D0 else r
        for r in OLD
    ]
    by_day = {o.ref_date: o for o in adapt(published_late + NEW).observations}
    assert by_day[D0].available_on == D(2005, 2, 1)
    assert by_day[D(2005, 3, 1)].available_on == publication_floor(S.US_DTWEXBGS, D(2005, 3, 1))
    assert all(o.available_on >= D(2005, 2, 1) for o in by_day.values() if o.ref_date >= D0)


# --- shape of the result --------------------------------------------------------------------


def test_the_result_is_sorted_and_made_of_library_observations():
    rows = raw_panel(2, 5)
    items = to_observations(rows)
    assert len(items) == len(rows)
    assert all(isinstance(o, Observation) for o in items)
    keys = [(o.series.value, o.ref_date, o.available_on) for o in items]
    assert keys == sorted(keys)


def test_an_empty_input_gives_an_empty_result():
    adapted = adapt([])
    assert adapted.observations == ()
    assert not adapted.dtwex_linked
    assert set(adapted.rule_dated_rows) == set(SeriesCode)
    assert not any(adapted.rule_dated_rows.values())
