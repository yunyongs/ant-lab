"""`bot-world/1` from the database: the pool of a base date and the months of one engine path.

Everything is read inside ONE `REPEATABLE READ READ ONLY` snapshot of a role that cannot write
(the checks of `PostgresHistoryReader`). The world is built along a path: the months are either
given or drawn by the engine's own month selection (`generate_months`) from the months in the
snapshot, so a world is the path a season with that seed would walk. Refusals carry a code and
never a month, an asset or a value:

- `FEED_EVENTS_UNREVIEWED` (as Java): a path month whose events are not reviewed;
- `FEED_INPUT`: a pool asset has no return, or a USD asset has no FX change, in a path month;
- `INVALID_ARGUMENT`: months that are not distinct first-of-month dates inside the replay range
  or not stored.

`python -m antlab_engine.bots.world_pg` writes the world JSON under `out/worlds/`; the batch CLI
stays offline and reads that file.
"""

import argparse
import sys
from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

from antlab_engine.api.projection_panel import panel_from_snapshot
from antlab_engine.bootstrap.simple import (
    ALLOWED_LENGTHS,
    generate_months,
    replay_upper_bound,
)
from antlab_engine.engine_errors import (
    EngineError,
    InvalidArgument,
    PoolNotFound,
    UnsafeDatabaseRole,
)
from antlab_engine.history import queries
from antlab_engine.history.reader import PostgresHistoryReader, Snapshot, read_snapshot
from antlab_engine.projection import ReturnPanel
from antlab_engine.settings import (
    DatabaseSettings,
    SettingsError,
    replay_start_from_env,
)
from antlab_engine.sim.feed.rules import (
    FAMILY_PREFIX,
    HIDDEN_FAMILY_LIMIT,
    Failure,
    FeedGenerationError,
)
from antlab_engine.sim.model import PathStep, PoolAsset, Position, Settings, SimInputs

from .world import BotWorld, canonical

ISOLATION = "repeatable read"
NO_POOL = "No pool is stored for the base date."
BAD_MONTHS = (
    "The months must be 12, 24 or 60 distinct stored first-of-month dates inside the replay range."
)
NOT_SNAPSHOT = "The world was not read in a repeatable-read read-only snapshot."
MISSING_DATA = "A pool asset has no return (or an FX change is missing) in a path month."
UNREVIEWED = "The events of a path month are not reviewed."
REACTION_MISSING = "An event of a path month has no reaction."
LENGTH_MISMATCH = "The settings path length must equal the number of path months."


@dataclass(frozen=True)
class WorldRows:
    """What the snapshot returned, in plain values: the input of the pure assembly."""

    base_date: date
    path_blocks: tuple[int, ...]
    reviewed: tuple[bool, ...]
    pool: tuple[tuple[Any, ...], ...]
    returns: Mapping[tuple[int, int], Decimal]
    fx: Mapping[tuple[int, str], Decimal]
    attention: tuple[tuple[int, int, Decimal], ...]
    events: tuple[tuple[int, str, str, Decimal | None, int, bool], ...]


def pick_months(
    stored: Sequence[date],
    base_date: date,
    replay_start: date,
    *,
    months: Sequence[date] | None,
    length: int | None,
    path_seed: int | None,
) -> list[date]:
    """The path months in turn order: the given ones (validated) or the engine's draw."""
    if months is not None and (length is not None or path_seed is not None):
        raise InvalidArgument("Give either months or a length and a path seed, not both.")
    if months is not None:
        chosen = list(months)
        upper = replay_upper_bound(base_date)
        known = set(stored)
        if (
            len(chosen) not in ALLOWED_LENGTHS
            or len(set(chosen)) != len(chosen)
            or any(m.day != 1 or m < replay_start or m >= upper or m not in known for m in chosen)
        ):
            raise InvalidArgument(BAD_MONTHS)
        return chosen
    if length is None or path_seed is None:
        raise InvalidArgument("Give either months or a length and a path seed.")
    return generate_months(stored, base_date, length, path_seed, replay_start)


