"""Season report: the Java hand cases replayed by value, then reports of played seasons.

Expected numbers of the first block are copied from Java `SeasonReportCalculatorTest` (they were
computed there with independent Python `Decimal` and `Fraction` code).
"""

from dataclasses import replace
from decimal import Decimal

import pytest
from report_support import (
    C_ASSET,
    KR,
    US,
    A,
    B,
    bd,
    eight_turns,
    holding,
    no_orders,
    prices,
    returns,
    role_holdings,
    season_facts,
    three_turns,
    turn,
    with_,
    won,
)

from antlab_engine.sim import measure, money, report
from antlab_engine.sim.model import Position, SeasonStatus, SimReportError
from antlab_engine.sim.report import (
    ChangeCost,
    ChangeFact,
    LineupFact,
    OrderFacts,
    ReconFacts,
    ReportSources,
    calculate,
)

JAVA_ORDER = [
    "seasonStatus",
    "isFinal",
    "reportTurns",
    "pathLength",
    "pendingOrders",
    "pendingChanges",
    "initialCash",
    "finalValue",
    "totalReturn",
    "monthlyVolatility",
    "maxDrawdown",
    "baselineFinalValue",
    "baselineReturn",
    "excessReturn",
    "riskLimit",
    "riskBreachTurns",
    "riskBreachDepth",
    "maxSingleWeight",
    "tradeNotional",
    "turnoverAnnual",
    "formationDeviation",
    "measuredFormationDeviation",
    "roleFit",
    "rolePlayersJudged",
    "rolePlayersUnjudged",
    "nudgeShare",
    "nudgeSameTurnShare",
    "totalFee",
    "teamFee",
    "formationChangeFee",
    "platformNudgeFee",
    "formationChanges",
    "formationChangeOrders",
    "changesWithoutOrders",
    "reconMismatchRows",
    "reconTurnBundles",
    "reconManualBundles",
    "reconOutOfRangeRows",
]


def wire(src: ReportSources) -> dict:
    return calculate(src).to_wire()


def test_the_report_has_the_thirty_eight_java_keys_in_java_order():
    assert list(wire(three_turns())) == JAVA_ORDER
    assert len(JAVA_ORDER) == 38


def test_three_turn_finished_season_matches_the_java_hand_values():
    w = wire(three_turns())
    expected = {
        "seasonStatus": "FINISHED",
        "isFinal": True,
        "reportTurns": 3,
        "pathLength": 3,
        "pendingOrders": 0,
        "pendingChanges": 0,
        "initialCash": "30000000",
        "finalValue": "30900002",
        "totalReturn": "0.03000007",
        "monthlyVolatility": "0.03010355",
        "maxDrawdown": "-0.01980198",
        "baselineFinalValue": "32884069",
        "baselineReturn": "0.09613563",
        "excessReturn": "-0.06613557",
        "riskLimit": "-0.01000000",
        "riskBreachTurns": 1,
        "riskBreachDepth": "-0.00980198",
        "maxSingleWeight": "0.12944983",
        "tradeNotional": "12000000",
        "turnoverAnnual": "1.58415838",
        "formationDeviation": "0.20206162",
        "nudgeShare": "0.25000000",
        "nudgeSameTurnShare": "0.10000000",
        "totalFee": "60000",
        "teamFee": "40000",
        "platformNudgeFee": "5000",
        "formationChangeFee": "1700",
        "formationChanges": 3,
        "formationChangeOrders": 3,
        "changesWithoutOrders": 1,
        "reconMismatchRows": 2,
        "reconTurnBundles": 3,
        "reconManualBundles": 1,
        "reconOutOfRangeRows": 0,
    }
    assert {k: w[k] for k in expected} == expected
    # three turns are fewer than the six months a season needs: nobody can be judged
    assert w["roleFit"] is None and w["rolePlayersJudged"] == 0 and w["rolePlayersUnjudged"] == 2


