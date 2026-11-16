from __future__ import annotations

import argparse
from collections import Counter

from ..settings import Settings
from .client import OpenRouterClient, SendCap, SendCapReached
from .ledger import Ledger
from .limiter import Limiter

MAX_PROBE_REQUESTS = 3
PROBE_MODELS = (
    "nvidia/nemotron-3-ultra-550b-a55b:free",
    "nvidia/nemotron-3.5-lightning:free",
)


def status_counts(rows: list[dict]) -> dict[str, int]:
    counts: Counter[str] = Counter()
    for row in rows:
        if row["phase"] == "attempt":
            counts[f"attempt {row['caller']} {row['ts'][:10]}"] += 1
        else:
            counts[f"outcome {row['status']}"] += 1
    return dict(sorted(counts.items()))


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    probe = sub.add_parser("probe")
    probe.add_argument("--live", action="store_true")
    probe.add_argument("--max-requests", type=int, default=3)
    probe.add_argument("--env-file")
    args = parser.parse_args()
    settings = Settings.from_env(getattr(args, "env_file", None), live=getattr(args, "live", False))
    if not settings.ledger_path:
        parser.error("ANTLAB_OPENROUTER_LEDGER is not set")
    ledger = Ledger(settings.ledger_path)
    if args.command == "status":
        for key, count in status_counts(ledger.rows()).items():
            print(f"{key}: {count}")
    elif args.live:
        # One cap covers the key GET, both chats and every retry: at most 3 sends in total.
        cap = SendCap(min(args.max_requests, MAX_PROBE_REQUESTS))
        client = OpenRouterClient(
            settings.api_key or "", Limiter(ledger, settings.tier), send_cap=cap
        )
        try:
            print("key_info", client.key_info(caller="product:probe").status)
            for model in PROBE_MODELS:
                result = client.chat(
                    model,
                    "You answer briefly.",
                    "Reply with the single word OK.",
                    caller="product:probe",
                )
                print(result.status)
        except SendCapReached:
            print("send cap reached")
    else:
        parser.error("probe requires --live")


if __name__ == "__main__":
    main()
