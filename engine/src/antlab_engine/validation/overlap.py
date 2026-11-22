"""Overlap analysis of the section 6.3 process items over bot populations (`process-overlap/1`).

For every item pair the statistic is taken per world (Spearman rho, Kendall tau-b), averaged over
the worlds that can carry it, and given a 95 % percentile bootstrap over worlds. A mechanical rule
turns the numbers into pair and item verdicts. Which bots enter a correlation is fixed by rule:
`random` (roles ref and eval, from `validation-component/1` lines) and `trait` (from
`process-overlap-trait/1` lines); static, redeclare and named bots are never correlated.

Pure module: no files, no database, no network; numpy is used for the Kendall counts only.
"""

from collections import Counter
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal

import numpy as np

from antlab_engine.sim import money

from .checks import (
    INTERVAL_RULE,
    RESAMPLES,
    ROLE_EVAL,
    ROLE_NAMED,
    ROLE_REF,
    Bootstrap,
    mean_of,
    num,
    spearman,
)

SCHEMA = "process-overlap/1"
COMPONENT_SCHEMA = "validation-component/1"
TRAIT_SCHEMA = "process-overlap-trait/1"
STATUS_PARTIAL = "partial"
STATUS_PROVISIONAL = "provisional"
STATUS_FINAL = "final"
STATUSES = (STATUS_PARTIAL, STATUS_PROVISIONAL, STATUS_FINAL)
MAX_SUMMARY_LINES = 40

ITEMS = ("risk", "discipline", "diversification", "turnover", "formation", "role", "noise", "nudge")
CONSISTENCY = "consistency"
TRAITS = ("follow", "route", "panic", "chase", "activity", "event")
TRAIT_OF = {
    "noise": ("follow",),
    "nudge": ("route",),
    "discipline": ("panic", "chase"),
    "turnover": ("activity",),
}
MERGE_PARTNERS = {
    "noise": ("discipline", "turnover", "nudge"),
    "nudge": ("discipline", "turnover", "noise"),
}
PAIRS = tuple((a, b) for i, a in enumerate(ITEMS) for b in ITEMS[i + 1 :])

POP_RANDOM = "random"
POP_TRAIT = "trait"
KIND_RANDOM = "random"
KIND_TRAIT = "trait"
RANDOM_ROLES = (ROLE_REF, ROLE_EVAL)

OVERLAP = "OVERLAP"
PARTIAL = "PARTIAL"
DISTINCT = "DISTINCT"
CONFLICT = "CONFLICT"
NOT_ASSESSABLE = "NOT_ASSESSABLE"
MERGE = "MERGE"
KEEP = "KEEP"
UNDECIDED = "UNDECIDED"

MIN_BOTS = 30
ASSESSABLE_SHARE = (4, 5)
TIE_SHARE_LIMIT = (1, 2)
TRACK_MIN = Decimal("0.50")
TRACK_MARGIN = Decimal("0.20")
EVENT_NOT_NEEDED = Decimal("0.80")
EVENT_NEEDED = Decimal("0.50")
EVENT_TRACK_LIMIT = Decimal("0.30")

_X = money.EXACT
_ZERO = Decimal(0)
_ONE = Decimal(1)


@dataclass(frozen=True)
class Thresholds:
    overlap: Decimal
    overlap_lower: Decimal
    distinct_upper: Decimal
    conflict: Decimal
    conflict_upper: Decimal


SPEARMAN = Thresholds(
    Decimal("0.70"), Decimal("0.60"), Decimal("0.50"), Decimal("-0.70"), Decimal("-0.60")
)
KENDALL = Thresholds(
    Decimal("0.49"), Decimal("0.41"), Decimal("0.33"), Decimal("-0.49"), Decimal("-0.41")
)

REASON_ORDER = ("no input", "constant", "few bots", "ties", "worlds")
STYLE_MISSING = (
    "master library data (EXT-10)",
    "master bots (M3-07)",
    "asset_fundamental",
    "a season style choice in the simulator",
)


class OverlapError(ValueError):
    """Lines or a document that do not match the overlap input or `process-overlap/1`."""


# ---------------------------------------------------------------------------- statistics


def _dense(values: Sequence[Decimal]) -> list[int]:
    order = {value: rank for rank, value in enumerate(sorted(set(values)))}
    return [order[v] for v in values]


