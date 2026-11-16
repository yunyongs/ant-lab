"""Test-only port of the Java PathRules checker. It returns rule names only."""

from datetime import date

from antlab_engine.bootstrap.simple import REPLAY_START, replay_upper_bound


def _next_month(d: date) -> date:
    return date(d.year + d.month // 12, d.month % 12 + 1, 1)


def violations(
    months: list[date], base_date: date, length: int, block_length: int = 0
) -> list[str]:
    result: list[str] = []
    if len(months) != length:
        result.append("S1_COUNT")
    upper = replay_upper_bound(base_date)
    out_of_range = calendar = reuse = False
    seen: set[date] = set()
    for i, m in enumerate(months):
        out_of_range |= m < REPLAY_START or m >= upper
        calendar |= m.month != (i % 12) + 1
        reuse |= m in seen
        seen.add(m)
    if out_of_range:
        result.append("S1_RANGE")
    if calendar:
        result.append("S4")
    if reuse:
        result.append("REUSE")
    if block_length > 0:
        gap = join = False
        for i in range(1, len(months)):
            consecutive = _next_month(months[i - 1]) == months[i]
            if i % block_length != 0:
                gap |= not consecutive
            else:
                join |= consecutive
        if gap:
            result.append("BLOCK_GAP")
        if join:
            result.append("BLOCK_JOIN")
    return result
