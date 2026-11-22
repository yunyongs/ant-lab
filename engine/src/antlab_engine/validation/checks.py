"""Checks C1-C8, static calibration, correlation and career analogues of the score validation.

The unit of independence is the world. Every statistic is taken per world, then averaged over
worlds, and the 95 % interval is a percentile bootstrap that resamples world positions with
SplitMix64 draws from the master seed. Interval arithmetic runs on integers (statistics scaled to
1e-9), so thresholds such as "interval lower bound > 0" have no floating-point noise.

Scores here are the unrounded weighted sums of a formula's parts. Pure module: no files, no
database; numpy is used only for the bootstrap and the career analogue.
"""

import re
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal

import numpy as np

from antlab_engine.bots.rng import BotRng, mix
from antlab_engine.sim import money
from antlab_engine.sim.report import plain

from .components import Components, ReferenceSet, mid_rank_percentile
from .composite import CAREER_SEASONS, s_parts

RESAMPLES = 2000
TAIL_PER_MILLE = 25
INTERVAL_RULE = {
    "method": "percentile",
    "unit": "world",
    "resamples": RESAMPLES,
    "orderStatistics": [
        RESAMPLES * TAIL_PER_MILLE // 1000 + 1,
        RESAMPLES - RESAMPLES * TAIL_PER_MILLE // 1000,
    ],
    "note": "bounds are on per-world statistics rounded to 1e-9; means are exact",
}
STAT_SCALE = 10**9
BOOTSTRAP_TAG = 0x626F6F74
CAREER_TAG = 0x63617265
CAREER_SUBSETS = 1000

ROLE_REF = "ref"
ROLE_EVAL = "eval"
ROLE_NAMED = "named"
ROLES = (ROLE_REF, ROLE_EVAL, ROLE_NAMED)

_X = money.EXACT
_ZERO = Decimal(0)
_HALF = Decimal("0.5")
_SCALE = Decimal(STAT_SCALE)
_NEWSMIX = re.compile(r"newsmix-(\d{1,3})")

C1_MEAN = (Decimal(45), Decimal(55))
C1_INTERVAL = (Decimal(40), Decimal(60))
C2_MEAN = (Decimal(-10), Decimal(10))
C3_MEAN = Decimal(10)
C3_PERCENTILE = Decimal(75)
C4_MEAN = Decimal(-10)
C4_PERCENTILE = Decimal(25)
C5_RHO = Decimal("-0.9")
C5_MIN_POINTS = 3
C8_MEAN = Decimal(3)
CALIBRATION_MEAN = (Decimal(45), Decimal(55))
KS_CRITICAL_5PCT = Decimal("1.358")


class CheckError(ValueError):
    """A table that cannot be checked."""


@dataclass(frozen=True)
class Formula:
    """A weighted sum of named parts; `result_mix` selects the result-only parts for C7."""

    name: str
    parts: tuple[str, ...]
    result_mix: tuple[Decimal, ...]


FORMULA_63 = Formula("s63", ("result", "process"), (Decimal(1), Decimal(0)))
FORMULA_S = Formula(
    "s",
    ("return", "riskAdjusted", "risk", "process"),
    (_HALF, _HALF, _ZERO, _ZERO),
)


@dataclass(frozen=True)
class BotRow:
    """One bot of one world: its components and whether it is a reference bot."""

    world: int
    role: str
    components: Components


@dataclass(frozen=True)
class PartsRow:
    world: int
    kind: str
    index: int
    parts: tuple[Decimal, ...]


@dataclass(frozen=True)
class WorldTable:
    """The bots of one world, grouped for the checks."""

    world: int
    eval_random: tuple[PartsRow, ...]
    static: tuple[PartsRow, ...]
    named: Mapping[str, PartsRow]
    redeclare: tuple[tuple[PartsRow, PartsRow], ...]


# ---------------------------------------------------------------------------- tables