def kendall_counts(xs: Sequence[Decimal], ys: Sequence[Decimal]) -> tuple[int, int, int]:
    """Exact integers (concordant minus discordant, x-untied pairs, y-untied pairs).

    Pairs are unordered; a pair tied in either series adds nothing to the first count.
    """
    if len(xs) != len(ys):
        raise OverlapError("two series of one length are needed")
    if len(xs) < 2:
        return 0, 0, 0
    rx = np.array(_dense(xs), dtype=np.int64)
    ry = np.array(_dense(ys), dtype=np.int64)
    sx = np.sign(rx[:, None] - rx[None, :]).astype(np.int8)
    sy = np.sign(ry[:, None] - ry[None, :]).astype(np.int8)
    ordered = int(np.sum(sx.astype(np.int64) * sy, dtype=np.int64))
    return ordered // 2, int(np.count_nonzero(sx)) // 2, int(np.count_nonzero(sy)) // 2


def kendall_tau_b(xs: Sequence[Decimal], ys: Sequence[Decimal]) -> Decimal | None:
    """tau-b from integer counts and one division; None when either side is constant."""
    diff, untied_x, untied_y = kendall_counts(xs, ys)
    if untied_x == 0 or untied_y == 0:
        return None
    root = money.sqrt_significant(Decimal(untied_x * untied_y))
    return money.round12(money.divide_significant(Decimal(diff), root))


def _largest_tie(values: Sequence[Decimal]) -> int:
    return max(Counter(values).values()) if values else 0


def unusable_reason(xs: Sequence[Decimal], ys: Sequence[Decimal]) -> str | None:
    """None when a world can carry the pair, else why not."""
    n = len(xs)
    if n == 0:
        return "no input"
    top_x, top_y = _largest_tie(xs), _largest_tie(ys)
    if top_x == n or top_y == n:
        return "constant"
    if n < MIN_BOTS:
        return "few bots"
    limit_n, limit_d = TIE_SHARE_LIMIT
    if top_x * limit_d > n * limit_n or top_y * limit_d > n * limit_n:
        return "ties"
    return None


def assessable(usable: int, total: int) -> bool:
    share_n, share_d = ASSESSABLE_SHARE
    return total > 0 and usable * share_d >= total * share_n


class Boots:
    """Bootstrap objects by world count, so equal counts share one draw from the master seed."""

    def __init__(self, master_seed: int) -> None:
        self.master_seed = master_seed
        self._by_count: dict[int, Bootstrap] = {}

    def interval(self, values: Sequence[Decimal]) -> tuple[Decimal, Decimal]:
        count = len(values)
        if count not in self._by_count:
            self._by_count[count] = Bootstrap(self.master_seed, count, RESAMPLES)
        return self._by_count[count].interval(values)


def _share(count: int, total: int) -> Decimal:
    return money.divide_significant(Decimal(count), Decimal(total))


def pair_verdict(
    rho: Decimal | None,
    lower: Decimal | None,
    upper: Decimal | None,
    *,
    is_assessable: bool,
    thresholds: Thresholds = SPEARMAN,
) -> str:
    """OVERLAP / DISTINCT / CONFLICT / PARTIAL / NOT_ASSESSABLE for a mean and its interval."""
    if not is_assessable or rho is None or lower is None or upper is None:
        return NOT_ASSESSABLE
    if rho >= thresholds.overlap and lower >= thresholds.overlap_lower:
        return OVERLAP
    if rho <= thresholds.conflict and upper <= thresholds.conflict_upper:
        return CONFLICT
    if upper < thresholds.distinct_upper and rho > thresholds.conflict:
        return DISTINCT
    return PARTIAL


@dataclass(frozen=True)
class PairStat:
    x: str
    y: str
    usable: int
    total: int
    is_assessable: bool
    reason: str | None
    rho: Decimal | None
    rho_lower: Decimal | None
    rho_upper: Decimal | None
    sign_share: Decimal | None
    tau: Decimal | None
    tau_lower: Decimal | None
    tau_upper: Decimal | None
    pooled: Decimal | None
    verdict: str
    tau_verdict: str

    @property
    def sensitive(self) -> bool:
        return self.is_assessable and self.verdict != self.tau_verdict


