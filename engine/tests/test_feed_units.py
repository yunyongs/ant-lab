"""Unit tests of the feed port: boundaries and refusals the vectors only touch from far away."""

import dataclasses
from datetime import date
from decimal import Decimal

import pytest

from antlab_engine.sim import money
from antlab_engine.sim.feed import facts as f
from antlab_engine.sim.feed.catalog import FILES, TEMPLATES_HEADER, Catalog, default_directory
from antlab_engine.sim.feed.feed import FeedGenerator, canonical_vars, group_facts
from antlab_engine.sim.feed.feedhash import byte_key, pick, utf16_key
from antlab_engine.sim.feed.ranking import RankingInput, board
from antlab_engine.sim.feed.rules import Failure, FeedGenerationError, grade
from antlab_engine.sim.feed.signals import FeedRow, SignalsError, signals_of


def d(text: str) -> Decimal:
    return Decimal(text)


def asset(aid: int, ticker: str, name: str, ret: str = "0.00000000", **kw) -> f.AssetFacts:
    base = {
        "as_of_turn": 5,
        "asset_id": aid,
        "market": "KR",
        "ticker": ticker,
        "name": name,
        "group_id": 1,
        "group_code": "G1",
        "group_name": "기술",
        "ret": d(ret),
        "prev_ret": None,
        "streak": 1,
        "attention": None,
        "attention_rank": None,
        "attention_n": 0,
        "prev_attention": None,
        "prev_attention_rank": None,
        "prev_story_direction": None,
        "prev_echo_repeat": 1,
        "focus_name": None,
    }
    base.update(kw)
    return f.AssetFacts(**base)


def event(key: str, category: str | None, hidden: bool = False, turn: int = 5) -> f.EventFacts:
    return f.EventFacts(turn, 1, key, category, d("0.02000000"), 2, hidden)


GENERATOR = FeedGenerator(Catalog.load())


@pytest.mark.parametrize(
    ("rank", "n", "expected"),
    [
        (None, 5, None),
        (1, 0, None),
        (1, 1, 3),
        (1, 10, 3),
        (2, 10, 2),
        (1, 11, 3),
        (2, 11, 3),
        (3, 11, 2),
        (6, 11, 2),
        (7, 11, 1),
        (5, 10, 2),
        (6, 10, 1),
    ],
)
def test_grade_boundaries(rank, n, expected):
    assert grade(rank, n) == expected


def test_streak_counts_a_run_of_zero_changes_as_a_sign_of_its_own():
    assert f.streak([d("100.0000"), d("100.0000"), d("100.0000")]) == 2
    assert f.streak([d("100.0000"), d("110.0000"), d("110.0000")]) == 1
    assert f.streak([d("100.0000"), d("90.0000"), d("95.0000"), d("99.0000")]) == 2
    assert f.streak([d("100.0000"), d("110.0000")]) == 1


def test_change_rounds_half_up_once_to_eight_digits():
    prices = [d("3.0000"), d("4.0000")]
    assert f.change(prices, 1) == d("0.33333333")
    assert f.change([d("1.0000"), d("2.0000")], 1) == d("1.00000000")
    # 100.0001 / 100.0000 - 1 = 0.000001 exactly
    assert f.change([d("100.0000"), d("100.0001")], 1) == d("0.00000100")
    assert f.change([d("200000.0000"), d("200000.0001")], 1) == d("0.00000000")


def test_asset_facts_refuse_a_return_that_is_not_scale_eight():
    with pytest.raises(FeedGenerationError) as e:
        asset(1, "A1", "가나", "0.0100")
    assert e.value.failure is Failure.FEED_INPUT


