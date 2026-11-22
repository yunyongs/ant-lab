"""Score components of a `bot-run/1` record, for the score-weight validation (M3-03).

Pure functions: records in, `Decimal` values out. Percentiles are mid-rank (ties count half),
CAGR is `(final / initial) ^ (12 / T) - 1`, and every ratio that can divide by zero has a capped
edge rule. Nothing here is stored or sent anywhere; the values are provisional definitions that
AG-06 finalises.
"""

from bisect import bisect_left, bisect_right
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Context, Decimal, DivisionByZero, InvalidOperation, Overflow
from typing import Self

from antlab_engine.sim import money
from antlab_engine.sim import score as s63
from antlab_engine.sim.counts import RankCounts
from antlab_engine.sim.model import SimShapeError
from antlab_engine.sim.report import _camel, plain

RUN_SCHEMA = "bot-run/1"
OFFLINE_REFERENCE_SIZE = 200
MONTHS_PER_YEAR = 12
RATIO_CAP = Decimal("999999.99999999")
ITEM_NAMES = (
    "risk",
    "discipline",
    "diversification",
    "turnover",
    "consistency",
    "formation",
    "role",
    "noise",
    "nudge",
)
REFERENCE_METRICS = (
    "excess_cagr",
    "sharpe_diff",
    "sortino_diff",
    "calmar_diff",
    "risk_item",
    "mdd",
)

_X = money.EXACT
_ZERO = Decimal(0)
_ONE = Decimal(1)
_HUNDRED = Decimal(100)
_POWER = Context(
    prec=34, rounding=ROUND_HALF_EVEN, traps=[InvalidOperation, DivisionByZero, Overflow]
)


class ComponentError(ValueError):
    """A record or reference set that cannot yield a component value."""


# ---------------------------------------------------------------------------- record parsing


@dataclass(frozen=True)
class RunSeries:
    """The numbers of one record that every component is computed from."""

    world_id: str
    kind: str
    index: int
    turns: int
    initial_cash: Decimal
    totals: tuple[Decimal, ...]
    returns: tuple[Decimal, ...]
    benchmark_returns: tuple[Decimal, ...]


def _decimal(value: object, field: str) -> Decimal:
    if not isinstance(value, str):
        raise ComponentError(f"{field} must be a decimal string")
    try:
        number = Decimal(value)
    except ArithmeticError as error:
        raise ComponentError(f"{field} is not a decimal string") from error
    if not number.is_finite():
        raise ComponentError(f"{field} must be finite")
    return number


def _optional(value: object, field: str) -> Decimal | None:
    return None if value is None else _decimal(value, field)


def series_of(record: Mapping[str, object]) -> RunSeries:
    if record.get("schema") != RUN_SCHEMA:
        raise ComponentError(f"not a {RUN_SCHEMA} record")
    if record.get("status") != "FINISHED":
        raise ComponentError("only a finished season has components")
    bot = _mapping(record, "bot")
    report = _mapping(record, "report")
    totals = tuple(_decimal(t, "totals") for t in _sequence(record, "totals"))
    returns = tuple(_decimal(r, "teamReturns") for r in _sequence(record, "teamReturns"))
    benchmark = tuple(
        _decimal(r, "benchmarkReturns") for r in _sequence(record, "benchmarkReturns")
    )
    turns = report.get("reportTurns")
    if not isinstance(turns, int) or turns < 2:
        raise ComponentError("a season needs at least two report turns")
    if len(returns) != turns or len(benchmark) != turns or len(totals) != turns + 1:
        raise ComponentError("series lengths do not match the report turns")
    initial = _decimal(report.get("initialCash"), "initialCash")
    if initial <= 0:
        raise ComponentError("initial cash must be positive")
    kind, index, world_id = bot.get("kind"), bot.get("index"), record.get("worldId")
    if not isinstance(kind, str) or not isinstance(index, int) or not isinstance(world_id, str):
        raise ComponentError("record lacks a bot kind, index or world id")
    return RunSeries(world_id, kind, index, turns, initial, totals, returns, benchmark)


