"""`bot-run/1` records with engine counts for the score-validation tests.

The runner fills `counts` from the counts source; here it is a fixed answer per bot.
"""

from decimal import Decimal

from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import synthetic_world
from antlab_engine.sim.counts import FixedCounts, PercentileCounts, RankCounts
from antlab_engine.validation.components import Components

PATHS = 2000
MASTER = 9


def counted_record(world, kind: str, index: int, *, with_counts: bool = True) -> dict:
    below = (index * 137 + len(kind) * 53) % 1900
    equal = index % 4
    answer = PercentileCounts(RankCounts(below, equal, PATHS), RankCounts(below // 2, 0, PATHS))
    source = FixedCounts(answer) if with_counts else None
    return dict(
        run_bot(world, make_bot(kind, index, MASTER, world.seed), counts_source=source).record
    )


def roster(world) -> list[dict]:
    kinds = [("random", i) for i in range(8)] + [("static", i) for i in range(3)]
    kinds += [("disciplined", 0), ("disciplined-noguard", 0), ("news", 0)]
    return [counted_record(world, kind, index) for kind, index in kinds]


def world_12():
    return synthetic_world(1, assets=12, length=12)


def world_24():
    return synthetic_world(2, assets=12, length=24)


def comp(
    kind: str = "random",
    index: int = 0,
    *,
    world_id: str = "w",
    path_length: int = 12,
    excess_cagr: str = "0",
    sharpe_diff: str = "0",
    sortino_diff: str = "0",
    calmar_diff: str = "0",
    risk: str = "100",
    mdd: str = "0",
    process: str = "50",
    result: str | None = None,
) -> Components:
    """A hand-built `Components` for composite maths; other fields are inert."""
    zero = Decimal(0)
    return Components(
        world_id,
        path_length,
        kind,
        index,
        zero,
        zero,
        Decimal(excess_cagr),
        zero,
        zero,
        Decimal(sharpe_diff),
        zero,
        zero,
        Decimal(sortino_diff),
        zero,
        zero,
        Decimal(calmar_diff),
        Decimal(mdd),
        zero,
        {"risk": Decimal(risk)},
        Decimal(process),
        None if result is None else Decimal(result),
    )