def test_feed_input_refuses_another_turn_a_duplicate_asset_and_a_duplicate_event():
    a = asset(1, "A1", "가나")
    with pytest.raises(FeedGenerationError) as e:
        f.FeedInput(1, 6, [a], [])
    assert e.value.failure is Failure.FEED_INPUT
    with pytest.raises(FeedGenerationError):
        f.FeedInput(1, 5, [a, a], [])
    with pytest.raises(FeedGenerationError):
        f.FeedInput(1, 5, [], [event("e1", "TRADE"), event("e1", "POLICY")])
    with pytest.raises(FeedGenerationError):
        f.FeedInput(1, 5, [], [event("e1", "TRADE", turn=4)])
    with pytest.raises(FeedGenerationError):
        f.FeedInput(1, 0, [], [])


def test_event_without_reaction_or_with_inconsistent_hidden_flag_is_refused():
    with pytest.raises(FeedGenerationError) as e:
        f.EventFacts(5, 1, "e1", "TRADE", None, 1, False)
    assert e.value.failure is Failure.FEED_REACTION_MISSING
    with pytest.raises(FeedGenerationError) as e:
        event("e1", "TRADE", hidden=True)
    assert e.value.failure is Failure.FEED_INPUT
    with pytest.raises(FeedGenerationError):
        event("e1", None, hidden=False)


def test_an_event_without_a_template_is_a_render_failure():
    inp = f.FeedInput(1, 5, [], [event("e1", "NO_SUCH_CATEGORY")])
    with pytest.raises(FeedGenerationError) as e:
        GENERATOR.generate(inp)
    assert e.value.failure is Failure.FEED_RENDER


def test_no_candidate_is_a_valid_zero_row_result():
    assert GENERATOR.generate(f.FeedInput(1, 5, [asset(1, "A1", "가나")], [])) == []
    assert GENERATOR.generate(f.FeedInput(1, 5, [], [])) == []


def test_events_only_gives_exactly_the_events():
    inp = f.FeedInput(
        1, 5, [asset(1, "A1", "가나")], [event("evt1", "TRADE"), event("evt2", "HEALTH")]
    )
    rows = GENERATOR.generate(inp)
    assert [r.kind for r in rows] == ["EVENT", "EVENT"]
    assert [r.seq for r in rows] == [1, 2]
    assert {r.month_event_id for r in rows} == {1}
    assert all(r.template_vars_json == '{"reaction": 0.02000000}' for r in rows)


def test_more_than_fifteen_rows_is_a_render_failure():
    events = [event(f"evt{i:03d}", "TRADE") for i in range(16)]
    with pytest.raises(FeedGenerationError) as e:
        GENERATOR.generate(f.FeedInput(1, 5, [], events))
    assert e.value.failure is Failure.FEED_RENDER


def test_the_same_input_gives_the_same_rows():
    assets = [asset(i, f"A{i}", f"이름{i}", "0.03000000") for i in range(1, 9)]
    inp = f.FeedInput(7, 5, assets, [event("evt1", "TRADE")])
    assert GENERATOR.generate(inp) == GENERATOR.generate(inp)
    other = f.FeedInput(8, 5, assets, [event("evt1", "TRADE")])
    assert GENERATOR.generate(inp) != GENERATOR.generate(other)


def test_a_zero_percent_asset_move_never_gets_an_asset_row():
    # 0.4 percent rounds to a whole 0 percent: no asset template of the pool keeps the card.
    tiny = [asset(i, f"A{i}", f"이름{i}", "0.00400000") for i in range(1, 9)]
    half = [asset(i, f"A{i}", f"이름{i}", "0.00500000") for i in range(1, 9)]
    tiny_rows = []
    half_rows = []
    for seed in range(1, 12):
        tiny_rows += GENERATOR.generate(f.FeedInput(seed, 5, tiny, []))
        half_rows += GENERATOR.generate(f.FeedInput(seed, 5, half, []))
    assert tiny_rows
    assert all(r.asset_id is None for r in tiny_rows)
    assert any(r.asset_id is not None for r in half_rows)


