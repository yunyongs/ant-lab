"""Weekly driver: signals, axes, smoothing, probabilities, label and phase (MACRO_ETF §2.4).

Each evaluation date is one step. The documented method is weekly (Saturdays), so the EMA half
life, the confirmation and the hold are counted in steps and `weekly_schedule` gives the dates.
Everything at step T uses only observations available on or before that step's date, because
every read goes through `PointInTime.snapshot` and the smoothing and label state only carry
earlier steps forward.
"""

import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from types import MappingProxyType

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.hysteresis import (
    AxisState,
    LabelState,
    advance_axis_if_fresh,
    advance_label,
)
from antlab_engine.regime.model import (
    Axis,
    Direction,
    Group,
    Market,
    Observation,
    Phase,
    Pool,
    SeriesCode,
    TermSignal,
    is_plain_date,
)
from antlab_engine.regime.params import DEFAULT_PARAMS, RegimeParams
from antlab_engine.regime.pit import PointInTime, month_last_day
from antlab_engine.regime.probability import phase_of, pool_probabilities
from antlab_engine.regime.scoring import (
    combine_axes,
    group_scores,
    indicator_values,
    quality_ok,
)
from antlab_engine.regime.stale import (
    Calendars,
    series_states,
    stale_indicators_from_states,
)
from antlab_engine.regime.zscore import signals_from_views

SATURDAY = 5


@dataclass(frozen=True, slots=True)
class AxisDisplay:
    direction: Direction
    strength: float  # |score|, never negative


@dataclass(frozen=True, slots=True, eq=False)
class RegimeSnapshot:
    """One evaluation. `None` means the value could not be computed at this step."""

    as_of: date = field(repr=False)
    step: int  # 1-based position in the evaluated dates
    signals: tuple[TermSignal, ...]
    indicator_z: Mapping[str, float | None]
    r_us: float | None
    r_kr: float | None
    g_us: float | None
    g_kr: float | None
    r_raw: float | None
    g_raw: float | None
    r: float | None  # smoothed (EMA)
    g: float | None
    f: float | None  # not smoothed
    s: float | None  # not smoothed
    p: tuple[float, float, float, float] | None
    rate_dir: Direction | None  # hysteresis state, UP or DOWN
    growth_dir: Direction | None
    label: Pool | None
    label_since_step: int | None
    phase: Phase | None
    display: Mapping[Axis, AxisDisplay | None]
    stale: tuple[str, ...]  # stale indicator ids
    unavailable: tuple[str, ...]  # indicator ids with no usable term (stale, missing, warming)
    quality_ok: bool
    carried: bool  # raw R or G was unavailable and the previous EMA was carried