def pair_stats(
    worlds: Sequence[tuple[Sequence[Decimal], Sequence[Decimal]]],
    total_worlds: int,
    boots: Boots,
    x: str = "x",
    y: str = "y",
) -> PairStat:
    """Per-world statistics of one pair over the cell's worlds (non-null pairs per world).

    The verdict rests on the mean of the per-world statistics and a bootstrap over worlds; the
    pooled-rows correlation is reported but never used.
    """
    rhos: list[Decimal] = []
    taus: list[Decimal] = []
    pooled_x: list[Decimal] = []
    pooled_y: list[Decimal] = []
    reasons: Counter[str] = Counter()
    for xs, ys in worlds:
        reason = unusable_reason(xs, ys)
        if reason is None:
            rho, tau = spearman(xs, ys), kendall_tau_b(xs, ys)
            if rho is not None and tau is not None:
                rhos.append(rho)
                taus.append(tau)
                pooled_x += xs
                pooled_y += ys
                continue
            reason = "constant"
        reasons[reason] += 1
    reasons["worlds"] += max(total_worlds - len(worlds), 0)
    usable = len(rhos)
    ok = assessable(usable, total_worlds)
    why = None
    if not ok:
        why = (
            "worlds"
            if not reasons
            else max(REASON_ORDER, key=lambda r: (reasons.get(r, 0), -REASON_ORDER.index(r)))
        )
    if usable == 0:
        return PairStat(
            x, y, 0, total_worlds, False, why, *([None] * 8), NOT_ASSESSABLE, NOT_ASSESSABLE
        )
    rho_mean, tau_mean = mean_of(rhos), mean_of(taus)
    rho_lo, rho_hi = boots.interval(rhos)
    tau_lo, tau_hi = boots.interval(taus)
    positive = sum(1 for r in rhos if r > 0)
    return PairStat(
        x,
        y,
        usable,
        total_worlds,
        ok,
        why,
        rho_mean,
        rho_lo,
        rho_hi,
        _share(positive, usable),
        tau_mean,
        tau_lo,
        tau_hi,
        spearman(pooled_x, pooled_y),
        pair_verdict(rho_mean, rho_lo, rho_hi, is_assessable=ok),
        pair_verdict(tau_mean, tau_lo, tau_hi, is_assessable=ok, thresholds=KENDALL),
    )


# ---------------------------------------------------------------------------- populations


@dataclass(frozen=True)
class Row:
    kind: str
    index: int
    items: Mapping[str, Decimal | None]
    traits: Mapping[str, Decimal]
    flags: Mapping[str, int]


@dataclass(frozen=True)
class Population:
    name: str
    worlds: Mapping[int, tuple[Row, ...]]

    @property
    def bots(self) -> int:
        return sum(len(rows) for rows in self.worlds.values())


def _decimal(value: object, where: str) -> Decimal:
    if not isinstance(value, str):
        raise OverlapError(f"{where} must be a decimal string")
    try:
        number = Decimal(value)
    except ArithmeticError:
        raise OverlapError(f"{where} is not a decimal: {value!r}") from None
    if not number.is_finite():
        raise OverlapError(f"{where} is not finite")
    return number


def _mapping(doc: Mapping[str, object], key: str, where: str) -> Mapping[str, object]:
    value = doc.get(key)
    if not isinstance(value, Mapping):
        raise OverlapError(f"{where} lacks {key}")
    return value


def _row(line: Mapping[str, object]) -> tuple[int, str, str, Row]:
    world, kind, index, role = (line.get(k) for k in ("worldIndex", "kind", "index", "role"))
    if type(world) is not int or not isinstance(kind, str) or type(index) is not int:
        raise OverlapError("a line lacks worldIndex, kind or index")
    if not isinstance(role, str):
        raise OverlapError("a line lacks role")
    raw = _mapping(_mapping(line, "components", "a line"), "items", "components")
    items: dict[str, Decimal | None] = {}
    for name in (*ITEMS, CONSISTENCY):
        if name not in raw:
            raise OverlapError(f"components.items lacks {name}")
        value = raw[name]
        items[name] = None if value is None else _decimal(value, f"items.{name}")
    traits: dict[str, Decimal] = {}
    if line.get("schema") == TRAIT_SCHEMA:
        given = _mapping(line, "traits", "a trait line")
        traits = {t: _decimal(given.get(t), f"traits.{t}") for t in TRAITS}
    flags: dict[str, int] = {}
    if isinstance(line.get("flags"), Mapping):
        for name, value in line["flags"].items():  # type: ignore[union-attr]
            if not isinstance(value, str) or not (value.isascii() and value.isdigit()):
                raise OverlapError(f"flags.{name} must be an integer string")
            flags[name] = int(value)
    return world, kind, role, Row(kind, index, items, traits, flags)


