"""Account-level call limits shared by every caller of the OpenRouter key."""

from __future__ import annotations

from datetime import datetime, timedelta

from .ledger import Ledger

MINUTE_CAP = 18
HARNESS_DAILY_CAP = 40
PRODUCT_DAILY_CAP = {50: 10, 1000: 850}


class RefusedLocal(RuntimeError):
    pass


def _parse(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


class Limiter:
    def __init__(self, ledger: Ledger, tier: int = 50):
        if tier not in PRODUCT_DAILY_CAP:
            raise ValueError("tier must be 50 or 1000")
        self.ledger, self.tier = ledger, tier

    def reserve(self, caller: str, model: str, **kwargs) -> str:
        """Reserve one attempt or raise RefusedLocal.

        The check and the append share one lock and one UTC instant, read after the lock is held.
        """
        klass = caller.split(":", 1)[0]
        if klass not in ("product", "harness"):
            raise ValueError("caller must start with product: or harness:")

        def guard(rows: list[dict], now: datetime) -> None:
            attempts = [r for r in rows if r["phase"] == "attempt"]
            today = [r for r in attempts if _parse(r["ts"]).date() == now.date()]
            in_class = [r for r in today if r["caller"].split(":", 1)[0] == klass]
            recent = [r for r in attempts if _parse(r["ts"]) > now - timedelta(seconds=60)]
            cap = PRODUCT_DAILY_CAP[self.tier] if klass == "product" else HARNESS_DAILY_CAP
            if len(recent) >= MINUTE_CAP or len(in_class) >= cap or len(today) >= self.tier:
                raise RefusedLocal("REFUSED_LOCAL")

        return self.ledger.reserve(caller, model, guard=guard, **kwargs)