def test_canonical_vars_orders_keys_by_length_then_bytes_and_escapes_strings():
    text = canonical_vars({"ret": d("0.01000000"), "asset_name": '가"나\\', "a": d("3")})
    assert text == '{"a": 3, "ret": 0.01000000, "asset_name": "가\\"나\\\\"}'
    assert canonical_vars({"k": "\n\x01"}) == '{"k": "\\n\\u0001"}'


def test_text_keys_compare_by_utf8_bytes_and_group_names_by_utf16_units():
    assert byte_key("asset:KR:F1") < byte_key("asset:KR:F10")
    assert byte_key("B") < byte_key("a")
    assert byte_key("가") < byte_key("나")
    assert byte_key("Ａ") < byte_key("\U0001f600")
    assert utf16_key("\U0001f600") < utf16_key("Ａ")
    assert pick(1, 1, "count", "") == pick(1, 1, "count", "")


def test_group_name_is_the_largest_by_utf16_code_units():
    a = asset(1, "A1", "가나", group_name="Ａ")
    b = asset(2, "A2", "다라", group_name="\U0001f600")
    (g,) = group_facts((a, b))
    assert g.name == "Ａ"


def test_group_figures_mean_round_once_and_rank_among_groups():
    a = asset(1, "A1", "가나", "0.01000000", group_id=1, attention=d("2.0"))
    b = asset(2, "A2", "다라", "0.00000001", group_id=1, attention=d("4.0"))
    c = asset(3, "A3", "마바", "-0.02000000", group_id=2, group_code="G2", attention=d("4.0"))
    g1, g2 = group_facts((a, b, c))
    assert g1.ret == d("0.00500001")  # 0.0100000 + 0.00000001 over two, half up at 8 digits
    assert g1.attention == d("3.0")
    assert (g1.rank, g2.rank, g1.ranked) == (2, 1, 2)
    assert g2.ret == d("-0.02000000")


def test_attention_rank_and_focus_ties_use_market_and_ticker_byte_order():
    pool = [
        f.PoolAsset(1, "KR", "B2", "나나", 1, "G1", "기술"),
        f.PoolAsset(2, "KR", "A1", "가가", 1, "G1", "기술"),
        f.PoolAsset(3, "US", "A1", "다다", 1, "G1", "기술"),
    ]
    history = {i: [d("100.0000")] for i in (1, 2, 3)}
    current = {i: d("101.0000") for i in (1, 2, 3)}
    attention = {1: d("5"), 2: d("5"), 3: d("4")}
    built = f.build_asset_facts(1, pool, history, current, attention, {}, {})
    assert [a.attention_rank for a in built] == [1, 1, 3]
    assert {a.focus_name for a in built} == {"가가"}
    assert all(a.attention_n == 3 for a in built)
    assert all(a.prev_ret is None for a in built)


def test_build_refuses_a_pool_asset_with_an_incomplete_history():
    pool = [f.PoolAsset(1, "KR", "A1", "가가", 1, "G1", "기술")]
    with pytest.raises(FeedGenerationError):
        f.build_asset_facts(2, pool, {1: [d("100.0000")]}, {1: d("101.0000")}, {}, {}, {})
    with pytest.raises(FeedGenerationError):
        f.build_asset_facts(1, pool, {1: [d("100.0000")]}, {}, {}, {}, {})


def test_previous_story_takes_the_last_direction_and_the_largest_echo_repeat():
    rows = [
        f.PreviousRow(1, "RISE", "UP", 0),
        f.PreviousRow(1, "ECHO", "DOWN", 2),
        f.PreviousRow(1, "FADE", None, 0),
        f.PreviousRow(2, "ECHO", "UP", 3),
        f.PreviousRow(2, "ECHO", "UP", 1),
    ]
    stories = f.previous_stories(rows, 3)
    assert stories[1] == f.Story("DOWN", 2)
    assert stories[2] == f.Story("UP", 3)
    assert f.previous_stories(rows, 1) == {}