def test_excess_return_is_divided_once_not_the_difference_of_two_returns():
    r = calculate(three_turns())
    assert r.total_return - r.baseline_return == Decimal("-0.06613556")
    assert r.excess_return == Decimal("-0.06613557")


def test_baseline_buys_whole_shares_and_keeps_the_rest_as_cash():
    # 499 x 33,001 + 213 x 77,000 + 15,570 = 32,884,069
    assert calculate(three_turns()).baseline_final_value == Decimal(32_884_069)
    bench = {KR: prices(30_000, 1, 1, 34_000), US: prices(70_000, 1, 1, 77_000)}
    assert calculate(with_(three_turns(), benchmark_prices=bench)).baseline_final_value == Decimal(
        33_382_570
    )


def test_the_later_change_of_a_turn_is_the_next_turn_target():
    base = three_turns()
    assert calculate(base).formation_deviation == Decimal("0.20206162")
    only_first = with_(base, changes=(base.changes[0], base.changes[2]))
    assert calculate(only_first).formation_deviation == Decimal("0.18028833")


def test_no_change_means_the_start_formation_is_the_target():
    r = calculate(with_(three_turns(), changes=(), change_costs=()))
    assert (r.formation_changes, r.formation_change_orders, r.changes_without_orders) == (0, 0, 0)
    assert r.formation_change_fee == Decimal(0)
    assert r.formation_deviation > 0


def test_a_change_cost_of_the_report_turn_is_not_counted():
    base = three_turns()
    costs = (*base.change_costs, ChangeCost(14, 3, 4, won(999)))
    r = calculate(with_(base, change_costs=costs))
    assert (r.formation_changes, r.formation_change_orders) == (3, 3)
    assert r.formation_change_fee == Decimal(1700)


def test_no_breach_means_zero_depth_and_equal_to_the_limit_is_not_a_breach():
    base = three_turns()
    loose = with_(base, season=season_facts(risk_limit="-0.50000000"))
    r = calculate(loose)
    assert r.risk_breach_turns == 0 and report.plain(r.risk_breach_depth) == "0.00000000"
    equal = with_(base, season=season_facts(risk_limit="-0.01980198"))
    r = calculate(equal)
    assert r.risk_breach_turns == 0 and report.plain(r.risk_breach_depth) == "0.00000000"


def test_without_holdings_the_max_weight_is_zero_and_role_fit_is_empty():
    r = calculate(with_(three_turns(), holdings=(), lineup=()))
    assert report.plain(r.max_single_weight) == "0.00000000"
    assert r.role_fit is None and (r.role_players_judged, r.role_players_unjudged) == (0, 0)


def test_no_traded_amount_means_no_nudge_share_and_zero_turnover():
    r = calculate(with_(three_turns(), orders=no_orders()))
    assert r.nudge_share is None and r.nudge_same_turn_share is None
    assert report.plain(r.turnover_annual) == "0.00000000" and r.total_fee == 0


def test_team_fee_stays_in_totals_when_nothing_was_traded_after_turn_zero():
    only_team = OrderFacts(won(0), won(7_000), won(7_000), won(0), won(0), won(0), 0, 0)
    r = calculate(with_(three_turns(), orders=only_team))
    assert (r.total_fee, r.team_fee, r.trade_notional) == (7000, 7000, 0)
    assert r.nudge_share is None and report.plain(r.turnover_annual) == "0.00000000"


def test_one_turn_season_has_no_volatility():
    facts = season_facts(path_length=1, current=1, risk_limit="-0.10000000")
    src = ReportSources(
        facts,
        (turn(1, 30_300_000, "0.00000000", 6_060_000, 9_090_000, 9_090_000, 6_060_000),),
        (),
        no_orders(),
        (),
        (),
        {KR: returns("0.01"), US: returns("0.01")},
        {KR: prices(30_000, 31_000), US: prices(70_000, 72_000)},
        (),
        ReconFacts(0, 1, 0, 0),
    )
    r = calculate(src)
    assert r.monthly_volatility is None and r.report_turns == 1
    assert (
        report.plain(r.formation_deviation) == "0.00000000"
        and report.plain(r.max_drawdown) == "0.00000000"
    )


