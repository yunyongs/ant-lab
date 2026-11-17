"""Prompt rendering rule `render/1` and template `detection-month/1`.

Masked on purpose: dates, block ids, event names, local-currency returns, the FX line and macro
values. Only relative month labels and KRW-basis returns reach the prompt.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal, localcontext
from importlib import resources

from .sample import Sample

RENDER_VERSION = "render/1"
TEMPLATE_VERSION = "detection-month/1"

_RATIO_QUANTUM = Decimal("0.0001")
_PERCENT_QUANTUM = Decimal("0.01")
_SPLIT = "\n=== user ===\n"


def krw_return(market: str, local_return: Decimal, fx_change: Decimal) -> Decimal:
    """KRW-basis month return rounded HALF_UP to 4 decimal places of ratio."""
    with localcontext() as context:
        context.prec = 40
        ratio = (1 + local_return) * (1 + fx_change) - 1 if market == "US" else local_return
        return ratio.quantize(_RATIO_QUANTUM, rounding=ROUND_HALF_UP)


def format_percent(ratio: Decimal) -> str:
    percent = (ratio * 100).quantize(_PERCENT_QUANTUM)
    if percent == 0:
        percent = abs(percent)
    return f"{percent:f}%"


def _template() -> tuple[str, str]:
    path = resources.files("antlab_ai.detection") / "prompts" / "detection-month-1.md"
    system, user = path.read_text(encoding="utf-8").split(_SPLIT)
    return system.strip(), user.strip()


def render_window(
    sample: Sample, first: int, count: int, proxies: tuple[tuple[str, str], ...]
) -> tuple[str, str]:
    """Render `count` months starting at global month index `first` as (system, user) text."""
    system, user = _template()
    lines = []
    for asset in sample.assets:
        cells = [
            format_percent(
                krw_return(asset.market, sample.returns[asset.key][index], sample.fx[index])
            )
            for index in range(first, first + count)
        ]
        lines.append(f"{asset.market} {asset.ticker} {asset.name}: {', '.join(cells)}")
    user = (
        user.replace("{{count}}", str(count))
        .replace("{{proxies}}", ", ".join(f"{market} {ticker}" for market, ticker in proxies))
        .replace("{{table}}", "\n".join(lines))
    )
    return system, user
