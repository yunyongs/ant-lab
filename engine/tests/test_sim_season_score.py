"""Report and score of played seasons.

The flat season has constant prices, so every number follows from fees alone and is worked out
here with `Fraction` (not with the module under test). The wavy season checks structure,
determinism and the wiring from the season to the calculators.
"""

from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction

import pytest
from score_support import AAA, BBB, KR_BENCH, US_BENCH, inputs

from antlab_engine.sim import money, report, score
from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.counts import (
    FixedCounts,
    PercentileCounts,
    PercentileRequest,
    RankCounts,
)
from antlab_engine.sim.model import (
    Order,
    OrderSource,
    Position,
    SeasonStatus,
    Side,
    SimReportError,
    SimStateError,
    SourceKind,
)
from antlab_engine.sim.money import MoneyError
from antlab_engine.sim.season import Season

BASE = date(2026, 1, 2)
SEED = 123456789
COUNTS = FixedCounts(PercentileCounts(RankCounts(1200, 100, 2000), RankCounts(900, 0, 2000)))


def scored_of(season, counts, **kw):
    return score.score_season(season, counts, **{"season_seed": SEED, "base_date": BASE, **kw})


def r8(value: Fraction) -> Decimal:
    """Half-up at 8 decimals from an exact fraction."""
    scaled = Fraction(value.numerator * 10**8, value.denominator)
    whole = Decimal(scaled.numerator) / Decimal(scaled.denominator)
    return (whole / Decimal(10**8)).quantize(Decimal("1E-8"), rounding=ROUND_HALF_UP)


def plain(value) -> str:
    return report.plain(value)


def play_flat(*, abandon_at: int | None = None, length: int = 12) -> Season:
    """Fees are the only thing that moves the value: see the module note for the hand numbers."""
    s = Season(inputs(length), strict=True)
    s.place_order(Order(AAA, Side.BUY, 100, Position.DEFENDER))
    s.place_order(Order(US_BENCH, Side.BUY, 10, Position.FORWARD_TARGET))
    s.advance()  # turn 1
    if abandon_at == 1:
        s.abandon()
        s.run_abandoned()
        return s
    s.place_order(Order(AAA, Side.SELL, 40, source=OrderSource(SourceKind.RANKING)))
    s.place_order(Order(US_BENCH, Side.BUY, 5, source=OrderSource(SourceKind.FEED, 1, 1)))
    s.advance()  # turn 2
    assert s.change_formation(20, 30, 30, 20).accepted
    s.place_order(Order(AAA, Side.BUY, 10, source=OrderSource(SourceKind.FEED, 1, 2)))
    s.advance()  # turn 3
    assert s.change_lineup(AAA, Position.MIDFIELDER).accepted
    while s.status is SeasonStatus.IN_PROGRESS:
        s.advance()
    return s


def test_the_flat_season_report_matches_the_hand_numbers():
    s = play_flat()
    w = report.report_of(s).to_wire()
    # fees: turn 0 = 150 + 4,550; turn 1 = 60 + 2,275; turn 2 = 15; nothing else moves the value
    assert (w["initialCash"], w["finalValue"]) == ("100000000", "99992950")
    assert w["totalReturn"] == "-0.00007050"
    assert w["maxDrawdown"] == "-0.00007050"
    assert (w["riskBreachTurns"], w["riskBreachDepth"]) == (0, "0.00000000")
    assert w["riskLimit"] == "-0.20000000"
    # traded after turn 0: sell 400,000 + buy 350,000 + buy 100,000
    assert (w["tradeNotional"], w["totalFee"], w["teamFee"]) == ("850000", "7050", "4700")
    assert w["platformNudgeFee"] == "60"  # only the RANKING sell
    assert w["nudgeShare"] == "1.00000000"
    assert w["nudgeSameTurnShare"] == str(
        r8(Fraction(750_000, 850_000))
    )  # RANKING + same-turn FEED
    assert (w["formationChanges"], w["formationChangeOrders"], w["changesWithoutOrders"]) == (
        1,
        1,
        0,
    )
    assert w["formationChangeFee"] == "15"
    assert (w["pendingOrders"], w["pendingChanges"], w["isFinal"]) == (0, 0, True)
    assert (w["reconTurnBundles"], w["reconMismatchRows"]) == (12, 0)
    # no month moves the market, so nobody can be judged
    assert (w["roleFit"], w["rolePlayersJudged"], w["rolePlayersUnjudged"]) == (None, 0, 2)


