"""Fixture ledgers; the sentinel strings must never reach the exporter output."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

NOW = datetime(2026, 11, 20, 3, 0, 0, tzinfo=UTC)  # 12:00 KST, same calendar day in both zones
SENTINELS = (
    "SECRET-PURPOSE",
    "11111111-2222-4333-8444-555555555555",
    "RUN-SENTINEL",
    "SUFFIX-SENTINEL",
    "TICKER-SENTINEL",
    "FREE-TEXT-SENTINEL",
)


def attempt(call_id, ts, caller="product:SUFFIX-SENTINEL", model="openai/gpt-oss-20b:free"):
    return {
        "v": 1,
        "phase": "attempt",
        "call_id": call_id,
        "ts": ts,
        "caller": caller,
        "run_id": "RUN-SENTINEL",
        "model": model,
        "kind": "chat",
        "max_tokens": 100,
        "worst_usd": "0",
    }


def outcome(call_id, ts, status="OK", tin=10, tout=5, cost="0.001"):
    return {
        "v": 1,
        "phase": "outcome",
        "call_id": call_id,
        "ts": ts,
        "status": status,
        "http": 200,
        "model_actual": "x/y",
        "provider": "TICKER-SENTINEL",
        "generation_id": "FREE-TEXT-SENTINEL",
        "tokens_in": tin,
        "tokens_out": tout,
        "cost_usd": cost,
        "latency_ms": 5,
        "retry_after_s": None,
    }


def write_jsonl(path: Path, rows) -> Path:
    path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    return path


def openrouter_ledger(path: Path) -> Path:
    big = "11111111-2222-4333-8444-555555555555"
    rows = [
        # yesterday UTC: counted nowhere
        attempt("a0", "2026-11-19T23:59:59.000Z"),
        outcome("a0", "2026-11-19T23:59:59.500Z", tin=999, tout=999, cost="9"),
        attempt(big, "2026-11-20T01:00:00.000Z"),
        outcome(big, "2026-11-20T01:00:01.000Z", tin=100, tout=50, cost="0.25"),
        attempt("a2", "2026-11-20T01:10:00.000Z", model="openai/gpt-oss-20b:free"),
        outcome("a2", "2026-11-20T01:10:01.000Z", status="TIMEOUT", tin=0, tout=0, cost="0"),
        attempt("a3", "2026-11-20T02:00:00.000Z", caller="harness:SUFFIX-SENTINEL", model="a/b"),
        outcome("a3", "2026-11-20T02:00:02.000Z", status="LIMITED", tin=None, tout=None, cost=None),
        attempt("a4", "2026-11-20T02:59:30.000Z", model="Not A Model! TICKER-SENTINEL"),
        attempt("a5", "2026-11-20T02:59:45.000Z", caller="weird", model="a/b"),
    ]
    return write_jsonl(path, rows)


def ollama_ledger(path: Path) -> Path:
    rows = [
        {
            "ts": "2026-11-19T23:30:00",
            "model": "m",
            "purpose": "SECRET-PURPOSE",
            "usd": 1.25,
            "status": "ok",
        },
        {
            "ts": "2026-11-20T00:10:00",
            "model": "m",
            "purpose": "SECRET-PURPOSE",
            "usd": 0.5,
            "status": "ok",
        },
        {
            "ts": "2026-11-20T09:00:00",
            "model": "m",
            "purpose": "SECRET-PURPOSE",
            "usd": 0.25,
            "status": "ok",
        },
    ]
    return write_jsonl(path, rows)


FETCH_HEADER = "day_kst,source,batch_no,requests,stop_reason,first_target,last_target\n"


def fetch_ledger(path: Path) -> Path:
    path.write_text(
        FETCH_HEADER
        + "2026-11-19,KIS,1,500,DONE,KR:TICKER-SENTINEL,KR:005930\n"
        + "2026-11-20,KIS,1,300,BUDGET,KR:TICKER-SENTINEL,KR:005930\n"
        + "2026-11-20,KIS,2,20,DONE,KR:005930,KR:005930\n"
        + "2026-11-20,YF,1,50,BUDGET,US:AAPL,US:ABT\n",
        encoding="utf-8",
    )
    return path
