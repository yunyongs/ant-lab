"""Reference key, world seed, bot run, cache and refusals of the manager-parts reference set."""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from leaderboard_support import SETTINGS_DOC, TEST_SIZE, FakeWorldReader, identity_of
from test_world_pg import rows

from antlab_engine.bots.runner import BotRejection, make_bot, run_bot
from antlab_engine.bots.world import BotWorld, synthetic_world
from antlab_engine.bots.world_pg import assemble
from antlab_engine.engine_errors import (
    EngineBusy,
    FeedInputUnusable,
    HistoryUnavailable,
    InvalidArgument,
    NoReference,
)
from antlab_engine.leaderboard import (
    ANTLAB_REF_MASTER,
    BOT_VERSION,
    REFERENCE_SIZE,
    REFERENCE_VERSION,
)
from antlab_engine.leaderboard.reference import (
    ReferenceService,
    build_reference,
    key_body,
    reference_key,
    world_seed,
)
from antlab_engine.sim import money
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError
from antlab_engine.sim.model import RISK_LIMITS, Formation, Settings, SimInputError, SimInputs
from antlab_engine.validation.components import REFERENCE_METRICS, ComponentError, components_of


def test_the_reference_constants_are_pinned():
    assert (REFERENCE_VERSION, REFERENCE_SIZE, ANTLAB_REF_MASTER, BOT_VERSION) == (
        "ref69-v1",
        200,
        69,
        "random/1",
    )


# ---------------------------------------------------------------------------- key and seed


def test_the_key_body_holds_the_identity_with_normalised_decimals():
    body = key_body(identity_of(), size=200, bot_version="random/1")
    assert body["settings"]["riskLimit"] == "-0.2"
    assert body["settings"]["initialCash"] == "100000000"
    assert body["settings"]["feeRateKr"] == "0.00015"
    assert body["months"][0] == "2009-01-01" and len(body["months"]) == 12
    assert set(body) == {"version", "baseDate", "months", "settings", "botVersion", "size"}


def test_the_scale_of_a_decimal_does_not_change_the_key():
    doc = {**SETTINGS_DOC, "riskLimit": "-0.2", "initialCash": "100000000.0000"}
    assert reference_key(identity_of(doc)) == reference_key(identity_of())


def _changed(field: str):
    identity = identity_of()
    s = identity.settings
    changes = {
        "risk limit": lambda: replace(identity, settings=replace(s, risk_limit=Decimal("-0.30"))),
        "initial cash": lambda: replace(identity, settings=replace(s, initial_cash=Decimal(10**7))),
        "fee kr": lambda: replace(identity, settings=replace(s, fee_rate_kr=Decimal("0.0002"))),
        "fee us": lambda: replace(identity, settings=replace(s, fee_rate_us=Decimal("0.003"))),
        "fx spread": lambda: replace(
            identity, settings=replace(s, fx_spread_rate=Decimal("0.005"))
        ),
        "formation": lambda: replace(
            identity, settings=replace(s, start_formation=Formation(20, 30, 30, 20))
        ),
        "member kind": lambda: replace(identity, settings=replace(s, member_kind="AGENT")),
        "month order": lambda: replace(identity, months=identity.months[::-1]),
        "base date": lambda: replace(identity, base_date=date(2025, 1, 2)),
        "version": lambda: replace(identity, reference_version="ref69-v2"),
    }
    return changes[field]()


@pytest.mark.parametrize(
    "field",
    [
        "risk limit",
        "initial cash",
        "fee kr",
        "fee us",
        "fx spread",
        "formation",
        "member kind",
        "month order",
        "base date",
        "version",
    ],
)
def test_every_identity_field_changes_the_key(field):
    assert reference_key(_changed(field)) != reference_key(identity_of())


def test_size_and_bot_version_change_the_key():
    identity = identity_of()
    base = reference_key(identity)
    assert reference_key(identity, size=199) != base
    assert reference_key(identity, bot_version="random/2") != base


def test_the_world_seed_is_the_first_eight_bytes_of_the_key_signed():
    assert world_seed("0000000000000001" + "f" * 48) == 1
    assert world_seed("8000000000000000" + "0" * 48) == -(2**63)
    assert world_seed("ffffffffffffffff" + "0" * 48) == -1
    key = reference_key(identity_of())
    assert world_seed(key) == int.from_bytes(bytes.fromhex(key[:16]), "big", signed=True)


