"""End-to-end look-ahead and determinism (TB10, TB11, TB18) and snapshot equivalence."""

import hashlib
import random
import subprocess
import sys
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

import pytest
from regime_synthetic import fingerprint, obs, panel

from antlab_engine.regime import (
    Observation,
    PointInTime,
    SeriesCode,
    effective_available,
    evaluate,
    weekly_schedule,
)
from antlab_engine.regime.model import SERIES_SPECS

S = SeriesCode
T = date(2022, 3, 5)  # a Saturday
DATES = weekly_schedule(date(2021, 1, 2), date(2023, 6, 3))
N = sum(1 for d in DATES if d <= T)
BASE = panel(9, 11)
BASE_PRINTS = [fingerprint(s) for s in evaluate(BASE, DATES)]


def late(o: Observation) -> bool:
    return effective_available(o) > T


def run(items) -> list:
    return [fingerprint(s) for s in evaluate(items, DATES)]


def rewritten(o: Observation) -> Observation:
    value = o.value * Decimal(7) + Decimal(13)
    return Observation(o.series, o.ref_date, value, o.available_on)


def test_the_base_panel_has_data_on_both_sides_of_t():
    assert any(late(o) for o in BASE) and any(not late(o) for o in BASE)
    assert any(p[13] is not None for p in BASE_PRINTS[:N])  # a probability exists before T


def test_deleting_everything_available_after_t_changes_nothing_up_to_t():
    assert run([o for o in BASE if not late(o)])[:N] == BASE_PRINTS[:N]


def test_rewriting_everything_available_after_t_changes_nothing_up_to_t():
    items = [rewritten(o) if late(o) else o for o in BASE]
    assert run(items)[:N] == BASE_PRINTS[:N]


def test_new_vintages_of_old_dates_and_late_first_releases_change_nothing_up_to_t():
    extra = []
    for o in BASE:
        revisable = SERIES_SPECS[o.series].revisable
        if revisable and o.ref_date < T - timedelta(days=200) and o.ref_date.day % 3 == 0:
            extra.append(Observation(o.series, o.ref_date, o.value * 2, T + timedelta(days=40)))
    saturday = date(2021, 6, 5)  # a day the daily series do not have
    extra += [obs(S.US_VIX, saturday, 99.0, T + timedelta(days=30))]
    extra += [obs(S.US_DGS2, saturday, 9.0, T + timedelta(days=30))]
    extra += [obs(S.KR_USDKRW, saturday, 2500.0, T + timedelta(days=30))]
    kr_old = [o for o in BASE if o.series in (S.KR_CLI, S.KR_CPI) and o.ref_date < date(2021, 6, 1)]
    extra += [
        Observation(o.series, o.ref_date, o.value + 5, T + timedelta(days=90)) for o in kr_old[:5]
    ]
    assert extra
    assert run(BASE + extra)[:N] == BASE_PRINTS[:N]


def test_the_comparison_can_see_a_change_that_is_visible_before_t():
    # positive control: one value available before T moves some output before T
    victim = max(
        (o for o in BASE if o.series is S.US_VIX and not late(o)), key=lambda o: o.ref_date
    )
    items = [
        Observation(o.series, o.ref_date, o.value * 3, o.available_on) if o is victim else o
        for o in BASE
    ]
    changed = run(items)[:N]
    assert changed != BASE_PRINTS[:N]
    assert changed[: N - 8] == BASE_PRINTS[: N - 8]  # earlier steps never saw it


def test_a_revision_published_after_t_is_ignored_even_when_it_is_the_only_change():
    victim = next(o for o in BASE if o.series is S.US_CPILFESL and o.ref_date > date(2021, 1, 1))
    newer = Observation(
        o_series := victim.series, victim.ref_date, victim.value * 5, T + timedelta(days=3)
    )
    assert o_series is S.US_CPILFESL
    assert run(BASE + [newer])[:N] == BASE_PRINTS[:N]
    assert run(BASE + [newer]) != BASE_PRINTS  # visible to the steps after it


