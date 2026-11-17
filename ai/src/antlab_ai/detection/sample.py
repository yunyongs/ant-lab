"""Reader for the P0-08 sample layout (db/sample CSVs).

Returns and FX stay exact `Decimal`s. Error messages carry file names and line numbers only,
never cell values: the return files are git-ignored licensed data.
"""

from __future__ import annotations

import csv
import os
import re
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from pathlib import Path

MARKETS = ("KR", "US")
SAMPLE_DIR_ENV = "ANTLAB_GOLDEN_SAMPLE_DIR"

_RATIO = re.compile(r"-?[0-9]+\.[0-9]{8}")


class SampleError(ValueError):
    pass


@dataclass(frozen=True)
class Asset:
    market: str
    ticker: str
    name: str

    @property
    def key(self) -> tuple[str, str]:
        return (self.market, self.ticker)


@dataclass(frozen=True)
class Sample:
    assets: tuple[Asset, ...]
    months: tuple[date, ...]
    # (start, end) index pairs into `months`, end exclusive; each is one contiguous calendar run.
    segments: tuple[tuple[int, int], ...]
    # Local-currency month returns per asset, aligned with `months`.
    returns: dict[tuple[str, str], tuple[Decimal, ...]]
    # USD month change aligned with `months`.
    fx: tuple[Decimal, ...]


def default_sample_dir() -> Path:
    env = os.environ.get(SAMPLE_DIR_ENV)
    if env:
        return Path(env)
    return Path(__file__).resolve().parents[4] / "db" / "sample"


def parse_ratio(text: str) -> Decimal:
    """Parse a ratio with exactly 8 fraction digits; anything else is refused."""
    if not _RATIO.fullmatch(text):
        raise SampleError("ratio must be a plain decimal with exactly 8 fraction digits")
    return Decimal(text)


def _read(directory: Path, name: str, columns: tuple[str, ...]) -> list[tuple[int, dict]]:
    path = directory / name
    if not path.is_file():
        raise SampleError(f"missing sample file {name}")
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not set(columns) <= set(reader.fieldnames):
            raise SampleError(f"{name}: header must contain {', '.join(columns)}")
        rows = []
        for row in reader:
            if any(row.get(column) is None for column in columns):
                raise SampleError(f"{name} line {reader.line_num}: short row")
            rows.append((reader.line_num, row))
    return rows


def _month(text: str, name: str, line: int) -> date:
    try:
        value = date.fromisoformat(text)
    except ValueError:
        raise SampleError(f"{name} line {line}: month_start is not a date") from None
    if value.day != 1:
        raise SampleError(f"{name} line {line}: month_start must be the first of the month")
    return value


def _month_number(value: date) -> int:
    return value.year * 12 + value.month


def detect_segments(months: tuple[date, ...]) -> tuple[tuple[int, int], ...]:
    segments: list[tuple[int, int]] = []
    start = 0
    for index in range(1, len(months)):
        if _month_number(months[index]) - _month_number(months[index - 1]) != 1:
            segments.append((start, index))
            start = index
    if months:
        segments.append((start, len(months)))
    return tuple(segments)


def load_sample(directory: Path | str) -> Sample:
    directory = Path(directory)

    assets: dict[tuple[str, str], Asset] = {}
    for line, row in _read(directory, "asset.csv", ("market", "ticker", "name")):
        asset = Asset(row["market"], row["ticker"], row["name"])
        if asset.market not in MARKETS:
            raise SampleError(f"asset.csv line {line}: unknown market")
        if asset.key in assets:
            raise SampleError(f"asset.csv line {line}: duplicate asset")
        assets[asset.key] = asset
    if not assets:
        raise SampleError("asset.csv has no rows")

    months: list[date] = []
    for line, row in _read(directory, "month_block.csv", ("month_start",)):
        month = _month(row["month_start"], "month_block.csv", line)
        if months and month <= months[-1]:
            raise SampleError(f"month_block.csv line {line}: months must be strictly ascending")
        months.append(month)
    if not months:
        raise SampleError("month_block.csv has no rows")
    position = {month: index for index, month in enumerate(months)}

    columns = ("market", "ticker", "month_start", "local_return")
    cells: dict[tuple[str, str], dict[int, Decimal]] = {key: {} for key in assets}
    for line, row in _read(directory, "asset_month_return.csv", columns):
        key = (row["market"], row["ticker"])
        month = _month(row["month_start"], "asset_month_return.csv", line)
        if key not in assets or month not in position:
            raise SampleError(f"asset_month_return.csv line {line}: unknown asset or month")
        try:
            value = parse_ratio(row["local_return"])
        except SampleError as error:
            raise SampleError(f"asset_month_return.csv line {line}: {error}") from None
        if position[month] in cells[key]:
            raise SampleError(f"asset_month_return.csv line {line}: duplicate row")
        cells[key][position[month]] = value
    for found in cells.values():
        if len(found) != len(months):
            raise SampleError("asset_month_return.csv: every asset needs every month")

    fx_cells: dict[int, Decimal] = {}
    for line, row in _read(directory, "fx_month.csv", ("month_start", "currency", "fx_change")):
        month = _month(row["month_start"], "fx_month.csv", line)
        if row["currency"] != "USD" or month not in position:
            raise SampleError(f"fx_month.csv line {line}: unknown currency or month")
        try:
            value = parse_ratio(row["fx_change"])
        except SampleError as error:
            raise SampleError(f"fx_month.csv line {line}: {error}") from None
        if position[month] in fx_cells:
            raise SampleError(f"fx_month.csv line {line}: duplicate row")
        fx_cells[position[month]] = value
    if len(fx_cells) != len(months):
        raise SampleError("fx_month.csv: every month needs a row")

    ordered = tuple(sorted(assets.values(), key=lambda asset: asset.key))
    month_tuple = tuple(months)
    return Sample(
        assets=ordered,
        months=month_tuple,
        segments=detect_segments(month_tuple),
        returns={
            key: tuple(found[index] for index in range(len(months))) for key, found in cells.items()
        },
        fx=tuple(fx_cells[index] for index in range(len(months))),
    )
