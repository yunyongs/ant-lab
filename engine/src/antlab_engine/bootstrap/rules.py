"""Path rule checker for both engine versions; it returns rule names only, never months.

`p0-boot6-v1` mirrors the Java PathRules. `m2-sb6-v1` checks the layout recorded in its blocks.
"""

from collections.abc import Sequence
from datetime import date

from antlab_engine.bootstrap import simple, stationary
from antlab_engine.bootstrap.simple import replay_upper_bound
from antlab_engine.bootstrap.stationary import Block, Tier


def _index(d: date) -> int:
    return d.year * 12 + d.month - 1


def _follows(previous: date, current: date) -> bool:
    return _index(current) == _index(previous) + 1


def violations(
    months: Sequence[date],
    base_date: date,
    length: int,
    *,
    engine_version: str,
    replay_start: date = simple.REPLAY_START,
    blocks: Sequence[Block] | None = None,
    block_length: int = simple.BLOCK_LENGTH,
) -> list[str]:
    """Rule names broken by `months`; `blocks` is needed for the `m2-sb6-v1` block rules."""
    if engine_version == simple.ENGINE_VERSION:
        return _p0(months, base_date, length, replay_start, block_length)
    if engine_version == stationary.ENGINE_VERSION:
        return _m2(months, base_date, length, replay_start, blocks)
    raise ValueError("unknown engine version")


def _common(
    months: Sequence[date], base_date: date, length: int, replay_start: date
) -> tuple[list[str], bool, bool]:
    result: list[str] = []
    if len(months) != length:
        result.append("S1_COUNT")
    upper = replay_upper_bound(base_date)
    out_of_range = reuse = False
    seen: set[date] = set()
    calendar = False
    for i, m in enumerate(months):
        out_of_range |= m < replay_start or m >= upper
        calendar |= m.month != (i % 12) + 1
        reuse |= m in seen
        seen.add(m)
    if out_of_range:
        result.append("S1_RANGE")
    return result, calendar, reuse


def _p0(
    months: Sequence[date], base_date: date, length: int, replay_start: date, block_length: int
) -> list[str]:
    result, calendar, reuse = _common(months, base_date, length, replay_start)
    if calendar:
        result.append("S4")
    if reuse:
        result.append("REUSE")
    if block_length > 0:
        gap = join = False
        for i in range(1, len(months)):
            consecutive = _follows(months[i - 1], months[i])
            if i % block_length != 0:
                gap |= not consecutive
            else:
                join |= consecutive
        if gap:
            result.append("BLOCK_GAP")
        if join:
            result.append("BLOCK_JOIN")
    return result


def _m2(
    months: Sequence[date],
    base_date: date,
    length: int,
    replay_start: date,
    blocks: Sequence[Block] | None,
) -> list[str]:
    result, _, reuse = _common(months, base_date, length, replay_start)
    if reuse:
        result.append("REUSE")
    if blocks is None:
        return result
    layout = gap = tier = join = False
    expected_start = 0
    for position, block in enumerate(blocks):
        end = block.start_turn + block.length
        if block.length < 1 or block.start_turn < 0 or end > len(months):
            # Unusable block: never index the month list with it.
            layout = True
            break
        layout |= block.start_turn != expected_start
        expected_start = end
        first = months[block.start_turn]
        gap |= any(not _follows(months[i - 1], months[i]) for i in range(block.start_turn + 1, end))
        previous_last = months[block.start_turn - 1] if block.start_turn > 0 else None
        continues = previous_last is not None and _follows(previous_last, first)
        tier |= not _tier_consistent(block, first, continues, position)
        join |= continues and block.tier is not Tier.JOIN
    if expected_start != len(months):
        layout = True
    if layout:
        result.append("BLOCK_LAYOUT")
    if gap:
        result.append("BLOCK_GAP")
    if tier:
        result.append("TIER")
    if join:
        result.append("BLOCK_JOIN")
    return result


def _tier_consistent(block: Block, first: date, continues: bool, position: int) -> bool:
    turn_month = block.start_turn % 12
    offset = (first.month - 1 - turn_month) % 12
    if block.tier is Tier.ALIGNED:
        return offset == 0
    if block.tier is Tier.NEAR:
        return offset in (0, 1, 11)
    if block.tier is Tier.JOIN:
        return position > 0 and continues and block.length == 1
    return True
