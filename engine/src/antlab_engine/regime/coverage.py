"""Input digest and coverage counts for a labelling run; counts only, no dates or values."""

import hashlib
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from types import MappingProxyType

from antlab_engine.regime.adapter import Adapted
from antlab_engine.regime.model import Market, Observation, SeriesCode
from antlab_engine.regime.monthly import INDICATOR_KEYS, LABEL_VERSION, MonthRegime, MonthStatus


def _canonical_value(observation: Observation) -> str:
    value = observation.value
    return "0" if value == 0 else format(value.normalize(), "f")


def input_digest(
    observations: Iterable[Observation], calendars: Mapping[Market, frozenset[date]] | None = None
) -> str:
    """SHA-256 over the label version, the adapted rows and the holiday calendars (64 hex digits).

    Rows and calendar days are sorted, so equal inputs give equal digests whatever their order,
    and two runs can be compared without printing any data. Calendars change the stale rule, so
    two runs that differ only in them differ here. Empty calendars add no line.
    """
    lines = sorted(
        f"{o.series.value}|{o.ref_date.isoformat()}|{o.available_on.isoformat()}|{_canonical_value(o)}"
        for o in observations
    )
    days = sorted(
        f"cal|{market.value}|{day.isoformat()}"
        for market, holidays in (calendars or {}).items()
        for day in holidays
    )
    return hashlib.sha256("\n".join([LABEL_VERSION, *lines, *days]).encode("ascii")).hexdigest()


@dataclass(frozen=True, slots=True, eq=False)
class Coverage:
    evaluated: int
    carried: int
    no_data: int
    indicators: Mapping[str, int]  # months with a value, per indicator key
    rule_dated_rows: Mapping[SeriesCode, int]  # rows dated by the rule: no publication date
    dtwex_linked: bool


def coverage(adapted: Adapted, months: Sequence[MonthRegime]) -> Coverage:
    status = [m.status for m in months]
    return Coverage(
        evaluated=status.count(MonthStatus.EVALUATED),
        carried=status.count(MonthStatus.CARRIED),
        no_data=status.count(MonthStatus.NO_DATA),
        indicators=MappingProxyType(
            {
                key: sum(1 for m in months if m.indicator_z[key] is not None)
                for key in INDICATOR_KEYS
            }
        ),
        rule_dated_rows=adapted.rule_dated_rows,
        dtwex_linked=adapted.dtwex_linked,
    )