def reference_snapshot(items, series, as_of):
    """The straightforward rule: latest effective vintage per reference date, known at as_of."""
    best = {}
    for o in items:
        if o.series is not series:
            continue
        available = effective_available(o)
        if available > as_of:
            continue
        key = o.ref_date
        if key not in best or available >= best[key][0]:
            best[key] = (available, float(o.value))
    first = {}
    for o in items:
        if o.series is series:
            available = effective_available(o)
            if available <= as_of:
                first[o.ref_date] = min(first.get(o.ref_date, available), available)
    dates = sorted(best)
    last = first[dates[-1]] if dates else None
    return dates, [best[d][1] for d in dates], last


def test_the_vectorised_snapshot_equals_the_plain_rule_with_vintages_and_back_fills():
    rng = random.Random(5)  # noqa: S311
    items = list(BASE[::40])
    for o in rng.sample(items, 60):
        if SERIES_SPECS[o.series].revisable:
            delay = timedelta(days=rng.randrange(1, 90))
            items.append(Observation(o.series, o.ref_date, o.value + 1, o.available_on + delay))
    items = [  # back-fills: a fifth of the rows arrive up to 40 days late
        Observation(
            o.series, o.ref_date, o.value, o.available_on + timedelta(days=rng.randrange(40))
        )
        if o.ref_date.day % 5 == 0
        else o
        for o in items
    ]
    pit = PointInTime(items)
    for as_of in (date(2019, 1, 1), date(2021, 2, 6), T, date(2023, 6, 3), date(2100, 1, 1)):
        for series in S:
            view = pit.snapshot(series, as_of)
            dates, values, last = reference_snapshot(items, series, as_of)
            assert list(view.ref_dates) == dates, (series, as_of)
            assert view.values.tolist() == values
            assert view.ordinals.tolist() == [d.toordinal() for d in dates]
            assert view.last_available_on == last


def test_korean_series_keep_every_collected_vintage():
    first = Observation(S.KR_CLI, date(2021, 1, 1), Decimal("100.5"), date(2021, 3, 1))
    second = Observation(S.KR_CLI, date(2021, 1, 1), Decimal("101.5"), date(2021, 5, 1))
    pit = PointInTime([first, second])
    assert pit.snapshot(S.KR_CLI, date(2021, 4, 1)).values.tolist() == [100.5]
    assert pit.snapshot(S.KR_CLI, date(2021, 5, 1)).values.tolist() == [101.5]
    assert pit.snapshot(S.KR_CLI, date(2021, 2, 28)).values.tolist() == []
    again = Observation(S.KR_CLI, date(2021, 1, 1), Decimal("102"), date(2021, 5, 1))
    with pytest.raises(Exception, match="duplicate"):
        PointInTime([second, again])
    for code in (S.KR_CPI, S.KR_EXPORTS):
        a = Observation(code, date(2021, 1, 1), Decimal("1"), date(2021, 3, 1))
        b = Observation(code, date(2021, 1, 1), Decimal("2"), date(2021, 4, 1))
        assert PointInTime([a, b]).snapshot(code, date(2021, 4, 1)).values.tolist() == [2.0]


SCRIPT = """
import hashlib, sys
sys.path.insert(0, sys.argv[1])
from datetime import date
from regime_synthetic import fingerprint, panel
from antlab_engine.regime import evaluate, weekly_schedule
snaps = evaluate(panel(9, 11), weekly_schedule(date(2021, 1, 2), date(2022, 3, 5)))
print(hashlib.sha256(repr([fingerprint(s) for s in snaps]).encode()).hexdigest())
"""


def test_a_fresh_process_gives_the_same_bits():
    # `python -I` ignores PYTHONPATH and loads the installed package, so this test cannot
    # run against a mutated copy of the source; leave it out when mutating a copy.
    here = str(Path(__file__).parent)
    first = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", SCRIPT, here], capture_output=True, text=True, check=True
    )
    second = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", SCRIPT, here], capture_output=True, text=True, check=True
    )
    mine = hashlib.sha256(repr(BASE_PRINTS[:N]).encode()).hexdigest()
    assert first.stdout == second.stdout
    assert first.stdout.strip() == mine