# ---------------------------------------------------------------- role fit on a measurable season


def test_role_fit_is_the_exposed_value_share_of_matching_players():
    r = calculate(eight_turns(Position.GOALKEEPER, Position.MIDFIELDER, role_holdings()))
    assert r.role_fit == Decimal("0.63636364")  # 7,000,000 / 11,000,000
    assert (r.role_players_judged, r.role_players_unjudged) == (2, 1)


def test_role_fit_is_one_when_every_declaration_matches_and_zero_when_none_does():
    right = calculate(eight_turns(Position.GOALKEEPER, Position.DEFENDER, role_holdings()))
    wrong = calculate(eight_turns(Position.DEFENDER, Position.GOALKEEPER, role_holdings()))
    assert (
        report.plain(right.role_fit) == "1.00000000"
        and report.plain(wrong.role_fit) == "0.00000000"
    )


def test_only_an_unjudgeable_holding_leaves_role_fit_empty():
    src = eight_turns(Position.GOALKEEPER, Position.DEFENDER, [holding(C_ASSET, 3, won(300_000))])
    r = calculate(src)
    assert r.role_fit is None and (r.role_players_judged, r.role_players_unjudged) == (0, 1)


def test_a_changed_declaration_counts_each_span_but_one_player():
    lineup = (
        LineupFact(A, Position.DEFENDER, 0, 3),
        LineupFact(A, Position.GOALKEEPER, 3, None),
        LineupFact(B, Position.DEFENDER, 0, None),
        LineupFact(C_ASSET, Position.DEFENDER, 0, None),
    )
    base = eight_turns(Position.GOALKEEPER, Position.DEFENDER, role_holdings())
    r = calculate(with_(base, lineup=lineup))
    assert r.role_fit == Decimal("0.81818182")  # (5,000,000 + 4,000,000) / 11,000,000
    assert (r.role_players_judged, r.role_players_unjudged) == (2, 1)


def test_an_unjudgeable_player_counts_once_even_with_two_spans():
    lineup = (
        LineupFact(A, Position.GOALKEEPER, 0, None),
        LineupFact(B, Position.MIDFIELDER, 0, None),
        LineupFact(C_ASSET, Position.DEFENDER, 0, 3),
        LineupFact(C_ASSET, Position.MIDFIELDER, 3, None),
    )
    base = eight_turns(Position.GOALKEEPER, Position.DEFENDER, role_holdings())
    holdings = (*base.holdings, holding(C_ASSET, 5, won(300_000)))
    r = calculate(with_(base, holdings=holdings, lineup=lineup))
    assert (r.role_players_judged, r.role_players_unjudged) == (2, 1)
    assert r.role_fit == Decimal("0.63636364")


def test_measured_positions_of_the_eight_turn_season():
    got = report.season_measured(eight_turns(Position.GOALKEEPER, Position.DEFENDER, ()))
    assert got[A] is Position.GOALKEEPER and got[B] is Position.DEFENDER
    assert C_ASSET not in got and set(got) >= {A, B}


def test_measurement_cache_hit_equals_a_fresh_measurement_and_keys_on_the_path():
    src = eight_turns(Position.GOALKEEPER, Position.DEFENDER, ())
    t = src.season.current_turn
    fresh = report._measure_pool(t, src.pool_returns)
    assert report.season_measured(src) == fresh
    assert report.season_measured(src) == fresh  # second call is the cache hit
    flipped = dict(src.pool_returns)
    flipped[A] = [None if r is None else -r for r in src.pool_returns[A]]
    other = with_(src, pool_returns=flipped)
    assert report.season_measured(other) == report._measure_pool(t, flipped)
    assert report.season_measured(src) == fresh
    result = report.season_measured(src)
    result.clear()
    assert report.season_measured(src) == fresh