def _freeze(grouped: Mapping[int, list[Row]]) -> dict[int, tuple[Row, ...]]:
    return {
        world: tuple(sorted(rows, key=lambda r: (r.kind, r.index)))
        for world, rows in sorted(grouped.items())
    }


def populations(lines: Iterable[Mapping[str, object]]) -> dict[str, Population]:
    """Build the correlation populations and the descriptive `named` family from lines.

    `random`: kind random, roles ref and eval, component lines only. `trait`: kind trait of trait
    lines, present only when such lines exist. `named`: role named, descriptive. Static,
    redeclare and every other kind are in none of them.
    """
    groups: dict[str, dict[int, list[Row]]] = {POP_RANDOM: {}, POP_TRAIT: {}, "named": {}}
    flagged: dict[int, list[Row]] = {}
    for line in lines:
        schema = line.get("schema")
        if schema not in (COMPONENT_SCHEMA, TRAIT_SCHEMA):
            raise OverlapError(f"unknown line schema {schema!r}")
        world, kind, role, row = _row(line)
        if schema == TRAIT_SCHEMA:
            if kind == KIND_TRAIT:
                groups[POP_TRAIT].setdefault(world, []).append(row)
            if row.flags and kind in (KIND_TRAIT, KIND_RANDOM):
                flagged.setdefault(world, []).append(row)
        elif kind == KIND_RANDOM and role in RANDOM_ROLES:
            groups[POP_RANDOM].setdefault(world, []).append(row)
        elif role == ROLE_NAMED:
            groups["named"].setdefault(world, []).append(row)
    found = {
        name: Population(name, _freeze(grouped))
        for name, grouped in groups.items()
        if grouped or name != POP_TRAIT
    }
    if flagged:
        found["flagged"] = Population("flagged", _freeze(flagged))
    return found


# ---------------------------------------------------------------------------- one population


def _vectors(rows: Sequence[Row], x: str, y: str) -> tuple[list[Decimal], list[Decimal]]:
    xs: list[Decimal] = []
    ys: list[Decimal] = []
    for row in rows:
        a = row.items[x]
        b = row.items[y] if y in row.items else row.traits.get(y)
        if a is not None and b is not None:
            xs.append(a)
            ys.append(b)
    return xs, ys


def item_stats(pop: Population) -> dict[str, dict[str, object]]:
    stats: dict[str, dict[str, object]] = {}
    for item in (*ITEMS, CONSISTENCY):
        count = constant = 0
        shares: list[Decimal] = []
        for rows in pop.worlds.values():
            values = [r.items[item] for r in rows if r.items[item] is not None]
            if not values:
                continue
            count += len(values)
            top = _largest_tie(values)  # type: ignore[arg-type]
            constant += top == len(values)
            shares.append(_share(top, len(values)))
        stats[item] = {
            "nonNull": count,
            "tieShareMean": None if not shares else num(mean_of(shares)),
            "constantWorlds": constant,
        }
    return stats


def trait_correlations(
    pop: Population, total_worlds: int
) -> dict[str, dict[str, tuple[Decimal | None, int, bool]]]:
    """Mean per-world Spearman of every item against every trait: (rho, usable, assessable)."""
    out: dict[str, dict[str, tuple[Decimal | None, int, bool]]] = {}
    for item in ITEMS:
        out[item] = {}
        for trait in TRAITS:
            rhos = []
            for rows in pop.worlds.values():
                xs, ys = _vectors(rows, item, trait)
                if unusable_reason(xs, ys) is None:
                    rho = spearman(xs, ys)
                    if rho is not None:
                        rhos.append(rho)
            ok = assessable(len(rhos), total_worlds)
            out[item][trait] = (mean_of(rhos) if ok else None, len(rhos), ok)
    return out


