"""Slow brute-force reference for `m2-sb6-v1`, written from the plan's normative algorithm only.

It shares nothing with the production module except SplitMix64 and the replay upper bound: no
buckets, run tables, prefix sums or byte scans. Every candidate set is rebuilt with plain set
operations, so a bug in the fast code cannot also be in here.
"""

from datetime import date
from fractions import Fraction

from antlab_engine.bootstrap.simple import replay_upper_bound
from antlab_engine.bootstrap.splitmix64 import SplitMix64


class TooLong(Exception):
    """The reference could not build a path (fewer months than turns)."""


def idx(d: date) -> int:
    return d.year * 12 + d.month - 1


def month_of(i: int) -> date:
    year, month0 = divmod(i, 12)
    return date(year, month0 + 1, 1)


def candidate_sets(
    avail: set[int], ell: int, used: set[int], cont: int | None, turn_month: int
) -> dict[str, list[int]]:
    """Eligible starts per tier for a run of `ell` months at a turn whose calendar month is 1..12.

    A start is eligible when the whole run is available and unused and the start is not the
    month right after the previous block (`cont`).
    """
    free = {s for s in avail if all(s + k in avail and s + k not in used for k in range(ell))}
    if cont is not None:
        free.discard(cont)
    aligned = {s for s in free if s % 12 + 1 == turn_month}
    near_months = {turn_month, (turn_month - 2) % 12 + 1, turn_month % 12 + 1}
    near = {s for s in free if s % 12 + 1 in near_months}
    return {"A": sorted(aligned), "N": sorted(near), "F": sorted(free)}


def min_overlap(candidates: list[int], ell: int, recent: set[int]) -> list[int]:
    """Candidates whose run holds the fewest recent months, ascending."""
    overlap = {s: len(set(range(s, s + ell)) & recent) for s in candidates}
    least = min(overlap.values())
    return [s for s in candidates if overlap[s] == least]


def generate(
    months,
    base_date: date,
    length: int,
    seed: int,
    recent_months=(),
    max_overlap=Fraction(1),
    replay_start: date = date(2007, 1, 1),
    mean: int = 6,
    cap: int = 24,
    min_candidates: int = 3,
    attempts: int = 32,
):
    """Returns (months, blocks, overlap, met, attempt); blocks are (start, drawn, length, tier)."""
    lo = idx(replay_start)
    hi = idx(replay_upper_bound(base_date))
    avail = {idx(m) for m in months if lo <= idx(m) < hi}
    recent = {idx(m) for m in recent_months}
    rng = SplitMix64(seed)
    scaled = Fraction(max_overlap) * length
    limit = scaled.numerator // scaled.denominator
    if len(avail) < length:
        raise TooLong
    best = None
    for attempt in range(attempts):
        used: set[int] = set()
        path: list[int] = []
        blocks = []
        previous_end = None
        turn = 0
        while turn < length:
            drawn = 1
            while drawn < min(cap, length - turn) and rng.next_int(mean) != 0:
                drawn += 1
            ell = drawn
            cont = None if previous_end is None else previous_end + 1
            placed = False
            while ell >= 1:
                sets = candidate_sets(avail, ell, used, cont, turn % 12 + 1)
                chosen = next((t for t in "ANF" if len(sets[t]) >= min_candidates), None)
                if chosen is None:
                    chosen = next((t for t in "ANF" if sets[t]), None)
                if chosen is None:
                    ell -= 1
                    continue
                kept = min_overlap(sets[chosen], ell, recent)
                start = kept[rng.next_int(len(kept))]
                path.extend(range(start, start + ell))
                used.update(range(start, start + ell))
                blocks.append((turn, drawn, ell, chosen))
                previous_end = start + ell - 1
                turn += ell
                placed = True
                break
            if not placed:
                if cont is None or cont not in avail or cont in used:
                    raise TooLong
                path.append(cont)
                used.add(cont)
                blocks.append((turn, drawn, 1, "J"))
                previous_end = cont
                turn += 1
        overlap = len(set(path) & recent)
        result = (tuple(month_of(i) for i in path), tuple(blocks), overlap, attempt)
        if best is None or overlap < best[2]:
            best = result
        if overlap <= limit:
            break
    assert best is not None
    path_months, blocks_out, overlap, attempt = best
    return path_months, blocks_out, overlap, overlap <= limit, attempt