def parts_rows(
    formula: Formula, rows: Sequence[BotRow], *, reference_size: int | None = None
) -> tuple[PartsRow, ...]:
    """Parts of every non-reference bot; S parts are ranked against the world's reference bots."""
    by_world: dict[int, list[BotRow]] = defaultdict(list)
    for row in rows:
        if row.role not in ROLES:
            raise CheckError(f"unknown role {row.role!r}")
        by_world[row.world].append(row)
    out: list[PartsRow] = []
    for world in sorted(by_world):
        items = by_world[world]
        references = [r.components for r in items if r.role == ROLE_REF]
        evaluated = [r.components for r in items if r.role != ROLE_REF]
        _check_world(world, references, evaluated)
        reference = None
        if formula.name == FORMULA_S.name:
            reference = ReferenceSet.from_components(references, expected_size=reference_size)
        for comp in evaluated:
            out.append(PartsRow(world, comp.kind, comp.index, _parts(formula, comp, reference)))
    return tuple(out)


def _check_world(world: int, references: Sequence[Components], evaluated: Sequence[Components]):
    if any(c.kind != "random" for c in references):
        raise CheckError(f"world {world}: reference bots are random bots")
    seen = set()
    for comp in evaluated:
        key = (comp.kind, comp.index)
        if key in seen:
            raise CheckError(f"world {world}: {comp.kind} bot {comp.index} appears twice")
        seen.add(key)
    reference_ids = {c.index for c in references}
    if any(c.kind == "random" and c.index in reference_ids for c in evaluated):
        raise CheckError(f"world {world}: a reference bot is also an evaluation bot")


def _parts(
    formula: Formula, comp: Components, reference: ReferenceSet | None
) -> tuple[Decimal, ...]:
    if formula.name == FORMULA_63.name:
        if comp.result is None:
            raise CheckError(f"{comp.kind} bot {comp.index} has no result score")
        return (comp.result, comp.process)
    if reference is None:
        raise CheckError("S needs a reference set")
    parts = s_parts(comp, reference)
    return (parts.ret, parts.risk_adjusted, parts.risk, parts.process)


def world_tables(parts: Sequence[PartsRow]) -> tuple[WorldTable, ...]:
    by_world: dict[int, list[PartsRow]] = defaultdict(list)
    for row in parts:
        by_world[row.world].append(row)
    tables = []
    for world in sorted(by_world):
        evaluated: list[PartsRow] = []
        static: list[PartsRow] = []
        redeclared: list[PartsRow] = []
        named: dict[str, PartsRow] = {}
        for row in sorted(by_world[world], key=lambda r: (r.kind, r.index)):
            if row.kind == "random":
                evaluated.append(row)
            elif row.kind == "static":
                static.append(row)
            elif row.kind == "redeclare":
                redeclared.append(row)
            elif row.kind in named:
                raise CheckError(f"world {world}: {row.kind} appears twice")
            else:
                named[row.kind] = row
        if not evaluated:
            raise CheckError(f"world {world}: no evaluation random bots")
        by_index = {r.index: r for r in evaluated}
        pairs = []
        for row in redeclared:
            if row.index not in by_index:
                raise CheckError(f"world {world}: redeclare {row.index} has no paired random bot")
            pairs.append((row, by_index[row.index]))
        tables.append(WorldTable(world, tuple(evaluated), tuple(static), named, tuple(pairs)))
    return tuple(tables)


# ---------------------------------------------------------------------------- arithmetic


def score(parts: Sequence[Decimal], weights: Sequence[Decimal]) -> Decimal:
    """Unrounded weighted sum."""
    if len(parts) != len(weights):
        raise CheckError("weights and parts differ in length")
    total = _ZERO
    for part, weight in zip(parts, weights, strict=True):
        total = _X.add(total, _X.multiply(part, weight))
    return total


def mean_of(values: Sequence[Decimal]) -> Decimal:
    if not values:
        raise CheckError("a mean needs at least one value")
    total = _ZERO
    for value in values:
        total = _X.add(total, value)
    return money.divide_significant(total, Decimal(len(values)))


