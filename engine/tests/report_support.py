"""Hand-made report sources, ported from the Java `SeasonReportFixtures`. Values are synthetic."""

from dataclasses import replace
from decimal import Decimal

from antlab_engine.sim.model import Position, SeasonStatus
from antlab_engine.sim.report import (
    ChangeCost,
    ChangeFact,
    HoldingFact,
    LineupFact,
    OrderFacts,
    ReconFacts,
    ReportSources,
    SeasonFacts,
    TurnFact,
)

KR = "KR:069500"
US = "US:SPY"
A = "KR:AAA001"
B = "US:BBB002"
C_ASSET = "KR:CCC003"


def bd(value: str) -> Decimal:
    return Decimal(value)


def won(value: int) -> Decimal:
    return Decimal(value).quantize(Decimal("0.0001"))


def turn(no: int, total: int, drawdown: str, gk: int, df: int, mf: int, fw: int) -> TurnFact:
    return TurnFact(no, won(total), bd(drawdown), won(gk), won(df), won(mf), won(fw))


def holding(asset: str, turn_no: int, value: Decimal) -> HoldingFact:
    return HoldingFact(asset, turn_no, value, Position.GOALKEEPER)


def no_orders() -> OrderFacts:
    return OrderFacts(won(0), won(0), won(0), won(0), won(0), won(0), 0, 0)


def returns(*values: str | None) -> list[Decimal | None]:
    return [None if v is None else bd(v) for v in values]


def prices(*values: int) -> list[Decimal]:
    return [won(v) for v in values]


def season_facts(
    status: SeasonStatus = SeasonStatus.FINISHED,
    path_length: int = 3,
    current: int = 3,
    risk_limit: str = "-0.01000000",
    start: tuple[int, int, int, int] = (20, 30, 30, 20),
    cash: int = 30_000_000,
) -> SeasonFacts:
    return SeasonFacts(
        status,
        path_length,
        current,
        won(cash),
        bd(risk_limit),
        bd("0.00100000"),
        bd("0.00200000"),
        bd("0.00400000"),
        *start,
    )


def three_turns() -> ReportSources:
    """Three turns, three changes (two in turn 1), two holdings and a zero-length span."""
    turns = (
        turn(1, 30_300_000, "0.00000000", 6_060_000, 9_090_000, 9_090_000, 6_060_000),
        turn(2, 29_700_000, "-0.01980198", 5_000_000, 9_000_000, 9_700_000, 6_000_000),
        turn(3, 30_900_002, "0.00000000", 7_000_000, 8_000_000, 7_900_002, 8_000_000),
    )
    changes = (
        ChangeFact(11, 1, 10, 40, 30, 20),
        ChangeFact(12, 1, 25, 25, 25, 25),
        ChangeFact(13, 2, 40, 20, 20, 20),
    )
    orders = OrderFacts(
        won(12_000_000), won(60_000), won(40_000), won(5_000), won(3_000_000), won(1_200_000), 0, 0
    )
    holdings = (
        holding(A, 1, won(3_000_000)),
        holding(B, 1, won(2_000_000)),
        holding(A, 2, won(3_300_000)),
        holding(B, 2, won(1_900_000)),
        holding(A, 3, won(4_000_000)),
    )
    lineup = (
        LineupFact(A, Position.FORWARD_TARGET, 0, 1),
        LineupFact(A, Position.MIDFIELDER, 1, 1),
        LineupFact(A, Position.GOALKEEPER, 1, None),
        LineupFact(B, Position.DEFENDER, 0, 2),
    )
    pool = {KR: returns("0.01", "-0.02", "0.03"), US: returns("0.01", "-0.02", "0.03")}
    bench = {KR: prices(30_000, 31_000, 29_000, 33_001), US: prices(70_000, 72_000, 68_000, 77_000)}
    costs = (
        ChangeCost(11, 1, 0, won(0)),
        ChangeCost(12, 1, 2, won(1_200)),
        ChangeCost(13, 2, 1, won(500)),
    )
    return ReportSources(
        season_facts(),
        turns,
        changes,
        orders,
        holdings,
        lineup,
        pool,
        bench,
        costs,
        ReconFacts(2, 3, 1, 0),
    )


def eight_turns(declared_a: Position, declared_b: Position, holdings) -> ReportSources:
    """Eight-turn season where A is a goalkeeper, B a defender and C cannot be judged."""
    facts = season_facts(path_length=8, current=8, risk_limit="-0.50000000", start=(100, 0, 0, 0))
    turns = tuple(
        turn(t, 30_000_000 + 100_000 * t, "0.00000000", 30_000_000 + 100_000 * t, 0, 0, 0)
        for t in range(1, 9)
    )
    m = ("0.03", "0.02", "-0.02", "0.04", "-0.03", "0.01", "-0.01", "0.05")
    a = ("0.003", "0.002", "-0.002", "0.004", "-0.003", "0.001", "-0.001", "0.005")
    b = ("0.015", "0.01", "-0.01", "0.02", "-0.015", "0.005", "-0.005", "0.025")
    pool = {
        KR: returns(*m),
        US: returns(*m),
        A: returns(*a),
        B: returns(*b),
        C_ASSET: returns(None, None, "0.01", "0.02", None, None, None, None),
    }
    bench = {KR: prices(*([30_000] * 9)), US: prices(*([70_000] * 9))}
    lineup = (
        LineupFact(A, declared_a, 0, None),
        LineupFact(B, declared_b, 0, None),
        LineupFact(C_ASSET, Position.DEFENDER, 0, None),
    )
    return ReportSources(
        facts,
        turns,
        (),
        no_orders(),
        tuple(holdings),
        lineup,
        pool,
        bench,
        (),
        ReconFacts(0, 8, 0, 0),
    )


def role_holdings() -> list[HoldingFact]:
    out = [holding(A, t, won(1_000_000)) for t in range(2, 9)]
    out += [holding(B, t, won(500_000)) for t in range(1, 9)]
    out.append(holding(C_ASSET, 3, won(300_000)))
    return out


def with_(src: ReportSources, **changes) -> ReportSources:
    return replace(src, **changes)
