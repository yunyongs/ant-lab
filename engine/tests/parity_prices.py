"""Shared reading and exact arithmetic of the price parity vectors (not real history).

The files live in the Java test resources and are read in place, never copied:
`inputs-v1.csv` (hand-designed returns), `p0-prices-v1.csv` (Java-owned) and `m2-prices-v1.csv`
(Python-owned, written by `make_m2_price_vectors.py`). A price is the initial won price times the
exact product of `(1 + local_return) * (1 + fx_change)` over turns 1..t, rounded half up to a whole
won once at the end, the same rule as MoneyMath.priceAt.
"""

import decimal
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Context, Decimal
from pathlib import Path

REPO_DIR = Path(__file__).resolve().parents[2]
PARITY_DIR = REPO_DIR / "src" / "test" / "resources" / "engine" / "parity"
INPUTS_FILE = PARITY_DIR / "inputs-v1.csv"
P0_FILE = PARITY_DIR / "p0-prices-v1.csv"
M2_FILE = PARITY_DIR / "m2-prices-v1.csv"

INPUTS_HEADER = "month,asset,currency,local_return,fx_change,initial_price"
PRICES_HEADER = "seed,length,turn,asset,month,price"
BASE_DATE = date(2026, 1, 2)

# A product of 120 factors with at most 9 significant digits each and a 15-digit price has fewer
# than 1100 digits, so 4000 digits keep every product exact; the Inexact trap proves it per call.
EXACT = Context(prec=4000, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN, traps=[decimal.Inexact])
ROUND = Context(prec=4000, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN, rounding=ROUND_HALF_UP)


@dataclass(frozen=True)
class Inputs:
    months: list[date]
    assets: list[str]
    local: dict[tuple[str, date], Decimal]
    fx: dict[tuple[str, date], Decimal]
    initial: dict[str, int]
    currency: dict[str, str]


def month_of(text: str) -> date:
    year, month = text.split("-")
    return date(int(year), int(month), 1)


def load_inputs() -> Inputs:
    lines = INPUTS_FILE.read_text(encoding="utf-8").splitlines()
    assert lines[0] == INPUTS_HEADER
    months: set[date] = set()
    local, fx, initial, currency = {}, {}, {}, {}
    for line in lines[1:]:
        text, asset, cur, local_text, fx_text, initial_text = line.split(",")
        month = month_of(text)
        assert (asset, month) not in local, "the same (month, asset) appears twice"
        months.add(month)
        local[(asset, month)] = Decimal(local_text)
        fx[(asset, month)] = Decimal(fx_text)
        assert initial.setdefault(asset, int(initial_text)) == int(initial_text)
        assert currency.setdefault(asset, cur) == cur
    return Inputs(sorted(months), sorted(initial), local, fx, initial, currency)


def exact_factor(inputs: Inputs, asset: str, path: list[date]) -> Decimal:
    """The exact product of the growth factors along `path`; raises if it would be inexact."""
    value = Decimal(1)
    for month in path:
        one_local = EXACT.add(Decimal(1), inputs.local[(asset, month)])
        one_fx = EXACT.add(Decimal(1), inputs.fx[(asset, month)])
        value = EXACT.multiply(EXACT.multiply(value, one_local), one_fx)
    return value


def exact_value(inputs: Inputs, asset: str, path: list[date]) -> Decimal:
    """Initial price times the exact factor product, before the won rounding."""
    return EXACT.multiply(Decimal(inputs.initial[asset]), exact_factor(inputs, asset, path))


def won(value: Decimal) -> int:
    """Whole won, half up, rounded once."""
    return int(ROUND.quantize(value, Decimal(1)))


def read_price_rows(file: Path) -> list[tuple[int, int, int, str, date, int]]:
    """(seed, length, turn, asset, month, price) rows of a vector file."""
    assert file.is_file(), f"tracked vector file is missing: {file}"
    lines = file.read_text(encoding="utf-8").splitlines()
    assert lines[0] == PRICES_HEADER
    rows = []
    for line in lines[1:]:
        seed, length, turn, asset, month, price = line.split(",")
        rows.append((int(seed), int(length), int(turn), asset, month_of(month), int(price)))
    return rows


def paths_of(rows) -> dict[tuple[int, int], list[date]]:
    """Months in turn order per (seed, length), read from the rows of the first asset."""
    paths: dict[tuple[int, int], list[date]] = {}
    for seed, length, turn, _, month, _ in rows:
        path = paths.setdefault((seed, length), [])
        if len(path) < turn:
            path.append(month)
        assert path[turn - 1] == month, "the months of one path differ between assets"
    return paths