def median_of(values: Sequence[Decimal]) -> Decimal:
    if not values:
        raise CheckError("a median needs at least one value")
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return _X.multiply(_X.add(ordered[mid - 1], ordered[mid]), _HALF)


mid_rank = mid_rank_percentile


def scaled(value: Decimal) -> int:
    return int(_X.multiply(value, _SCALE).to_integral_value(rounding=ROUND_HALF_EVEN))


def num(value: Decimal) -> str:
    return plain(money.round12(value))


def ranks(values: Sequence[Decimal]) -> list[Decimal]:
    """1-based ranks, ties share the average rank."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    out = [_ZERO] * len(values)
    start = 0
    while start < len(order):
        stop = start
        while stop + 1 < len(order) and values[order[stop + 1]] == values[order[start]]:
            stop += 1
        shared = _X.multiply(Decimal(start + stop + 2), _HALF)
        for position in range(start, stop + 1):
            out[order[position]] = shared
        start = stop + 1
    return out


def spearman(xs: Sequence[Decimal], ys: Sequence[Decimal]) -> Decimal | None:
    """Pearson correlation of the ranks; None when either side is constant."""
    if len(xs) != len(ys) or len(xs) < 2:
        raise CheckError("a rank correlation needs two equal series of at least two values")
    rx, ry = ranks(xs), ranks(ys)
    centre = _X.multiply(Decimal(len(xs) + 1), _HALF)
    sxy = sxx = syy = _ZERO
    for a, b in zip(rx, ry, strict=True):
        da, db = _X.subtract(a, centre), _X.subtract(b, centre)
        sxy = _X.add(sxy, _X.multiply(da, db))
        sxx = _X.add(sxx, _X.multiply(da, da))
        syy = _X.add(syy, _X.multiply(db, db))
    if sxx == 0 or syy == 0:
        return None
    return money.round12(
        money.divide_significant(sxy, money.sqrt_significant(_X.multiply(sxx, syy)))
    )


# ---------------------------------------------------------------------------- bootstrap


class Bootstrap:
    """Resample counts over `worlds` positions, drawn once from the master seed."""

    def __init__(self, master_seed: int, worlds: int, resamples: int = RESAMPLES) -> None:
        if worlds < 1:
            raise CheckError("a bootstrap needs at least one world")
        rng = BotRng(mix(master_seed, BOOTSTRAP_TAG, worlds))
        counts = np.zeros((resamples, worlds), dtype=np.int64)
        for row in range(resamples):
            for _ in range(worlds):
                counts[row, rng.below(worlds)] += 1
        self.worlds = worlds
        self.resamples = resamples
        self.counts = counts
        self.tail = resamples * TAIL_PER_MILLE // 1000

    def interval_sums(self, stats: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Lower and upper percentile of the resampled sums of scaled statistics.

        `stats` is (worlds,) or (worlds, grid) int64; the sums are `worlds` times the means.
        """
        if stats.shape[0] != self.worlds:
            raise CheckError("one statistic per world is needed")
        ordered = np.sort(self.counts @ stats, axis=0)
        return ordered[self.tail], ordered[self.resamples - 1 - self.tail]

    def interval(self, values: Sequence[Decimal]) -> tuple[Decimal, Decimal]:
        lower, upper = self.interval_sums(np.array([scaled(v) for v in values], dtype=np.int64))
        divisor = Decimal(self.worlds * STAT_SCALE)
        return (
            money.divide_significant(Decimal(int(lower)), divisor),
            money.divide_significant(Decimal(int(upper)), divisor),
        )


# ---------------------------------------------------------------------------- C1-C8


def _check(check_id: str, name: str, **fields: object) -> dict[str, object]:
    return {"id": check_id, "name": name, **fields}


def _unavailable(check_id: str, name: str, reason: str) -> dict[str, object]:
    return _check(check_id, name, available=False, passed=None, reason=reason)