def test_public_types_count_key_families_only_inside_the_replay_range():
    start = date(2007, 1, 1)
    episodes = [
        ("A", "aaaaaa_1", date(2007, 1, 1)),
        ("A", "aaaaaa_2", date(2008, 5, 1)),  # same family: still one
        ("A", "bbbbbb_1", date(2009, 1, 1)),
        ("A", "cccccc_1", date(2026, 1, 1)),  # base-date year: outside the range
        ("A", "dddddd_1", date(2006, 12, 1)),  # before the replay start: outside
        ("B", "aaaaaa_1", date(2007, 1, 1)),
        ("B", "bbbbbb_1", date(2009, 1, 1)),
        ("B", "cccccc_1", date(2025, 12, 1)),
        ("C", "aaaaaa_1", date(2026, 3, 1)),
    ]
    assert f.public_event_types(episodes, start, 2026) == {"B"}
    assert f.public_event_types(episodes, start, 2027) == {"A", "B"}
    assert f.public_event_types(episodes, date(2006, 12, 1), 2026) == {"A", "B"}
    public = f.public_event_types(episodes, start, 2026)
    assert [f.is_hidden_type(t, public) for t in ("A", "B", "C", "never seen")] == [
        True,
        False,
        True,
        True,
    ]


def test_the_input_types_hold_no_path_block_month_or_date_field():
    banned = ("path", "block", "month_start", "date", "future", "next")
    for cls in (f.AssetFacts, f.EventFacts, f.FeedInput, RankingInput):
        for field in dataclasses.fields(cls):
            assert not any(word in field.name for word in banned), (cls.__name__, field.name)


def test_board_lists_cut_at_five_and_break_ties_by_market_and_ticker():
    assets = [
        asset(
            i,
            f"T{i:02d}",
            f"이름{i}",
            "0.02000000",
            streak=3,
            attention=d("1.5"),
            attention_rank=1,
            attention_n=8,
        )
        for i in range(1, 9)
    ]
    b = board(RankingInput(5, tuple(assets)))
    assert [r.asset_id for r in b.gainers] == [1, 2, 3, 4, 5]
    assert [r.rank for r in b.gainers] == [1, 2, 3, 4, 5]
    assert b.losers == []
    assert [r.asset_id for r in b.rising_streaks] == [1, 2, 3, 4, 5]
    assert len(b.attention) == 5
    assert [r.change_rate for r in b.group_gainers] == [d("0.02000000")]


def test_board_orders_losers_ascending_and_streaks_by_length_then_size():
    assets = [
        asset(1, "A1", "가", "-0.01000000", streak=2),
        asset(2, "A2", "나", "-0.03000000", streak=2),
        asset(3, "A3", "다", "-0.02000000", streak=5),
        asset(4, "A4", "라", "0.02000000", streak=1),
    ]
    b = board(RankingInput(5, tuple(assets)))
    assert [r.asset_id for r in b.losers] == [2, 3, 1]
    assert [r.asset_id for r in b.falling_streaks] == [3, 2, 1]
    assert b.rising_streaks == []


def test_ranking_input_refuses_disagreeing_attention_and_rank():
    with pytest.raises(FeedGenerationError):
        RankingInput(5, (asset(1, "A1", "가", attention=d("1"), attention_rank=None),))
    with pytest.raises(FeedGenerationError):
        RankingInput(5, (asset(1, "A1", "가", attention=d("1"), attention_rank=3, attention_n=2),))
    with pytest.raises(FeedGenerationError):
        RankingInput(5, (asset(1, "A1", "가", attention=None, attention_rank=1, attention_n=1),))
    with pytest.raises(FeedGenerationError):
        RankingInput(6, (asset(1, "A1", "가"),))


