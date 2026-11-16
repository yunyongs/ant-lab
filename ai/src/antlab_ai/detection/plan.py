"""Deterministic call plan: one unit per (target month, window) inside one contiguous segment."""

from __future__ import annotations

import hashlib
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from .render import RENDER_VERSION, TEMPLATE_VERSION, render_window
from .sample import Sample, SampleError

WINDOWS = (1, 3, 6)
DEFAULT_PROXIES = (("KR", "069500"), ("US", "SPY"))


@dataclass(frozen=True)
class Unit:
    segment: int
    window: int
    # Offset of the target month inside its segment.
    target: int


def units_for_segments(lengths: Sequence[int]) -> tuple[Unit, ...]:
    """Targets need `window` months ending at the target and one more month after it."""
    units = []
    for segment, length in enumerate(lengths):
        for window in WINDOWS:
            for target in range(window - 1, length - 1):
                units.append(Unit(segment, window, target))
    return tuple(units)


def build_units(sample: Sample) -> tuple[Unit, ...]:
    return units_for_segments([end - start for start, end in sample.segments])


def unit_months(sample: Sample, unit: Unit) -> list[date]:
    """Window months followed by the direction month."""
    start = sample.segments[unit.segment][0]
    first = start + unit.target - unit.window + 1
    return list(sample.months[first : start + unit.target + 2])


def render_unit(
    sample: Sample, unit: Unit, proxies: tuple[tuple[str, str], ...] = DEFAULT_PROXIES
) -> tuple[str, str]:
    start = sample.segments[unit.segment][0]
    return render_window(sample, start + unit.target - unit.window + 1, unit.window, proxies)


def check_proxies(sample: Sample, proxies: tuple[tuple[str, str], ...]) -> None:
    keys = {asset.key for asset in sample.assets}
    if any(proxy not in keys for proxy in proxies):
        raise SampleError("a market proxy is not in the sample pool")


def plan_hash(units: Sequence[Unit]) -> str:
    lines = [RENDER_VERSION, TEMPLATE_VERSION]
    lines += [f"{unit.segment},{unit.window},{unit.target}" for unit in units]
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class CallPlan:
    units: tuple[Unit, ...]
    model_count: int
    max_tokens: int

    @property
    def units_by_window(self) -> dict[int, int]:
        return {k: sum(1 for unit in self.units if unit.window == k) for k in WINDOWS}

    @property
    def total_calls(self) -> int:
        return len(self.units) * self.model_count

    @property
    def worst_output_tokens(self) -> int:
        return self.total_calls * self.max_tokens

    @property
    def worst_usd(self) -> Decimal:
        # Product models are free-only (policy), so tokens and calls are the real bounds.
        return Decimal("0")

    def fits(self, max_calls: int) -> bool:
        return self.total_calls <= max_calls
