"""Regime scoring library: adapter, point-in-time views, 24-term catalog, z, axes, label."""

from antlab_engine.regime.adapter import (
    Adapted,
    RawObservation,
    SourceSeries,
    adapt,
    to_observations,
)
from antlab_engine.regime.catalog import TERMS, TERMS_BY_ID, Term
from antlab_engine.regime.coverage import Coverage, coverage, input_digest
from antlab_engine.regime.evaluate import (
    AxisDisplay,
    RegimeSnapshot,
    evaluate,
    month_end_sample,
    weekly_schedule,
)
from antlab_engine.regime.model import (
    Direction,
    Observation,
    Phase,
    Pool,
    SeriesCode,
    SeriesView,
    TermSignal,
    TermStatus,
    Transformed,
)
from antlab_engine.regime.monthly import (
    LABEL_VERSION,
    SCHEDULE_START,
    MonthRegime,
    MonthStatus,
    label_months,
)
from antlab_engine.regime.params import DEFAULT_PARAMS, REGIME_VERSION, RegimeParams
from antlab_engine.regime.pit import PointInTime, effective_available
from antlab_engine.regime.probability import phase_of, pool_of, pool_probabilities
from antlab_engine.regime.publication import publication_floor
from antlab_engine.regime.scoring import combine_axes
from antlab_engine.regime.source import RegimeInputs, RegimeSource
from antlab_engine.regime.stale import SeriesState, series_state, stale_indicators
from antlab_engine.regime.zscore import term_signals, z_of_latest

__all__ = [
    "LABEL_VERSION",
    "SCHEDULE_START",
    "Adapted",
    "Coverage",
    "MonthRegime",
    "MonthStatus",
    "RawObservation",
    "RegimeInputs",
    "RegimeSource",
    "SourceSeries",
    "adapt",
    "coverage",
    "input_digest",
    "label_months",
    "publication_floor",
    "to_observations",
    "DEFAULT_PARAMS",
    "REGIME_VERSION",
    "AxisDisplay",
    "Direction",
    "Phase",
    "Pool",
    "RegimeParams",
    "RegimeSnapshot",
    "combine_axes",
    "evaluate",
    "month_end_sample",
    "phase_of",
    "pool_of",
    "pool_probabilities",
    "weekly_schedule",
    "TERMS",
    "TERMS_BY_ID",
    "Observation",
    "PointInTime",
    "SeriesCode",
    "SeriesState",
    "SeriesView",
    "Term",
    "TermSignal",
    "TermStatus",
    "Transformed",
    "effective_available",
    "series_state",
    "stale_indicators",
    "term_signals",
    "z_of_latest",
]