def test_signals_follow_the_stated_rules_and_refuse_mixed_turns():
    assets = (
        asset(1, "A1", "가", "0.03000000", attention=d("9"), attention_rank=1, attention_n=3),
        asset(2, "A2", "나", "-0.03000000", attention=d("5"), attention_rank=2, attention_n=3),
        asset(3, "A3", "다", "0.00000000", attention=d("1"), attention_rank=3, attention_n=3),
        asset(4, "A4", "라"),
    )
    rows = [
        FeedRow(5, "ECHO", 4, "DOWN"),
        FeedRow(5, "FADE", 3, None),
        FeedRow(5, "SPREAD", None, "UP"),
        FeedRow(5, "EVENT", None, None),
    ]
    s = signals_of(5, rows, RankingInput(5, assets))
    assert s.up == {1, 2, 3}
    assert s.down == {2, 4}
    assert s.top_attention == {1}
    with pytest.raises(SignalsError):
        signals_of(5, [FeedRow(4, "RISE", 1, "UP")], RankingInput(5, assets))
    with pytest.raises(SignalsError):
        signals_of(4, [], RankingInput(5, assets))


def test_pool_drops_zero_percent_candidates_except_event_shaped_sentences():
    catalog = Catalog.load()
    keys = {"asset_name", "group_name", "ret"}
    full = catalog.pool("RISE", "ASSET", "UP", 3, keys, False, None)
    zero = catalog.pool("RISE", "ASSET", "UP", 0, keys, False, None)
    assert full
    assert len(zero) < len(full)
    assert [t.id for t in full] == sorted((t.id for t in full), key=byte_key)
    assert catalog.pool("RISE", "ASSET", "UP", None, keys, False, None) == full


def test_attention_ranks_count_pool_assets_only():
    pool = [
        f.PoolAsset(1, "KR", "A1", "가가", 1, "G1", "기술"),
        f.PoolAsset(2, "KR", "A2", "나나", 1, "G1", "기술"),
    ]
    history = {1: [d("100.0000")], 2: [d("100.0000")]}
    current = {1: d("101.0000"), 2: d("101.0000")}
    attention = {1: d("5"), 2: d("3"), 99: d("9")}
    before = {1: d("4"), 2: d("6"), 99: d("9")}
    built = f.build_asset_facts(1, pool, history, current, attention, before, {})
    assert [a.attention_rank for a in built] == [1, 2]
    assert [a.prev_attention_rank for a in built] == [2, 1]
    assert all(a.attention_n == 2 for a in built)


def test_prices_are_rescaled_to_stored_scale_or_refused():
    pool = [f.PoolAsset(1, "KR", "A1", "가가", 1, "G1", "기술")]
    ok = f.build_asset_facts(1, pool, {1: [d("100")]}, {1: d("101.50")}, {}, {}, {})
    assert ok[0].ret == d("0.01500000")
    with pytest.raises(money.MoneyError):
        f.build_asset_facts(1, pool, {1: [d("100.00001")]}, {1: d("101")}, {}, {}, {})
    with pytest.raises(money.MoneyError):
        f.build_asset_facts(1, pool, {1: [d("100")]}, {1: d("101.00001")}, {}, {}, {})


def test_event_pool_filters_on_type_and_outcome():
    real = Catalog.load()
    files = {name: (default_directory() / name).read_bytes() for name in FILES}
    header = ",".join(TEMPLATES_HEADER) + "\n"
    rows = (
        "event.plain.v1,EVENT,ACTIVE,NONE,,,TRADE,,,false,무역 {reaction}\n"
        "event.typed.v1,EVENT,ACTIVE,NONE,,,TRADE,SOME_TYPE,,false,유형 {reaction}\n"
        "event.outcome.v1,EVENT,ACTIVE,NONE,,,TRADE,,WIN,false,결과 {reaction}\n"
    )
    files["templates.csv"] = (header + rows).encode("utf-8")
    hand = Catalog(files)
    pool = hand.pool("EVENT", "NONE", None, None, {"reaction"}, False, "TRADE")
    assert [t.id for t in pool] == ["event.plain.v1"]
    assert real.sha256["templates.csv"] != hand.sha256["templates.csv"]
