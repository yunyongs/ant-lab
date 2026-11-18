"""POST /v1/regime/evaluate: the regime of each requested month from the collected history.

Months and values are replay-only: they leave the engine in the answer of this route and are
never logged or put in an error. The route reads nothing until the request is valid, and
raw rows reach the library only through the adapter.
"""

import math
from datetime import date
from decimal import ROUND_HALF_UP, Context, Decimal
from threading import BoundedSemaphore
from typing import Annotated

from fastapi import APIRouter, Depends, Request

from antlab_engine.engine_errors import (
    EngineBusy,
    EngineError,
    HistoryUnavailable,
    InvalidArgument,
    RegimeDataInvalid,
)
from antlab_engine.errors import Problem
from antlab_engine.regime import (
    LABEL_VERSION,
    REGIME_VERSION,
    MonthRegime,
    adapt,
    coverage,
    input_digest,
    label_months,
)
from antlab_engine.regime.evaluate import AxisDisplay
from antlab_engine.regime.model import Axis
from antlab_engine.regime.pit import month_last_day
from antlab_engine.regime.source import RegimeInputs, RegimeSource

from . import PROBLEM_RESPONSES, RETRY_AFTER_HEADER
from .deps import regime_slot, regime_source
from .regime_models import (
    Axes,
    AxisReading,
    Probabilities,
    Quality,
    RegimeCoverage,
    RegimeMonth,
    RegimeRequest,
    RegimeResult,
)

STRENGTH_STEP = Decimal("1e-8")  # NUMERIC(12,8)
PROBABILITY_STEP = Decimal("1e-4")  # NUMERIC(5,4)
WIRE_LIMIT = 1e4  # four integer digits, the width of the patterns
ROUND_CONTEXT = Context(prec=80)
SOURCE_FAILED = "The regime data source could not be read."
BUSY = "Another regime evaluation is running; try again later."

router = APIRouter()


def fixed(value: float, step: Decimal) -> str:
    """The exact binary value as a decimal string at `step`, rounded half up once; never `-0`."""
    number = float(value)
    if not math.isfinite(number) or abs(number) >= WIRE_LIMIT:
        raise RuntimeError("a regime value is outside the wire range")
    rounded = Decimal(number).quantize(step, rounding=ROUND_HALF_UP, context=ROUND_CONTEXT)
    return format(abs(rounded) if rounded == 0 else rounded, "f")


def _reading(display: AxisDisplay | None) -> AxisReading | None:
    if display is None:
        return None
    return AxisReading(
        direction=display.direction.value, strength=fixed(display.strength, STRENGTH_STEP)
    )


def _month(month: MonthRegime) -> RegimeMonth:
    probabilities = None
    if month.p is not None:
        values = [fixed(x, PROBABILITY_STEP) for x in month.p]
        probabilities = Probabilities(p1=values[0], p2=values[1], p3=values[2], p4=values[3])
    return RegimeMonth(
        month=f"{month.month:%Y-%m}",
        status=month.status.value,
        axes=Axes(**{axis.value.lower(): _reading(month.axes[axis]) for axis in Axis}),
        probabilities=probabilities,
        label=None if month.label is None else month.label.value,
        phase=None if month.phase is None else month.phase.value,
        indicators={
            key: None if z is None else fixed(z, STRENGTH_STEP)
            for key, z in month.indicator_z.items()
        },
        quality=Quality(
            ok=month.quality_ok, stale=list(month.stale), unavailable=list(month.unavailable)
        ),
    )


def _first_of_month(text: str) -> date:
    return date(int(text[:4]), int(text[5:7]), 1)


@router.post(
    "/v1/regime/evaluate",
    response_model=RegimeResult,
    operation_id="evaluateRegime",
    responses={
        **PROBLEM_RESPONSES,
        400: {"model": Problem, "description": "Invalid argument"},
        422: {"model": Problem, "description": "The collected observations cannot be used"},
        503: {
            "model": Problem,
            "description": "No data source or the engine is busy",
            "headers": RETRY_AFTER_HEADER,
        },
    },
)
def evaluate_regime(
    request: RegimeRequest,
    http_request: Request,
    slot: Annotated[BoundedSemaphore, Depends(regime_slot)],
) -> RegimeResult:
    months = [_first_of_month(m) for m in request.months]
    # resolved after the request checks: a bad request is 400 even without a data source
    source = regime_source(http_request)
    if not slot.acquire(blocking=False):
        raise EngineBusy(BUSY)
    try:
        return _run(source, months)
    finally:
        slot.release()


def _read(source: RegimeSource, upper: date) -> RegimeInputs:
    try:
        return source.read(upper)
    except InvalidArgument:
        raise RegimeDataInvalid("The collected observations cannot be used.") from None
    except EngineError:
        raise
    except Exception:
        raise HistoryUnavailable(SOURCE_FAILED) from None


def _run(source: RegimeSource, months: list[date]) -> RegimeResult:
    inputs = _read(source, month_last_day(months[-1]))
    try:
        adapted = adapt(inputs.rows, inputs.calendars)
    except (InvalidArgument, ArithmeticError) as exc:
        detail = str(exc) if isinstance(exc, InvalidArgument) else "a value is out of range"
        raise RegimeDataInvalid(f"The collected observations cannot be used: {detail}") from None
    results = label_months(adapted.observations, months, calendars=inputs.calendars)
    counts = coverage(adapted, results)
    return RegimeResult(
        labelVersion=LABEL_VERSION,
        regimeVersion=REGIME_VERSION,
        inputDigest=input_digest(adapted.observations, inputs.calendars),
        months=[_month(m) for m in results],
        coverage=RegimeCoverage(
            evaluated=counts.evaluated,
            carried=counts.carried,
            noData=counts.no_data,
            indicators=dict(counts.indicators),
            ruleDatedRows={code.value: n for code, n in counts.rule_dated_rows.items()},
            dtwexLinked=counts.dtwex_linked,
        ),
    )
