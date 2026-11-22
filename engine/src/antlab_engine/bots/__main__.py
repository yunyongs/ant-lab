"""Batch CLI: `python -m antlab_engine.bots --world synthetic:7 --random 200 --static 1`.

Offline only: no database, no network. Output goes to `out/bots/<run>/` (git-ignored).
`--world panel:INDEX[:ASSETS[:LENGTH[:GENERATOR]]]` draws a synthetic panel world from
`--master-seed`; `--counts [N]` then fills the rank counts and the result score from N engine paths.
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from antlab_engine import __version__ as engine_version
from antlab_engine.projection import ReturnPanel
from antlab_engine.sim.counts import MAX_PATHS, MIN_PATHS
from antlab_engine.validation.counts_cache import CachedEngineCounts

from . import io
from .disciplined_bot import VERSION as DISCIPLINED_VERSION
from .news_bot import VERSION as NEWS_VERSION
from .newsmix_bot import VERSION as NEWSMIX_VERSION
from .random_bot import VERSION as RANDOM_VERSION
from .redeclare_bot import VERSION as REDECLARE_VERSION
from .runner import run_batch
from .static_bot import VERSION as STATIC_VERSION
from .world import BotWorld, synthetic_world, world_from_doc
from .world_feed import default_catalog
from .world_panel import GENERATORS, panel_world


def load_world_with_panel(spec: str, master_seed: int = 1) -> tuple[BotWorld, ReturnPanel | None]:
    if spec.startswith("panel:"):
        fields = spec.split(":")[1:]
        generator = "simple"
        if fields and fields[-1] in GENERATORS:
            generator = fields.pop()
        try:
            parts = [int(p) for p in fields]
        except ValueError:
            parts = []
        if not 1 <= len(parts) <= 3:
            raise SystemExit("panel:INDEX[:ASSETS[:LENGTH[:simple|stationary]]]")
        index = parts[0]
        assets = parts[1] if len(parts) > 1 else 30
        length = parts[2] if len(parts) > 2 else 60
        return panel_world(master_seed, index, length=length, assets=assets, generator=generator)
    return load_world(spec), None


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


def parse_newsmix(text: str) -> list[int]:
    try:
        values = [int(p) for p in text.split(",")]
    except ValueError:
        values = []
    if not values or any(not 0 <= v <= 100 for v in values) or len(set(values)) != len(values):
        raise argparse.ArgumentTypeError("--newsmix takes distinct whole percents, e.g. 25,50,75")
    return values


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
    parser.add_argument(
        "--newsmix",
        type=parse_newsmix,
        default=[],
        metavar="Q,Q",
        help="also run one newsmix-Q bot per Q (news on Q percent of turns), e.g. 25,50,75",
    )
    parser.add_argument(
        "--redeclare",
        type=int,
        default=0,
        metavar="N",
        help="also run N redeclare bots (random bots 0..N-1 that redeclare the formation)",
    )
    parser.add_argument(
        "--counts",
        type=int,
        nargs="?",
        const=2000,
        default=None,
        metavar="N",
        help="rank counts from N engine paths over the panel of a panel: world (default 2000)",
    )
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--keep-decisions", action="store_true")
    parser.add_argument("--git-commit", default=None)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)
    if args.redeclare < 0:
        parser.error("--redeclare must not be negative")
    if args.counts is not None and not MIN_PATHS <= args.counts <= MAX_PATHS:
        parser.error(f"--counts must be between {MIN_PATHS} and {MAX_PATHS}")

    world, panel = load_world_with_panel(args.world, args.master_seed)
    counts_source = None
    if args.counts is not None:
        if panel is None:
            raise SystemExit("--counts needs a panel: world")
        counts_source = CachedEngineCounts(panel)
    specs = [("random", i) for i in range(args.randoms)] + [
        ("static", i) for i in range(args.statics)
    ]
    specs += [("disciplined", 0)] * args.disciplined
    specs += [("disciplined-noguard", 0)] * (args.disciplined and args.no_guard)
    specs += [("news", 0)] * args.news
    specs += [(f"newsmix-{q}", 0) for q in args.newsmix]
    specs += [("redeclare", i) for i in range(args.redeclare)]
    records = run_batch(
        world,
        specs,
        args.master_seed,
        workers=args.workers,
        keep_decisions=args.keep_decisions,
        counts_source=counts_source,
        path_count=2000 if args.counts is None else args.counts,
    )
    key = (
        f"{world.world_id}:{args.master_seed}:{args.randoms}:{args.statics}"
        f":{args.disciplined}:{int(args.no_guard)}:{args.news}"
    )
    if args.newsmix:
        key += ":newsmix" + ",".join(str(q) for q in args.newsmix)
    if args.redeclare:
        key += f":redeclare{args.redeclare}"
    if args.counts is not None:
        key += f":counts{args.counts}"
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
            **({"newsmix": NEWSMIX_VERSION} if args.newsmix else {}),
            **({"redeclare": REDECLARE_VERSION} if args.redeclare else {}),
        },
        "catalogSha256": None if world.feed is None else default_catalog().sha256,
        "engineVersion": engine_version,
        "gitCommit": args.git_commit,
        "runs": len(records),
    }
    if args.counts is not None:
        manifest["counts"] = {"paths": args.counts, "source": "CachedEngineCounts"}
    runs, manifest_path = io.write_batch(out, records, manifest)
    print(f"{len(records)} runs -> {runs}; manifest {manifest_path}")  # noqa: T201
    return 0


if __name__ == "__main__":
    sys.exit(main())