def named_everywhere(worlds: Sequence[WorldTable], kind: str) -> bool:
    present = [kind in w.named for w in worlds]
    if any(present) and not all(present):
        raise CheckError(f"{kind} is missing in some worlds")
    return all(present)


def ordered_worlds(worlds: Sequence[WorldTable]) -> tuple[WorldTable, ...]:
    """Worlds sorted by id: bootstrap positions must not depend on the caller's order."""
    if not worlds:
        raise CheckError("no worlds to check")
    ordered = tuple(sorted(worlds, key=lambda w: w.world))
    if len({w.world for w in ordered}) != len(ordered):
        raise CheckError("a world appears twice")
    return ordered


def dose_points(worlds: Sequence[WorldTable]) -> list[tuple[int, str]]:
    kinds = {"disciplined": 0, "news": 100}
    for world in worlds:
        for kind in world.named:
            match = _NEWSMIX.fullmatch(kind)
            if match:
                kinds[kind] = int(match.group(1))
    points = []
    for kind, dose in kinds.items():
        if named_everywhere(worlds, kind):
            points.append((dose, kind))
    return sorted(points)


def evaluate_checks(
    worlds: Sequence[WorldTable],
    formula: Formula,
    weights: Sequence[Decimal],
    bootstrap: Bootstrap,
) -> dict[str, dict[str, object]]:
    """C1-C8 of one formula at exact weights over the given worlds (Decimal throughout)."""
    worlds = ordered_worlds(worlds)
    if bootstrap.worlds != len(worlds):
        raise CheckError("the bootstrap was drawn for a different number of worlds")
    if len(weights) != len(formula.parts):
        raise CheckError("weights and parts differ in length")
    mix_weights = formula.result_mix
    scores = [[score(r.parts, weights) for r in w.eval_random] for w in worlds]
    medians = [median_of(s) for s in scores]
    out: dict[str, dict[str, object]] = {}
    out["C1"] = _c1(medians, bootstrap)
    out["C2"] = _c2(worlds, weights, medians)
    have_disciplined = named_everywhere(worlds, "disciplined")
    have_news = named_everywhere(worlds, "news")
    if have_disciplined:
        out["C3"] = _c3(worlds, weights, scores, medians, bootstrap)
    else:
        out["C3"] = _unavailable("C3", "disciplined high", "no disciplined bot")
    if have_news:
        out["C4"] = _c4(worlds, weights, scores, medians, bootstrap)
    else:
        out["C4"] = _unavailable("C4", "news low", "no news bot")
    out["C5"] = _c5(worlds, weights)
    out["C6"] = _c6(worlds, weights, medians)
    if have_news:
        out["C7"] = _c7(worlds, mix_weights, bootstrap)
    else:
        out["C7"] = _unavailable("C7", "news on result only", "no news bot")
    out["C8"] = _c8(worlds, weights, bootstrap)
    return out


def _c1(medians: Sequence[Decimal], boot: Bootstrap) -> dict[str, object]:
    mean = mean_of(medians)
    lower, upper = boot.interval(medians)
    passed = (
        C1_MEAN[0] <= mean <= C1_MEAN[1] and lower >= C1_INTERVAL[0] and upper <= C1_INTERVAL[1]
    )
    return _check(
        "C1",
        "random middle",
        available=True,
        passed=passed,
        mean=num(mean),
        ci=[num(lower), num(upper)],
        rule="mean in [45, 55] and interval inside [40, 60]",
    )


def _c2(worlds: Sequence[WorldTable], weights, medians) -> dict[str, object]:
    if not all(w.static for w in worlds):
        return _unavailable("C2", "static middle", "no static bots")
    deltas = [
        _X.subtract(median_of([score(r.parts, weights) for r in w.static]), m)
        for w, m in zip(worlds, medians, strict=True)
    ]
    mean = mean_of(deltas)
    return _check(
        "C2",
        "static middle",
        available=True,
        passed=C2_MEAN[0] <= mean <= C2_MEAN[1],
        mean=num(mean),
        rule="mean static minus random in [-10, 10]",
    )


