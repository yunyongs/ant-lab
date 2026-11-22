"""`panel-v1` worlds: a synthetic return panel plus a season drawn along it from real-shaped months.

The season path and the rank-count paths come from the same panel, so a team's percentile among
engine paths is comparable with its simulated season. Everything is integer-seeded and fixed-point:
the same (master seed, index, length, assets, generator) gives the same panel, world and bytes.

The panel is synthetic (market factor, two-state volatility regime, one currency move per month);
its feed facts are synthetic too: attention is a rank of the absolute return plus a streak bonus,
not the erd attention formula.
"""

from datetime import date
from decimal import Decimal

from antlab_engine.bootstrap import simple, stationary
from antlab_engine.projection import ReturnPanel, ReturnRow
from antlab_engine.sim.model import (
    PathStep,
    PoolAsset,
    Position,
    Settings,
    SimInputs,
    SimShapeError,
)

from .rng import BotRng, mix
from .world import BotWorld, synthetic_feed

PANEL_FIRST = date(1997, 1, 1)
PANEL_LAST = date(2025, 12, 1)
BASE_DATE = date(2026, 1, 1)
GENERATORS = ("simple", "stationary")
MIN_ASSETS = 4

KIND_PANEL_WORLD = 0x9A_4E_01
KIND_PANEL_PATH = 0x9A_4E_02
_SYSTEM = (
    Position.DEFENDER,
    Position.MIDFIELDER,
    Position.FORWARD_TARGET,
    Position.GOALKEEPER,
)
_CALM, _STRESS = 0, 1
_VOL_BP = {_CALM: 300, _STRESS: 700}
_STAY = {_CALM: (95, 100), _STRESS: (85, 100)}
_UNIT_SD = 1155  # sd of the sum of four draws uniform on [-1000, 1000]


def panel_months() -> list[date]:
    months = []
    year, month = PANEL_FIRST.year, PANEL_FIRST.month
    while (year, month) <= (PANEL_LAST.year, PANEL_LAST.month):
        months.append(date(year, month, 1))
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return months


def world_seed(master_seed: int, index: int) -> int:
    return mix(master_seed, index, KIND_PANEL_WORLD)


def path_seed(season_seed: int) -> int:
    """Seed of the season path; the rank-count paths use `derive_path_seeds(season_seed, n)`."""
    return mix(season_seed, KIND_PANEL_PATH)


def _gauss(rng: BotRng, sd_bp: int) -> int:
    raw = sum(rng.below(2001) - 1000 for _ in range(4))
    return raw * sd_bp // _UNIT_SD


def _asset_names(assets: int) -> list[tuple[str, str]]:
    names = [("KR:069500", "KRW"), ("US:SPY", "USD")]
    for i in range(assets - 2):
        names.append((f"KR:S{i:03d}", "KRW") if i % 2 == 0 else (f"US:S{i:03d}", "USD"))
    return names


def _bp(value: int) -> Decimal:
    return Decimal(value) / 10000


def _build_panel(
    seed: int, names: list[tuple[str, str]]
) -> tuple[ReturnPanel, dict[str, dict[date, tuple[Decimal, Decimal]]]]:
    rng = BotRng(mix(seed, 0xDA7A))
    months = panel_months()
    beta = {}
    idio = {}
    for asset, _ in names:
        proxy = asset in ("KR:069500", "US:SPY")
        beta[asset] = 10 if proxy else rng.between(5, 15)
        idio[asset] = 40 if proxy else rng.between(150, 400)
    state = _CALM
    cells: dict[str, dict[date, tuple[Decimal, Decimal]]] = {a: {} for a, _ in names}
    rows = []
    for month in months:
        if not rng.chance(*_STAY[state]):
            state = _STRESS if state == _CALM else _CALM
        market = _gauss(rng, _VOL_BP[state]) + (60 if state == _CALM else -40)
        fx = _gauss(rng, 150)
        for asset, currency in names:
            local = beta[asset] * market // 10 + _gauss(rng, idio[asset])
            local = max(local, -9000)
            fx_change = _bp(fx) if currency == "USD" else Decimal(0)
            cell = (_bp(local), fx_change)
            cells[asset][month] = cell
            rows.append(ReturnRow(asset, month, cell[0], cell[1]))
    panel = ReturnPanel.from_rows(months, [a for a, _ in names], rows)
    return panel, cells