def tracking_of(
    item: str, correlations: Mapping[str, Mapping[str, tuple[Decimal | None, int, bool]]]
) -> dict[str, object] | None:
    """Does the item fall when its own trait rises, clearly more than for any other trait?

    None for items without a trait. `tracks` is None when the own trait is not assessable.
    """
    own_traits = TRAIT_OF.get(item)
    if own_traits is None:
        return None
    row = correlations[item]
    own = [-row[t][0] for t in own_traits if row[t][0] is not None]  # type: ignore[operator]
    other = [-row[t][0] for t in TRAITS if t not in own_traits and row[t][0] is not None]  # type: ignore[operator]
    best_own = max(own) if own else None
    best_other = max(other) if other else None
    tracks: bool | None = None
    if best_own is not None:
        tracks = best_own >= TRACK_MIN and (
            best_other is None or best_own - best_other >= TRACK_MARGIN
        )
    return {"traits": list(own_traits), "tracks": tracks, "own": best_own, "other": best_other}


def item_verdicts(
    trait_pairs: Mapping[tuple[str, str], PairStat] | None,
    random_pairs: Mapping[tuple[str, str], PairStat],
    tracking: Mapping[str, Mapping[str, object] | None],
) -> dict[str, dict[str, object]]:
    """MERGE / KEEP / UNDECIDED / NOT_ASSESSABLE per measurable item.

    Decided on the trait population; the random population is context (it only adds the
    no-overlap condition for items without a trait). With no trait population an item with some
    assessable pair is UNDECIDED, never decided on the random bots.
    """
    out: dict[str, dict[str, object]] = {}
    for item in ITEMS:
        mine_r = [s for (a, b), s in random_pairs.items() if item in (a, b)]
        mine_t = (
            []
            if trait_pairs is None
            else [s for (a, b), s in trait_pairs.items() if item in (a, b)]
        )
        if all(s.verdict == NOT_ASSESSABLE for s in (*mine_r, *mine_t)):
            reasons = sorted(
                {s.reason or "worlds" for s in (*mine_r, *mine_t)}, key=REASON_ORDER.index
            )
            out[item] = {"verdict": NOT_ASSESSABLE, "partner": None, "reasons": reasons}
            continue
        if trait_pairs is None:
            out[item] = {"verdict": UNDECIDED, "partner": None, "reasons": ["no trait population"]}
            continue
        overlaps = [s for s in mine_t if s.verdict == OVERLAP]
        info = tracking.get(item)
        tracks = None if info is None else info["tracks"]
        allowed = MERGE_PARTNERS.get(item)
        partners = [
            (s.rho, s.y if s.x == item else s.x)
            for s in overlaps
            if allowed is None or (s.y if s.x == item else s.x) in allowed
        ]
        reasons: list[str] = []
        verdict, partner = UNDECIDED, None
        if partners and (info is None or tracks is False):
            verdict = MERGE
            partner = max(partners, key=lambda p: (p[0], p[1]))[1]  # type: ignore[type-var]
            reasons.append("overlap and no own-trait tracking" if info else "overlap")
        elif not overlaps and info is not None and tracks is True:
            verdict = KEEP
            reasons.append("tracks its trait, no overlap")
        elif not overlaps and info is None and not any(s.verdict == OVERLAP for s in mine_r):
            verdict = KEEP
            reasons.append("no overlap in the trait and random populations")
        else:
            if overlaps:
                reasons.append(
                    "overlap present" + (" while tracking" if tracks else "")
                    if partners or allowed is None
                    else "overlap only with a non-partner item"
                )
            if info is not None and tracks is not True:
                reasons.append(
                    "own trait not tracked" if tracks is False else "tracking not assessable"
                )
            if info is None and not overlaps:
                reasons.append("overlap in the random population")
        out[item] = {"verdict": verdict, "partner": partner, "reasons": reasons}
    return _resolve_merges(out)


def _resolve_merges(out: dict[str, dict[str, object]]) -> dict[str, dict[str, object]]:
    """Two items never merge into each other and a merge target never merges away itself.

    Judged on the verdicts before this pass; which of two overlapping items survives is left to
    the proposal that follows (M3-08).
    """
    merged = {k: v["partner"] for k, v in out.items() if v["verdict"] == MERGE}
    for item, target in merged.items():
        if merged.get(target) == item:
            out[item] = {
                "verdict": UNDECIDED,
                "partner": None,
                "reasons": ["mutual overlap"],
                "overlapWith": target,
            }
        elif target in merged:
            out[item] = {
                "verdict": UNDECIDED,
                "partner": None,
                "reasons": ["merge chain"],
                "overlapWith": target,
            }
    return out


