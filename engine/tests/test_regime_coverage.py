"""Coverage counts and the input digest (TA11, TA12)."""

import hashlib
import random
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

from regime_synthetic import month_fingerprint, raw, raw_panel

from antlab_engine.regime import (
    MonthStatus,
    RawObservation,
    SeriesCode,
    SourceSeries,
    adapt,
    coverage,
    input_digest,
    label_months,
)
from antlab_engine.regime.model import Market

D = date
SRC = SourceSeries
ROWS = raw_panel(8, 3)
ADAPTED = adapt(ROWS)
MONTHS = [D(2021, 6, 1), D(2022, 6, 1), D(2023, 6, 1)]


def test_the_digest_is_64_hex_digits_and_ignores_row_order():
    digest = input_digest(ADAPTED.observations)
    assert re.fullmatch(r"[0-9a-f]{64}", digest)
    rows = list(ROWS)
    random.Random(9).shuffle(rows)  # noqa: S311
    shuffled = adapt(rows)
    assert shuffled.observations == ADAPTED.observations
    assert input_digest(shuffled.observations) == digest
    assert input_digest(reversed(ADAPTED.observations)) == digest


def test_the_digest_reacts_to_a_value_a_date_a_missing_row_and_the_label_version(monkeypatch):
    base = input_digest(ADAPTED.observations)
    one = ROWS[100]
    changed = RawObservation(one.source, one.ref_date, one.value + 1, one.published_on)
    assert input_digest(adapt([changed, *ROWS[:100], *ROWS[101:]]).observations) != base
    later = RawObservation(one.source, one.ref_date, one.value, D(2030, 1, 1))
    assert input_digest(adapt([later, *ROWS[:100], *ROWS[101:]]).observations) != base
    assert input_digest(adapt(ROWS[1:]).observations) != base
    monkeypatch.setattr(sys.modules["antlab_engine.regime.coverage"], "LABEL_VERSION", "other")
    assert input_digest(ADAPTED.observations) != base


def test_equal_numbers_written_differently_give_the_same_digest():
    a = adapt([raw(SRC.US_DGS2, D(2024, 3, 8), "4.10")]).observations
    b = adapt([raw(SRC.US_DGS2, D(2024, 3, 8), "4.1")]).observations
    zero = adapt([raw(SRC.US_DGS2, D(2024, 3, 8), "0.00")]).observations
    negative_zero = adapt([raw(SRC.US_DGS2, D(2024, 3, 8), "-0")]).observations
    assert input_digest(a) == input_digest(b)
    assert input_digest(zero) == input_digest(negative_zero)
    assert input_digest(a) != input_digest(zero)


def test_the_digest_of_nothing_is_still_a_digest():
    assert re.fullmatch(r"[0-9a-f]{64}", input_digest([]))


SCRIPT = """
import hashlib, sys
sys.path.insert(0, sys.argv[1])
from datetime import date
from regime_synthetic import month_fingerprint, raw_panel
from antlab_engine.regime import adapt, input_digest, label_months
adapted = adapt(raw_panel(8, 3))
months = label_months(adapted.observations, [date(2022, 6, 1), date(2023, 6, 1)])
print(input_digest(adapted.observations))
print(hashlib.sha256(repr([month_fingerprint(m) for m in months]).encode()).hexdigest())
"""


def test_a_fresh_process_gives_the_same_digest_and_month_bits():
    # `python -I` loads the installed package, so this cannot run against a mutated copy of the
    # source; leave it out when mutating a copy.
    here = str(Path(__file__).parent)
    out = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", SCRIPT, here], capture_output=True, text=True, check=True
    ).stdout.split()
    months = label_months(ADAPTED.observations, [D(2022, 6, 1), D(2023, 6, 1)])
    mine = hashlib.sha256(repr([month_fingerprint(m) for m in months]).encode()).hexdigest()
    assert out == [input_digest(ADAPTED.observations), mine]


# --- TA12 ------------------------------------------------------------------------------------