def _read_rows(
    conn: Snapshot,
    base_date: date,
    replay_start: date,
    *,
    months: Sequence[date] | None = None,
    length: int | None = None,
    path_seed: int | None = None,
) -> WorldRows:
    """Run the world's statements on an open snapshot."""
    isolation = conn.execute(queries.SESSION_SQL).fetchone()
    if isolation is None or isolation[1] != "on" or isolation[2] != ISOLATION:
        raise UnsafeDatabaseRole(NOT_SNAPSHOT)
    upper = replay_upper_bound(base_date)
    stored = conn.execute(queries.WORLD_MONTHS_SQL, {"upper": upper}).fetchall()
    block_of = {start: (block_id, reviewed) for block_id, start, reviewed in stored}
    path = pick_months(
        list(block_of),
        base_date,
        replay_start,
        months=months,
        length=length,
        path_seed=path_seed,
    )
    blocks = [block_of[m][0] for m in path]
    reviewed = tuple(block_of[m][1] for m in path)
    pool = tuple(tuple(r) for r in conn.execute(queries.WORLD_POOL_SQL, {"base_date": base_date}))
    if not pool:
        raise PoolNotFound(NO_POOL)
    assets = [r[0] for r in pool]
    returns = {
        (a, b): v
        for a, b, v in conn.execute(queries.WORLD_RETURNS_SQL, {"blocks": blocks, "assets": assets})
    }
    fx = {(b, c): v for b, c, v in conn.execute(queries.WORLD_FX_SQL, {"blocks": blocks})}
    attention = tuple(
        (b, a, v)
        for b, a, v in conn.execute(
            queries.WORLD_ATTENTION_SQL, {"blocks": blocks, "assets": assets}
        )
    )
    events = tuple(
        tuple(r)
        for r in conn.execute(
            queries.WORLD_EVENTS_SQL,
            {
                "blocks": blocks,
                "replay_start": replay_start,
                "upper": upper,
                "prefix": FAMILY_PREFIX,
                "limit": HIDDEN_FAMILY_LIMIT,
            },
        )
    )
    return WorldRows(base_date, tuple(blocks), reviewed, pool, returns, fx, attention, events)


def _plain(value: Decimal) -> str:
    return format(value, "f")


def assemble(rows: WorldRows, seed: int, settings: Settings | None = None) -> BotWorld:
    """The pure part: refusals, shaping and ordering. No I/O.

    `settings` replaces the simulator defaults (a season's own cash, costs and limits); its path
    length must be the number of path months.
    """
    if settings is not None and settings.path_length != len(rows.path_blocks):
        raise InvalidArgument(LENGTH_MISMATCH)
    if not all(rows.reviewed):
        raise FeedGenerationError(Failure.FEED_EVENTS_UNREVIEWED, UNREVIEWED)
    if any(e[3] is None for e in rows.events):
        raise FeedGenerationError(Failure.FEED_REACTION_MISSING, REACTION_MISSING)
    sim_pool: list[PoolAsset] = []
    feed_assets: list[dict[str, Any]] = []
    ref_of: dict[int, str] = {}
    for (
        asset_id,
        market,
        ticker,
        currency,
        name,
        group_code,
        group_name,
        price,
        fx,
        pos,
    ) in rows.pool:
        ref = f"{market}:{ticker}"
        ref_of[asset_id] = ref
        sim_pool.append(PoolAsset(ref, currency, price, fx, None if pos is None else Position(pos)))
        feed_assets.append(
            {"asset": ref, "name": name, "groupCode": group_code, "groupName": group_name}
        )
    currencies = {a.currency for a in sim_pool if a.currency != "KRW"}
    path: list[PathStep] = []
    for turn, block in enumerate(rows.path_blocks, start=1):
        local: dict[str, Decimal | None] = {}
        for asset_id, ref in ref_of.items():
            value = rows.returns.get((asset_id, block))
            if value is None:
                raise FeedGenerationError(Failure.FEED_INPUT, MISSING_DATA)
            local[ref] = value
        changes: dict[str, Decimal | None] = {}
        for currency in sorted(currencies):
            value = rows.fx.get((block, currency))
            if value is None:
                raise FeedGenerationError(Failure.FEED_INPUT, MISSING_DATA)
            changes[currency] = value
        path.append(PathStep(turn, local, changes))
    turn_of = {block: turn for turn, block in enumerate(rows.path_blocks, start=1)}
    values_by_turn: dict[int, dict[str, str]] = defaultdict(dict)
    for block, asset_id, index in rows.attention:
        values_by_turn[turn_of[block]][ref_of[asset_id]] = _plain(index)
    attention = [
        {"turn": t, "values": dict(sorted(values_by_turn.get(t, {}).items()))}
        for t in range(len(rows.path_blocks) + 1)
    ]
    events = []
    ordered = sorted(rows.events, key=lambda e: (turn_of[e[0]], e[1].encode()))
    for block, key, category, reaction, strength, hidden in ordered:
        events.append(
            {
                "turn": turn_of[block],
                "key": key,
                "category": None if hidden else category,
                "hidden": bool(hidden),
                "reaction": None if reaction is None else _plain(reaction),
                "strength": strength,
            }
        )
    feed = {
        "assets": feed_assets,
        "attention": attention,
        "events": events,
        "reviewed": list(range(1, len(rows.path_blocks) + 1)),
    }
    chosen = Settings(path_length=len(path)) if settings is None else settings
    inputs = SimInputs(chosen, tuple(sim_pool), tuple(path))
    return BotWorld(seed, rows.base_date, inputs, feed)