def event_verdict(source: str, coverage: Decimal | None, max_tracking: Decimal | None) -> str:
    """Whether section 4.11 event response needs its own score item."""
    if source != "db":
        return "MECHANICS_ONLY"
    if coverage is None:
        return UNDECIDED
    if coverage >= EVENT_NOT_NEEDED:
        return "NOT_NEEDED"
    if coverage < EVENT_NEEDED and max_tracking is not None and max_tracking < EVENT_TRACK_LIMIT:
        return "NEEDED"
    return UNDECIDED


# ---------------------------------------------------------------------------- documents


def _text(value: Decimal | None) -> str | None:
    return None if value is None else num(value)


def _pair_doc(stat: PairStat) -> dict[str, object]:
    return {
        "x": stat.x,
        "y": stat.y,
        "usableWorlds": stat.usable,
        "assessable": stat.is_assessable,
        "reason": stat.reason,
        "rho": _text(stat.rho),
        "rhoLower": _text(stat.rho_lower),
        "rhoUpper": _text(stat.rho_upper),
        "signShare": _text(stat.sign_share),
        "tau": _text(stat.tau),
        "tauLower": _text(stat.tau_lower),
        "tauUpper": _text(stat.tau_upper),
        "pooledRho": _text(stat.pooled),
        "verdict": stat.verdict,
        "tauVerdict": stat.tau_verdict,
        "sensitive": stat.sensitive,
    }


def _population_pairs(pop: Population, total: int, boots: Boots) -> dict[tuple[str, str], PairStat]:
    stats = {}
    for x, y in PAIRS:
        worlds = [_vectors(rows, x, y) for rows in pop.worlds.values()]
        stats[(x, y)] = pair_stats(worlds, total, boots, x, y)
    return stats


def _named_doc(pop: Population) -> list[dict[str, object]]:
    by_kind: dict[str, dict[str, list[Decimal]]] = {}
    for rows in pop.worlds.values():
        for row in rows:
            slot = by_kind.setdefault(row.kind, {name: [] for name in (*ITEMS, CONSISTENCY)})
            for name, value in row.items.items():
                if value is not None:
                    slot[name].append(value)
    return [
        {
            "kind": kind,
            "items": {n: None if not v else num(mean_of(v)) for n, v in slot.items()},
        }
        for kind, slot in sorted(by_kind.items())
    ]


def _flag_coverage(pop: Population | None) -> tuple[Decimal | None, int, int]:
    caught = direction = 0
    if pop is not None:
        for rows in pop.worlds.values():
            for row in rows:
                caught += row.flags.get("eventCaught", 0)
                direction += row.flags.get("eventDirection", 0)
    return (None if direction == 0 else _share(caught, direction)), caught, direction


def analyze_cell(
    descriptor: Mapping[str, object],
    lines: Iterable[Mapping[str, object]],
    boots: Boots,
    *,
    partial: bool = False,
) -> dict[str, object]:
    """One cell's document; `lines` is read once. A partial cell is judged on the worlds it has."""
    declared = descriptor["worlds"]
    if type(declared) is not int or declared < 1:
        raise OverlapError("a cell descriptor needs a positive world count")
    pops = populations(lines)
    analysed = sorted({w for p in pops.values() for w in p.worlds})
    total = len(analysed) if partial else declared
    docs: dict[str, object] = {}
    pairs: dict[str, dict[tuple[str, str], PairStat]] = {}
    tracking: dict[str, Mapping[str, object] | None] = {}
    correlations = None
    for name in (POP_RANDOM, POP_TRAIT):
        pop = pops.get(name)
        if pop is None:
            continue
        pairs[name] = _population_pairs(pop, total, boots)
        entry: dict[str, object] = {
            "bots": pop.bots,
            "worlds": len(pop.worlds),
            "itemStats": item_stats(pop),
            "pairs": [_pair_doc(s) for s in pairs[name].values()],
        }
        if name == POP_TRAIT:
            correlations = trait_correlations(pop, total)
            tracking = {item: tracking_of(item, correlations) for item in ITEMS}
            entry["traitCorrelations"] = {
                item: {
                    t: {"rho": _text(r), "usableWorlds": u, "assessable": a}
                    for t, (r, u, a) in row.items()
                }
                for item, row in correlations.items()
            }
            entry["tracking"] = {
                item: None
                if info is None
                else {
                    "traits": info["traits"],
                    "tracks": info["tracks"],
                    "own": _text(info["own"]),  # type: ignore[arg-type]
                    "other": _text(info["other"]),  # type: ignore[arg-type]
                }
                for item, info in tracking.items()
            }
        docs[name] = entry
    verdicts = item_verdicts(pairs.get(POP_TRAIT), pairs[POP_RANDOM], tracking)
    coverage, caught, direction = _flag_coverage(pops.get("flagged"))
    event_track = None
    if correlations is not None:
        values = [
            -rho
            for row in correlations.values()
            for t, (rho, _, _) in row.items()
            if t == "event" and rho is not None
        ]
        event_track = max(values) if values else None
    source = str(descriptor.get("source"))
    return {
        "cell": {"id": descriptor.get("id"), "source": source, "worlds": declared},
        "worlds": {"total": declared, "analysed": len(analysed)},
        "assessableBase": "analysed" if partial else "declared",
        "populations": docs,
        "named": _named_doc(pops["named"]),
        "items": verdicts,
        "eventResponse": {
            "verdict": event_verdict(source, coverage, event_track),
            "source": source,
            "coverage": _text(coverage),
            "eventCaughtWon": str(caught),
            "eventDirectionWon": str(direction),
            "maxTracking": _text(event_track),
        },
    }


