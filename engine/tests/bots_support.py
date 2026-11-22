"""Hand-built views and a scripted random stream for the bot rule tests."""

from collections import deque
from decimal import Decimal

from antlab_engine.bots.view import BotView, ViewAsset
from antlab_engine.sim.model import Formation, Position

KR = ViewAsset("KR:AAA", "KR", Position.DEFENDER)
KR2 = ViewAsset("KR:BBB", "KR", None)
US = ViewAsset("US:CCC", "US", Position.FORWARD_TARGET)
START = Formation(10, 30, 30, 30)
RATE_KR = Decimal("0.00015000")
RATE_US = Decimal("0.00650000")
RATE = {
    "KR:AAA": Decimal("0.00015000"),
    "KR:BBB": Decimal("0.00015000"),
    "US:CCC": Decimal("0.00650000"),
}


def view(
    turn: int = 1,
    holdings: dict[str, int] | None = None,
    cash: int = 10_000_000,
    total: int | None = 50_000_000,
    prices: dict[str, int] | None = None,
    formation: Formation = START,
    pool: tuple[ViewAsset, ...] = (KR, KR2, US),
) -> BotView:
    p = {a.asset: Decimal(10_000) for a in pool}
    p.update({k: Decimal(v) for k, v in (prices or {}).items()})
    return BotView(
        turn=turn,
        season_turns=12,
        cash=Decimal(cash),
        total=None if total is None else Decimal(total),
        holdings=holdings or {},
        prices=p,
        prev_prices=None if turn == 0 else p,
        formation=formation,
        pool=pool,
        rates={a.asset: RATE_KR if a.market == "KR" else RATE_US for a in pool},
    )


class ScriptedRng:
    """Answers `below`, `between` and `chance` from queues; a wrong range fails loudly."""

    def __init__(self, below=(), between=(), chance=()) -> None:
        self.below_q = deque(below)
        self.between_q = deque(between)
        self.chance_q = deque(chance)
        self.chance_args: list[tuple[int, int]] = []

    def below(self, n: int) -> int:
        v = self.below_q.popleft()
        assert 0 <= v < n, f"below({n}) scripted {v}"
        return v

    def between(self, low: int, high: int) -> int:
        v = self.between_q.popleft()
        assert low <= v <= high, f"between({low},{high}) scripted {v}"
        return v

    def chance(self, numerator: int, denominator: int) -> bool:
        self.chance_args.append((numerator, denominator))
        return self.chance_q.popleft()
