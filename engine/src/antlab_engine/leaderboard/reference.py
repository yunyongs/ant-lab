"""The reference set of a season: random bots on the season's own world, cached by content key.

The key is the SHA-256 of the canonical season identity (reference version, base date, months in
turn order, settings, bot version and size), so seasons that walked the same months with the same
settings (the seats of one league) share one reference and one bot run. The world seed of the bots
is the first 8 bytes of that key, never a season id.
"""

import hashlib
from collections import OrderedDict
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from threading import BoundedSemaphore, Lock
from typing import Any, Protocol

from antlab_engine.bots import world_pg
from antlab_engine.bots.runner import make_bot, run_bot
from antlab_engine.bots.world import BotWorld, canonical
from antlab_engine.engine_errors import (
    EngineBusy,
    FeedInputUnusable,
    HistoryUnavailable,
    NoReference,
)
from antlab_engine.settings import DatabaseSettings
from antlab_engine.sim import money
from antlab_engine.sim.feed.rules import FeedGenerationError
from antlab_engine.sim.model import Settings, SimError
from antlab_engine.sim.report import plain
from antlab_engine.validation.components import (
    ComponentError,
    Components,
    ReferenceSet,
    components_of,
)

from . import ANTLAB_REF_MASTER, BOT_VERSION, REFERENCE_KIND, REFERENCE_SIZE

CACHE_SIZE = 16
NOT_CONFIGURED = "The history database is not configured."
BUSY = "A reference set is being built; try again later."
UNUSABLE = "The stored history of a path month cannot build the season world."
NO_REFERENCE = "The reference bots cannot play this season's world."


@dataclass(frozen=True)
class SeasonIdentity:
    """What fixes a season's reference: the path months in turn order and the season settings."""

    reference_version: str
    base_date: date
    months: tuple[date, ...]
    settings: Settings


def _number(value: Decimal) -> str:
    """One text per value whatever its scale, so `0.20` and `0.20000000` give the same key."""
    return plain(value.normalize())


def key_body(identity: SeasonIdentity, *, size: int, bot_version: str) -> dict[str, Any]:
    s = identity.settings
    f = s.start_formation
    return {
        "version": identity.reference_version,
        "baseDate": identity.base_date.isoformat(),
        "months": [m.isoformat() for m in identity.months],
        "settings": {
            "pathLength": s.path_length,
            "initialCash": _number(s.initial_cash),
            "feeRateKr": _number(s.fee_rate_kr),
            "feeRateUs": _number(s.fee_rate_us),
            "fxSpreadRate": _number(s.fx_spread_rate),
            "riskLimit": _number(s.risk_limit),
            "startFormation": {
                "goalkeeper": f.goalkeeper,
                "defender": f.defender,
                "midfielder": f.midfielder,
                "forward": f.forward,
            },
            "memberKind": s.member_kind,
        },
        "botVersion": bot_version,
        "size": size,
    }


def reference_key(
    identity: SeasonIdentity, *, size: int = REFERENCE_SIZE, bot_version: str = BOT_VERSION
) -> str:
    body = key_body(identity, size=size, bot_version=bot_version)
    return hashlib.sha256(canonical(body).encode()).hexdigest()


def world_seed(key: str) -> int:
    """The first 8 bytes of the reference key as a signed 64-bit integer."""
    return int.from_bytes(bytes.fromhex(key[:16]), "big", signed=True)


@dataclass(frozen=True)
class Reference:
    """Sorted reference metrics (risk item at its stored 2 places) and the world's benchmark."""

    key: str
    size: int
    bot_version: str
    metrics: ReferenceSet
    benchmark_returns: tuple[Decimal, ...]


