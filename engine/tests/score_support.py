"""Season inputs that carry the two benchmark proxies, for report and score tests. Synthetic."""

from dataclasses import replace
from decimal import Decimal

from antlab_engine.sim.model import Formation, PathStep, PoolAsset, Position, Settings, SimInputs

KR_BENCH = "KR:069500"
US_BENCH = "US:SPY"
AAA = "KR:AAA001"
BBB = "US:BBB002"

SYSTEM = (Position.DEFENDER, Position.MIDFIELDER, Position.FORWARD_TARGET, Position.GOALKEEPER)


def pool(extra: int = 0) -> tuple[PoolAsset, ...]:
    """Four named assets plus `extra` filler assets (alternating KR and US)."""
    base = [
        PoolAsset(KR_BENCH, "KRW", Decimal("30000"), Decimal(1), Position.MIDFIELDER),
        PoolAsset(US_BENCH, "USD", Decimal(50), Decimal(1400), Position.FORWARD_TARGET),
        PoolAsset(AAA, "KRW", Decimal("10000"), Decimal(1), Position.DEFENDER),
        PoolAsset(BBB, "USD", Decimal(20), Decimal(1400), Position.FORWARD_TARGET),
    ]
    for i in range(extra):
        if i % 2 == 0:
            base.append(
                PoolAsset(f"KR:F{i:03d}", "KRW", Decimal(5000 + 100 * i), Decimal(1), SYSTEM[i % 4])
            )
        else:
            base.append(
                PoolAsset(f"US:F{i:03d}", "USD", Decimal(10 + i), Decimal(1400), SYSTEM[i % 4])
            )
    return tuple(base)


def flat_path(assets: tuple[PoolAsset, ...], length: int) -> tuple[PathStep, ...]:
    zero = Decimal(0)
    return tuple(
        PathStep(t, dict.fromkeys((a.asset for a in assets), zero), {"USD": zero})
        for t in range(1, length + 1)
    )


def wavy_path(assets: tuple[PoolAsset, ...], length: int) -> tuple[PathStep, ...]:
    """Deterministic returns between -5 % and +5 %, so every proxy sees up and down months."""
    steps = []
    for t in range(1, length + 1):
        returns = {
            a.asset: Decimal((t * 7 + k * 3) % 11 - 5) / Decimal(100) for k, a in enumerate(assets)
        }
        steps.append(PathStep(t, returns, {"USD": Decimal((t * 5) % 7 - 3) / Decimal(200)}))
    return tuple(steps)


def inputs(length: int = 12, *, flat: bool = True, extra: int = 0, **settings) -> SimInputs:
    config = replace(
        Settings(path_length=length, start_formation=Formation(10, 20, 30, 40)), **settings
    )
    assets = pool(extra)
    return SimInputs(
        config, assets, flat_path(assets, length) if flat else wavy_path(assets, length)
    )