def test_measurement_cache_keeps_equal_values_of_different_scale_apart():
    src = eight_turns(Position.GOALKEEPER, Position.DEFENDER, ())
    rescaled = {
        k: [None if r is None else r.quantize(Decimal(1).scaleb(-12)) for r in v]
        for k, v in src.pool_returns.items()
    }
    assert rescaled[A] == src.pool_returns[A]
    report._MEASURED_CACHE.clear()
    report.season_measured(src)
    report.season_measured(with_(src, pool_returns=rescaled))
    assert len(report._MEASURED_CACHE) == 2


# ---------------------------------------------------------------- refusals, in Java's order


def broken(src: ReportSources, **changes) -> ReportSources:
    return with_(src, **changes)


def test_inconsistent_sources_raise_before_any_ratio():
    base = three_turns()
    zero_turn = (*base.turns[:2], replace(base.turns[2], total_value=won(0)))
    cases = {
        "turn out of range": broken(base, season=season_facts(current=4)),
        "turn zero": broken(base, season=season_facts(current=0)),
        "snapshot gap": broken(base, turns=(base.turns[0], base.turns[2])),
        "snapshot order": broken(base, turns=(base.turns[1], base.turns[0], base.turns[2])),
        "a turn without value": broken(base, turns=zero_turn),
        "order after report": broken(base, orders=replace(base.orders, orders_after_report=1)),
        "finished with pending orders": broken(base, orders=replace(base.orders, pending_orders=1)),
        "finished with a pending change": broken(
            base, changes=(*base.changes, ChangeFact(14, 3, 25, 25, 25, 25))
        ),
        "finished before the end": broken(base, season=season_facts(path_length=4)),
        "missing benchmark": broken(base, benchmark_prices={KR: base.benchmark_prices[KR]}),
        "short benchmark": broken(
            base, benchmark_prices={KR: prices(1, 2, 3), US: base.benchmark_prices[US]}
        ),
        "missing market proxy": broken(base, pool_returns={KR: base.pool_returns[KR]}),
        "short returns": broken(
            base, pool_returns={KR: returns("0.01"), US: base.pool_returns[US]}
        ),
        "holding out of range": broken(base, holdings=(holding(A, 4, won(1)),)),
        "negative holding": broken(base, holdings=(holding(A, 1, won(-1)),)),
        "no open span": broken(base, lineup=()),
    }
    for name, src in cases.items():
        with pytest.raises(SimReportError):
            calculate(src)
        assert name


def test_a_held_asset_without_measured_or_system_position_is_refused():
    base = eight_turns(Position.GOALKEEPER, Position.DEFENDER, ())
    held = (replace(holding(C_ASSET, 3, won(300_000)), system=None),)
    lineup = (*base.lineup,)
    with pytest.raises(SimReportError):
        calculate(with_(base, holdings=held, lineup=lineup))


def test_an_unmeasured_holding_falls_back_on_its_base_date_position():
    base = eight_turns(Position.GOALKEEPER, Position.DEFENDER, ())
    as_defender = (replace(holding(C_ASSET, 3, won(300_000)), system=Position.DEFENDER),)
    as_keeper = (replace(holding(C_ASSET, 3, won(300_000)), system=Position.GOALKEEPER),)
    a = calculate(with_(base, holdings=as_defender)).measured_formation_deviation
    b = calculate(with_(base, holdings=as_keeper)).measured_formation_deviation
    assert a > b  # the target is 100 % goalkeeper, so a defender holding deviates more


def test_measured_class_values_above_the_total_are_refused():
    base = eight_turns(Position.GOALKEEPER, Position.DEFENDER, ())
    huge = (holding(B, 1, won(31_000_000)),)  # defender measured, bigger than the 30.1M total
    with pytest.raises(SimReportError):
        calculate(with_(base, holdings=huge, lineup=(LineupFact(B, Position.DEFENDER, 0, None),)))


def test_a_value_that_is_not_whole_won_is_refused():
    base = three_turns()
    fractional = replace(base.turns[0], defender_value=bd("9090000.005"))
    with pytest.raises(SimReportError):
        calculate(with_(base, turns=(fractional, *base.turns[1:])))