# ---------------------------------------------------------------------------- the bot run


class SeedRecorder(FakeWorldReader):
    def __init__(self) -> None:
        super().__init__()
        self.seeds: list[int] = []

    def read(self, identity, seed):
        self.seeds.append(seed)
        return super().read(identity, seed)


def test_the_reference_is_random_bots_zero_to_n_on_the_key_seeded_world_without_feed():
    reader = SeedRecorder()
    service = ReferenceService(reader, size=TEST_SIZE)
    identity = identity_of()
    reference = service.reference(identity)
    key = service.key(identity)
    assert reader.seeds == [world_seed(key)]
    assert (reference.key, reference.size, reference.bot_version) == (key, TEST_SIZE, BOT_VERSION)
    world = reader.read(identity, world_seed(key))
    bots = [
        components_of(run_bot(world, make_bot("random", i, ANTLAB_REF_MASTER, world.seed)).record)
        for i in range(TEST_SIZE)
    ]
    for metric in REFERENCE_METRICS:
        expected = sorted(b.metric(metric) for b in bots)
        if metric == "risk_item":
            expected = [money.round2(v) for v in expected]
        assert list(reference.metrics.values[metric]) == expected, metric
    assert reference.metrics.world_id == world.world_id


def test_the_reference_ignores_the_feed_facts_of_the_world():
    reader = FakeWorldReader()
    identity = identity_of()
    key = reference_key(identity, size=3)
    plain = reader.read(identity, world_seed(key))
    fed = BotWorld(plain.seed, plain.base_date, plain.inputs, {"assets": "never read"})
    assert build_reference(fed, key, 3) == build_reference(plain, key, 3)


def test_a_world_seeded_from_anything_but_the_key_is_refused():
    reader = FakeWorldReader()
    identity = identity_of()
    key = reference_key(identity, size=3)
    with pytest.raises(ComponentError):
        build_reference(reader.read(identity, world_seed(key) + 1), key, 3)


def test_the_benchmark_of_the_reference_is_the_worlds():
    reader = FakeWorldReader()
    service = ReferenceService(reader, size=3)
    identity = identity_of()
    reference = service.reference(identity)
    world = reader.read(identity, world_seed(service.key(identity)))
    record = run_bot(world, make_bot("static", 0, 1, world.seed)).record
    assert reference.benchmark_returns == tuple(Decimal(r) for r in record["benchmarkReturns"])
    assert len(reference.benchmark_returns) == 12


@pytest.mark.parametrize("length", [12, 24, 60])
@pytest.mark.parametrize("limit", RISK_LIMITS)
def test_reference_bots_play_every_risk_limit_and_length_without_a_rejection(length, limit):
    doc = {**SETTINGS_DOC, "pathLength": length, "riskLimit": str(limit)}
    service = ReferenceService(FakeWorldReader(seed=length), size=3)
    assert service.reference(identity_of(doc)).size == 3


@pytest.mark.parametrize(
    "changes",
    [
        {"initialCash": "10000000"},
        {"startFormation": {"goalkeeper": 40, "defender": 20, "midfielder": 20, "forward": 20}},
        {"startFormation": {"goalkeeper": 1, "defender": 0, "midfielder": 0, "forward": 99}},
        {"memberKind": "AGENT", "feeRateKr": "0", "feeRateUs": "0", "fxSpreadRate": "0"},
    ],
)
def test_reference_bots_play_other_season_settings_without_a_rejection(changes):
    service = ReferenceService(FakeWorldReader(), size=3)
    assert service.reference(identity_of({**SETTINGS_DOC, **changes})).size == 3


# ---------------------------------------------------------------------------- cache and refusals


def test_a_cache_hit_returns_the_identical_object_without_a_read():
    reader = FakeWorldReader()
    service = ReferenceService(reader, size=3)
    first = service.reference(identity_of())
    assert service.reference(identity_of()) is first
    assert reader.calls == 1


def test_the_cache_drops_the_least_recently_used_reference():
    reader = FakeWorldReader()
    service = ReferenceService(reader, size=3, cache_size=2)
    a = identity_of()
    b = identity_of({**SETTINGS_DOC, "riskLimit": "-0.30"})
    c = identity_of({**SETTINGS_DOC, "riskLimit": "-0.40"})
    service.reference(a)
    service.reference(b)
    service.reference(a)  # a is now the most recent
    service.reference(c)  # drops b
    assert reader.calls == 3
    service.reference(a)
    assert reader.calls == 3
    service.reference(b)
    assert reader.calls == 4