def reference_from_components(
    bots: Sequence[Components],
    benchmark_returns: Sequence[Decimal],
    *,
    key: str,
    size: int,
) -> Reference:
    """The M3-03 reference set with each bot's risk item rounded half up to 2 places.

    The season's own risk item is only stored at 2 places (`season_score.risk_score`), so both
    sides are compared at that precision.
    """
    exact = ReferenceSet.from_components(bots, expected_size=size)
    stored = tuple(sorted(money.round2(v) for v in exact.values["risk_item"]))
    values = {**exact.values, "risk_item": stored}
    metrics = ReferenceSet(exact.world_id, exact.path_length, exact.size, values)
    return Reference(key, size, BOT_VERSION, metrics, tuple(benchmark_returns))


def build_reference(world: BotWorld, key: str, size: int) -> Reference:
    """Runs random bots 0..size-1 on the world without its feed facts (they trade DIRECT only)."""
    if world.seed != world_seed(key):
        raise ComponentError("the world seed must come from the reference key")
    bare = BotWorld(world.seed, world.base_date, world.inputs)
    bots = []
    benchmarks = set()
    for index in range(size):
        bot = make_bot(REFERENCE_KIND, index, ANTLAB_REF_MASTER, bare.seed)
        record = run_bot(bare, bot).record
        bots.append(components_of(record))
        benchmarks.add(tuple(record["benchmarkReturns"]))
    if len(benchmarks) != 1:
        raise ComponentError("the reference bots disagree on the benchmark")
    benchmark = tuple(Decimal(r) for r in benchmarks.pop())
    return reference_from_components(bots, benchmark, key=key, size=size)


class WorldReader(Protocol):
    def read(self, identity: SeasonIdentity, seed: int) -> BotWorld: ...


class PostgresWorldReader:
    """The season world from one read-only snapshot (`world_pg.read_world` with given months)."""

    def __init__(self, database: DatabaseSettings, replay_start: date) -> None:
        self._database = database
        self._replay_start = replay_start

    def read(self, identity: SeasonIdentity, seed: int) -> BotWorld:
        return world_pg.read_world(
            self._database,
            identity.base_date,
            seed,
            months=list(identity.months),
            replay_start=self._replay_start,
            sim_settings=identity.settings,
        )


class ReferenceService:
    """Least-recently-used cache of references; one build at a time, a second one is refused."""

    def __init__(
        self,
        reader: WorldReader | None,
        *,
        size: int = REFERENCE_SIZE,
        cache_size: int = CACHE_SIZE,
    ) -> None:
        self._reader = reader
        self.size = size
        self._cache_size = cache_size
        self._cache: OrderedDict[str, Reference] = OrderedDict()
        self._lock = Lock()
        self._slot = BoundedSemaphore(1)

    def key(self, identity: SeasonIdentity) -> str:
        return reference_key(identity, size=self.size)

    def _get(self, key: str) -> Reference | None:
        with self._lock:
            found = self._cache.get(key)
            if found is not None:
                self._cache.move_to_end(key)
            return found

    def _put(self, reference: Reference) -> None:
        with self._lock:
            self._cache[reference.key] = reference
            self._cache.move_to_end(reference.key)
            while len(self._cache) > self._cache_size:
                self._cache.popitem(last=False)

    def reference(self, identity: SeasonIdentity) -> Reference:
        key = self.key(identity)
        found = self._get(key)
        if found is not None:
            return found
        if self._reader is None:
            raise HistoryUnavailable(NOT_CONFIGURED)
        if not self._slot.acquire(blocking=False):
            raise EngineBusy(BUSY)
        try:
            found = self._get(key)
            if found is not None:
                return found
            try:
                world = self._reader.read(identity, world_seed(key))
                built = build_reference(world, key, self.size)
            except FeedGenerationError:
                raise FeedInputUnusable(UNUSABLE) from None
            except (SimError, ComponentError, money.MoneyError, money.MoneyArithmeticError):
                # deterministic for the request (a bot rejection, a world the simulator refuses):
                # a named refusal lets the caller exclude the season instead of retrying a 500
                raise NoReference(NO_REFERENCE) from None
            self._put(built)
            return built
        finally:
            self._slot.release()
