"""Stationary block bootstrap `m2-sb6-v1`: calendar-aligned geometric blocks of real months.

Pure functions: no database, settings, clock or float arithmetic. The draw order below is part of
the contract; if any constant, draw or candidate rule changes, the same seed yields a different
path, so ENGINE_VERSION must change. Months are integer indexes (year * 12 + month - 1).

Per call there is one SplitMix64 stream. Each attempt builds a whole path block by block:
draw a geometric length (mean 6, cap 24), collect candidate starts whose full run is free, pick the
lowest calendar tier (ALIGNED, NEAR, FREE) with enough candidates, keep the candidates with the
fewest months of recent seasons, pick one, and shrink the length only when every tier is empty.
Months and blocks are replay-only data: they never appear in messages.

Cost: one attempt is linear in the path length times the candidate starts per block. Measured
worst inputs (120 turns, threshold 0 with every month recent, so all attempts run) take about
4 ms per attempt on 348 months, 1,200 months or a history with no consecutive months, i.e. under
0.15 s per path at the default 32 attempts; supported history is up to about 1,200 months. The
`attempts` budget is not capped here (cost grows linearly with it, 256 attempts is about 1 s);
callers that expose it must fix or bound it, and the HTTP service keeps the default 32.
"""

from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from enum import StrEnum
from fractions import Fraction

from antlab_engine.bootstrap.simple import REPLAY_START
from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.transitions import (
    POOLS,
    TransitionCounts,
    count_pairs,
    label_indexes,
    replayable_indexes,
    require_enough_labels,
)
from antlab_engine.engine_errors import InvalidArgument, PathTooLong

ENGINE_VERSION = "m2-sb6-v1"
CONDITIONAL_VERSION = "m2-sbc6-v1"
ALLOWED_LENGTHS = (12, 24, 60, 120)
SEED_MIN = -(1 << 63)
SEED_MAX = (1 << 63) - 1
INT_LIMIT = 1 << 31


class Tier(StrEnum):
    ALIGNED = "ALIGNED"
    NEAR = "NEAR"
    FREE = "FREE"
    JOIN = "JOIN"


@dataclass(frozen=True)
class BootstrapParams:
    mean_block_length: int = 6
    max_block_length: int = 24
    min_candidates: int = 3
    attempts: int = 32

    def __post_init__(self) -> None:
        for name in ("mean_block_length", "max_block_length", "min_candidates", "attempts"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int):
                raise TypeError(f"{name} must be an int")
            if value < 1:
                raise InvalidArgument(f"{name} must be at least 1")
        if self.mean_block_length >= INT_LIMIT:
            raise InvalidArgument("mean_block_length is too large")


DEFAULT_PARAMS = BootstrapParams()


@dataclass(frozen=True)
class Block:
    start_turn: int
    drawn_length: int
    length: int
    tier: Tier
    target: str | None = field(default=None, repr=False)
    matched: bool = False


@dataclass(frozen=True)
class StationaryPath:
    engine_version: str
    months: tuple[date, ...]
    blocks: tuple[Block, ...]
    overlap_months: int
    overlap_met: bool
    attempt: int


@dataclass(frozen=True)
class _Cond:
    labels: dict[int, int]
    counts: TransitionCounts


@dataclass(frozen=True)
class _Pool:
    base: int
    run: list[int]
    avail: list[int]
    aligned: list[list[int]]
    near: list[list[int]]
    recent_prefix: list[int] | None
    recent_flags: bytes


def _is_date(value: object) -> bool:
    return isinstance(value, date) and not isinstance(value, datetime)


def _index(d: date) -> int:
    return d.year * 12 + d.month - 1


def _first_of_month(index: int) -> date:
    year, month0 = divmod(index, 12)
    return date(year, month0 + 1, 1)