def path_months(panel: ReturnPanel, season_seed: int, length: int, generator: str) -> list[date]:
    """The panel months the season walks through, in turn order."""
    seed = path_seed(season_seed)
    if generator == "simple":
        return simple.generate_months(list(panel.months), BASE_DATE, length, seed)
    if generator == "stationary":
        return list(stationary.generate(list(panel.months), BASE_DATE, length, seed).months)
    raise SimShapeError(f"generator must be one of {GENERATORS}")


def _attention_at(
    names: list[tuple[str, str]],
    returns: dict[str, Decimal],
    streaks: dict[str, int],
) -> dict[str, str | None]:
    order = sorted(returns, key=lambda a: (abs(returns[a]), a))
    top = max(len(order) - 1, 1)
    return {
        asset: f"{min(80 * order.index(asset) // top + 4 * min(streaks[asset], 5), 99)}.00"
        for asset, _ in names
    }


def _attention(
    names: list[tuple[str, str]],
    cells: dict[str, dict[date, tuple[Decimal, Decimal]]],
    path: list[date],
) -> list[dict[str, object]]:
    """Attention per turn 0..length from that turn's returns; turn 0 is empty, as in DB worlds."""
    streaks = dict.fromkeys((a for a, _ in names), 0)
    last_sign = dict.fromkeys(streaks, 0)
    rows: list[dict[str, object]] = []
    for turn, month in enumerate([None, *path]):
        if month is None:
            rows.append({"turn": turn, "values": dict.fromkeys(streaks)})
            continue
        returns = {a: cells[a][month][0] for a in streaks}
        for asset, value in returns.items():
            sign = (value > 0) - (value < 0)
            streaks[asset] = streaks[asset] + 1 if sign and sign == last_sign[asset] else 0
            last_sign[asset] = sign
        rows.append({"turn": turn, "values": _attention_at(names, returns, streaks)})
    return rows


def panel_world(
    master_seed: int, index: int, *, length: int, assets: int, generator: str
) -> tuple[BotWorld, ReturnPanel]:
    """One synthetic world and the panel its rank counts draw from."""
    if generator not in GENERATORS:
        raise SimShapeError(f"generator must be one of {GENERATORS}")
    if assets < MIN_ASSETS:
        raise SimShapeError(f"a panel world needs at least {MIN_ASSETS} assets")
    seed = world_seed(master_seed, index)
    names = _asset_names(assets)
    panel, cells = _build_panel(seed, names)
    path = path_months(panel, seed, length, generator)
    rng = BotRng(mix(seed, 0xB00C))
    pool = []
    for asset, currency in names:
        if asset == "KR:069500":
            pool.append(PoolAsset(asset, "KRW", Decimal(30000), Decimal(1), Position.MIDFIELDER))
        elif asset == "US:SPY":
            pool.append(
                PoolAsset(asset, "USD", Decimal(50), Decimal(1400), Position.FORWARD_TARGET)
            )
        elif currency == "KRW":
            pool.append(
                PoolAsset(
                    asset,
                    "KRW",
                    Decimal(5000 + 100 * rng.below(300)),
                    Decimal(1),
                    _SYSTEM[rng.below(len(_SYSTEM))],
                )
            )
        else:
            pool.append(
                PoolAsset(
                    asset,
                    "USD",
                    Decimal(10 + rng.below(190)),
                    Decimal(1400),
                    _SYSTEM[rng.below(len(_SYSTEM))],
                )
            )
    steps = tuple(
        PathStep(
            turn,
            {a: cells[a][month][0] for a, _ in names},
            {"USD": next(cells[a][month][1] for a, c in names if c == "USD")},
        )
        for turn, month in enumerate(path, start=1)
    )
    feed = synthetic_feed(seed, tuple(pool), length)
    feed["attention"] = _attention(names, cells, path)
    inputs = SimInputs(Settings(path_length=length), tuple(pool), steps)
    return BotWorld(seed, BASE_DATE, inputs, feed), panel