def test_an_odd_initial_cash_cannot_buy_the_half_budget_baseline():
    """Java `MoneyMath.teamQty` needs a whole-won budget; half of an odd cash is not."""
    odd = replace(three_turns().season, initial_cash=won(30_000_001))
    with pytest.raises(money.MoneyError):
        calculate(with_(three_turns(), season=odd))


def test_the_status_is_carried_and_only_finished_is_final():
    base = three_turns()
    mid = with_(
        base,
        season=season_facts(status=SeasonStatus.IN_PROGRESS, path_length=5, current=3),
        orders=replace(base.orders, pending_orders=2),
    )
    r = calculate(mid)
    assert r.season_status is SeasonStatus.IN_PROGRESS and r.is_final is False
    assert (r.path_length, r.pending_orders) == (5, 2)


def test_plain_never_prints_a_negative_zero_or_an_exponent():
    assert report.plain(Decimal("-0E-8")) == "0.00000000"
    assert report.plain(Decimal("1E+3")) == "1000"
    assert report.plain(Decimal("-0.5")) == "-0.5"


# ------------------------------------------------------------ position meter


def test_max_drawdown_is_the_worst_peak_to_trough_of_the_compounded_path():
    path = [bd("0.10"), bd("-0.20"), bd("0.05")]
    # wealth 1.1, 0.88, 0.924 -> worst 0.88/1.1 - 1 = -0.2
    assert report.plain(measure.max_drawdown(path)) == "-0.20000000"
    assert measure.max_drawdown([]) is None
    assert report.plain(measure.max_drawdown([bd("0.01"), bd("0.02")])) == "0.00000000"


def test_market_return_needs_both_proxies():
    assert measure.market_return(bd("0.02"), bd("0.04")) == bd("0.03")
    with pytest.raises(SimReportError):
        measure.market_return(None, bd("0.04"))


def test_krw_return_compounds_the_fx_change_exactly():
    assert measure.krw_return(True, bd("0.10"), None) == bd("0.10")
    assert measure.krw_return(False, bd("0.10"), bd("0.01")) == bd("0.1110")
    with pytest.raises(SimReportError):
        measure.krw_return(False, bd("0.10"), None)


def test_measure_refuses_in_the_sql_case_order():
    market = [bd("0.02")] * 3 + [bd("-0.02")] * 3
    flat = [bd("0.01")] * 6
    assert measure.measure(flat, market, 7, 3, 3).reason is measure.Reason.MONTHS
    assert measure.measure(flat, market, 6, 4, 3).reason is measure.Reason.N_UP
    assert measure.measure(flat, market, 6, 3, 4).reason is measure.Reason.N_DOWN
    ok = measure.measure(flat, market, 6, 3, 3)
    assert ok.judgeable and ok.n_up == 3 and ok.n_down == 3
    # a month missing on either side is dropped, not zeroed
    gap = measure.measure([None, *flat[1:]], market, 6, 3, 3)
    assert gap.months_used == 5 and gap.reason is measure.Reason.MONTHS
    with pytest.raises(SimReportError):
        measure.measure(flat, market[:5], 6, 3, 3)
    with pytest.raises(SimReportError):
        measure.measure(flat, market, 1, 3, 3)


