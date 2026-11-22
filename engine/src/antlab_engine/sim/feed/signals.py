"""What the player was pointed at in one turn (Java `TurnSignals`).

The sets are a function of that turn's feed rows and that turn's ranking board only.
"""

from collections.abc import Iterable
from dataclasses import dataclass

from .facts import DOWN, UP
from .ranking import RankingInput, board
from .rules import TOP_GRADE, grade


class SignalsError(ValueError):
    """Java `IllegalStateException` of an inconsistent signal source."""


@dataclass(frozen=True, slots=True)
class FeedRow:
    turn_no: int
    kind: str
    asset_id: int | None
    direction: str | None


@dataclass(frozen=True, slots=True)
class TurnSignals:
    turn_no: int
    up: frozenset[int]
    down: frozenset[int]
    top_attention: frozenset[int]


def signals_of(turn_no: int, feed_rows: Iterable[FeedRow], inp: RankingInput) -> TurnSignals:
    if inp.turn_no != turn_no:
        raise SignalsError("the ranking input is of another turn")
    up: set[int] = set()
    down: set[int] = set()
    for row in feed_rows:
        if row.turn_no != turn_no:
            raise SignalsError("a feed row of another turn was mixed in")
        if row.asset_id is None:
            continue
        if row.kind in ("RISE", "ECHO") and row.direction == UP:
            up.add(row.asset_id)
        if row.kind in ("FALL", "ECHO") and row.direction == DOWN:
            down.add(row.asset_id)
    ranked = board(inp)
    up.update(r.asset_id for r in ranked.gainers)
    up.update(r.asset_id for r in ranked.attention)
    down.update(r.asset_id for r in ranked.losers)
    top = {a.asset_id for a in inp.assets if grade(a.attention_rank, a.attention_n) == TOP_GRADE}
    return TurnSignals(turn_no, frozenset(up), frozenset(down), frozenset(top))