def read_world(
    settings: DatabaseSettings,
    base_date: date,
    seed: int,
    *,
    months: Sequence[date] | None = None,
    length: int | None = None,
    path_seed: int | None = None,
    replay_start: date,
    sim_settings: Settings | None = None,
) -> BotWorld:
    """One snapshot, one world. `seed` is the world seed the bots and the feed use.

    `sim_settings` replaces the simulator defaults of the world (see `assemble`).

    A drawn path is the one `generate_months` (`p0-boot6-v1`, the Java bootstrap) gives for
    the seed; the api default path source is `m2-sb6-v1` since M2-09. `replay_start` has no
    default: callers pass the api's setting.
    """
    with read_snapshot(settings) as conn:
        rows = _read_rows(
            conn, base_date, replay_start, months=months, length=length, path_seed=path_seed
        )
    return assemble(rows, seed, sim_settings)


def read_panel(settings: DatabaseSettings, base_date: date) -> ReturnPanel:
    """The return panel of the stored history up to `base_date`: what the rank counts draw from."""
    return panel_from_snapshot(PostgresHistoryReader(settings).read(base_date))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="antlab_engine.bots.world_pg", description=__doc__)
    parser.add_argument("--base-date", required=True, type=date.fromisoformat)
    parser.add_argument("--seed", required=True, type=int, help="world seed")
    parser.add_argument("--length", type=int, default=12, choices=(12, 24, 60))
    parser.add_argument("--path-seed", type=int, default=None, help="default: the world seed")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)
    try:
        world = read_world(
            DatabaseSettings.from_env(),
            args.base_date,
            args.seed,
            length=args.length,
            path_seed=args.seed if args.path_seed is None else args.path_seed,
            replay_start=replay_start_from_env(),
        )
    except SettingsError as exc:
        print(f"error: SETTINGS: {exc}", file=sys.stderr)  # noqa: T201
        return 1
    except EngineError as exc:
        print(f"error: {exc.code}: {exc}", file=sys.stderr)  # noqa: T201
        return 1
    except FeedGenerationError as exc:
        print(f"error: {exc.failure.value}", file=sys.stderr)  # noqa: T201
        return 1
    out = args.out or Path("out/worlds") / f"{world.world_id[:12]}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(canonical(world.to_doc()) + "\n", encoding="utf-8", newline="\n")
    print(f"world {world.world_id[:12]} -> {out}")  # noqa: T201
    return 0


if __name__ == "__main__":
    sys.exit(main())
