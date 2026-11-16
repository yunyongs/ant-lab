from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Retry:
    delay_s: float


@dataclass(frozen=True)
class Stop:
    reason: str


def decide(
    status: str, attempt_no: int, retry_after_s: int | None = None, jitter: float = 0.0
) -> Retry | Stop:
    """Next step after attempt number `attempt_no` (1-based) ended with `status`."""
    if status in ("UPSTREAM", "TIMEOUT") and attempt_no <= 2:
        return Retry(2.0**attempt_no + jitter)
    if (
        status == "LIMITED"
        and retry_after_s is not None
        and retry_after_s <= 60
        and attempt_no <= 3
    ):
        return Retry(float(retry_after_s))
    return Stop("daily-like" if status == "LIMITED" else status.lower())
