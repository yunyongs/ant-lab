"""Small synthetic season inputs for the simulator tests."""

from dataclasses import replace
from decimal import Decimal

from antlab_engine.sim.model import PathStep, PoolAsset, Settings, SimInputs

KR_A = "KR:AAA"
KR_B = "KR:BBB"
US_X = "US:SPY"


def pool() -> tuple[PoolAsset, ...]:
    return (
        PoolAsset(KR_A, "KRW", Decimal("10000"), Decimal("1")),
        PoolAsset(KR_B, "KRW", Decimal("2500.5000"), Decimal("1")),
        PoolAsset(US_X, "USD", Decimal("100"), Decimal("1300")),
    )


def path(length: int, returns: dict[str, Decimal] | None = None, fx: str = "0.01") -> tuple:
    base = {KR_A: Decimal("0.10"), KR_B: Decimal("-0.05"), US_X: Decimal("0.02")}
    base.update(returns or {})
    return tuple(PathStep(t, dict(base), {"USD": Decimal(fx)}) for t in range(1, length + 1))


def inputs(length: int = 12, returns: dict[str, Decimal] | None = None, **settings) -> SimInputs:
    config = replace(Settings(path_length=length), **settings)
    return SimInputs(config, pool(), path(length, returns))
