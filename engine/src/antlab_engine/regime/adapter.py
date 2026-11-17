"""Adapter: raw collected rows to library observations, dated by their publication rule.

`to_observations` is the only constructor path for the regime library from collected data:
it validates each row, maps the source series to a `SeriesCode`, links the legacy dollar
index (MACRO_ETF §4.1) and sets `available_on = max(published_on or floor, floor)` (B2).
Dates and values never appear in an error message.
"""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import date
from decimal import Context, Decimal
from enum import StrEnum
from types import MappingProxyType

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime.model import (
    SERIES_SPECS,
    Frequency,
    Market,
    Observation,
    SeriesCode,
    is_plain_date,
)
from antlab_engine.regime.publication import publication_floor

Calendars = Mapping[Market, frozenset[date]]
DECIMAL_PRECISION = 28


class SourceSeries(StrEnum):
    """Series as the collectors name them: the 15 library series plus the legacy dollar index."""

    US_DGS2 = "US_DGS2"
    US_CPILFESL = "US_CPILFESL"
    KR_KTB3Y = "KR_KTB3Y"
    KR_BASE_RATE = "KR_BASE_RATE"
    KR_CPI = "KR_CPI"
    US_ICSA = "US_ICSA"
    US_SAHM = "US_SAHM"
    US_T10Y3M = "US_T10Y3M"
    KR_CLI = "KR_CLI"
    KR_EXPORTS = "KR_EXPORTS"
    KR_USDKRW = "KR_USDKRW"
    US_DTWEXBGS = "US_DTWEXBGS"
    US_VIX = "US_VIX"
    US_BAA10Y = "US_BAA10Y"
    KR_CORP_AA = "KR_CORP_AA"
    FRED_DTWEXM = "FRED_DTWEXM"  # FRED DTWEXM, major-currencies dollar index (to 2019)


SOURCE_TO_CODE: Mapping[SourceSeries, SeriesCode] = MappingProxyType(
    {
        SourceSeries.US_DGS2: SeriesCode.US_DGS2,
        SourceSeries.US_CPILFESL: SeriesCode.US_CPILFESL,
        SourceSeries.KR_KTB3Y: SeriesCode.KR_KTB3Y,
        SourceSeries.KR_BASE_RATE: SeriesCode.KR_BASE_RATE,
        SourceSeries.KR_CPI: SeriesCode.KR_CPI,
        SourceSeries.US_ICSA: SeriesCode.US_ICSA,
        SourceSeries.US_SAHM: SeriesCode.US_SAHM,
        SourceSeries.US_T10Y3M: SeriesCode.US_T10Y3M,
        SourceSeries.KR_CLI: SeriesCode.KR_CLI,
        SourceSeries.KR_EXPORTS: SeriesCode.KR_EXPORTS,
        SourceSeries.KR_USDKRW: SeriesCode.KR_USDKRW,
        SourceSeries.US_DTWEXBGS: SeriesCode.US_DTWEXBGS,
        SourceSeries.US_VIX: SeriesCode.US_VIX,
        SourceSeries.US_BAA10Y: SeriesCode.US_BAA10Y,
        SourceSeries.KR_CORP_AA: SeriesCode.KR_CORP_AA,
        SourceSeries.FRED_DTWEXM: SeriesCode.US_DTWEXBGS,  # linked, see `_link`
    }
)


@dataclass(frozen=True, slots=True)
class RawObservation:
    """One collected row. `published_on` is the vintage or collection date; None = back-fill."""

    source: SourceSeries
    ref_date: date = field(repr=False)
    value: Decimal = field(repr=False)
    published_on: date | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.source, SourceSeries):
            raise InvalidArgument("a raw observation needs a SourceSeries")
        if not is_plain_date(self.ref_date):
            raise InvalidArgument("a raw observation needs a reference date")
        if self.published_on is not None and not is_plain_date(self.published_on):
            raise InvalidArgument("a publication date must be a date or None")
        if not isinstance(self.value, Decimal) or not self.value.is_finite():
            raise InvalidArgument("a raw observation value must be a finite Decimal")


@dataclass(frozen=True, slots=True, eq=False)
class Adapted:
    observations: tuple[Observation, ...]
    dtwex_linked: bool
    rule_dated_rows: Mapping[SeriesCode, int]  # rows with no publication date, per series


@dataclass(frozen=True, slots=True)
class _Row:
    source: SourceSeries
    code: SeriesCode
    ref_date: date
    value: Decimal
    original: date  # stored publication date, or the floor for a back-fill
    available_on: date
    rule_dated: bool


def _row(raw: RawObservation, calendars: Calendars | None) -> _Row:
    code = SOURCE_TO_CODE[raw.source]
    if raw.published_on is not None and raw.published_on < raw.ref_date:
        raise InvalidArgument(f"publication date before the reference date in {raw.source}")
    if SERIES_SPECS[code].frequency is Frequency.MONTHLY and raw.ref_date.day != 1:
        raise InvalidArgument(f"a monthly reference date must be the first of the month: {code}")
    floor = publication_floor(code, raw.ref_date, calendars)
    original = floor if raw.published_on is None else raw.published_on
    return _Row(
        raw.source,
        code,
        raw.ref_date,
        raw.value,
        original,
        max(original, floor),
        raw.published_on is None,
    )