def test_the_flat_season_baseline_buys_whole_shares_for_half_the_cash_each():
    w = report.report_of(play_flat()).to_wire()
    # KR 50,000,000 / (30,000 x 1.00015) -> 1,666 shares, fee 7,497, cash 12,503
    # US 50,000,000 / (70,000 x 1.0065) -> 709 shares, fee 322,595, cash 47,405
    assert w["baselineFinalValue"] == "99669908"
    assert w["baselineReturn"] == "-0.00330092"
    assert w["excessReturn"] == "0.00323042"


def totals_of(s: Season) -> list[int]:
    return [int(t.total) for t in s.turns]


def test_the_flat_season_ratios_follow_from_the_snapshots():
    s = play_flat()
    w = report.report_of(s).to_wire()
    totals = totals_of(s)
    assert totals[:3] == [99_995_300, 99_992_965, 99_992_950] and set(totals[2:]) == {99_992_950}
    assert w["turnoverAnnual"] == str(r8(Fraction(12 * 850_000, sum(totals))))
    # turn 1 holds AAA 1,000,000 and BBB-free SPY 700,000; later turns hold SPY 1,050,000 at most
    weights = [Fraction(1_000_000, totals[0])] + [Fraction(1_050_000, t) for t in totals[1:]]
    assert w["maxSingleWeight"] == str(r8(max(weights)))
    returns = [Fraction(totals[0] - 100_000_000, 100_000_000)]
    returns += [Fraction(b - a, a) for a, b in zip(totals, totals[1:], strict=False)]
    mean = sum(returns) / len(returns)
    variance = sum((x - mean) ** 2 for x in returns) / (len(returns) - 1)
    root = Decimal(variance.numerator * 10**120 // variance.denominator).sqrt() / Decimal(10**60)
    assert w["monthlyVolatility"] == str(root.quantize(Decimal("1E-8"), rounding=ROUND_HALF_UP))


def test_the_flat_season_formation_deviation_uses_declared_and_measured_positions():
    s = play_flat()
    w = report.report_of(s).to_wire()
    targets = {1: (10, 20, 30, 40), 2: (10, 20, 30, 40)}
    declared = []
    measured = []
    for snap in s.turns:
        f = targets.get(snap.turn, (20, 30, 30, 20))
        total = int(snap.total)
        values = tuple(
            int(v) for v in (snap.goalkeeper, snap.defender, snap.midfielder, snap.forward)
        )
        declared.append(
            sum(
                Fraction(abs(100 * v - t * total), 100 * total)
                for v, t in zip(values, f, strict=True)
            )
        )
        # measured: AAA counts by its base-date position (defender) and SPY as forward target
        held = sum(
            e.quantity * (1 if e.side is Side.BUY else -1)
            for e in s.executions
            if e.asset == AAA and e.turn < snap.turn
        )
        spy = sum(
            e.quantity * (1 if e.side is Side.BUY else -1)
            for e in s.executions
            if e.asset == US_BENCH and e.turn < snap.turn
        )
        d, fw = held * 10_000, spy * 70_000
        mv = (total - d - fw, d, 0, fw)
        measured.append(
            sum(Fraction(abs(100 * v - t * total), 100 * total) for v, t in zip(mv, f, strict=True))
        )
    assert w["formationDeviation"] == str(r8(sum(declared) / len(declared)))
    assert w["measuredFormationDeviation"] == str(r8(sum(measured) / len(measured)))


def test_the_flat_season_score_wires_the_report_into_the_items():
    s = play_flat()
    scored = scored_of(s, COUNTS)
    sc = scored.score
    assert sc.score_version == "s63-v3" and scored.exclusion is None
    assert (plain(sc.risk_score), plain(sc.discipline_score)) == ("100.00", "100.00")
    assert plain(sc.diversification_score) == "100.00"
    assert plain(sc.nudge_score) == "0.00" and sc.noise_score is None and sc.role_score is None
    assert sc.process_items == 6  # all but noise (no signals) and role (nobody judged)
    # panic and chase shares: nothing fell or rose by 8 % in a flat season
    assert (
        plain(scored.discipline.panic_sell_ratio),
        plain(scored.discipline.chase_buy_ratio),
    ) == (
        "0.00000000",
        "0.00000000",
    )
    assert (
        plain(sc.sharpe_diff_percentile) == "0.62500000"
        and plain(sc.luck_percentile) == "0.45000000"
    )
    assert sc.result_score == Decimal("62.50")


def test_the_percentile_request_is_the_start_team_after_turn_zero():
    s = play_flat()
    scored = scored_of(s, COUNTS, path_count=2000)
    req = scored.percentile_request
    assert req is not None
    # cash 100,000,000 - 1,000,000 - 150 - 700,000 - 4,550 = 98,295,300; total 99,995,300
    total = 99_995_300
    aaa = Fraction(1_000_000, total)
    spy = Fraction(700_000, total)

    def floor12(x: Fraction) -> Decimal:
        return Decimal(x.numerator * 10**12 // x.denominator).scaleb(-12)

    wire = req.to_wire()
    team = wire["team"]
    assert [w["asset"] for w in team["weights"]] == [US_BENCH, AAA]  # pool order
    assert team["weights"][0]["weight"] == format(floor12(spy), "f")
    assert team["weights"][1]["weight"] == format(floor12(aaa), "f")
    assert Decimal(team["cash"]) + sum(Decimal(w["weight"]) for w in team["weights"]) == 1
    assert wire["benchmark"] == {
        "cash": "0",
        "weights": [{"asset": KR_BENCH, "weight": "0.5"}, {"asset": US_BENCH, "weight": "0.5"}],
    }
    assert wire["seasonSeed"] == str(SEED) and wire["baseDate"] == "2026-01-02"
    assert (wire["horizonTurns"], wire["n"]) == (12, 2000)
    # the start team held still ends where it started when prices never move
    assert wire["observed"]["endMultiple"] == "1.000000000000"


def test_the_noise_item_needs_signals_and_follows_buys_up_and_sells_down():
    s = play_flat()
    followed = [
        TurnSignals(1, frozenset({US_BENCH}), frozenset({AAA})),
        TurnSignals(2, frozenset({AAA}), frozenset()),
    ]
    scored = scored_of(s, COUNTS, signals=followed)
    assert scored.noise_share == Decimal("1.00000000")
    assert plain(scored.score.noise_score) == "0.00"
    half = [
        TurnSignals(1, frozenset({US_BENCH}), frozenset({AAA})),
        TurnSignals(2, frozenset(), frozenset()),
    ]
    scored = scored_of(s, COUNTS, signals=half)
    assert (
        scored.noise_share == Decimal("0.50000000") and plain(scored.score.noise_score) == "50.00"
    )
    assert scored.score.process_items == 7
    with pytest.raises(SimReportError):  # an order turn without its signals
        scored_of(s, COUNTS, signals=followed[:1])
    with pytest.raises(SimReportError):  # a signal for the report turn itself
        scored_of(s, COUNTS, signals=[*followed, TurnSignals(12, frozenset(), frozenset())])


def test_without_counts_there_is_no_score_and_the_season_is_excluded():
    scored = scored_of(play_flat(), None)
    assert scored.score.score is None and scored.score.result_score is None
    assert scored.percentile_request is None and scored.counts is None
    assert scored.exclusion is score.RatingExclusion.NO_SCORE
    assert scored.score.process_score is not None


def test_a_season_abandoned_before_turn_three_is_not_started():
    s = play_flat(abandon_at=1)
    assert s.status is SeasonStatus.FINISHED and s.abandoned_turn == 1
    scored = scored_of(s, COUNTS)
    assert scored.exclusion is score.RatingExclusion.NOT_STARTED
    assert scored.report.report_turns == 12  # auto turns carry the season to its end


def test_only_a_finished_season_is_scored_and_a_started_one_can_be_reported():
    s = Season(inputs(), strict=True)
    s.place_order(Order(AAA, Side.BUY, 10, Position.DEFENDER))
    with pytest.raises(SimReportError):
        report.report_of(s)  # turn 0 has no snapshot
    s.advance()
    mid = report.report_of(s)
    assert mid.season_status is SeasonStatus.IN_PROGRESS and mid.is_final is False
    assert (mid.report_turns, mid.path_length) == (1, 12)
    with pytest.raises(SimStateError):
        scored_of(s, COUNTS)


def test_orders_of_the_report_turn_are_pending_not_counted():
    s = Season(inputs(), strict=True)
    s.place_order(Order(AAA, Side.BUY, 10, Position.DEFENDER))
    s.advance()
    s.place_order(Order(AAA, Side.BUY, 5, source=OrderSource(SourceKind.RANKING)))
    r = report.report_of(s)
    assert r.pending_orders == 1 and r.trade_notional == 0 and r.nudge_share is None
    assert r.total_fee == 15  # turn 0 only: 10 x 10,000 x 0.00015
    s.change_formation(20, 30, 30, 20)
    assert report.report_of(s).pending_changes == 1


def test_an_odd_initial_cash_cannot_be_reported():
    s = Season(inputs(initial_cash=Decimal(100_000_001)), strict=True)
    s.place_order(Order(AAA, Side.BUY, 10, Position.DEFENDER))
    s.advance()
    with pytest.raises(MoneyError):
        report.report_of(s)


def test_a_pool_without_the_benchmark_proxies_cannot_be_reported():
    from dataclasses import replace

    base = inputs()
    no_kr = replace(base, pool=tuple(a for a in base.pool if a.asset != KR_BENCH), path=base.path)
    s = Season(no_kr)
    s.advance()
    with pytest.raises(SimReportError):
        report.report_of(s)


# ---------------------------------------------------------------- a wavy season


def play_wavy(length: int = 12) -> Season:
    s = Season(inputs(length, flat=False, single_asset_limit=Decimal("0.6")), strict=True)
    s.place_order(Order(AAA, Side.BUY, 300, Position.DEFENDER))
    s.place_order(Order(BBB, Side.BUY, 40, Position.FORWARD_BREAKOUT))
    s.place_order(Order(KR_BENCH, Side.BUY, 20, Position.MIDFIELDER))
    for t in range(length - 1):
        s.advance()
        if t % 3 == 0:
            s.place_order(
                Order(AAA, Side.SELL, 20, source=OrderSource(SourceKind.ALERT, s.current_turn, 1))
            )
        if t % 3 == 1:
            s.place_order(Order(BBB, Side.BUY, 3, source=OrderSource(SourceKind.RANKING)))
        if t == 4:
            s.change_formation(20, 30, 30, 20)
        if t == 6:
            s.change_lineup(BBB, Position.FORWARD_DEFENSIVE)
    s.advance()
    return s


def test_a_wavy_season_reports_deterministically_and_measures_some_players():
    first = scored_of(play_wavy(), COUNTS)
    again = scored_of(play_wavy(), COUNTS)
    assert first.report.to_wire() == again.report.to_wire()
    assert first.score.to_wire() == again.score.to_wire()
    assert first.percentile_request.to_wire() == again.percentile_request.to_wire()
    r = first.report
    assert r.role_players_judged + r.role_players_unjudged == 3
    assert r.role_players_judged >= 1 and r.role_fit is not None
    assert r.final_value == play_wavy().turns[-1].total
    assert Decimal(0) <= r.max_single_weight <= Decimal(1)
    assert r.monthly_volatility is not None and r.monthly_volatility > 0
    assert first.score.score is not None and Decimal(0) <= first.score.score <= Decimal(100)


def test_the_wavy_report_is_the_source_the_score_reads():
    scored = scored_of(play_wavy(), COUNTS)
    src = report.sources_from_season(play_wavy())
    assert report.calculate(src) == scored.report
    assert report.baseline_returns(src) == list(scored.observation.benchmark_returns)
    # the same report fed through the calculator by hand gives the stored score
    inputs_ = score.ScoreInputs(
        scored.observation.returns,
        scored.observation.benchmark_returns,
        scored.report.report_turns,
        scored.report.risk_breach_turns,
        scored.report.risk_breach_depth,
        scored.report.risk_limit,
        scored.report.max_single_weight,
        scored.report.turnover_annual,
        scored.report.measured_formation_deviation,
        scored.report.role_fit,
        scored.report.nudge_share,
        None,
        scored.discipline,
        COUNTS.answer.sharpe_diff,
        COUNTS.answer.end_multiple,
    )
    assert score.calculate(inputs_) == scored.score


def test_recorded_counts_must_come_from_the_requested_number_of_paths():
    wrong = FixedCounts(PercentileCounts(RankCounts(1, 0, 10), RankCounts(1, 0, 10)))
    with pytest.raises(SimReportError):
        scored_of(play_flat(), wrong)


def test_the_request_is_built_before_the_counts_are_asked():
    seen: list[PercentileRequest] = []

    class Spy:
        def counts(self, request: PercentileRequest) -> PercentileCounts:
            seen.append(request)
            return COUNTS.answer

    scored_of(play_flat(), Spy(), season_seed=-5, path_count=100)
    assert len(seen) == 1 and seen[0].n == 100 and seen[0].season_seed == -5


def test_the_risk_limit_is_one_of_the_four_declarations():
    from antlab_engine.sim.model import Settings, SimShapeError

    for good in ("-0.10", "-0.20", "-0.30", "-0.40", "-0.20000000"):
        assert Settings(risk_limit=Decimal(good)).risk_limit == Decimal(good)
    for bad in ("0.20", "0", "-0.15", "-0.50"):
        with pytest.raises(SimShapeError):
            Settings(risk_limit=Decimal(bad))


def test_the_end_multiple_values_the_start_team_at_the_last_turn_on_moving_prices():
    s = play_wavy()
    scored = scored_of(s, COUNTS)
    cash = next(r.balance_after for r in reversed(s.ledger) if r.turn == 0)
    held: dict[str, int] = {}
    for e in s.executions:
        if e.turn == 0:
            held[e.asset] = held.get(e.asset, 0) + (
                e.quantity if e.side is Side.BUY else -e.quantity
            )
    start = cash + sum(q * s.turn_prices[0][a] for a, q in held.items())
    end = cash + sum(q * s.turn_prices[s.current_turn][a] for a, q in held.items())
    assert end != start and end != cash + sum(q * s.turn_prices[11][a] for a, q in held.items())
    ratio = Fraction(int(end), int(start))
    scaled = (ratio.numerator * 10**12 * 2 + ratio.denominator) // (2 * ratio.denominator)
    want = f"{scaled // 10**12}.{scaled % 10**12:012d}"
    req = scored.percentile_request
    assert req.end_multiple == want
    assert req.sharpe_diff == report.plain(money.round12(scored.observation.sharpe_diff))


def test_a_history_that_cannot_carry_the_horizon_stores_no_score_but_keeps_the_request():
    class TooLong:
        def counts(self, request):
            return None

    for source in (TooLong(), FixedCounts(None)):
        got = scored_of(play_flat(), source)
        assert got.score.score is None and got.score.result_score is None
        assert got.exclusion is score.RatingExclusion.NO_SCORE
        assert got.percentile_request is not None and got.counts is None
        assert got.score.process_score is not None and got.score.process_items == 6
    # not-started wins over no-score, as for any unscored season
    assert scored_of(play_flat(abandon_at=1), FixedCounts(None)).exclusion is (
        score.RatingExclusion.NOT_STARTED
    )