def _named_deltas(worlds, weights, medians, kind):
    return [
        _X.subtract(score(w.named[kind].parts, weights), m)
        for w, m in zip(worlds, medians, strict=True)
    ]


def _percentiles(worlds, weights, scores, kind):
    return [
        mid_rank(score(w.named[kind].parts, weights), s)
        for w, s in zip(worlds, scores, strict=True)
    ]


def _c3(worlds, weights, scores, medians, boot) -> dict[str, object]:
    deltas = _named_deltas(worlds, weights, medians, "disciplined")
    mean = mean_of(deltas)
    lower, upper = boot.interval(deltas)
    median_percentile = median_of(_percentiles(worlds, weights, scores, "disciplined"))
    passed = mean >= C3_MEAN and lower > 0 and median_percentile >= C3_PERCENTILE
    return _check(
        "C3",
        "disciplined high",
        available=True,
        passed=passed,
        mean=num(mean),
        ci=[num(lower), num(upper)],
        medianPercentile=num(median_percentile),
        rule="mean >= 10, interval lower > 0, median percentile >= 75",
    )


def _c4(worlds, weights, scores, medians, boot) -> dict[str, object]:
    deltas = _named_deltas(worlds, weights, medians, "news")
    mean = mean_of(deltas)
    lower, upper = boot.interval(deltas)
    median_percentile = median_of(_percentiles(worlds, weights, scores, "news"))
    passed = mean <= C4_MEAN and upper < 0 and median_percentile <= C4_PERCENTILE
    return _check(
        "C4",
        "news low",
        available=True,
        passed=passed,
        mean=num(mean),
        ci=[num(lower), num(upper)],
        medianPercentile=num(median_percentile),
        rule="mean <= -10, interval upper < 0, median percentile <= 25",
    )


def _c5(worlds, weights) -> dict[str, object]:
    points = dose_points(worlds)
    if len(points) < C5_MIN_POINTS:
        return _unavailable("C5", "dose response", "fewer than three dose points")
    means = [mean_of([score(w.named[kind].parts, weights) for w in worlds]) for _, kind in points]
    decreasing = all(a > b for a, b in zip(means, means[1:], strict=False))
    rho = spearman([Decimal(dose) for dose, _ in points], means)
    return _check(
        "C5",
        "dose response",
        available=True,
        passed=decreasing and rho is not None and rho <= C5_RHO,
        doses=[dose for dose, _ in points],
        means=[num(m) for m in means],
        rho=None if rho is None else num(rho),
        rule="world-mean score strictly decreasing in the news dose and rho <= -0.9",
    )


def _c6(worlds, weights, medians) -> dict[str, object]:
    if not named_everywhere(worlds, "disciplined-noguard"):
        return _unavailable("C6", "held-out disciplined", "no disciplined-noguard bot")
    mean = mean_of(_named_deltas(worlds, weights, medians, "disciplined-noguard"))
    return _check(
        "C6",
        "held-out disciplined",
        available=True,
        passed=mean > 0,
        mean=num(mean),
        rule="mean disciplined-noguard minus random > 0",
    )


def _result_only(parts: Sequence[Decimal], mix_weights: Sequence[Decimal]) -> Decimal:
    return score(parts, mix_weights)


def _c7(worlds, mix_weights, boot) -> dict[str, object]:
    deltas = []
    for w in worlds:
        random_part = median_of([_result_only(r.parts, mix_weights) for r in w.eval_random])
        news_part = _result_only(w.named["news"].parts, mix_weights)
        deltas.append(_X.subtract(news_part, random_part))
    mean = mean_of(deltas)
    lower, upper = boot.interval(deltas)
    flagged = lower > 0
    return _check(
        "C7",
        "news on result only",
        available=True,
        passed=not flagged,
        flagged=flagged,
        mean=num(mean),
        ci=[num(lower), num(upper)],
        rule="reported; flagged when the interval lower bound is above 0",
    )