def weekly_schedule(start: date, end: date) -> tuple[date, ...]:
    """Every Saturday from `start` to `end`, both inclusive."""
    if not (is_plain_date(start) and is_plain_date(end)):
        raise InvalidArgument("start and end must be dates")
    first = start + timedelta(days=(SATURDAY - start.weekday()) % 7)
    count = max(0, (end - first).days // 7 + 1)
    return tuple(first + timedelta(days=7 * i) for i in range(count))


def month_end_sample(
    snapshots: Iterable[RegimeSnapshot], year: int, month: int
) -> RegimeSnapshot | None:
    """The last snapshot whose evaluation date lies inside the calendar month, or None."""
    if not (isinstance(year, int) and isinstance(month, int) and 1 <= month <= 12):
        raise InvalidArgument("year and month must be integers, month from 1 to 12")
    first = date(year, month, 1)
    last = month_last_day(first)
    inside = [s for s in snapshots if first <= s.as_of <= last]
    return max(inside, key=lambda s: s.as_of, default=None)


def axis_display(value: float | None, band: float) -> AxisDisplay | None:
    """UP above +band, DOWN below -band, else NEUTRAL; strength is |value|."""
    if value is None:
        return None
    direction = (
        Direction.UP if value > band else Direction.DOWN if value < -band else Direction.NEUTRAL
    )
    return AxisDisplay(direction, abs(value))


def _check_dates(as_of_dates: Sequence[date]) -> None:
    previous: date | None = None
    for day in as_of_dates:
        if not is_plain_date(day):
            raise InvalidArgument("evaluation dates must be dates")
        if previous is not None and day <= previous:
            raise InvalidArgument("evaluation dates must be strictly increasing")
        previous = day


def _ema(previous: float | None, raw: float | None, alpha: float) -> float | None:
    if raw is None:
        return previous
    return raw if previous is None else alpha * raw + (1.0 - alpha) * previous


def _finite_or_none(value: float | None) -> float | None:
    return value if value is not None and math.isfinite(value) else None


def evaluate(
    observations: Iterable[Observation],
    as_of_dates: Sequence[date],
    *,
    calendars: Mapping[Market, frozenset[date]] | None = None,
    params: RegimeParams = DEFAULT_PARAMS,
) -> tuple[RegimeSnapshot, ...]:
    """Evaluate the regime on each date, in order; the state of a step comes from earlier steps."""
    _check_dates(as_of_dates)
    pit = PointInTime(observations)
    holidays: Calendars | None = calendars
    alpha = params.alpha
    ema_r: float | None = None
    ema_g: float | None = None
    rate_state: AxisState | None = None
    growth_state: AxisState | None = None
    label_state: LabelState | None = None
    out: list[RegimeSnapshot] = []
    for step, as_of in enumerate(as_of_dates, start=1):
        views = {code: pit.snapshot(code, as_of) for code in SeriesCode}
        states = series_states(views, as_of, holidays)
        signals = signals_from_views(views, states)
        stale_ids = stale_indicators_from_states(states)
        indicators = indicator_values(signals)
        scores = group_scores(signals)
        r_raw, g_raw = combine_axes(
            scores[Group.R_US], scores[Group.R_KR], scores[Group.G_US], scores[Group.G_KR], params
        )
        r_raw, g_raw = _finite_or_none(r_raw), _finite_or_none(g_raw)
        ema_r, ema_g = _ema(ema_r, r_raw, alpha), _ema(ema_g, g_raw, alpha)
        carried = (r_raw is None and ema_r is not None) or (g_raw is None and ema_g is not None)
        rate_state = advance_axis_if_fresh(
            rate_state, ema_r, r_raw is not None, params.dead_band, params.confirm_steps
        )
        growth_state = advance_axis_if_fresh(
            growth_state, ema_g, g_raw is not None, params.dead_band, params.confirm_steps
        )
        if rate_state is not None and growth_state is not None:
            label_state = advance_label(
                label_state,
                rate_state.direction,
                growth_state.direction,
                step,
                params.min_hold_steps,
            )
        p = (
            pool_probabilities(ema_r, ema_g, params.k)
            if ema_r is not None and ema_g is not None
            else None
        )
        phase = (
            phase_of(p[list(Pool).index(label_state.label)], params.phase_cuts)
            if p is not None and label_state is not None
            else None
        )
        f, s = _finite_or_none(scores[Group.F]), _finite_or_none(scores[Group.S])
        out.append(
            RegimeSnapshot(
                as_of=as_of,
                step=step,
                signals=signals,
                indicator_z=MappingProxyType(indicators),
                r_us=scores[Group.R_US],
                r_kr=scores[Group.R_KR],
                g_us=scores[Group.G_US],
                g_kr=scores[Group.G_KR],
                r_raw=r_raw,
                g_raw=g_raw,
                r=ema_r,
                g=ema_g,
                f=f,
                s=s,
                p=p,
                rate_dir=rate_state.direction if rate_state else None,
                growth_dir=growth_state.direction if growth_state else None,
                label=label_state.label if label_state else None,
                label_since_step=label_state.since_step if label_state else None,
                phase=phase,
                display=MappingProxyType(
                    {
                        Axis.RATE: axis_display(ema_r, params.dead_band),
                        Axis.GROWTH: axis_display(ema_g, params.dead_band),
                        Axis.FX: axis_display(f, params.dead_band),
                        Axis.SENTIMENT: axis_display(s, params.dead_band),
                    }
                ),
                stale=tuple(str(i) for i in sorted(stale_ids)),
                unavailable=tuple(str(i) for i in range(1, 15) if indicators[str(i)] is None),
                quality_ok=quality_ok(stale_ids, params),
                carried=carried,
            )
        )
    return tuple(out)