def _mapping(record: Mapping[str, object], key: str) -> Mapping[str, object]:
    value = record.get(key)
    if not isinstance(value, Mapping):
        raise ComponentError(f"record lacks {key}")
    return value


def _sequence(record: Mapping[str, object], key: str) -> Sequence[object]:
    value = record.get(key)
    if not isinstance(value, list | tuple):
        raise ComponentError(f"record lacks {key}")
    return value


def rank_counts_of(record: Mapping[str, object], name: str) -> RankCounts | None:
    """`counts.<name>` as `RankCounts`; None when the record has no counts (no result score).

    `counts.n` must equal the requested `percentileRequest.n`.
    """
    counts = record.get("counts")
    if counts is None:
        return None
    if not isinstance(counts, Mapping) or not isinstance(counts.get(name), Mapping):
        raise ComponentError(f"counts lacks {name}")
    cell = counts[name]
    below, equal, n = cell.get("below"), cell.get("equal"), cell.get("n")
    if not all(isinstance(x, int) and not isinstance(x, bool) for x in (below, equal, n)):
        raise ComponentError(f"counts.{name} needs integer below, equal and n")
    request = record.get("percentileRequest")
    requested = request.get("n") if isinstance(request, Mapping) else None
    if requested != n:
        raise ComponentError(f"counts.{name}.n differs from the requested n")
    try:
        return RankCounts(below, equal, n)
    except SimShapeError as error:
        raise ComponentError(str(error)) from error


# ---------------------------------------------------------------------------- series maths


def cagr(initial: Decimal, final: Decimal, turns: int) -> Decimal:
    """`(final / initial) ^ (12 / turns) - 1`: power at 34 digits, result rounded to 12 places."""
    if turns < 1 or initial <= 0 or final < 0:
        raise ComponentError("cagr needs turns >= 1, a positive start and a non-negative end")
    multiple = money.score_divide(final, initial)
    exponent = _POWER.divide(Decimal(MONTHS_PER_YEAR), Decimal(turns))
    return money.round12(_X.subtract(_POWER.power(multiple, exponent), _ONE))


def compound(returns: Sequence[Decimal]) -> tuple[Decimal, ...]:
    """Value of 1 after each return."""
    value = _ONE
    out = []
    for r in returns:
        value = _X.multiply(value, _X.add(_ONE, r))
        out.append(value)
    return tuple(out)


def max_drawdown(initial: Decimal, values: Sequence[Decimal]) -> Decimal:
    """Deepest fall from a running peak that starts at `initial`; 0 or negative."""
    if initial <= 0:
        raise ComponentError("the start value must be positive")
    peak = initial
    worst = _ZERO
    for value in values:
        peak = max(peak, value)
        if value < peak:
            worst = min(worst, money.divide_significant(_X.subtract(value, peak), peak))
    return worst


def clamp_ratio(value: Decimal) -> Decimal:
    return min(max(value, -RATIO_CAP), RATIO_CAP)


def capped_ratio(numerator: Decimal, denominator: Decimal) -> Decimal:
    """Numerator over a non-negative denominator; a zero denominator gives the capped sign."""
    if denominator == 0:
        return _ZERO if numerator == 0 else RATIO_CAP.copy_sign(numerator)
    return clamp_ratio(money.divide_significant(numerator, denominator))


def sortino(returns: Sequence[Decimal]) -> Decimal:
    """Mean return over sqrt(sum of squared negative returns / (n - 1)); risk-free rate 0."""
    n = len(returns)
    if n < 2:
        raise ComponentError("sortino needs at least two returns")
    total = squares = _ZERO
    for r in returns:
        total = _X.add(total, r)
        if r < 0:
            squares = _X.add(squares, _X.multiply(r, r))
    mean = money.divide_significant(total, Decimal(n))
    downside = money.sqrt_significant(money.divide_significant(squares, Decimal(n - 1)))
    return capped_ratio(mean, downside)