def build_overlap(
    manifest: Mapping[str, object],
    profile: str | None,
    cells: Iterable[tuple[Mapping[str, object], Iterable[Mapping[str, object]]]],
    *,
    status: str,
) -> dict[str, object]:
    """Analyse every cell in turn (one cell's lines alive at a time) and assemble the document."""
    boots = Boots(int(str(manifest["masterSeed"])))
    partial = status == STATUS_PARTIAL
    analysed = [analyze_cell(d, lines, boots, partial=partial) for d, lines in cells]
    return assemble_overlap(manifest, profile, analysed, status=status)


def assemble_overlap(
    manifest: Mapping[str, object],
    profile: str | None,
    analysed: Sequence[dict[str, object]],
    *,
    status: str,
) -> dict[str, object]:
    """The `process-overlap/1` document; decided on the first cell (the primary one)."""
    if not analysed:
        raise OverlapError("an overlap analysis needs at least one cell")
    primary = analysed[0]
    items: dict[str, dict[str, object]] = {}
    for item, verdict in primary["items"].items():  # type: ignore[attr-defined]
        others = [c["items"][item]["verdict"] for c in analysed[1:]]  # type: ignore[index]
        items[item] = {
            **verdict,
            "cellSensitive": any(v != verdict["verdict"] for v in others),
        }
    items[CONSISTENCY] = {
        "verdict": NOT_ASSESSABLE,
        "partner": None,
        "reasons": ["no input"],
        "cellSensitive": False,
    }
    doc: dict[str, object] = {
        "schema": SCHEMA,
        "status": status,
        "profile": profile,
        "source": {
            key: manifest.get(key)
            for key in ("masterSeed", "gitCommit", "scoreVersion", "engineVersion", "botVersions")
        },
        "rules": _rules(),
        "primaryCell": primary["cell"]["id"],  # type: ignore[index]
        "cells": analysed,
        "items": items,
        "eventResponse": primary["eventResponse"],
        "styleAdherence": {
            "verdict": NOT_ASSESSABLE,
            "missing": list(STYLE_MISSING),
            "recommendation": "display-only",
        },
    }
    validate_overlap(doc)
    return doc


def _rules() -> dict[str, object]:
    def block(t: Thresholds) -> dict[str, str]:
        return {
            "overlap": str(t.overlap),
            "overlapLower": str(t.overlap_lower),
            "distinctUpper": str(t.distinct_upper),
            "conflict": str(t.conflict),
            "conflictUpper": str(t.conflict_upper),
        }

    return {
        "intervalRule": dict(INTERVAL_RULE),
        "tieShareLimit": "1/2",
        "assessableShare": "4/5",
        "spearman": block(SPEARMAN),
        "kendall": block(KENDALL),
        "minBots": MIN_BOTS,
        "trackMin": str(TRACK_MIN),
        "trackMargin": str(TRACK_MARGIN),
        "eventNotNeeded": str(EVENT_NOT_NEEDED),
        "eventNeeded": str(EVENT_NEEDED),
        "eventTrackLimit": str(EVENT_TRACK_LIMIT),
    }