def _dedupe(rows: Iterable[_Row]) -> list[_Row]:
    """One row per source and reference date; a revisable series keeps one per usable date.

    Collections of a revisable value that become usable on the same date are the same vintage
    for the library. A collection with a publication date beats a back-fill, and of two dated
    ones the later wins. Repeating a (back-fill or dated) collection is a duplicate whatever
    the row order, so the same data is accepted or rejected alike.
    """
    kept: dict[tuple, _Row] = {}
    seen: dict[tuple, set[tuple[bool, date]]] = {}
    for row in rows:
        if not SERIES_SPECS[row.code].revisable:
            key = (row.source, row.ref_date)
            if key in kept:
                raise InvalidArgument(f"duplicate row in {row.source}")
            kept[key] = row
            continue
        key = (row.source, row.ref_date, row.available_on)
        identity = (row.rule_dated, row.original)
        if identity in seen.setdefault(key, set()):
            raise InvalidArgument(f"duplicate row in {row.source}")
        seen[key].add(identity)
        other = kept.get(key)
        if other is None or (not row.rule_dated, row.original) > (
            not other.rule_dated,
            other.original,
        ):
            kept[key] = row
    return list(kept.values())


@dataclass(frozen=True, slots=True)
class _Link:
    first_common: date
    factor: Decimal
    available_on: date  # the factor is known only when both reference rows are


def _link(rows: list[_Row]) -> _Link | None:
    """MACRO_ETF §4.1: the newer index is rescaled to the older one at their first common date.

    Rescaling the newer series changes no value dated before the common date, and the factor
    uses only numbers available when that date's rows are, so no earlier step can see it.
    """
    old = {r.ref_date: r for r in rows if r.source is SourceSeries.FRED_DTWEXM}
    new = {r.ref_date: r for r in rows if r.source is SourceSeries.US_DTWEXBGS}
    common = old.keys() & new.keys()
    if not common:
        return None
    first = min(common)
    if new[first].value == 0:
        raise InvalidArgument("the dollar index has a zero value at the link date")
    factor = Context(prec=DECIMAL_PRECISION).divide(old[first].value, new[first].value)
    return _Link(first, factor, max(old[first].available_on, new[first].available_on))


def _apply_link(rows: list[_Row], link: _Link | None) -> list[_Row]:
    """Rescale the newer index from the common date on and settle each reference date.

    With no common date both indexes stay as collected. From the common date on, a date that
    both indexes have uses the rescaled newer row when it is usable no later than the old row,
    else the old row. That decision looks only at rows that are usable at the same time, so
    removing rows that become usable later never changes an earlier answer, and the older
    index covers the days before the newer one is usable. A newer row dated before the common
    date (an index with gaps) stays as collected.
    """
    if link is None:
        return rows
    context = Context(prec=DECIMAL_PRECISION)
    scaled: dict[date, _Row] = {}
    for r in rows:
        if r.source is SourceSeries.US_DTWEXBGS and r.ref_date >= link.first_common:
            value = context.multiply(r.value, link.factor)
            available = max(r.available_on, link.available_on)
            scaled[r.ref_date] = _Row(
                r.source, r.code, r.ref_date, value, r.original, available, r.rule_dated
            )
    old_available = {
        r.ref_date: r.available_on for r in rows if r.source is SourceSeries.FRED_DTWEXM
    }
    out = []
    for r in rows:
        if r.source is SourceSeries.FRED_DTWEXM:
            newer = scaled.get(r.ref_date)
            if (
                r.ref_date < link.first_common
                or newer is None
                or newer.available_on > r.available_on
            ):
                out.append(r)
        elif r.source is SourceSeries.US_DTWEXBGS and r.ref_date >= link.first_common:
            old = old_available.get(r.ref_date)
            if old is None or scaled[r.ref_date].available_on <= old:
                out.append(scaled[r.ref_date])
        else:
            out.append(r)
    return out


def adapt(rows: Iterable[RawObservation], calendars: Calendars | None = None) -> Adapted:
    raw = tuple(rows)
    if not all(isinstance(r, RawObservation) for r in raw):
        raise InvalidArgument("the adapter takes RawObservation objects")
    dated = _dedupe(_row(r, calendars) for r in raw)
    link = _link(dated)
    linked = _apply_link(dated, link)
    observations = tuple(
        sorted(
            (Observation(r.code, r.ref_date, r.value, r.available_on) for r in linked),
            key=lambda o: (o.series.value, o.ref_date, o.available_on),
        )
    )
    counts = dict.fromkeys(SeriesCode, 0)
    for r in linked:
        counts[r.code] += r.rule_dated
    return Adapted(observations, link is not None, MappingProxyType(counts))


def to_observations(
    rows: Iterable[RawObservation], calendars: Calendars | None = None
) -> tuple[Observation, ...]:
    return adapt(rows, calendars).observations
