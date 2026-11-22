"""Turn loop of a bot over the M3-01 simulator, and the `bot-run/1` record.

The runner owns the season. A bot only ever gets a `BotView` built from the season's state at the
current turn, so what it can see is decided here and nowhere else.
"""

from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Protocol

from antlab_engine.sim import money
from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.counts import CountsSource, PercentileCounts, RankCounts
from antlab_engine.sim.decisions import Unit, apply_unit
from antlab_engine.sim.model import SimError, SimInputError
from antlab_engine.sim.report import plain
from antlab_engine.sim.score import ScoredSeason, score_season, team_returns
from antlab_engine.sim.season import Season

from . import io
from .disciplined_bot import DisciplinedBot
from .news_bot import NewsBot
from .newsmix_bot import KIND_PREFIX as NEWSMIX_PREFIX
from .newsmix_bot import NewsMixBot
from .random_bot import RandomBot
from .redeclare_bot import RedeclareBot
from .rng import KIND_DISCIPLINED, KIND_NEWSMIX_COIN, KIND_RANDOM, mix
from .static_bot import StaticBot
from .view import BotView, FeedSource, TurnFeed, ViewAsset
from .world import BotWorld
from .world_feed import feed_source_for


class Bot(Protocol):
    kind: str
    version: int
    index: int
    seed: int
    params: dict[str, object]

    def decide(self, view: BotView) -> tuple[Unit, ...]: ...


class BotRejection(SimError):
    """A bot emitted a unit the simulator refused: the bot, not the world, is wrong."""


def make_bot(kind: str, index: int, master_seed: int, world_seed: int) -> Bot:
    # The static bot reuses the random bot's seed of the same index (same start team).
    seed = mix(master_seed, world_seed, KIND_RANDOM, index)
    team_seed = mix(master_seed, world_seed, KIND_DISCIPLINED, index)
    if kind == "random":
        return RandomBot(index, seed)
    if kind == "static":
        return StaticBot(index, seed)
    # The news bot reuses the disciplined bot's seed of its index (same start team).
    if kind == "disciplined":
        return DisciplinedBot(index, team_seed)
    if kind == "disciplined-noguard":
        return DisciplinedBot(index, team_seed, guard=False)
    if kind == "news":
        return NewsBot(index, team_seed)
    if kind == "redeclare":
        return RedeclareBot(index, seed)
    if kind.startswith(NEWSMIX_PREFIX):
        digits = kind[len(NEWSMIX_PREFIX) :]
        if (
            digits.isascii()
            and digits.isdigit()
            and int(digits) <= 100
            and str(int(digits)) == digits
        ):
            coin_seed = mix(team_seed, KIND_NEWSMIX_COIN)
            return NewsMixBot(index, team_seed, coin_seed, int(digits))
    raise ValueError(f"unknown bot kind {kind!r}")


@dataclass(frozen=True)
class BotRun:
    record: dict[str, Any]
    units_by_turn: tuple[tuple[int, tuple[Unit, ...]], ...]
    decisions: dict[str, Any]


def _view(
    season: Season,
    pool_view: tuple[ViewAsset, ...],
    rates: Mapping[str, Decimal],
    feed: TurnFeed,
) -> BotView:
    turn = season.current_turn
    return BotView(
        turn=turn,
        season_turns=season.path_length,
        cash=season.cash,
        total=season.turns[-1].total if season.turns else None,
        holdings=MappingProxyType({a: q for a, (q, _) in season.holdings.items() if q}),
        prices=MappingProxyType(season.turn_prices[turn]),
        prev_prices=MappingProxyType(season.turn_prices[turn - 1]) if turn else None,
        formation=season.formation,
        pool=pool_view,
        rates=MappingProxyType(rates),
        feed=feed.items,
        signals=feed.signals,
        gainers=feed.gainers,
        losers=feed.losers,
        attention=feed.attention,
    )