def test_classification_follows_the_documented_order():
    c = measure.classify
    mdd, market_mdd = bd("-0.05"), bd("-0.04")
    assert c(bd("1"), bd("0.2"), mdd, market_mdd, False, True) is Position.GOALKEEPER
    assert c(bd("1"), bd("0.5"), mdd, market_mdd, False, True) is Position.DEFENDER
    assert (
        c(bd("1.4"), bd("0.7"), bd("-0.06"), market_mdd, True, False) is Position.FORWARD_BREAKOUT
    )
    # the breakout needs a drawdown at least 1.5 x as deep as the market's (-0.06 is the edge);
    # a shallower one falls through to the next rule
    assert (
        c(bd("1.4"), bd("0.7"), bd("-0.059"), market_mdd, True, False) is Position.FORWARD_DEFENSIVE
    )
    assert c(bd("1.0"), bd("0.7"), mdd, market_mdd, False, False) is Position.FORWARD_DEFENSIVE
    assert c(bd("1.2"), bd("1.2"), mdd, market_mdd, False, False) is Position.FORWARD_TARGET
    assert c(bd("1.2"), bd("1.2"), mdd, market_mdd, True, False) is Position.MIDFIELDER
    assert c(bd("0.9"), bd("1.0"), mdd, market_mdd, False, False) is Position.MIDFIELDER
    # boundaries: down 0.6 is not a defender, down 0.2 is still a goalkeeper
    assert c(bd("0.9"), bd("0.6"), mdd, market_mdd, False, False) is Position.MIDFIELDER
    assert c(bd("0.9"), bd("0.59"), mdd, market_mdd, False, False) is Position.DEFENDER
    assert c(bd("1"), bd("0.20"), mdd, market_mdd, False, True) is Position.GOALKEEPER


def test_the_top_fifth_by_volatility_includes_ties_at_the_edge():
    low_vol = measure.Metrics(6, 3, 3, bd("1"), bd("0.1"), bd("0.01"), bd("-0.01"), None)

    def breakout(vol: str) -> measure.Metrics:
        return measure.Metrics(6, 3, 3, bd("1.4"), bd("1.0"), bd(vol), bd("-0.06"), None)

    def plain_m(vol: str) -> measure.Metrics:
        return measure.Metrics(6, 3, 3, bd("1"), bd("0.9"), bd(vol), bd("-0.01"), None)

    short = measure.Metrics(2, 0, 0, None, None, None, None, measure.Reason.MONTHS)
    pool = {
        "a": low_vol,
        "b": plain_m("0.02"),
        "c": plain_m("0.02"),
        "d": plain_m("0.03"),
        "e": breakout("0.04"),
        "z": short,
    }
    # five judged, k = ceil(0.2 x 5) = 1: only e is top, only a is bottom; z is not ranked at all
    got = measure.classify_pool(pool, bd("-0.04"))
    assert set(got) == set("abcde")
    assert got["a"] is Position.GOALKEEPER and got["e"] is Position.FORWARD_BREAKOUT
    assert got["d"] is Position.MIDFIELDER
    # d ties e for the top volatility: both are in the top fifth, so d is a breakout too
    tie = {**pool, "d": breakout("0.04")}
    got = measure.classify_pool(tie, bd("-0.04"))
    assert got["d"] is Position.FORWARD_BREAKOUT and got["e"] is Position.FORWARD_BREAKOUT
    # two tied lowest volatilities are both in the bottom fifth
    both_low = {**pool, "b": replace(low_vol)}
    assert measure.classify_pool(both_low, bd("-0.04"))["b"] is Position.GOALKEEPER


def test_a_market_month_of_exactly_one_percent_counts_as_up_and_down():
    market = [bd("0.01")] * 3 + [bd("-0.01")] * 3
    m = measure.measure([bd("0.02")] * 6, market, 6, 3, 3)
    assert m.judgeable and (m.n_up, m.n_down) == (3, 3)


def test_benchmark_returns_start_from_the_initial_cash_not_from_the_first_value():
    got = report.baseline_returns(three_turns())

    def half_up(numerator: int, denominator: int) -> Decimal:
        q = (numerator * 10**30 * 2 + denominator) // (2 * denominator)
        return Decimal(f"{q}E-30")

    # values 29,895,570 (turn 0), 30,820,570, 28,970,570, 32,884,069: fees count in the first return
    assert got == [
        half_up(820_570, 30_000_000),
        half_up(-1_850_000, 30_820_570),
        half_up(3_913_499, 28_970_570),
    ]


# ------------------------------------------------------------ check order (Java `validate`)


