"""Composite scores at chosen weights: `s63-v3` recomposed, PLAN §6.9 S, and the career score L.

Weights are `Decimal` and validated against the search constraints of the M3-03 plan. The recomposed
`s63` score uses unrounded parts and rounds once, so at the draft weights it reproduces the stored
score character for character.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

from antlab_engine.sim import money
from antlab_engine.sim import score as s63
from antlab_engine.sim.report import plain

from .components import ComponentError, Components, ReferenceSet

CAREER_SEASONS = 10
TRIM_EACH_SIDE = 1
L_SD_WEIGHT = Decimal("0.5")

_X = money.EXACT
_ONE = Decimal(1)
_RESULT_RANGE = (Decimal("0.30"), Decimal("0.70"))
_S_MIN = Decimal("0.05")
_S_PROCESS_RANGE = (Decimal("0.15"), Decimal("0.50"))


class WeightError(ValueError):
    """Weights that break the sum or the search constraints."""


@dataclass(frozen=True)
class Weights63:
    """`s63` result and process weights: sum 1, result between 0.30 and 0.70."""

    result: Decimal
    process: Decimal

    def __post_init__(self) -> None:
        if _X.add(self.result, self.process) != _ONE:
            raise WeightError("weights must add up to exactly 1")
        if not _RESULT_RANGE[0] <= self.result <= _RESULT_RANGE[1]:
            raise WeightError("the result weight must be within 0.30..0.70")


@dataclass(frozen=True)
class SWeights:
    """§6.9 S weights: sum 1, each at least 0.05, process between 0.15 and 0.50."""

    ret: Decimal
    risk_adjusted: Decimal
    risk: Decimal
    process: Decimal

    def __post_init__(self) -> None:
        parts = (self.ret, self.risk_adjusted, self.risk, self.process)
        total = Decimal(0)
        for part in parts:
            total = _X.add(total, part)
        if total != _ONE:
            raise WeightError("weights must add up to exactly 1")
        if any(part < _S_MIN for part in parts):
            raise WeightError("every weight must be at least 0.05")
        if not _S_PROCESS_RANGE[0] <= self.process <= _S_PROCESS_RANGE[1]:
            raise WeightError("the process weight must be within 0.15..0.50")


DRAFT_63 = Weights63(Decimal("0.5"), Decimal("0.5"))
DRAFT_S = SWeights(Decimal("0.30"), Decimal("0.30"), Decimal("0.15"), Decimal("0.25"))


# ---------------------------------------------------------------------------- s63 recomposition


def recompose_63(components: Components, weights: Weights63 = DRAFT_63) -> Decimal | None:
    """`result x w + process x (1 - w)` from unrounded parts, rounded once to 2 places.

    None when the season has no result score (no path distribution).
    """
    if components.result is None:
        return None
    return money.round2(
        _X.add(
            _X.multiply(weights.result, components.result),
            _X.multiply(weights.process, components.process),
        )
    )


def recompose_63_wire(components: Components, weights: Weights63 = DRAFT_63) -> str | None:
    value = recompose_63(components, weights)
    return None if value is None else plain(value)


# ---------------------------------------------------------------------------- S


@dataclass(frozen=True)
class SParts:
    """The four parts of S in 0..100; the process part is the raw §6.3 process score."""

    ret: Decimal
    risk_adjusted: Decimal
    risk: Decimal
    process: Decimal


def s_parts(components: Components, reference: ReferenceSet) -> SParts:
    if (components.world_id, components.path_length) != (
        reference.world_id,
        reference.path_length,
    ):
        raise ComponentError("the bot and its reference set come from different worlds")
    risk_adjusted = s63._mean(
        [
            reference.percentile("sharpe_diff", components.metric("sharpe_diff")),
            reference.percentile("sortino_diff", components.metric("sortino_diff")),
            reference.percentile("calmar_diff", components.metric("calmar_diff")),
        ]
    )
    risk = s63._mean(
        [
            reference.percentile("risk_item", components.metric("risk_item")),
            reference.percentile("mdd", components.metric("mdd")),
        ]
    )
    return SParts(
        reference.percentile("excess_cagr", components.metric("excess_cagr")),
        risk_adjusted,
        risk,
        components.process,
    )


def s_score(parts: SParts, weights: SWeights = DRAFT_S) -> Decimal:
    """Weighted sum of the four parts, unrounded."""
    total = Decimal(0)
    for weight, part in (
        (weights.ret, parts.ret),
        (weights.risk_adjusted, parts.risk_adjusted),
        (weights.risk, parts.risk),
        (weights.process, parts.process),
    ):
        total = _X.add(total, _X.multiply(weight, part))
    return total


# ---------------------------------------------------------------------------- L


def sample_stddev(values: Sequence[Decimal]) -> Decimal:
    """Square root of the sample variance (n - 1); 0 when every value is equal."""
    n = len(values)
    if n < 2:
        raise ComponentError("a sample standard deviation needs two values")
    total = squares = Decimal(0)
    for value in values:
        total = _X.add(total, value)
        squares = _X.add(squares, _X.multiply(value, value))
    scatter = _X.subtract(_X.multiply(Decimal(n), squares), _X.multiply(total, total))
    if scatter == 0:
        return Decimal(0)
    variance = money.divide_significant(scatter, Decimal(n * (n - 1)))
    return money.sqrt_significant(variance)


def career_l(season_scores: Sequence[Decimal]) -> Decimal:
    """Trimmed mean of ten season scores (one dropped at each end) minus 0.5 x their sample sd."""
    if len(season_scores) != CAREER_SEASONS:
        raise ComponentError(f"a career is {CAREER_SEASONS} seasons")
    kept = sorted(season_scores)[TRIM_EACH_SIDE : len(season_scores) - TRIM_EACH_SIDE]
    total = Decimal(0)
    for value in kept:
        total = _X.add(total, value)
    mean = money.divide_significant(total, Decimal(len(kept)))
    return _X.subtract(mean, _X.multiply(L_SD_WEIGHT, sample_stddev(season_scores)))