def run_bot(
    world: BotWorld,
    bot: Bot,
    *,
    feed_source: FeedSource | None = None,
    counts_source: CountsSource | None = None,
    path_count: int = 2000,
    require_accepted: bool = True,
) -> BotRun:
    inputs = world.inputs
    s = inputs.settings
    if s.single_asset_limit is not None:
        raise SimInputError("the reference bots assume a world without a single-asset limit")
    if feed_source is None:
        feed_source = feed_source_for(world)
    season = Season(inputs)
    pool_view = tuple(ViewAsset(a.asset, a.market, a.system_position) for a in inputs.pool)
    rates = {
        a.asset: money.fee_rate(a.market, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate)
        for a in inputs.pool
    }
    units_by_turn: list[tuple[int, tuple[Unit, ...]]] = []
    totals: list[str] = []
    signals: list[TurnSignals] = []
    verdicts: Counter[str] = Counter()
    accepted = 0
    for turn in range(s.path_length):
        feed = TurnFeed()
        if feed_source is not None and turn >= 1:
            feed = feed_source.at(turn, tuple(MappingProxyType(p) for p in season.turn_prices))
            if feed.signals is not None:
                signals.append(feed.signals)
        units = bot.decide(_view(season, pool_view, rates, feed))
        for unit in units:
            verdict = apply_unit(season, unit)
            if verdict.accepted:
                accepted += 1
            else:
                verdicts[verdict.code] += 1
                if require_accepted:
                    raise BotRejection(f"{bot.kind} bot {bot.index} turn {turn}: {verdict.code}")
        if units:
            units_by_turn.append((turn, units))
        if turn == 0:
            held = [
                money.valuation(season.turn_prices[0][a], q)
                for a, (q, _) in season.holdings.items()
            ]
            totals.append(plain(money.total_value(season.cash, held)))
        totals_snapshot = season.advance()
        totals.append(plain(totals_snapshot.total))
    scored = score_season(
        season,
        counts_source,
        season_seed=world.seed,
        base_date=world.base_date,
        path_count=path_count,
        signals=signals if feed_source is not None else None,
    )
    decisions = io.decisions_doc(units_by_turn)
    record = _record(world, bot, season, scored, totals, accepted, verdicts, decisions)
    return BotRun(record, tuple(units_by_turn), decisions)


def _record(
    world: BotWorld,
    bot: Bot,
    season: Season,
    scored: ScoredSeason,
    totals: list[str],
    accepted: int,
    rejected: Counter[str],
    decisions: dict[str, Any],
) -> dict[str, Any]:
    discipline = scored.discipline
    return {
        "schema": io.RUN_SCHEMA,
        "worldId": world.world_id,
        "pathLength": season.path_length,
        "bot": {
            "kind": bot.kind,
            "version": bot.version,
            "index": bot.index,
            "seed": str(bot.seed),
            "params": bot.params,
        },
        "verdicts": {"accepted": accepted, "rejected": dict(sorted(rejected.items()))},
        "status": season.status.value,
        "totals": totals,
        "teamReturns": [plain(r) for r in team_returns(season)],
        "benchmarkReturns": [plain(r) for r in scored.observation.benchmark_returns],
        "report": scored.report.to_wire(),
        "score": scored.score.to_wire(),
        "noiseShare": None if scored.noise_share is None else plain(scored.noise_share),
        "discipline": {
            "panic": None
            if discipline is None or discipline.panic_sell_ratio is None
            else plain(discipline.panic_sell_ratio),
            "chase": None
            if discipline is None or discipline.chase_buy_ratio is None
            else plain(discipline.chase_buy_ratio),
        },
        "exclusion": None if scored.exclusion is None else scored.exclusion.value,
        "percentileRequest": None
        if scored.percentile_request is None
        else scored.percentile_request.to_wire(),
        "counts": None if scored.counts is None else _counts_doc(scored.counts),
        "decisionsSha256": io.decisions_sha256(decisions),
    }


def _counts_doc(counts: PercentileCounts) -> dict[str, Any]:
    def one(c: RankCounts) -> dict[str, int]:
        return {"below": c.below, "equal": c.equal, "n": c.n}

    return {"sharpeDiff": one(counts.sharpe_diff), "endMultiple": one(counts.end_multiple)}


def _run_spec(
    args: tuple[BotWorld, str, int, int, bool, CountsSource | None, int],
) -> dict[str, Any]:
    world, kind, index, master_seed, keep, counts_source, path_count = args
    run = run_bot(
        world,
        make_bot(kind, index, master_seed, world.seed),
        counts_source=counts_source,
        path_count=path_count,
    )
    record = dict(run.record)
    if keep:
        record["decisions"] = run.decisions
    return record


def run_batch(
    world: BotWorld,
    specs: Sequence[tuple[str, int]],
    master_seed: int,
    *,
    workers: int = 1,
    keep_decisions: bool = False,
    counts_source: CountsSource | None = None,
    path_count: int = 2000,
    progress: Callable[[int], None] | None = None,
) -> list[dict[str, Any]]:
    """One record per (kind, index) in the order of `specs`, whatever the worker count."""
    jobs = [
        (world, kind, index, master_seed, keep_decisions, counts_source, path_count)
        for kind, index in specs
    ]
    if workers <= 1:
        records = []
        for i, job in enumerate(jobs):
            records.append(_run_spec(job))
            if progress:
                progress(i + 1)
        return records
    with ProcessPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(_run_spec, jobs, chunksize=max(1, len(jobs) // (workers * 4))))