def calmar(annual_return: Decimal, drawdown: Decimal) -> Decimal:
    """CAGR over |max drawdown|."""
    return capped_ratio(annual_return, drawdown.copy_abs())


def mid_rank_percentile(value: Decimal, reference: Sequence[Decimal]) -> Decimal:
    """100 x (below + equal / 2) / n among `reference`, exact to 30 places."""
    return _percentile(value, tuple(sorted(reference)))


def _percentile(value: Decimal, ordered: Sequence[Decimal]) -> Decimal:
    if not ordered:
        raise ComponentError("a reference set must not be empty")
    below = bisect_left(ordered, value)
    equal = bisect_right(ordered, value) - below
    return money.score_divide(Decimal(100 * (2 * below + equal)), Decimal(2 * len(ordered)))


# ---------------------------------------------------------------------------- items and result


def score_inputs(record: Mapping[str, object]) -> s63.ScoreInputs:
    """The inputs the `s63-v3` calculator got for this record, rebuilt from its stored facts."""
    series = series_of(record)
    report = _mapping(record, "report")
    discipline = _mapping(record, "discipline")
    sharpe_diff = rank_counts_of(record, "sharpeDiff")
    end_multiple = rank_counts_of(record, "endMultiple")
    breach = report.get("riskBreachTurns")
    if breach is not None and (not isinstance(breach, int) or isinstance(breach, bool)):
        raise ComponentError("riskBreachTurns must be an integer")
    ratios = (
        _optional(discipline.get("panic"), "discipline.panic"),
        _optional(discipline.get("chase"), "discipline.chase"),
    )
    return s63.ScoreInputs(
        series.returns,
        series.benchmark_returns,
        series.turns,
        breach,
        _optional(report.get("riskBreachDepth"), "riskBreachDepth"),
        _optional(report.get("riskLimit"), "riskLimit"),
        _optional(report.get("maxSingleWeight"), "maxSingleWeight"),
        _optional(report.get("turnoverAnnual"), "turnoverAnnual"),
        _optional(report.get("measuredFormationDeviation"), "measuredFormationDeviation"),
        _optional(report.get("roleFit"), "roleFit"),
        _optional(report.get("nudgeShare"), "nudgeShare"),
        _optional(record.get("noiseShare"), "noiseShare"),
        None if ratios == (None, None) else s63.DisciplineRatios(*ratios),
        sharpe_diff,
        end_multiple,
    )


def item_scores(inputs: s63.ScoreInputs) -> dict[str, Decimal | None]:
    """Unrounded process item scores in 0..100; None where the item has no value."""
    s63._validate(inputs)
    return {
        "risk": s63._risk(inputs),
        "discipline": s63._discipline(inputs.discipline),
        "diversification": s63._diversification(inputs.max_single_weight),
        "turnover": s63._turnover(inputs.turnover_annual),
        "consistency": None,
        "formation": s63._formation(inputs.measured_formation_deviation),
        "role": s63._role(inputs.role_fit),
        "noise": s63._noise(inputs.noise_share),
        "nudge": s63._nudge(inputs.nudge_share),
    }


def process_exact(items: Mapping[str, Decimal | None]) -> Decimal:
    """Equal-weight mean of the items that have a value, unrounded."""
    present = [v for v in items.values() if v is not None]
    if not present:
        raise ComponentError("at least one process item is needed")
    return s63._mean(present)


def result_exact(inputs: s63.ScoreInputs) -> Decimal | None:
    """100 x the mid-rank percentile of the Sharpe difference among the engine paths."""
    if inputs.sharpe_diff_counts is None:
        return None
    return _X.multiply(_HUNDRED, s63.percentile_exact(inputs.sharpe_diff_counts))


# ---------------------------------------------------------------------------- components