def test_the_counts_follow_the_statuses_and_the_indicator_months():
    months = label_months(ADAPTED.observations, [D(2019, 1, 1), D(2019, 3, 1), *MONTHS])
    report = coverage(ADAPTED, months)
    assert report.evaluated == sum(m.status is MonthStatus.EVALUATED for m in months)
    assert report.carried == sum(m.status is MonthStatus.CARRIED for m in months)
    assert report.no_data == sum(m.status is MonthStatus.NO_DATA for m in months)
    assert report.evaluated + report.carried + report.no_data == len(months)
    assert report.no_data >= 2 and report.evaluated >= 3  # the panel gives both kinds
    assert list(report.indicators) == list(months[0].indicator_z)
    for key, count in report.indicators.items():
        assert count == sum(m.indicator_z[key] is not None for m in months)
    assert report.indicators["1"] == 3 and report.indicators["14"] >= 3


def test_carried_months_are_counted():
    keep = {SRC.KR_USDKRW, SRC.US_DTWEXBGS, SRC.US_VIX}
    rows = [r for r in ROWS if r.source in keep or r.ref_date <= D(2022, 6, 30)]
    adapted = adapt(rows)
    months = label_months(adapted.observations, [D(2022, 6, 1), D(2023, 3, 1), D(2023, 4, 1)])
    report = coverage(adapted, months)
    assert (report.evaluated, report.carried, report.no_data) == (1, 2, 0)


def test_rule_dated_rows_count_the_rows_without_a_publication_date_per_series():
    rows = [
        raw(SRC.US_DGS2, D(2024, 3, 6), "4.1"),
        raw(SRC.US_DGS2, D(2024, 3, 7), "4.1"),
        raw(SRC.US_DGS2, D(2024, 3, 8), "4.1", D(2024, 3, 12)),
        raw(SRC.KR_CPI, D(2024, 1, 1), "100", D(2024, 2, 7)),
        raw(SRC.KR_CPI, D(2024, 2, 1), "100"),
        raw(SRC.US_VIX, D(2024, 3, 8), "18"),
    ]
    adapted = adapt(rows)
    counts = adapted.rule_dated_rows
    assert counts[SeriesCode.US_DGS2] == 2
    assert counts[SeriesCode.KR_CPI] == 1
    assert counts[SeriesCode.US_VIX] == 1
    assert sum(counts.values()) == 4
    assert set(counts) == set(SeriesCode)


def test_the_link_flag_and_the_rule_dated_counts_reach_the_report():
    old = [raw(SRC.FRED_DTWEXM, D(2005, 1, 3), "80"), raw(SRC.FRED_DTWEXM, D(2004, 12, 31), "79")]
    new = [raw(SRC.US_DTWEXBGS, D(2005, 1, 3), "100", D(2005, 1, 10))]
    linked = adapt(old + new)
    assert linked.dtwex_linked
    report = coverage(linked, [])
    assert report.dtwex_linked and report.rule_dated_rows[SeriesCode.US_DTWEXBGS] == 1
    assert (report.evaluated, report.carried, report.no_data) == (0, 0, 0)
    assert not coverage(adapt(old), []).dtwex_linked


def test_a_report_has_no_dates_or_values_in_its_repr():
    text = repr(coverage(ADAPTED, label_months(ADAPTED.observations, MONTHS)))
    assert not re.search(r"\d{4}-\d{2}", text)


def test_the_digest_covers_the_calendars_and_empty_calendars_change_nothing():
    plain = input_digest(ADAPTED.observations)
    assert input_digest(ADAPTED.observations, {}) == plain
    assert input_digest(ADAPTED.observations, {Market.US: frozenset()}) == plain
    us = input_digest(ADAPTED.observations, {Market.US: frozenset({D(2021, 5, 31)})})
    kr = input_digest(ADAPTED.observations, {Market.KR: frozenset({D(2021, 5, 31)})})
    more = input_digest(
        ADAPTED.observations, {Market.US: frozenset({D(2021, 5, 31), D(2021, 7, 5)})}
    )
    assert len({plain, us, kr, more}) == 4
    reordered = {Market.US: frozenset({D(2021, 7, 5), D(2021, 5, 31)})}
    assert input_digest(ADAPTED.observations, reordered) == more
