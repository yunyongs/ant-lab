"""Hand-built score-part tables for the checks and search tests."""

from decimal import Decimal

from antlab_engine.validation.checks import PartsRow, WorldTable, world_tables

D = Decimal
RESULT_ONLY = (D(1), D(0))
HALF_AND_HALF = (D("0.5"), D("0.5"))


def prow(world: int, kind: str, index: int, *parts: object) -> PartsRow:
    return PartsRow(world, kind, index, tuple(D(str(p)) for p in parts))


def world_rows(
    world: int,
    *,
    random: tuple = (50, 50),
    random_bots: int = 4,
    disciplined: tuple | None = (50, 69),
    news: tuple | None = (27, 50),
    noguard: tuple | None = None,
    mixes: dict[int, tuple] | None = None,
    statics: tuple[tuple, ...] = (),
    redeclare: tuple[tuple, ...] = (),
    random_each: tuple[tuple, ...] | None = None,
) -> list[PartsRow]:
    """One world: identical evaluation random bots unless `random_each` lists them."""
    rows = []
    each = random_each or tuple(random for _ in range(random_bots))
    for i, parts in enumerate(each):
        rows.append(prow(world, "random", 100 + i, *parts))
    if disciplined is not None:
        rows.append(prow(world, "disciplined", 0, *disciplined))
    if news is not None:
        rows.append(prow(world, "news", 0, *news))
    if noguard is not None:
        rows.append(prow(world, "disciplined-noguard", 0, *noguard))
    for dose, parts in (mixes or {}).items():
        rows.append(prow(world, f"newsmix-{dose}", 0, *parts))
    for i, parts in enumerate(statics):
        rows.append(prow(world, "static", i, *parts))
    for i, parts in enumerate(redeclare):
        rows.append(prow(world, "redeclare", 100 + i, *parts))
    return rows


def tables(*worlds: list[PartsRow]) -> tuple[WorldTable, ...]:
    return world_tables([row for world in worlds for row in world])


def blend(a: tuple, b: tuple) -> tuple:
    """Midpoint of two part tuples (the newsmix-50 bot between disciplined and news)."""
    return tuple((D(str(x)) + D(str(y))) / 2 for x, y in zip(a, b, strict=True))


def selection_world(world: int, *, disciplined: tuple, news: tuple) -> list[PartsRow]:
    """Random (50, 50); newsmix-50 half-way; noguard like disciplined; one static like random."""
    return world_rows(
        world,
        disciplined=disciplined,
        news=news,
        noguard=disciplined,
        mixes={50: blend(disciplined, news)},
        statics=((50, 50),),
    )