def _c8(worlds, weights, boot) -> dict[str, object]:
    if not all(w.redeclare for w in worlds):
        return _unavailable("C8", "redeclare exploit", "no redeclare bots")
    deltas = [
        mean_of(
            [
                _X.subtract(score(red.parts, weights), score(rnd.parts, weights))
                for red, rnd in w.redeclare
            ]
        )
        for w in worlds
    ]
    mean = mean_of(deltas)
    lower, upper = boot.interval(deltas)
    flagged = mean > C8_MEAN and lower > 0
    return _check(
        "C8",
        "redeclare exploit",
        available=True,
        passed=not flagged,
        flagged=flagged,
        mean=num(mean),
        ci=[num(lower), num(upper)],
        rule="reported; flagged when the mean is above 3 and the interval lower bound above 0",
    )


# ---------------------------------------------------------------------------- calibration


def static_calibration(rows: Sequence[BotRow]) -> dict[str, object]:
    """Weight-independent: the static bot's s63 result percentile should be uniform."""
    statics = [r for r in rows if r.components.kind == "static" and r.components.result is not None]
    if not statics:
        return {"available": False, "reason": "no static bot with a result score"}
    values = [r.components.result for r in statics]
    mean = mean_of(values)
    histogram = [0] * 10
    for value in values:
        histogram[min(int(value // 10), 9)] += 1
    first: dict[int, tuple[int, Decimal]] = {}
    for r in statics:
        held = first.get(r.world)
        if held is None or r.components.index < held[0]:
            first[r.world] = (r.components.index, r.components.result)
    ordered = sorted(result for _, result in first.values())
    n = len(ordered)
    statistic = _ZERO
    for i, value in enumerate(ordered):
        u = money.score_divide(value, Decimal(100))
        above = _X.subtract(money.score_divide(Decimal(i + 1), Decimal(n)), u)
        below = _X.subtract(u, money.score_divide(Decimal(i), Decimal(n)))
        statistic = max(statistic, above, below)
    critical = money.divide_significant(KS_CRITICAL_5PCT, money.sqrt_significant(Decimal(n)))
    mean_ok = CALIBRATION_MEAN[0] <= mean <= CALIBRATION_MEAN[1]
    ks_ok = statistic <= critical
    return {
        "available": True,
        "statics": len(statics),
        "worlds": n,
        "mean": num(mean),
        "decileCounts": histogram,
        "ks": {"statistic": num(statistic), "critical": num(critical), "passed": ks_ok},
        "meanPassed": mean_ok,
        "passed": mean_ok and ks_ok,
        "rule": "mean in [45, 55] and KS statistic of the first static per world below critical",
    }


def rank_correlation(
    first: Sequence[PartsRow],
    first_weights: Sequence[Decimal],
    second: Sequence[PartsRow],
    second_weights: Sequence[Decimal],
) -> dict[str, object]:
    """Spearman correlation of two formulas over the bots both scored."""
    other = {(r.world, r.kind, r.index): r for r in second}
    xs, ys = [], []
    for row in first:
        match = other.get((row.world, row.kind, row.index))
        if match is not None:
            xs.append(score(row.parts, first_weights))
            ys.append(score(match.parts, second_weights))
    rho = spearman(xs, ys) if len(xs) >= 2 else None
    return {"n": len(xs), "rho": None if rho is None else num(rho)}


# ---------------------------------------------------------------------------- careers


def career_subsets(master_seed: int, worlds: int, subsets: int = CAREER_SUBSETS) -> np.ndarray:
    """Seeded subsets of `CAREER_SEASONS` distinct world positions (partial Fisher-Yates)."""
    if worlds < CAREER_SEASONS:
        raise CheckError(f"a career needs {CAREER_SEASONS} worlds")
    out = np.zeros((subsets, CAREER_SEASONS), dtype=np.int64)
    for s in range(subsets):
        rng = BotRng(mix(master_seed, CAREER_TAG, worlds, s))
        pool = list(range(worlds))
        for i in range(CAREER_SEASONS):
            j = i + rng.below(worlds - i)
            pool[i], pool[j] = pool[j], pool[i]
            out[s, i] = pool[i]
    return out


def _fixed(value: float) -> str:
    text = format(value, ".6f")
    return text[1:] if text.startswith("-") and float(text) == 0 else text


def career_checks(
    worlds: Sequence[WorldTable],
    weights: Sequence[Decimal],
    master_seed: int,
    *,
    subsets: int = CAREER_SUBSETS,
) -> dict[str, object]:
    """C1, C3 and C4 analogues on L over seeded 10-world subsets (float64, diagnostic only).

    Subsets overlap, so no interval is reported; the rules use the point values.
    """
    worlds = ordered_worlds(worlds)
    if len(worlds) < CAREER_SEASONS:
        return {"available": False, "reason": f"fewer than {CAREER_SEASONS} worlds"}
    if not (named_everywhere(worlds, "disciplined") and named_everywhere(worlds, "news")):
        return {"available": False, "reason": "disciplined and news bots are needed"}
    indices = sorted(set.intersection(*({r.index for r in w.eval_random} for w in worlds)))
    if not indices:
        return {"available": False, "reason": "no evaluation bot is present in every world"}
    w_float = np.array([float(x) for x in weights])

    def column(rows: Sequence[PartsRow]) -> np.ndarray:
        by_index = {r.index: r for r in rows}
        return np.array([[float(p) for p in by_index[i].parts] for i in indices]) @ w_float

    eval_scores = np.stack([column(w.eval_random) for w in worlds])
    disciplined = np.array([float(score(w.named["disciplined"].parts, weights)) for w in worlds])
    news = np.array([float(score(w.named["news"].parts, weights)) for w in worlds])
    chosen = career_subsets(master_seed, len(worlds), subsets)

    def career(values: np.ndarray) -> np.ndarray:
        ordered = np.sort(values[chosen], axis=1)
        trimmed = ordered[:, 1 : CAREER_SEASONS - 1].mean(axis=1)
        return trimmed - 0.5 * values[chosen].std(axis=1, ddof=1)

    random_l = np.stack([career(eval_scores[:, k]) for k in range(len(indices))], axis=1)
    random_median = np.median(random_l, axis=1)
    disciplined_l, news_l = career(disciplined), career(news)

    def percentile(value: np.ndarray) -> np.ndarray:
        below = (random_l < value[:, None]).sum(axis=1)
        equal = (random_l == value[:, None]).sum(axis=1)
        return 100.0 * (below + 0.5 * equal) / random_l.shape[1]

    r_mean = float(random_median.mean())
    d_mean = float((disciplined_l - random_median).mean())
    n_mean = float((news_l - random_median).mean())
    d_pct = float(np.median(percentile(disciplined_l)))
    n_pct = float(np.median(percentile(news_l)))
    checks = {
        "C1": {"mean": _fixed(r_mean), "passed": 45.0 <= r_mean <= 55.0},
        "C3": {
            "mean": _fixed(d_mean),
            "medianPercentile": _fixed(d_pct),
            "passed": d_mean >= 10.0 and d_pct >= 75.0,
        },
        "C4": {
            "mean": _fixed(n_mean),
            "medianPercentile": _fixed(n_pct),
            "passed": n_mean <= -10.0 and n_pct <= 25.0,
        },
    }
    return {
        "available": True,
        "subsets": subsets,
        "worlds": len(worlds),
        "bots": len(indices),
        "medianL": {
            "random": _fixed(float(np.median(random_median))),
            "disciplined": _fixed(float(np.median(disciplined_l))),
            "news": _fixed(float(np.median(news_l))),
        },
        "checks": checks,
        "rule": "same thresholds as C1, C3, C4 on L; point values, subsets overlap",
    }