def test_the_first_broken_rule_names_the_error():
    base = three_turns()
    gap = (base.turns[0], base.turns[2])
    zero = replace(base.turns[0], total_value=won(0))
    after = replace(base.orders, orders_after_report=1)
    pending = replace(base.orders, pending_orders=1)
    short = {KR: returns("0.01"), US: base.pool_returns[US]}
    one_proxy = {KR: base.benchmark_prices[KR]}
    bad_holding = (holding(A, 9, won(1)),)
    chain = [
        # (sources with two faults, the message of the one that must win)
        (with_(base, season=season_facts(current=4), turns=gap), "report turn is out of range"),
        (with_(base, turns=(zero, base.turns[2])), "do not run from 1"),
        (with_(base, turns=(zero, *base.turns[1:]), orders=after), "has no value left"),
        (with_(base, orders=replace(after, pending_orders=1)), "after the report turn"),
        (with_(base, orders=pending, benchmark_prices=one_proxy), "still holds orders"),
        (with_(base, benchmark_prices=one_proxy, pool_returns=short), "benchmark proxy"),
        (with_(base, pool_returns=short, holdings=bad_holding), "path returns"),
        (with_(base, holdings=(holding(A, 9, won(-1)),)), "outside the report range"),
    ]
    for src, message in chain:
        with pytest.raises(SimReportError, match=message):
            calculate(src)


def test_a_huge_cash_cannot_buy_an_int_overflowing_number_of_proxy_shares():
    src = three_turns()
    huge = replace(src.season, initial_cash=won(999_999_999_999_998))
    cheap = {KR: prices(1, 1, 1, 1), US: prices(1, 1, 1, 1)}
    with pytest.raises(money.MoneyError):
        calculate(with_(src, season=huge, benchmark_prices=cheap))


def test_money_far_above_the_int_range_still_reports_exactly():
    src = three_turns()
    cash = 10_000_000_000_000
    big = replace(src.season, initial_cash=won(cash))
    turns = tuple(
        replace(
            t,
            total_value=won(cash + t.turn),
            goalkeeper_value=won(cash + t.turn),
            defender_value=won(0),
            midfielder_value=won(0),
            forward_value=won(0),
        )
        for t in src.turns
    )
    r = calculate(
        with_(
            src,
            season=big,
            turns=turns,
            holdings=(),
            lineup=(),
            changes=(),
            change_costs=(),
            orders=no_orders(),
        )
    )
    assert r.final_value == Decimal(cash + 3)
    assert r.baseline_final_value > 0 and r.formation_deviation >= 0


# ------------------------------------------------ measured deviation (Java twin test)

TARGET = (10, 20, 30, 40)


def measured_scenario(declared_a, declared_b, holdings, changes=()):
    base = eight_turns(declared_a, declared_b, holdings)
    season = replace(
        base.season,
        start_goalkeeper=TARGET[0],
        start_defender=TARGET[1],
        start_midfielder=TARGET[2],
        start_forward=TARGET[3],
    )
    return with_(base, season=season, changes=tuple(changes))


def c_holdings(system: Position, value: int = 300_000):
    out = []
    for h in role_holdings():
        out.append(replace(h, value=won(value), system=system) if h.asset == C_ASSET else h)
    return out


def measured(src) -> str:
    return report.plain(calculate(src).measured_formation_deviation)


GK, DF = Position.GOALKEEPER, Position.DEFENDER


def test_measured_deviation_groups_by_measured_position_with_hand_values():
    # A measured goalkeeper, B measured defender, C unmeasured: system forward 300,000 in turn 3
    fw = c_holdings(Position.FORWARD_TARGET)
    assert measured(measured_scenario(GK, DF, fw)) == "1.76468217"
    for forward in (Position.FORWARD_BREAKOUT, Position.FORWARD_DEFENSIVE):
        assert measured(measured_scenario(GK, DF, c_holdings(forward))) == "1.76468217"
    # the system judgement moves C into the goalkeeper residual
    assert measured(measured_scenario(GK, DF, c_holdings(GK))) == "1.76715742"
    # no holdings: |100V - 10V| + 3 x |0 - 30V| over 100V = 1.8
    assert measured(measured_scenario(GK, DF, [])) == "1.80000000"


