"""The data port of the regime route: where raw observations and holiday calendars come from.

No implementation reads a database yet; the route answers 503 until one is configured.
"""

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from typing import Protocol

from antlab_engine.regime.adapter import RawObservation
from antlab_engine.regime.model import Market


@dataclass(frozen=True, slots=True, eq=False)
class RegimeInputs:
    rows: tuple[RawObservation, ...]
    calendars: Mapping[Market, frozenset[date]]


class RegimeSource(Protocol):
    def read(self, upper: date) -> RegimeInputs:
        """Every row usable on or before `upper` (the last day of the last requested month).

        A source may return less than that, never needs to return more. It must not write.
        """
        ...
