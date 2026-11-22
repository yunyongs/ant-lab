"""Policy constants of feed rule version `p0-18:v2` and the shared error type."""

from enum import StrEnum

RULE_VERSION = "p0-18:v2"
MIN_ROWS = 5
ROW_SPAN = 11
MAX_ROWS = 15
FADE_TOP = 5

BOARD_SIZE = 5
MIN_STREAK = 2
TOP_GRADE = 3

# Hidden event types: fewer than this many distinct key families inside the replay range.
HIDDEN_FAMILY_LIMIT = 3
FAMILY_PREFIX = 6


class Failure(StrEnum):
    FEED_INPUT = "FEED_INPUT"
    FEED_EVENTS_UNREVIEWED = "FEED_EVENTS_UNREVIEWED"
    FEED_REACTION_MISSING = "FEED_REACTION_MISSING"
    FEED_RENDER = "FEED_RENDER"


class FeedGenerationError(RuntimeError):
    """A feed that cannot be produced: a data defect (Java `FeedGenerationException`)."""

    def __init__(self, failure: Failure, reason: str) -> None:
        super().__init__(f"{failure.value}: {reason}")
        self.failure = failure


def grade(rank: int | None, n: int) -> int | None:
    """Attention grade: top 10 percent (ties included) 3, top half 2, the rest 1, no rank None."""
    if rank is None or n == 0:
        return None
    if rank <= (n + 9) // 10:
        return 3
    return 2 if rank <= (n + 1) // 2 else 1
