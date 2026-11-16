from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal


class BudgetExhausted(RuntimeError):
    pass


def _require_amounts(tokens: int, usd: Decimal) -> None:
    """Reject negative or non-finite usage so spending can never shrink or turn into NaN."""
    if type(tokens) is not int or tokens < 0:
        raise ValueError("tokens must be a non-negative integer")
    if not usd.is_finite() or usd < 0:
        raise ValueError("usd must be finite and non-negative")


@dataclass
class RunBudget:
    max_calls: int
    max_tokens_out: int
    max_usd: Decimal
    calls: int = 0
    tokens_out: int = 0
    spent_usd: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if not isinstance(self.max_usd, Decimal):
            raise TypeError("max_usd must be Decimal")

    def check(self, max_tokens: int, worst_usd: Decimal) -> bool:
        if not isinstance(worst_usd, Decimal):
            raise TypeError("worst_usd must be Decimal")
        _require_amounts(max_tokens, worst_usd)
        return (
            self.calls < self.max_calls
            and self.tokens_out + max_tokens <= self.max_tokens_out
            and self.spent_usd + worst_usd <= self.max_usd
        )

    def consume(self, tokens: int, cost: Decimal) -> None:
        if not isinstance(cost, Decimal):
            raise TypeError("cost must be Decimal")
        _require_amounts(tokens, cost)
        self.calls += 1
        self.tokens_out += tokens
        self.spent_usd += cost
