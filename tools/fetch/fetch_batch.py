"""Incremental batch fetch CLI (MVP-04). Run from the main checkout only; it refuses to send from a git worktree.

  PY=tools/fetch/.venv/bin/python
  $PY tools/fetch/fetch_batch.py status                      # no network: per-asset state and today's requests
  $PY tools/fetch/fetch_batch.py sweep --daily-cap 400       # monthly bars + base-date close, whole assets only
  $PY tools/fetch/fetch_batch.py drip                        # daily closes + base-date close, whole assets only
  $PY tools/fetch/fetch_batch.py yf                          # one full-range yfinance call per symbol
  $PY tools/fetch/fetch_batch.py compare                     # offline: INTERNAL and CROSS compare from the cache, applies judgments.csv
  $PY tools/fetch/fetch_batch.py judge                       # offline: RULE judgements for three mechanical classes + human_queue.csv
  $PY tools/fetch/fetch_batch.py norm                        # offline: norm/{daily,dividends,shares}.csv + MANIFEST.csv

Secrets are read from .env only and masked in every message. No DB access, no price values in any output.
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from batch import (  # noqa: E402
    CLEAN_STOPS, DEFAULT_CAP, HARD_MAX_CAP, KIS_BATCH_RPS, BatchRefused, BatchResult, batch_status, kst_now,
    load_fetch_targets, run_kis_batch, run_yf_batch, Terminated,
)
from common import REPO_ROOT, SecretFilter, load_config, mask, read_env  # noqa: E402
from compare_full import blocking, run_compare, run_judge, run_norm, summary  # noqa: E402
from kis import Clock, KisError  # noqa: E402

DEFAULT_TARGETS = REPO_ROOT / "db" / "full" / "fetch_targets.csv"
DEFAULT_RAW_DIR = REPO_ROOT / "data" / "raw" / "mvp-04"


def _summary(r: BatchResult) -> str:
    return (f"{r.source} {r.kind} batch {r.batch_no} on {r.day}: requests={r.requests} stop={r.stop_reason} "
            f"assets_done={r.assets_done} assets_left={r.assets_left} first={r.first_target or '-'} last={r.last_target or '-'}"
            + (f" stopped_on={r.stopped_on}" if r.stopped_on else "")
            + (f" failed={','.join(r.failed)}" if r.failed else ""))


def cmd_status(args: argparse.Namespace) -> int:
    targets = load_fetch_targets(Path(args.targets))
    st = batch_status(targets, Path(args.raw_dir), kst_now(Clock()).strftime("%Y-%m-%d"), repo_root=REPO_ROOT)
    for kind in ("sweep", "drip", "yf"):
        print(f"{kind}: " + ", ".join(f"{k} {v}" for k, v in sorted(st[kind].items())) + f" (assets {len(targets)})")
    for line in st["failed"]:
        print(f"FAILED {line} (delete data/raw/mvp-04/failed/<file> after fixing the cause to retry)")
    print(f"requests today: KIS {st['requests_today']['KIS']}, YF {st['requests_today']['YF']}")
    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    res, jprob = run_compare(load_fetch_targets(Path(args.targets)), Path(args.raw_dir), REPO_ROOT, refetch_diff=args.refetch_diff)
    print(f"compare: {summary(res.rows)}")
    print(f"assets not done {len(res.skipped)}, asset problems {len(res.problems)}, judgement problems {len(jprob)}")
    for line in res.problems + jprob:
        print(f"problem: {line}", file=sys.stderr)
    if res.refetch:
        print(f"{len(res.refetch)} assets need a whole refetch (INTERNAL DIFF or NO_BAR; list in compare/refetch.csv)"
              + (" - moved to stale/" if args.refetch_diff else " - run compare --refetch-diff to move them to stale/"))
    for line in res.skipped:
        print(f"not done: {line}", file=sys.stderr)
    return 1 if blocking(res.rows) or res.problems or jprob or res.skipped else 0


def cmd_judge(args: argparse.Namespace) -> int:
    out = run_judge(load_fetch_targets(Path(args.targets)), Path(args.raw_dir), REPO_ROOT)
    print(f"judge: {summary(out['rows'])}")
    print("new RULE judgements: " + (", ".join(f"{k} {v}" for k, v in sorted(out["proposed"].items())) or "none")
          + f"; human queue {len(out['queue'])} (compare/human_queue.csv); assets not done {len(out['skipped'])}")
    for line in out["problems"]:
        print(f"problem: {line}", file=sys.stderr)
    for line in out["skipped"]:
        print(f"not done: {line}", file=sys.stderr)
    return 1 if out["queue"] or out["problems"] or blocking(out["rows"]) or out["skipped"] else 0


def cmd_norm(args: argparse.Namespace) -> int:
    res = run_norm(load_fetch_targets(Path(args.targets)), Path(args.raw_dir), REPO_ROOT)
    for line in res.messages:
        print(line, file=sys.stderr)
    for name, h in res.hashes.items():
        print(f"{name} sha256={h}")
    return 0 if res.ok else 1


def _kis(args: argparse.Namespace) -> BatchResult:
    import requests  # lazy import

    cfg = load_config(read_env(Path(args.env_file)), KIS_BATCH_RPS)
    for h in logging.getLogger().handlers:
        h.addFilter(SecretFilter(cfg.secrets()))
    session = requests.Session()
    session.trust_env = False
    return run_kis_batch(args.cmd, load_fetch_targets(Path(args.targets)), cfg=cfg, session=session, raw_dir=Path(args.raw_dir),
                         repo_root=REPO_ROOT, cap=args.daily_cap, same_day=args.same_day, outside_window=args.outside_window)


def _yf(args: argparse.Namespace) -> BatchResult:
    return run_yf_batch(load_fetch_targets(Path(args.targets)), raw_dir=Path(args.raw_dir), repo_root=REPO_ROOT,
                        cap=args.daily_cap, same_day=args.same_day, outside_window=args.outside_window)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("status", "sweep", "drip", "yf", "compare", "judge", "norm"):
        p = sub.add_parser(name)
        p.add_argument("--targets", default=str(DEFAULT_TARGETS), help="fetch_targets.csv (db/full)")
        p.add_argument("--raw-dir", default=str(DEFAULT_RAW_DIR), help="raw cache folder, inside data/ of the repository")
        if name == "compare":
            p.add_argument("--refetch-diff", action="store_true", help="move assets with an INTERNAL DIFF or missing bar to stale/ so the next sweep and drip fetch them whole")
        if name not in ("status", "compare", "judge", "norm"):
            src = "YF" if name == "yf" else "KIS"
            p.add_argument("--daily-cap", type=int, default=DEFAULT_CAP[src], help=f"requests per KST day (default {DEFAULT_CAP[src]}, hard maximum {HARD_MAX_CAP[src]})")
            p.add_argument("--same-day", action="store_true", help="owner only: allow a second batch on the same KST day (the cap still applies)")
            p.add_argument("--outside-window", action="store_true", help="owner only: run outside 21:00-23:00 KST")
            if name != "yf":
                p.add_argument("--env-file", default=str(REPO_ROOT / ".env"), help="secrets .env (default: repository root)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        if args.cmd == "status":
            return cmd_status(args)
        if args.cmd in ("compare", "judge", "norm"):
            return {"compare": cmd_compare, "judge": cmd_judge, "norm": cmd_norm}[args.cmd](args)
        result = _yf(args) if args.cmd == "yf" else _kis(args)
    except Terminated as exc:
        print(f"terminated by {exc}; the ledger row was written", file=sys.stderr)
        return 1
    except BatchRefused as exc:
        print(f"refused ({exc.reason}): {mask(exc)}", file=sys.stderr)
        return 3
    except (KisError, ValueError, OSError) as exc:
        print(mask(str(exc)), file=sys.stderr)
        return 2
    print(_summary(result))
    return 0 if result.stop_reason in CLEAN_STOPS else 1


if __name__ == "__main__":
    sys.exit(main())
