"""`python -m antlab_ai.detection dry-run`: render prompts and print the call plan, no network."""

from __future__ import annotations

import argparse
import json
import sys
from math import ceil
from pathlib import Path

from ..openrouter.limiter import PRODUCT_DAILY_CAP
from ..openrouter.policy import validate_model
from ..settings import Settings
from .plan import (
    DEFAULT_PROXIES,
    CallPlan,
    build_units,
    check_proxies,
    plan_hash,
    render_unit,
)
from .render import RENDER_VERSION, TEMPLATE_VERSION
from .sample import SampleError, default_sample_dir, load_sample

DEFAULT_MAX_TOKENS = 512
UNIT_FILE = "unit-{:04d}.txt"


def default_out_dir() -> Path:
    return Path(__file__).resolve().parents[4] / "data" / "ai" / "detection" / "dryrun"


def _write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def dry_run(args: argparse.Namespace) -> int:
    settings = Settings.from_env()
    for model in args.model:
        validate_model(model)
    sample = load_sample(args.sample_dir or default_sample_dir())
    check_proxies(sample, DEFAULT_PROXIES)
    units = build_units(sample)
    plan = CallPlan(units, max(len(args.model), 1), args.max_tokens)
    digest = plan_hash(units)
    product_cap = PRODUCT_DAILY_CAP[settings.tier]

    summary = {
        "calls_per_model": len(units),
        "days_at_product_cap": ceil(plan.total_calls / product_cap),
        "models": sorted(args.model),
        "plan_hash": digest,
        "product_daily_cap": product_cap,
        "render": RENDER_VERSION,
        "segment_lengths": [end - start for start, end in sample.segments],
        "template": TEMPLATE_VERSION,
        "tier": settings.tier,
        "total_calls": plan.total_calls,
        "units": len(units),
        "units_by_window": {str(k): n for k, n in plan.units_by_window.items()},
        "worst_output_tokens": plan.worst_output_tokens,
        "worst_usd": str(plan.worst_usd),
    }
    print(f"units: {summary['units']}")
    print("units by window: " + " ".join(f"{k}={n}" for k, n in plan.units_by_window.items()))
    print(f"segments: {len(sample.segments)}")
    print(f"calls per model: {summary['calls_per_model']}")
    print(f"total calls: {plan.total_calls} ({plan.model_count} model(s))")
    print(f"worst-case output tokens: {plan.worst_output_tokens}")
    print(f"worst-case usd: {plan.worst_usd}")
    print(
        f"days at product cap ({product_cap}/day, tier {settings.tier}): {summary['days_at_product_cap']}"
    )
    print(f"plan hash: {digest}")
    if args.max_calls is not None and not plan.fits(args.max_calls):
        print(f"over the call limit of {args.max_calls}: nothing written", file=sys.stderr)
        return 2

    out = Path(args.out) if args.out else default_out_dir()
    out.mkdir(parents=True, exist_ok=True)
    for index, unit in enumerate(units, start=1):
        system, user = render_unit(sample, unit)
        _write(out / UNIT_FILE.format(index), f"[system]\n{system}\n\n[user]\n{user}\n")
    stale = len(units) + 1
    while (out / UNIT_FILE.format(stale)).exists():
        (out / UNIT_FILE.format(stale)).unlink()
        stale += 1
    _write(out / "plan.json", json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(f"files: {len(units) + 1}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="antlab_ai.detection")
    sub = parser.add_subparsers(dest="command", required=True)
    dry = sub.add_parser("dry-run")
    dry.add_argument("--sample-dir", help="defaults to ANTLAB_GOLDEN_SAMPLE_DIR, then db/sample")
    dry.add_argument("--out", help="defaults to data/ai/detection/dryrun (git-ignored)")
    dry.add_argument("--model", action="append", default=[], help="repeat per model")
    dry.add_argument("--max-tokens", type=int, default=DEFAULT_MAX_TOKENS)
    dry.add_argument("--max-calls", type=int, help="refuse the plan when total calls exceed it")
    args = parser.parse_args(argv)
    try:
        return dry_run(args)
    except (SampleError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
