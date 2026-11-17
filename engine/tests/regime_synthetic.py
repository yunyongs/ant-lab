"""Synthetic observations for the regime tests. Every date and value is made up."""

from collections.abc import Callable, Iterable
from datetime import date, timedelta
from decimal import Decimal

import numpy as np

from antlab_engine.regime import Observation, PointInTime, SeriesCode, SeriesView
from antlab_engine.regime.pit import add_months

FAR = date(2100, 1, 1)


def weekdays(start: date, count: int) -> list[date]:
    days, day = [], start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def decimal(value: float) -> Decimal:
    return Decimal(repr(float(value)))  # float(decimal(x)) == x exactly


def obs(series: SeriesCode, ref: date, value: float, available: date | None = None) -> Observation:
    return Observation(series, ref, decimal(value), ref if available is None else available)


def daily(
    series: SeriesCode, start: date, count: int, fn: Callable[[int], float]
) -> list[Observation]:
    return [obs(series, day, fn(i)) for i, day in enumerate(weekdays(start, count))]


def weekly(
    series: SeriesCode, start: date, count: int, fn: Callable[[int], float]
) -> list[Observation]:
    return [obs(series, start + timedelta(days=7 * i), fn(i)) for i in range(count)]


def monthly(
    series: SeriesCode, start: date, count: int, fn: Callable[[int], float]
) -> list[Observation]:
    return [obs(series, add_months(start, i), fn(i)) for i in range(count)]


def view_of(items: Iterable[Observation], series: SeriesCode, as_of: date = FAR) -> SeriesView:
    return PointInTime(items).snapshot(series, as_of)


def panel(years: int = 8, seed: int = 1) -> list[Observation]:
    """All 15 series: random walks around a level, daily to 2025-08-14, monthly to 2025-06."""
    rng = np.random.default_rng(seed)
    start = date(2025 - years, 9, 1)
    stop = date(2025, 8, 14)
    out: list[Observation] = []

    def walk(count: int, level: float, step: float) -> list[float]:
        return list(level * np.exp(np.cumsum(rng.normal(0.0, step, count))))

    dates = [d for d in weekdays(start, 366 * years) if d <= stop]
    for code, level, step in (
        (SeriesCode.US_DGS2, 4.0, 0.006),
        (SeriesCode.KR_KTB3Y, 3.0, 0.006),
        (SeriesCode.KR_BASE_RATE, 3.0, 0.0005),
        (SeriesCode.US_T10Y3M, 0.5, 0.02),
        (SeriesCode.KR_USDKRW, 1200.0, 0.004),
        (SeriesCode.US_DTWEXBGS, 110.0, 0.002),
        (SeriesCode.US_VIX, 20.0, 0.04),
        (SeriesCode.US_BAA10Y, 2.0, 0.01),
        (SeriesCode.KR_CORP_AA, 4.0, 0.006),
    ):
        values = walk(len(dates), level, step)
        out += [obs(code, d, v) for d, v in zip(dates, values, strict=True)]
    months = (2025 - start.year) * 12 + 6 - start.month + 1
    for code, level, step in (
        (SeriesCode.US_CPILFESL, 300.0, 0.002),
        (SeriesCode.KR_CPI, 110.0, 0.002),
        (SeriesCode.US_SAHM, 0.3, 0.05),
        (SeriesCode.KR_CLI, 100.0, 0.003),
        (SeriesCode.KR_EXPORTS, 500.0, 0.03),
    ):
        values = walk(months, level, step)
        out += [obs(code, add_months(start, i), v) for i, v in enumerate(values)]
    weeks = (date(2025, 8, 9) - start).days // 7 + 1
    out += [
        obs(SeriesCode.US_ICSA, start + timedelta(days=7 * i), v)
        for i, v in enumerate(walk(weeks, 220.0, 0.02))
    ]
    return out


def hexed(value: float | None) -> str | None:
    return None if value is None else float(value).hex()


def fingerprint(snap) -> tuple:  # noqa: ANN001
    """Every output of one snapshot as exact text, so two runs compare bit for bit."""
    return (
        snap.step,
        tuple((s.term_id, s.status.value, hexed(s.z)) for s in snap.signals),
        tuple(sorted((k, hexed(v)) for k, v in snap.indicator_z.items())),
        *(
            hexed(getattr(snap, name))
            for name in ("r_us", "r_kr", "g_us", "g_kr", "r_raw", "g_raw", "r", "g", "f", "s")
        ),
        None if snap.p is None else tuple(hexed(x) for x in snap.p),
        snap.rate_dir,
        snap.growth_dir,
        snap.label,
        snap.label_since_step,
        snap.phase,
        tuple(
            (axis.value, None if d is None else (d.direction.value, hexed(d.strength)))
            for axis, d in sorted(snap.display.items())
        ),
        snap.stale,
        snap.quality_ok,
        snap.carried,
    )
