"""Ranking board of one turn (Java `RankingBoardCalculator`), a pure function of as-of-turn facts.

Every list is filtered, sorted by a total order (ties end in market, ticker, then group code, all
by UTF-8 byte order), cut at `BOARD_SIZE` and numbered by row position.
"""

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from decimal import Decimal

from .. import money
from .facts import AssetFacts
from .feedhash import byte_key
from .rules import BOARD_SIZE, MIN_STREAK, Failure, FeedGenerationError, grade


@dataclass(frozen=True, slots=True)
class AssetRow:
    rank: int
    asset_id: int
    change_rate: Decimal


@dataclass(frozen=True, slots=True)
class AttentionRow:
    rank: int
    asset_id: int
    grade: int


@dataclass(frozen=True, slots=True)
class StreakRow:
    rank: int
    asset_id: int
    streak: int
    change_rate: Decimal


@dataclass(frozen=True, slots=True)
class GroupRow:
    rank: int
    group_id: int
    change_rate: Decimal


@dataclass(frozen=True, slots=True)
class RankingBoard:
    turn_no: int
    gainers: list[AssetRow]
    losers: list[AssetRow]
    attention: list[AttentionRow]
    rising_streaks: list[StreakRow]
    falling_streaks: list[StreakRow]
    group_gainers: list[GroupRow]
    group_losers: list[GroupRow]


@dataclass(frozen=True, slots=True)
class RankingInput:
    turn_no: int
    assets: tuple[AssetFacts, ...]

    def __post_init__(self) -> None:
        if self.turn_no < 1:
            raise FeedGenerationError(Failure.FEED_INPUT, "turn or fact list is missing")
        object.__setattr__(self, "assets", tuple(self.assets))
        refs: set[str] = set()
        for a in self.assets:
            if a.as_of_turn != self.turn_no:
                raise FeedGenerationError(
                    Failure.FEED_INPUT, "an asset fact is not as of the board's turn"
                )
            if a.ref in refs:
                raise FeedGenerationError(Failure.FEED_INPUT, "duplicate asset")
            refs.add(a.ref)
            ranked = a.attention_rank is not None
            if ranked != (a.attention is not None) or (
                a.attention_rank is not None and not 1 <= a.attention_rank <= a.attention_n
            ):
                raise FeedGenerationError(
                    Failure.FEED_INPUT, "attention figure and rank do not agree"
                )


def _natural(a: AssetFacts) -> tuple[bytes, bytes]:
    return (byte_key(a.market), byte_key(a.ticker))


def _top[T](rows: Iterable[T], keep: Callable[[T], bool], order: Callable[[T], tuple]) -> list[T]:
    return sorted((r for r in rows if keep(r)), key=order)[:BOARD_SIZE]


def group_changes(assets: Iterable[AssetFacts]) -> list[tuple[int, str, Decimal]]:
    """(group id, code, mean member return rounded once to 8 digits) in first-seen group order."""
    by_group: dict[int, list[AssetFacts]] = {}
    for a in assets:
        by_group.setdefault(a.group_id, []).append(a)
    out = []
    for gid, members in by_group.items():
        mean = money.pg_avg([m.ret for m in members])
        assert mean is not None  # noqa: S101 - a group has at least one member
        out.append((gid, members[0].group_code, money.round8(mean)))
    return out


def board(inp: RankingInput) -> RankingBoard:
    assets = inp.assets
    gainers = _top(assets, lambda a: a.ret > 0, lambda a: (a.ret.copy_negate(), *_natural(a)))
    losers = _top(assets, lambda a: a.ret < 0, lambda a: (a.ret, *_natural(a)))
    attention = _top(
        assets, lambda a: a.attention is not None, lambda a: (_neg(a.attention), *_natural(a))
    )
    rising = _top(
        assets,
        lambda a: a.ret > 0 and a.streak >= MIN_STREAK,
        lambda a: (-a.streak, a.ret.copy_abs().copy_negate(), *_natural(a)),
    )
    falling = _top(
        assets,
        lambda a: a.ret < 0 and a.streak >= MIN_STREAK,
        lambda a: (-a.streak, a.ret.copy_abs().copy_negate(), *_natural(a)),
    )
    groups = group_changes(assets)
    group_gainers = _top(groups, lambda g: g[2] > 0, lambda g: (g[2].copy_negate(), byte_key(g[1])))
    group_losers = _top(groups, lambda g: g[2] < 0, lambda g: (g[2], byte_key(g[1])))
    return RankingBoard(
        inp.turn_no,
        [AssetRow(i + 1, a.asset_id, a.ret) for i, a in enumerate(gainers)],
        [AssetRow(i + 1, a.asset_id, a.ret) for i, a in enumerate(losers)],
        [AttentionRow(i + 1, a.asset_id, _grade(a)) for i, a in enumerate(attention)],
        [StreakRow(i + 1, a.asset_id, a.streak, a.ret) for i, a in enumerate(rising)],
        [StreakRow(i + 1, a.asset_id, a.streak, a.ret) for i, a in enumerate(falling)],
        [GroupRow(i + 1, g[0], g[2]) for i, g in enumerate(group_gainers)],
        [GroupRow(i + 1, g[0], g[2]) for i, g in enumerate(group_losers)],
    )


def _neg(value: Decimal | None) -> Decimal:
    assert value is not None  # noqa: S101 - filtered on attention
    return value.copy_negate()


def _grade(a: AssetFacts) -> int:
    g = grade(a.attention_rank, a.attention_n)
    assert g is not None  # noqa: S101 - attention implies a rank
    return g