def _require_int(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{name} must be an int")
    return value


def _recent_indexes(recent_months: Collection[date]) -> set[int]:
    result: set[int] = set()
    for start in recent_months:
        if not _is_date(start):
            raise TypeError("recent_months must contain date values")
        if start.day != 1:
            raise InvalidArgument("invalid recent month list: a month does not start on day 1")
        result.add(_index(start))
    return result


def _pool(avail: list[int], recent: set[int]) -> _Pool:
    base = avail[0]
    span = avail[-1] - base + 1
    run = [0] * (span + 1)
    present = bytearray(span)
    for i in avail:
        present[i - base] = 1
    for offset in range(span - 1, -1, -1):
        if present[offset]:
            run[offset] = run[offset + 1] + 1
    aligned: list[list[int]] = [[] for _ in range(12)]
    for i in avail:
        aligned[i % 12].append(i)
    near = [sorted(aligned[(c - 1) % 12] + aligned[c] + aligned[(c + 1) % 12]) for c in range(12)]
    flags = bytearray(span)
    for i in recent:
        if base <= i < base + span:
            flags[i - base] = 1
    prefix: list[int] | None = None
    if any(flags):
        prefix = [0] * (span + 1)
        for offset in range(span):
            prefix[offset + 1] = prefix[offset] + flags[offset]
    return _Pool(base, run, avail, aligned, near, prefix, bytes(flags))


def _draw_length(rng: SplitMix64, params: BootstrapParams, room: int) -> int:
    cap = min(params.max_block_length, room)
    length = 1
    while length < cap and rng.next_int(params.mean_block_length) != 0:
        length += 1
    return length


def _eligible(pool: _Pool, starts: list[int], ell: int, used: bytearray, cont: int) -> list[int]:
    base = pool.base
    run = pool.run
    find = used.find
    return [
        s
        for s in starts
        if s != cont and run[s - base] >= ell and find(1, s - base, s - base + ell) < 0
    ]


def _choose_tier(
    pool: _Pool,
    ell: int,
    turn_month: int,
    used: bytearray,
    cont: int,
    params: BootstrapParams,
    counts: list[int],
) -> tuple[Tier, list[int]] | None:
    levels = (
        (Tier.ALIGNED, pool.aligned[turn_month]),
        (Tier.NEAR, pool.near[turn_month]),
        (Tier.FREE, pool.avail),
    )
    fallback: tuple[Tier, list[int]] | None = None
    for tier, starts in levels:
        candidates = _eligible(pool, starts, ell, used, cont)
        counts.append(len(candidates))
        if len(candidates) >= params.min_candidates:
            return tier, candidates
        if fallback is None and candidates:
            fallback = (tier, candidates)
    return fallback


def _draw_target(cond: _Cond, previous_end: int, rng: SplitMix64) -> int | None:
    """Pool position drawn from the previous block's last-month row; no draw without a row."""
    label = cond.labels.get(previous_end)
    if label is None:
        return None
    total = cond.counts.row_total(label)
    if total == 0:
        return None
    r = rng.next_int(total)
    running = 0
    for j, count in enumerate(cond.counts.counts[label]):
        running += count
        if r < running:
            return j
    raise AssertionError("cumulative counts exhausted")


def _choose_matched(
    pool: _Pool,
    cond: _Cond,
    target: int,
    ell: int,
    turn_month: int,
    used: bytearray,
    cont: int,
    params: BootstrapParams,
    counts: list[int],
) -> tuple[Tier, list[int], bool] | None:
    levels = (
        (Tier.ALIGNED, pool.aligned[turn_month]),
        (Tier.NEAR, pool.near[turn_month]),
        (Tier.FREE, pool.avail),
    )
    labels = cond.labels
    fallback: tuple[Tier, list[int], bool] | None = None
    for tier, starts in levels:
        candidates = _eligible(pool, starts, ell, used, cont)
        hits = [s for s in candidates if labels.get(s) == target]
        counts.append(len(hits))
        if len(hits) >= params.min_candidates:
            return tier, hits, True
        if fallback is None and hits:
            fallback = (tier, hits, True)
        counts.append(len(candidates))
        if len(candidates) >= params.min_candidates:
            return tier, candidates, False
        if fallback is None and candidates:
            fallback = (tier, candidates, False)
    return fallback


def _pick(pool: _Pool, candidates: list[int], ell: int, rng: SplitMix64) -> int:
    kept = candidates
    prefix = pool.recent_prefix
    if prefix is not None:
        base = pool.base
        overlaps = [prefix[s - base + ell] - prefix[s - base] for s in candidates]
        least = min(overlaps)
        kept = [s for s, c in zip(candidates, overlaps, strict=True) if c == least]
    return kept[rng.next_int(len(kept))]


def _attempt(
    pool: _Pool,
    length: int,
    rng: SplitMix64,
    params: BootstrapParams,
    trace: list[tuple[int, int, tuple[int, ...]]] | None,
    cond: _Cond | None = None,
) -> tuple[list[int], list[Block]]:
    base = pool.base
    used = bytearray(len(pool.run) - 1)
    path: list[int] = []
    blocks: list[Block] = []
    previous_end: int | None = None
    turn = 0
    while turn < length:
        drawn = _draw_length(rng, params, length - turn)
        cont = -1 if previous_end is None else previous_end + 1
        target = None
        if cond is not None and previous_end is not None:
            target = _draw_target(cond, previous_end, rng)
        ell = drawn
        matched = False
        chosen: tuple[Tier, list[int]] | None = None
        while ell >= 1:
            counts: list[int] = []
            if target is None or cond is None:
                chosen = _choose_tier(pool, ell, turn % 12, used, cont, params, counts)
            else:
                found = _choose_matched(
                    pool, cond, target, ell, turn % 12, used, cont, params, counts
                )
                chosen = None if found is None else (found[0], found[1])
                matched = found is not None and found[2]
            if trace is not None:
                trace.append((turn, ell, tuple(counts)))
            if chosen is not None:
                break
            ell -= 1
        if chosen is not None:
            tier, candidates = chosen
            start = _pick(pool, candidates, ell, rng)
        else:
            matched = False
            tier = Tier.JOIN
            ell = 1
            start = cont
            offset = start - base
            if previous_end is None or not 0 <= offset < len(used) or not pool.run[offset]:
                raise PathTooLong(f"The replay range cannot build a path of {length} turns")
            if used[offset]:
                raise PathTooLong(f"The replay range cannot build a path of {length} turns")
        path.extend(range(start, start + ell))
        used[start - base : start - base + ell] = b"\x01" * ell
        blocks.append(
            Block(turn, drawn, ell, tier, None if target is None else POOLS[target], matched)
        )
        previous_end = start + ell - 1
        turn += ell
    return path, blocks


def _validate(
    length: object, seed: object, max_overlap: object, replay_start: object
) -> tuple[int, int, Fraction, date]:
    length = _require_int(length, "length")
    seed = _require_int(seed, "seed")
    if length not in ALLOWED_LENGTHS:
        raise InvalidArgument("Path length must be one of 12, 24, 60, 120")
    if not SEED_MIN <= seed <= SEED_MAX:
        raise InvalidArgument("seed must fit in a signed 64-bit integer")
    if isinstance(max_overlap, bool) or not isinstance(max_overlap, Fraction | int):
        raise TypeError("max_overlap must be a Fraction")
    fraction = Fraction(max_overlap)
    if not 0 <= fraction <= 1:
        raise InvalidArgument("max_overlap must be between 0 and 1")
    if not _is_date(replay_start):
        raise TypeError("replay_start must be a date")
    return length, seed, fraction, replay_start


def _generate(
    months: Sequence[date],
    base_date: date,
    length: int,
    seed: int,
    recent_months: Collection[date],
    max_overlap: Fraction,
    replay_start: date,
    params: BootstrapParams,
    trace: list[tuple[int, int, int, tuple[int, ...]]] | None,
    labels: Mapping[date, str] | None = None,
) -> StationaryPath:
    length, seed, max_overlap, replay_start = _validate(length, seed, max_overlap, replay_start)
    avail = replayable_indexes(months, base_date, replay_start)
    recent = _recent_indexes(recent_months)
    if len(avail) < length:
        raise PathTooLong(f"The replay range cannot build a path of {length} turns")
    cond: _Cond | None = None
    if labels is not None:
        by_index = {
            i: label for i, label in label_indexes(labels, months).items() if i in set(avail)
        }
        counts = count_pairs(avail, by_index)
        require_enough_labels(counts)
        cond = _Cond(by_index, counts)
    pool = _pool(avail, recent)
    limit = (max_overlap.numerator * length) // max_overlap.denominator
    rng = SplitMix64(seed)
    best: tuple[int, int, list[int], list[Block]] | None = None
    for attempt in range(params.attempts):
        attempt_trace: list[tuple[int, int, tuple[int, ...]]] | None = None if trace is None else []
        path, blocks = _attempt(pool, length, rng, params, attempt_trace, cond)
        if trace is not None and attempt_trace is not None:
            trace.extend((attempt, t, ell, counts) for t, ell, counts in attempt_trace)
        flags = pool.recent_flags
        overlap = sum(flags[i - pool.base] for i in path) if pool.recent_prefix else 0
        if best is None or overlap < best[0]:
            best = (overlap, attempt, path, blocks)
        if overlap <= limit:
            break
    if best is None:
        raise InvalidArgument("attempts must be at least 1")
    overlap, attempt, path, blocks = best
    return StationaryPath(
        engine_version=ENGINE_VERSION if cond is None else CONDITIONAL_VERSION,
        months=tuple(_first_of_month(i) for i in path),
        blocks=tuple(blocks),
        overlap_months=overlap,
        overlap_met=overlap <= limit,
        attempt=attempt,
    )


def generate(
    months: Sequence[date],
    base_date: date,
    length: int,
    seed: int,
    *,
    recent_months: Collection[date] = (),
    max_overlap: Fraction = Fraction(1),
    replay_start: date = REPLAY_START,
    params: BootstrapParams = DEFAULT_PARAMS,
    labels: Mapping[date, str] | None = None,
) -> StationaryPath:
    """One path of `length` turns; raises PathTooLong only when fewer than `length` months exist.

    `labels=None` is `m2-sb6-v1`. A mapping (even an empty one) selects `m2-sbc6-v1`: at each block
    boundary a target regime is drawn from the transition counts of the previous block's last
    month, and candidate starts of that regime are preferred inside each calendar tier. The mapping
    holds every labelled month of the history (a NULL label is an absent key); an empty mapping is
    refused, never answered with the unconditional rule.
    """
    return _generate(
        months,
        base_date,
        length,
        seed,
        recent_months,
        max_overlap,
        replay_start,
        params,
        None,
        labels,
    )