def test_relabelling_the_declaration_does_not_move_the_measured_deviation():
    fw = c_holdings(Position.FORWARD_TARGET)
    right = calculate(measured_scenario(GK, DF, fw))
    relabelled = calculate(measured_scenario(Position.FORWARD_BREAKOUT, Position.MIDFIELDER, fw))
    assert relabelled.measured_formation_deviation == right.measured_formation_deviation
    assert right.measured_formation_deviation != right.formation_deviation


def test_an_unmeasured_player_counts_by_system_not_by_declaration():
    fw = c_holdings(Position.FORWARD_TARGET)
    base = measured_scenario(GK, DF, fw)
    declared_fw = (
        LineupFact(A, GK, 0, None),
        LineupFact(B, DF, 0, None),
        LineupFact(C_ASSET, Position.FORWARD_BREAKOUT, 0, None),
    )
    assert calculate(with_(base, lineup=declared_fw)).measured_formation_deviation == (
        calculate(base).measured_formation_deviation
    )


def test_a_formation_change_moves_the_measured_target_from_the_next_turn():
    changes = [ChangeFact(1, 3, 40, 10, 20, 30)]
    src = measured_scenario(GK, DF, c_holdings(Position.FORWARD_TARGET), changes)
    assert measured(src) == "1.38968217"


def test_a_season_nobody_can_measure_uses_the_base_date_positions():
    up = returns(*("0.02", "0.03", "0.02", "0.04", "0.03", "0.02", "0.03", "0.05"))
    pool = {k: list(up) for k in (KR, US, A, B, C_ASSET)}
    held = [
        replace(holding(B, 3, won(500_000)), system=DF),
        replace(holding(C_ASSET, 3, won(300_000)), system=DF),
    ]
    base = measured_scenario(GK, DF, held)
    r = calculate(with_(base, pool_returns=pool))
    assert r.role_fit is None and r.measured_formation_deviation is not None
    # 800,000 defender in turn 3 only equals B alone measured as defender
    only_b = [replace(holding(B, 3, won(800_000)), system=DF)]
    assert measured(measured_scenario(GK, DF, only_b)) == "1.79339934"


def test_measured_class_values_equal_to_the_total_pass_and_one_won_more_is_refused():
    # turn 1 total is 30,100,000 (defender-only holding at turn 1)
    ok = measured_scenario(GK, DF, [replace(holding(B, 1, won(30_100_000)), system=DF)])
    calculate(ok)
    over = measured_scenario(GK, DF, [replace(holding(B, 1, won(30_100_001)), system=DF)])
    with pytest.raises(SimReportError):
        calculate(over)


def test_a_class_above_its_target_tells_forward_from_midfielder():
    """C is 12,000,000 in turn 3: above the 30 % midfield target, below the 40 % forward one."""
    from fractions import Fraction

    def expected(c_class: int) -> str:
        total_sum = Fraction(0)
        for t in range(1, 9):
            total = 30_000_000 + 100_000 * t
            classes = [0, 500_000, 0, 0]  # keeper residual below, defender B
            if t == 3:
                classes[c_class] += 12_000_000
            classes[0] = total - sum(classes[1:])
            total_sum += sum(
                Fraction(abs(100 * v - f * total), 100 * total)
                for v, f in zip(classes, TARGET, strict=True)
            )
        value = total_sum / 8
        scaled = (value.numerator * 10**8 * 2 + value.denominator) // (2 * value.denominator)
        return f"{scaled // 10**8}.{scaled % 10**8:08d}"

    for system, index in ((Position.FORWARD_TARGET, 3), (Position.MIDFIELDER, 2)):
        src = measured_scenario(GK, DF, c_holdings(system, 12_000_000))
        got = calculate(src)
        # A (1,000,000 from turn 2) is a measured goalkeeper, so it stays in the residual
        assert report.plain(got.measured_formation_deviation) == expected(index), system
