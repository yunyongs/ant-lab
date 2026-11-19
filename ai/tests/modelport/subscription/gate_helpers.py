"""Builders for the gate, ledger and usage tests: a scripted meter and a gate that is ready to run."""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

from antlab_ai.modelport.subscription import (
    BatchGate,
    BatchLedger,
    BatchLock,
    OwnerProof,
    UsageSnapshot,
    UsageUnavailable,
)

NOW = datetime(2026, 11, 19, 12, 0, 0, tzinfo=UTC)
MODEL = "claude-x"


def snapshot(weekly="10", session="5", route="CLAUDE_CLI", as_of=NOW) -> UsageSnapshot:
    return UsageSnapshot(route, Decimal(weekly), Decimal(session), as_of)


class FakeUsage:
    """A meter that returns a scripted snapshot (or raises) and counts its reads."""

    def __init__(self, weekly="10", session="5"):
        self.current: UsageSnapshot | None = snapshot(weekly, session)
        self.script: list[UsageSnapshot | None] = []
        self.reads = 0

    def set(self, weekly="10", session="5") -> None:
        self.current = snapshot(weekly, session)

    def read(self, route: str) -> UsageSnapshot:
        self.reads += 1
        value = self.script.pop(0) if self.script else self.current
        if value is None:
            raise UsageUnavailable
        return value


def make_gate(
    tmp_path: Path,
    *,
    usage: FakeUsage | None = None,
    route: str = "CLAUDE_CLI",
    model_id: str = MODEL,
    batch_id: str = "batch-1",
    max_calls: int = 15,
    environ: dict[str, str] | None = None,
    lock: bool = False,
    owned: bool = True,
    markers: tuple[Path, ...] | None = None,
    start: bool | None = None,
) -> BatchGate:
    """A gate; by default a started, active one (startup run against a healthy meter)."""
    ledger = BatchLedger(tmp_path / "state" / "subscription" / "calls.jsonl")
    gate = BatchGate(
        route=route,
        model_id=model_id,
        batch_id=batch_id,
        max_calls=max_calls,
        usage=usage or FakeUsage(),
        ledger=ledger,
        lock=BatchLock(tmp_path / "state" / "subscription" / "batch.lock") if lock else None,
        lockless_for_tests=not lock,
        environ={} if environ is None else environ,
        container_markers=markers if markers is not None else (tmp_path / "no-dockerenv",),
    )
    if owned:
        gate.attach_owner(OwnerProof(11, route, model_id, 1))
    if start is None:
        start = owned and not environ and not lock
    if start:
        start_gate(gate, usage)
    return gate


def start_gate(gate: BatchGate, usage: FakeUsage | None = None) -> None:
    """Run the whole startup of a gate that already holds its owner proof."""
    saved = None if usage is None else (usage.current, list(usage.script))
    if usage is not None:
        usage.current, usage.script = snapshot("10", "5"), []
    gate.begin(is_terminal=lambda: True)
    gate.finish_start(ask=lambda prompt: gate.route, say=lambda text: None)
    gate.activate()
    if usage is not None and saved is not None:
        usage.current, usage.script = saved
        usage.reads = 0


def ledger_of(tmp_path: Path) -> BatchLedger:
    return BatchLedger(tmp_path / "state" / "subscription" / "calls.jsonl")
