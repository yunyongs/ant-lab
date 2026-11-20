"""PostgreSQL implementation of the RegimeSource port: one read-only snapshot per call.

Reads `series` and `series_observation` as the SELECT-only `antlab_engine` role. Rows are
dated by the as-of rule of erd 10.6.2 (the SQL is in `history/queries.py`); the adapter applies
the publication floors. Errors never echo the cause, a date or a value.
"""

from datetime import date

from antlab_engine.history import queries
from antlab_engine.history.reader import read_snapshot
from antlab_engine.regime.adapter import SOURCE_TO_CODE, RawObservation, SourceSeries
from antlab_engine.regime.model import SERIES_SPECS, Market
from antlab_engine.regime.source import RegimeInputs
from antlab_engine.settings import DatabaseSettings

SOURCE_CODES = tuple(s.value for s in SourceSeries)
REVISABLE_CODES = tuple(s.value for s in SourceSeries if SERIES_SPECS[SOURCE_TO_CODE[s]].revisable)
# A series the library treats as not revisable is read at vintage 1 only: the adapter takes one
# row per reference date, and a routine correction (append-only) must not stop every later run.
EMPTY_CALENDARS = {Market.US: frozenset[date](), Market.KR: frozenset[date]()}


class PostgresRegimeSource:
    """Connects on every read; construction opens no connection."""

    def __init__(self, settings: DatabaseSettings) -> None:
        self._settings = settings

    def read(self, upper: date) -> RegimeInputs:
        with read_snapshot(self._settings) as conn:
            cursor = conn.execute(
                queries.REGIME_OBSERVATIONS_SQL,
                {"upper": upper, "codes": list(SOURCE_CODES), "revisable": list(REVISABLE_CODES)},
            )
            rows = tuple(
                RawObservation(SourceSeries(code), ref_date, value, published_on)
                for code, ref_date, value, published_on in cursor
            )
        return RegimeInputs(rows=rows, calendars=EMPTY_CALENDARS)
