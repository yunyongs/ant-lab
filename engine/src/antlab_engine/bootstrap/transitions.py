"""Regime transition counts from labelled replayable months; pure, integer-only.

Labels are replay-only data like the months: they never appear in a repr or an error message.
A label is the regime stored for its own month (known as of that month), so a count uses no month
beyond the replay range the path already draws from. Pool names are plain strings "P1".."P4"
(a `regime.Pool` member is such a string); this module must not import the regime package.
"""

from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from fractions import Fraction

from antlab_engine.bootstrap.simple import REPLAY_START, replay_upper_bound
from antlab_engine.engine_errors import InvalidArgument, RegimeLabelsMissing

POOLS = ("P1", "P2", "P3", "P4")
MIN_LABELLED_MONTHS = 24


def _is_date(value: object) -> bool:
    return isinstance(value, date) and not isinstance(value, datetime)


def month_index(d: date) -> int:
    return d.year * 12 + d.month - 1


def replayable_indexes(months: Sequence[date], base_date: date, replay_start: date) -> list[int]:
    """Month indexes inside the replay range, ascending; input order does not matter."""
    upper = replay_upper_bound(base_date)
    seen: set[int] = set()
    for start in months:
        if not _is_date(start):
            raise TypeError("months must contain date values")
        if start.day != 1:
            raise InvalidArgument("invalid month list: a month does not start on day 1")
        if start < replay_start or start >= upper:
            continue
        index = month_index(start)
        if index in seen:
            raise InvalidArgument("invalid month list: the same month appears twice")
        seen.add(index)
    return sorted(seen)


def label_indexes(labels: Mapping[date, str], months: Collection[date]) -> dict[int, int]:
    """Month index -> pool position 0..3 for every label; keys must be listed months."""
    if not isinstance(labels, Mapping):
        raise TypeError("labels must be a mapping")
    known = {month_index(m) for m in months if _is_date(m)}
    result: dict[int, int] = {}
    for key, value in labels.items():
        if not _is_date(key):
            raise TypeError("label keys must be date values")
        if key.day != 1:
            raise InvalidArgument("invalid label list: a month does not start on day 1")
        if not isinstance(value, str):
            raise TypeError("label values must be pool names")
        if value not in POOLS:
            raise InvalidArgument("invalid label list: a value is not a pool name")
        index = month_index(key)
        if index not in known:
            raise InvalidArgument("invalid label list: a label has no month")
        result[index] = POOLS.index(value)
    return result


@dataclass(frozen=True)
class TransitionCounts:
    """counts[i][j] = consecutive labelled replayable month pairs from pool i to pool j."""

    counts: tuple[tuple[int, int, int, int], ...] = field(repr=False)
    labelled_months: int
    pairs: int

    def row_total(self, i: int) -> int:
        return sum(self.counts[i])

    def probability(self, i: int, j: int) -> Fraction | None:
        total = self.row_total(i)
        return None if total == 0 else Fraction(self.counts[i][j], total)


def count_pairs(avail: Sequence[int], label_of: Mapping[int, int]) -> TransitionCounts:
    """Counts over replayable month indexes `avail`; a pair needs both months labelled."""
    rows = [[0] * 4 for _ in POOLS]
    labelled = 0
    pairs = 0
    previous: int | None = None
    previous_label = -1
    for index in avail:
        label = label_of.get(index)
        if label is None:
            previous = None
            continue
        labelled += 1
        if previous is not None and index == previous + 1:
            rows[previous_label][label] += 1
            pairs += 1
        previous = index
        previous_label = label
    return TransitionCounts(tuple((r[0], r[1], r[2], r[3]) for r in rows), labelled, pairs)


def require_enough_labels(counts: TransitionCounts) -> None:
    if counts.labelled_months < MIN_LABELLED_MONTHS or counts.pairs < 1:
        raise RegimeLabelsMissing(
            f"Regime labels are not usable: {counts.labelled_months} labelled months "
            f"and {counts.pairs} transition pairs in the replay range, "
            f"at least {MIN_LABELLED_MONTHS} months and 1 pair are needed"
        )


def transition_counts(
    months: Sequence[date],
    labels: Mapping[date, str],
    base_date: date,
    replay_start: date = REPLAY_START,
) -> TransitionCounts:
    """Count label pairs over `[replay_start, Jan 1 of base_date's year)`; a gap breaks a pair."""
    if not _is_date(replay_start):
        raise TypeError("replay_start must be a date")
    avail = replayable_indexes(months, base_date, replay_start)
    return count_pairs(avail, label_indexes(labels, months))
