"""Score-weight validation CLI.

    python -m antlab_engine.validation run --profile ci
    python -m antlab_engine.validation analyze out/validation/ci-303

`run` writes the per-cell component files, then analyses them; `analyze` reads the same files, so
both give the same `result.json`. Offline only: no network, and the database is read-only.
"""

import argparse
import sys
from datetime import date
from pathlib import Path

from .experiment import (
    DEFAULT_BASE_DATE,
    DEFAULT_MASTER_SEED,
    MAX_WORKERS,
    PROFILES,
    ExperimentError,
    ProfileSkipped,
    analyze_run,
    run_profile,
)


def _say(message: str) -> None:
    print(message, flush=True)  # noqa: T201


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="antlab_engine.validation", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="run a profile (resumable) and analyse the files")
    run.add_argument("--profile", required=True, choices=PROFILES)
    run.add_argument("--workers", type=int, default=1, choices=range(1, MAX_WORKERS + 1))
    run.add_argument("--master-seed", type=int, default=DEFAULT_MASTER_SEED)
    run.add_argument("--base-date", type=date.fromisoformat, default=DEFAULT_BASE_DATE)
    run.add_argument(
        "--out", type=Path, default=None, help="default: out/validation/<profile>-<seed>"
    )
    analyze = commands.add_parser("analyze", help="analyse the files of a finished run")
    analyze.add_argument("directory", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "analyze":
            result, summary = analyze_run(args.directory)
        else:
            out = args.out or Path("out/validation") / f"{args.profile}-{args.master_seed}"
            done = run_profile(
                args.profile,
                out,
                master_seed=args.master_seed,
                workers=args.workers,
                base_date=args.base_date,
                say=_say,
            )
            result, summary = done.result_path, done.summary_path
            _say(f"wall {done.wall_seconds:.1f} s, cpu {done.cpu_seconds:.1f} s")
    except ProfileSkipped as skipped:
        _say(f"skipped: {skipped}")
        return 0
    except ExperimentError as error:
        print(f"error: {error}", file=sys.stderr)  # noqa: T201
        return 2
    _say(f"result {result}")
    _say(f"summary {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