def test_the_production_cache_holds_sixteen_references():
    reader = FakeWorldReader()
    service = ReferenceService(reader, size=1)
    identities = [identity_of(months=_months(start)) for start in range(2000, 2017)]
    for identity in identities:
        service.reference(identity)
    assert reader.calls == 17
    service.reference(identities[1])
    assert reader.calls == 17
    service.reference(identities[0])
    assert reader.calls == 18


def _months(year: int) -> list[str]:
    return [f"{year}-{m:02d}-01" for m in range(1, 13)]


def test_without_a_reader_the_history_is_unavailable():
    with pytest.raises(HistoryUnavailable):
        ReferenceService(None, size=3).reference(identity_of())


def test_a_second_build_while_one_runs_is_busy():
    service = ReferenceService(FakeWorldReader(), size=3)
    service._slot.acquire()
    try:
        with pytest.raises(EngineBusy):
            service.reference(identity_of())
    finally:
        service._slot.release()
    assert service.reference(identity_of()).size == 3


class FeedRefusing:
    def __init__(self, failure: Failure) -> None:
        self.failure = failure

    def read(self, identity, seed):
        raise FeedGenerationError(self.failure, "turn 3 asset KR:069500 has no return")


@pytest.mark.parametrize(
    "failure",
    [Failure.FEED_INPUT, Failure.FEED_EVENTS_UNREVIEWED, Failure.FEED_REACTION_MISSING],
)
def test_an_unusable_season_world_is_feed_input_and_names_nothing(failure):
    service = ReferenceService(FeedRefusing(failure), size=3)
    with pytest.raises(FeedInputUnusable) as error:
        service.reference(identity_of())
    assert "KR:" not in str(error.value) and "turn" not in str(error.value)
    assert error.value.__cause__ is None


# ---------------------------------------------------------------------------- world settings


def test_assemble_takes_the_season_settings():
    settings = Settings(risk_limit=Decimal("-0.30"), initial_cash=Decimal(10**7), path_length=12)
    world = assemble(rows(), 7, settings)
    assert world.inputs.settings == settings
    assert assemble(rows(), 7).inputs.settings == Settings(path_length=12)


def test_assemble_refuses_settings_of_another_length():
    with pytest.raises(InvalidArgument):
        assemble(rows(), 7, Settings(path_length=24))


def test_random_bot_decisions_do_not_depend_on_the_feed():
    fed = synthetic_world(3, assets=12, length=12, feed=True)
    bare = BotWorld(fed.seed, fed.base_date, fed.inputs)
    for index in range(3):
        with_feed = run_bot(fed, make_bot("random", index, ANTLAB_REF_MASTER, fed.seed)).record
        without = run_bot(bare, make_bot("random", index, ANTLAB_REF_MASTER, fed.seed)).record
        assert with_feed["decisionsSha256"] == without["decisionsSha256"]
        assert with_feed["totals"] == without["totals"]


class Raising:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def read(self, identity, seed):
        raise self.error


def test_a_world_the_simulator_refuses_is_no_reference_and_names_nothing():
    service = ReferenceService(Raising(SimInputError("turn 3: no return for KR:069500")), size=3)
    with pytest.raises(NoReference) as error:
        service.reference(identity_of())
    assert "KR:" not in str(error.value) and error.value.__cause__ is None


def test_a_world_with_a_single_asset_limit_is_no_reference():
    class Limited(FakeWorldReader):
        def read(self, identity, seed):
            world = super().read(identity, seed)
            limited = replace(world.inputs.settings, single_asset_limit=Decimal("0.30"))
            inputs = SimInputs(limited, world.inputs.pool, world.inputs.path)
            return BotWorld(world.seed, world.base_date, inputs)

    with pytest.raises(NoReference):
        ReferenceService(Limited(), size=3).reference(identity_of())


def test_a_bot_rejection_is_no_reference():
    service = ReferenceService(Raising(BotRejection("random bot 4 turn 7: NO_CASH")), size=3)
    with pytest.raises(NoReference):
        service.reference(identity_of())


def test_a_failed_build_is_not_cached():
    reader = FakeWorldReader()
    service = ReferenceService(Raising(SimInputError("x")), size=3)
    with pytest.raises(NoReference):
        service.reference(identity_of())
    service._reader = reader
    assert service.reference(identity_of()).size == 3
    assert reader.calls == 1
