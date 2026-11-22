"""Constrained weight-grid search over stored score parts, and the per-cell analysis.

The grid is the whole search space: S weights in steps of 0.05, each at least 0.05, process between
0.15 and 0.50; s63 result weight between 0.30 and 0.70. A point passes when C1, C3, C4 and C5 pass
on the training worlds (even world indices); the selected point is the passing one nearest to the
draft weights, never the one with the largest separation. Selection reads training worlds only;
odd worlds and the held-out bots (C2, C6, C8) check the selected point afterwards.

The grid screen runs on float64 numpy; the selected point, the draft and every reported number are
recomputed exactly in `Decimal` through `checks.evaluate_checks`.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from decimal import Decimal

import numpy as np

from .checks import (
    FORMULA_63,
    FORMULA_S,
    STAT_SCALE,
    Bootstrap,
    BotRow,
    CheckError,
    Formula,
    WorldTable,
    career_checks,
    dose_points,
    evaluate_checks,
    named_everywhere,
    ordered_worlds,
    parts_rows,
    rank_correlation,
    static_calibration,
    world_tables,
)
from .composite import SWeights, Weights63

UNITS = 20
EPS = 1e-7
TOLERANCE = 1000
STEP = Decimal("0.05")
SELECTION_CHECKS = ("C1", "C3", "C4", "C5")
HELD_OUT_CHECKS = ("C2", "C6")
OUTCOME_PROPOSAL = "proposal"
OUTCOME_NONE_PASSING = "no-passing-point"
OUTCOME_NOT_ROBUST = "no-robust-point"

_DRAFT_UNITS = {FORMULA_63.name: (10, 10), FORMULA_S.name: (6, 6, 3, 5)}
_CONSTRAINTS = {
    FORMULA_63.name: "result 0.30..0.70, process the rest, step 0.05",
    FORMULA_S.name: "each weight >= 0.05, process 0.15..0.50, step 0.05, sum 1",
}


class SearchError(CheckError):
    """A table the search cannot run on."""


# ---------------------------------------------------------------------------- grid


def draft_units(formula: Formula) -> tuple[int, ...]:
    return _DRAFT_UNITS[formula.name]


def grid_units(formula: Formula) -> tuple[tuple[int, ...], ...]:
    """Every grid point in units of 0.05, in lexicographic order."""
    if formula.name == FORMULA_63.name:
        return tuple((result, UNITS - result) for result in range(6, 15))
    points = []
    for process in range(3, 11):
        for ret in range(1, UNITS - process):
            for risk_adjusted in range(1, UNITS - process - ret):
                risk = UNITS - process - ret - risk_adjusted
                if risk >= 1:
                    points.append((ret, risk_adjusted, risk, process))
    return tuple(sorted(points))


def weights_of(formula: Formula, units: Sequence[int]) -> tuple[Decimal, ...]:
    """Exact weights of a grid point, validated by the constraint classes of `composite`."""
    weights = tuple(Decimal(u) * STEP for u in units)
    if formula.name == FORMULA_63.name:
        Weights63(*weights)
    else:
        SWeights(*weights)
    return weights


def wire_weights(formula: Formula, weights: Sequence[Decimal]) -> dict[str, str]:
    return {name: format(w, "f") for name, w in zip(formula.parts, weights, strict=True)}


def distance_squared(units: Sequence[int], draft: Sequence[int]) -> int:
    return sum((a - b) ** 2 for a, b in zip(units, draft, strict=True))


# ---------------------------------------------------------------------------- float screen


def _require_selectable(worlds: Sequence[WorldTable]) -> list[tuple[int, str]]:
    if not worlds:
        raise SearchError("no worlds to screen")
    if not (named_everywhere(worlds, "disciplined") and named_everywhere(worlds, "news")):
        raise SearchError("the search needs a disciplined and a news bot in every world")
    points = dose_points(worlds)
    if len(points) < 3:
        raise SearchError("the search needs at least three dose points for C5")
    return points


def _array(parts) -> np.ndarray:
    return np.array([[float(p) for p in row.parts] for row in parts])


def screen(
    worlds: Sequence[WorldTable], formula: Formula, units: Sequence[Sequence[int]], boot: Bootstrap
) -> np.ndarray:
    """Flags of C1, C3, C4, C5 for every grid point: a (points, 4) bool array (float64).

    A superset screen: every threshold is relaxed by a small margin, so a rejected point also
    fails the exact checks and the exact walk over flagged points finds the exact selection.
    """
    worlds = ordered_worlds(worlds)
    doses = _require_selectable(worlds)
    weights = np.array(units, dtype=float).T / UNITS
    medians, d_delta, n_delta, d_pct, n_pct = [], [], [], [], []
    dose_scores: list[list[np.ndarray]] = [[] for _ in doses]
    for world in worlds:
        evaluated = _array(world.eval_random) @ weights
        median = np.median(evaluated, axis=0)
        medians.append(median)
        for target, delta, pct in (
            ("disciplined", d_delta, d_pct),
            ("news", n_delta, n_pct),
        ):
            value = _array([world.named[target]]) @ weights
            value = value[0]
            delta.append(value - median)
            # bounds of the mid-rank percentile: optimistic for the disciplined bot (more
            # random bots count as below), pessimistic for the news bot
            shift = EPS if target == "disciplined" else -EPS
            pct.append(100.0 * (evaluated < value + shift).sum(axis=0) / evaluated.shape[0])
        for slot, (_, kind) in enumerate(doses):
            dose_scores[slot].append((_array([world.named[kind]]) @ weights)[0])
    n_worlds = len(worlds)
    limit = n_worlds * STAT_SCALE
    tol = n_worlds * TOLERANCE

    def scaled(stack: list[np.ndarray]) -> np.ndarray:
        return np.rint(np.stack(stack) * STAT_SCALE).astype(np.int64)

    r_int, d_int, n_int = scaled(medians), scaled(d_delta), scaled(n_delta)
    r_lo, r_hi = boot.interval_sums(r_int)
    d_lo, _ = boot.interval_sums(d_int)
    _, n_hi = boot.interval_sums(n_int)
    r_sum, d_sum, n_sum = r_int.sum(axis=0), d_int.sum(axis=0), n_int.sum(axis=0)
    c1 = (
        (r_sum >= 45 * limit - tol)
        & (r_sum <= 55 * limit + tol)
        & (r_lo >= 40 * limit - tol)
        & (r_hi <= 60 * limit + tol)
    )
    c3 = (
        (d_sum >= 10 * limit - tol)
        & (d_lo > -tol)
        & (np.median(np.stack(d_pct), axis=0) >= 75.0 - EPS)
    )
    c4 = (
        (n_sum <= -10 * limit + tol)
        & (n_hi < tol)
        & (np.median(np.stack(n_pct), axis=0) <= 25.0 + EPS)
    )
    means = np.stack([np.stack(s).mean(axis=0) for s in dose_scores])
    c5 = np.all(np.diff(means, axis=0) < EPS, axis=0)
    return np.stack([c1, c3, c4, c5], axis=1)


# ---------------------------------------------------------------------------- search


@dataclass(frozen=True)
class SearchResult:
    formula: str
    outcome: str
    proposal_units: tuple[int, ...] | None
    doc: dict[str, object]


def _passes(checks: dict[str, dict[str, object]], ids: Sequence[str]) -> bool:
    return all(checks[c]["passed"] is True for c in ids)


def _select(train: Sequence[WorldTable], formula: Formula, master_seed: int):
    """Draft if it passes exactly, else the nearest passing grid point; training worlds only."""
    boot = Bootstrap(master_seed, len(train))
    units = grid_units(formula)
    draft = draft_units(formula)
    flags = screen(train, formula, units, boot).all(axis=1)
    draft_checks = evaluate_checks(train, formula, weights_of(formula, draft), boot)
    if _passes(draft_checks, SELECTION_CHECKS):
        return draft, draft_checks, int(flags.sum())
    order = sorted(
        (i for i in range(len(units)) if flags[i]),
        key=lambda i: (distance_squared(units[i], draft), units[i]),
    )
    for i in order:
        checks = evaluate_checks(train, formula, weights_of(formula, units[i]), boot)
        if _passes(checks, SELECTION_CHECKS):
            return units[i], checks, int(flags.sum())
    return None, None, int(flags.sum())


def search_weights(
    worlds: Sequence[WorldTable], formula: Formula, master_seed: int
) -> SearchResult:
    worlds = ordered_worlds(worlds)
    train = tuple(w for w in worlds if w.world % 2 == 0)
    test = tuple(w for w in worlds if w.world % 2 == 1)
    if not train or not test:
        raise SearchError("both world halves need at least one world")
    train_boot, test_boot = Bootstrap(master_seed, len(train)), Bootstrap(master_seed, len(test))
    units = grid_units(formula)
    draft = draft_units(formula)
    draft_weights = weights_of(formula, draft)
    selected, selected_train, passing_train = _select(train, formula, master_seed)
    passing_test = int(screen(test, formula, units, test_boot).all(axis=1).sum())

    draft_train = evaluate_checks(train, formula, draft_weights, train_boot)
    draft_test = evaluate_checks(test, formula, draft_weights, test_boot)
    doc: dict[str, object] = {
        "formula": formula.name,
        "grid": {
            "step": format(STEP, "f"),
            "points": len(units),
            "constraints": _CONSTRAINTS[formula.name],
        },
        "worlds": {"train": len(train), "test": len(test)},
        "screenPassing": {"train": passing_train, "test": passing_test},
        "draft": {
            "weights": wire_weights(formula, draft_weights),
            "train": draft_train,
            "test": draft_test,
            "passesSelection": _passes(draft_train, SELECTION_CHECKS),
        },
        "selected": None,
        "heldOut": None,
        "proposal": None,
    }
    if selected is None or selected_train is None:
        doc["outcome"] = OUTCOME_NONE_PASSING
        return SearchResult(formula.name, OUTCOME_NONE_PASSING, None, doc)

    weights = weights_of(formula, selected)
    selected_test = evaluate_checks(test, formula, weights, test_boot)
    distance = distance_squared(selected, draft)
    doc["selected"] = {
        "weights": wire_weights(formula, weights),
        "distanceSquaredSteps": distance,
        "draftKept": distance == 0,
        "train": selected_train,
        "test": selected_test,
    }
    failed = [
        c for c in SELECTION_CHECKS + HELD_OUT_CHECKS if selected_test[c]["passed"] is not True
    ]
    robust = not failed
    doc["heldOut"] = {"robust": robust, "failed": failed}
    if robust:
        doc["outcome"] = OUTCOME_PROPOSAL
        doc["proposal"] = {"weights": wire_weights(formula, weights)}
        return SearchResult(formula.name, OUTCOME_PROPOSAL, selected, doc)
    doc["outcome"] = OUTCOME_NOT_ROBUST
    return SearchResult(formula.name, OUTCOME_NOT_ROBUST, None, doc)


# ---------------------------------------------------------------------------- cell analysis


def analyze_cell(
    rows: Sequence[BotRow], master_seed: int, *, reference_size: int | None = None
) -> dict[str, object]:
    """Checks, search, calibration, careers and correlation of one experiment cell."""
    formulas: list[dict[str, object]] = []
    searches: dict[str, object] = {}
    tables_by_formula = {}
    weights_by_formula: dict[str, dict[str, tuple[Decimal, ...]]] = {}
    world_ids: set[int] = set()
    for formula in (FORMULA_63, FORMULA_S):
        parts = parts_rows(formula, rows, reference_size=reference_size)
        tables = world_tables(parts)
        tables_by_formula[formula.name] = (parts, tables)
        world_ids = {t.world for t in tables}
        result = search_weights(tables, formula, master_seed)
        searches[formula.name] = result.doc
        chosen = {"draft": weights_of(formula, draft_units(formula))}
        if result.proposal_units is not None:
            chosen["proposal"] = weights_of(formula, result.proposal_units)
        weights_by_formula[formula.name] = chosen
        boot = Bootstrap(master_seed, len(tables))
        train = tuple(t for t in tables if t.world % 2 == 0)
        for role, weights in chosen.items():
            formulas.append(
                {
                    "name": f"{formula.name}-{role}",
                    "formula": formula.name,
                    "role": role,
                    "weights": wire_weights(formula, weights),
                    "checks": evaluate_checks(tables, formula, weights, boot),
                    "careers": career_checks(train, weights, master_seed),
                }
            )
    parts_63, _ = tables_by_formula[FORMULA_63.name]
    parts_s, _ = tables_by_formula[FORMULA_S.name]
    correlation = {
        "draft": rank_correlation(
            parts_63,
            weights_by_formula[FORMULA_63.name]["draft"],
            parts_s,
            weights_by_formula[FORMULA_S.name]["draft"],
        )
    }
    if all("proposal" in w for w in weights_by_formula.values()):
        correlation["proposal"] = rank_correlation(
            parts_63,
            weights_by_formula[FORMULA_63.name]["proposal"],
            parts_s,
            weights_by_formula[FORMULA_S.name]["proposal"],
        )
    return {
        "worlds": {
            "total": len(world_ids),
            "train": sum(1 for w in world_ids if w % 2 == 0),
            "test": sum(1 for w in world_ids if w % 2 == 1),
        },
        "formulas": formulas,
        "search": searches,
        "calibration": static_calibration(rows),
        "correlation": correlation,
    }