@dataclass(frozen=True)
class Components:
    world_id: str
    path_length: int
    kind: str
    index: int
    cagr: Decimal
    bench_cagr: Decimal
    excess_cagr: Decimal
    sharpe: Decimal
    bench_sharpe: Decimal
    sharpe_diff: Decimal
    sortino: Decimal
    bench_sortino: Decimal
    sortino_diff: Decimal
    calmar: Decimal
    bench_calmar: Decimal
    calmar_diff: Decimal
    mdd: Decimal
    bench_mdd: Decimal
    items: Mapping[str, Decimal | None]
    process: Decimal
    result: Decimal | None

    def metric(self, name: str) -> Decimal:
        """The value ranked against a reference set; every metric is better when higher."""
        value = self.items["risk"] if name == "risk_item" else getattr(self, name, None)
        if value is None:
            raise ComponentError(f"{name} has no value for {self.kind} bot {self.index}")
        return value

    def to_wire(self) -> dict[str, object]:
        """Strings rounded to 12 places (items and parts too); the exact values stay in memory."""
        scalars = (
            "cagr",
            "bench_cagr",
            "excess_cagr",
            "sharpe",
            "bench_sharpe",
            "sharpe_diff",
            "sortino",
            "bench_sortino",
            "sortino_diff",
            "calmar",
            "bench_calmar",
            "calmar_diff",
            "mdd",
            "bench_mdd",
            "process",
        )
        doc: dict[str, object] = {
            _camel(name): plain(money.round12(getattr(self, name))) for name in scalars
        }
        doc["result"] = None if self.result is None else plain(money.round12(self.result))
        doc["items"] = {
            name: None if value is None else plain(money.round12(value))
            for name, value in self.items.items()
        }
        return doc


def components_of(record: Mapping[str, object]) -> Components:
    series = series_of(record)
    inputs = score_inputs(record)
    initial, final = series.initial_cash, series.totals[-1]
    growth = cagr(initial, final, series.turns)
    bench_multiples = compound(series.benchmark_returns)
    bench_growth = cagr(_ONE, bench_multiples[-1], series.turns)
    drawdown = max_drawdown(initial, series.totals[1:])
    bench_drawdown = max_drawdown(_ONE, bench_multiples)
    sharpe = s63.sharpe_exact(series.returns)
    bench_sharpe = s63.sharpe_exact(series.benchmark_returns)
    sort, bench_sort = sortino(series.returns), sortino(series.benchmark_returns)
    calm, bench_calm = calmar(growth, drawdown), calmar(bench_growth, bench_drawdown)
    items = item_scores(inputs)
    return Components(
        series.world_id,
        series.turns,
        series.kind,
        series.index,
        growth,
        bench_growth,
        _X.subtract(growth, bench_growth),
        sharpe,
        bench_sharpe,
        _X.subtract(sharpe, bench_sharpe),
        sort,
        bench_sort,
        _X.subtract(sort, bench_sort),
        calm,
        bench_calm,
        _X.subtract(calm, bench_calm),
        drawdown,
        bench_drawdown,
        items,
        process_exact(items),
        result_exact(inputs),
    )


# ---------------------------------------------------------------------------- reference set


@dataclass(frozen=True)
class ReferenceSet:
    """Sorted metric values of the reference random bots of one world."""

    world_id: str
    path_length: int
    size: int
    values: Mapping[str, tuple[Decimal, ...]]

    @classmethod
    def from_components(
        cls, bots: Sequence[Components], *, expected_size: int | None = None
    ) -> Self:
        if not bots:
            raise ComponentError("a reference set must not be empty")
        if expected_size is not None and len(bots) != expected_size:
            raise ComponentError(f"a reference set has {expected_size} bots, got {len(bots)}")
        first = bots[0]
        if len({b.index for b in bots}) != len(bots):
            raise ComponentError("a reference bot appears twice")
        for bot in bots:
            if bot.kind != "random":
                raise ComponentError("reference bots are random bots")
            if (bot.world_id, bot.path_length) != (first.world_id, first.path_length):
                raise ComponentError("reference bots must share one world")
        values = {m: tuple(sorted(b.metric(m) for b in bots)) for m in REFERENCE_METRICS}
        return cls(first.world_id, first.path_length, len(bots), values)

    def percentile(self, metric: str, value: Decimal) -> Decimal:
        return _percentile(value, self.values[metric])