def _leaves(value: object, path: str) -> None:
    if type(value).__name__ == "float":
        raise OverlapError(f"{path}: numbers must be strings, got a float")
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise OverlapError(f"{path}: object keys must be strings")
            _leaves(item, f"{path}.{key}")
    elif isinstance(value, list):
        for position, item in enumerate(value):
            _leaves(item, f"{path}[{position}]")
    elif value is not None and type(value) not in (str, int, bool):
        raise OverlapError(f"{path}: {type(value).__name__} is not a JSON value")


def validate_overlap(doc: Mapping[str, object]) -> None:
    for key in (
        "schema",
        "status",
        "profile",
        "source",
        "rules",
        "primaryCell",
        "cells",
        "items",
        "eventResponse",
        "styleAdherence",
    ):
        if key not in doc:
            raise OverlapError(f"overlap lacks {key}")
    if doc["schema"] != SCHEMA:
        raise OverlapError(f"schema must be {SCHEMA}")
    if doc["status"] not in STATUSES:
        raise OverlapError("status must be partial, provisional or final")
    if not isinstance(doc["cells"], list) or not doc["cells"]:
        raise OverlapError("cells must be a non-empty list")
    _leaves(doc, "overlap")


# ---------------------------------------------------------------------------- summary


def _verdict_line(item: str, entry: Mapping[str, object], tracking: object) -> str:
    text = f"- {item}: {entry['verdict']}"
    if entry.get("partner"):
        text += f" (into {entry['partner']})"
    if isinstance(tracking, Mapping):
        text += f"; tracks its trait: {str(tracking['tracks']).lower()}"
    reasons = entry.get("reasons") or []
    if reasons:
        text += "; " + ", ".join(str(r) for r in reasons)  # type: ignore[union-attr]
    if entry.get("cellSensitive"):
        text += "; cell-sensitive"
    return text


def render_summary(doc: Mapping[str, object]) -> str:
    """Fixed template, at most 40 lines, every number taken from `doc`."""
    validate_overlap(doc)
    primary = doc["cells"][0]  # type: ignore[index]
    pops = primary["populations"]
    decide = pops.get(POP_TRAIT) or pops[POP_RANDOM]
    name = POP_TRAIT if POP_TRAIT in pops else POP_RANDOM
    source = doc["source"]
    lines = [
        "# Process-item overlap",
        "",
        f"Status: {doc['status']}"
        + (f" (profile {doc['profile']})" if doc.get("profile") else "")
        + f". Source commit {source['gitCommit']}, master seed {source['masterSeed']}, "  # type: ignore[index]
        f"decided on cell {doc['primaryCell']}, {name} population.",
        "Bots are not people: verdicts say whether the items measure different behaviour.",
        "They are provisional until the real-history rerun and change no score constant.",
        "Statistic: mean of per-world Spearman rho with a bootstrap interval over worlds.",
        "",
        "## Item verdicts",
    ]
    tracking = decide.get("tracking", {})
    for item in (*ITEMS, CONSISTENCY):
        lines.append(_verdict_line(item, doc["items"][item], tracking.get(item)))  # type: ignore[index]
    lines += ["", f"## Strongest pairs ({name})"]
    shown = [p for p in decide["pairs"] if p["rho"] is not None and p["assessable"]]
    shown.sort(key=lambda p: (-abs(Decimal(p["rho"])), p["x"], p["y"]))
    for pair in shown[:5]:
        lines.append(
            f"- {pair['x']} and {pair['y']}: rho {pair['rho']} "
            f"[{pair['rhoLower']}, {pair['rhoUpper']}], tau {pair['tau']}, {pair['verdict']}"
            + ("; sensitive" if pair["sensitive"] else "")
        )
    if not shown:
        lines.append("- no assessable pair")
    event = doc["eventResponse"]
    lines += [
        "",
        "## Event response and style adherence",
        f"- Event response: {event['verdict']} (coverage {event['coverage'] or 'n/a'}, "  # type: ignore[index]
        f"source {event['source']}).",  # type: ignore[index]
        f"- Style adherence: {doc['styleAdherence']['verdict']}; stays "  # type: ignore[index]
        f"{doc['styleAdherence']['recommendation']}.",  # type: ignore[index]
    ]
    if len(lines) > MAX_SUMMARY_LINES:
        raise OverlapError(f"the summary would exceed {MAX_SUMMARY_LINES} lines")
    return "\n".join(lines) + "\n"
