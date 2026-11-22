"""Batch CLI: `python -m antlab_engine.bots --world synthetic:7 --random 200 --static 1`.

Offline only: no database, no network. Output goes to `out/bots/<run>/` (git-ignored).
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from antlab_engine import __version__ as engine_version

from . import io
from .disciplined_bot import VERSION as DISCIPLINED_VERSION
from .news_bot import VERSION as NEWS_VERSION
from .random_bot import VERSION as RANDOM_VERSION
from .runner import run_batch
from .static_bot import VERSION as STATIC_VERSION
from .world import BotWorld, synthetic_world, world_from_doc
from .world_feed import default_catalog


def load_world(spec: str) -> BotWorld:
    if spec.startswith("synthetic:"):
        try:
            parts = [int(p) for p in spec.split(":")[1:]]
        except ValueError:
            parts = []
        if not 1 <= len(parts) <= 3:
            raise SystemExit("synthetic:SEED[:ASSETS[:LENGTH]]")
        return synthetic_world(*parts, feed=True)
    return world_from_doc(json.loads(Path(spec).read_text(encoding="utf-8")))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="antlab_engine.bots", description=__doc__)
    parser.add_argument("--world", required=True, help="synthetic:SEED[:ASSETS[:LENGTH]] or a file")
    parser.add_argument("--master-seed", type=int, default=1)
    parser.add_argument("--random", type=int, default=200, dest="randoms")
    parser.add_argument("--static", type=int, default=1, dest="statics")
    parser.add_argument("--disciplined", type=int, default=1, choices=(0, 1), dest="disciplined")
    parser.add_argument(
        "--no-guard",
        action="store_true",
        help="also run the disciplined bot without the 8 percent guard",
    )
    parser.add_argument("--news", type=int, default=1, choices=(0, 1), dest="news")
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--keep-decisions", action="store_true")
    parser.add_argument("--git-commit", default=None)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    world = load_world(args.world)
    specs = [("random", i) for i in range(args.randoms)] + [
        ("static", i) for i in range(args.statics)
    ]
    specs += [("disciplined", 0)] * args.disciplined
    specs += [("disciplined-noguard", 0)] * (args.disciplined and args.no_guard)
    specs += [("news", 0)] * args.news
    records = run_batch(
        world,
        specs,
        args.master_seed,
        workers=args.workers,
        keep_decisions=args.keep_decisions,
    )
    key = (
        f"{world.world_id}:{args.master_seed}:{args.randoms}:{args.statics}"
        f":{args.disciplined}:{int(args.no_guard)}:{args.news}"
    )
    run = hashlib.sha256(key.encode()).hexdigest()[:12]
    out = args.out or Path("out/bots") / run
    manifest = {
        "schema": "bot-manifest/1",
        "masterSeed": args.master_seed,
        "worldIds": [world.world_id],
        "botVersions": {
            "random": RANDOM_VERSION,
            "static": STATIC_VERSION,
            "disciplined": DISCIPLINED_VERSION,
            "news": NEWS_VERSION,
        },
        "catalogSha256": None if world.feed is None else default_catalog().sha256,
        "engineVersion": engine_version,
        "gitCommit": args.git_commit,
        "runs": len(records),
    }
    runs, manifest_path = io.write_batch(out, records, manifest)
    print(f"{len(records)} runs -> {runs}; manifest {manifest_path}")  # noqa: T201
    return 0


if __name__ == "__main__":
    sys.exit(main())
