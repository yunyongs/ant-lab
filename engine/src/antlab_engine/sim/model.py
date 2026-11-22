"""Season inputs, units and result records of the offline simulator.

Everything here is a frozen value. A shape error (a value Java would refuse before any state
changes) raises `SimShapeError` at construction, so a malformed unit never reaches the season.
"""

from dataclasses import dataclass, field
from decimal import Decimal
from enum import StrEnum

from .rules import INT_MAX, INT_MIN

PATH_LENGTHS = (12, 24, 60)
KRW = "KRW"
DEFAULT_INITIAL_CASH = Decimal(100_000_000)


class SimError(Exception):
    """Base of the simulator's own errors."""


class SimShapeError(SimError):
    """A unit or input has a shape Java refuses before running anything."""


class SimInputError(SimError):
    """The season inputs cannot produce a season (Java refuses to start or advance on them)."""


class SimStateError(SimError):
    """A call that the season state does not allow (an order after the season ended)."""


class SimInvariantError(SimError):
    """A ledger, holding or valuation identity broke; the run must not continue."""


class Position(StrEnum):
    GOALKEEPER = "GOALKEEPER"
    DEFENDER = "DEFENDER"
    MIDFIELDER = "MIDFIELDER"
    FORWARD_TARGET = "FORWARD_TARGET"
    FORWARD_BREAKOUT = "FORWARD_BREAKOUT"
    FORWARD_DEFENSIVE = "FORWARD_DEFENSIVE"

    @property
    def is_forward(self) -> bool:
        return self.value.startswith("FORWARD_")


class Side(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class SourceKind(StrEnum):
    DIRECT = "DIRECT"
    RANKING = "RANKING"
    FEED = "FEED"
    ALERT = "ALERT"
    BENCH = "BENCH"


class SeasonStatus(StrEnum):
    IN_PROGRESS = "IN_PROGRESS"
    ABANDONING = "ABANDONING"
    FINISHED = "FINISHED"


class LedgerType(StrEnum):
    DEPOSIT = "DEPOSIT"
    BUY = "BUY"
    SELL = "SELL"
    FEE = "FEE"


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


@dataclass(frozen=True)
class Formation:
    goalkeeper: int
    defender: int
    midfielder: int
    forward: int

    def __post_init__(self) -> None:
        shares = (self.goalkeeper, self.defender, self.midfielder, self.forward)
        if not all(_is_int(s) and 0 <= s <= 100 for s in shares):
            raise SimShapeError("formation shares must be whole numbers in 0-100")
        if self.goalkeeper < 1:
            raise SimShapeError("goalkeeper share must be at least 1")
        if sum(shares) != 100:
            raise SimShapeError("formation shares must add up to 100")


@dataclass(frozen=True)
class OrderSource:
    kind: SourceKind = SourceKind.DIRECT
    feed_turn: int | None = None
    feed_seq: int | None = None

    def __post_init__(self) -> None:
        carries_ref = self.kind in (SourceKind.FEED, SourceKind.ALERT)
        if carries_ref:
            if not (_is_int(self.feed_turn) and _is_int(self.feed_seq)):
                raise SimShapeError("FEED and ALERT need feed_turn and feed_seq")
            if self.feed_turn < 1 or self.feed_seq < 1:
                raise SimShapeError("feed_turn and feed_seq start at 1")
        elif self.feed_turn is not None or self.feed_seq is not None:
            raise SimShapeError("only FEED and ALERT carry a feed reference")


@dataclass(frozen=True)
class Order:
    asset: str
    side: Side
    quantity: int
    position: Position | None = None
    source: OrderSource = field(default_factory=OrderSource)

    def __post_init__(self) -> None:
        if not _is_int(self.quantity) or not INT_MIN <= self.quantity <= INT_MAX:
            raise SimShapeError("quantity must be a 32-bit integer")
        if self.side is Side.SELL and self.position is not None:
            raise SimShapeError("a sell carries no declared position")


@dataclass(frozen=True)
class Settings:
    initial_cash: Decimal = DEFAULT_INITIAL_CASH
    fee_rate_kr: Decimal = Decimal("0.00015000")
    fee_rate_us: Decimal = Decimal("0.00250000")
    fx_spread_rate: Decimal = Decimal("0.00400000")
    risk_limit: Decimal = Decimal("0.20000000")
    start_formation: Formation = Formation(10, 30, 30, 30)
    path_length: int = 12
    member_kind: str = "HUMAN"
    single_asset_limit: Decimal | None = None

    def __post_init__(self) -> None:
        if self.path_length not in PATH_LENGTHS:
            raise SimShapeError(f"path length must be one of {PATH_LENGTHS}")
        if self.member_kind not in ("HUMAN", "AGENT"):
            raise SimShapeError("member kind must be HUMAN or AGENT")
        limit = self.single_asset_limit
        if limit is not None and not Decimal(0) < limit <= Decimal(1):
            raise SimShapeError("single asset limit must be in (0, 1]")


@dataclass(frozen=True)
class PoolAsset:
    asset: str
    currency: str
    base_price: Decimal
    base_fx_rate: Decimal

    @property
    def market(self) -> str:
        return self.asset.partition(":")[0]


@dataclass(frozen=True)
class PathStep:
    """One path turn: a local return per pool asset and an FX change per non-KRW currency."""

    turn: int
    local_returns: dict[str, Decimal | None]
    fx_change: dict[str, Decimal | None] = field(default_factory=dict)


@dataclass(frozen=True)
class SimInputs:
    settings: Settings
    pool: tuple[PoolAsset, ...]
    path: tuple[PathStep, ...]

    def __post_init__(self) -> None:
        if not self.pool:
            raise SimInputError("the pool is empty")
        keys = [a.asset for a in self.pool]
        if len(set(keys)) != len(keys):
            raise SimInputError("duplicate pool asset")
        for a in self.pool:
            if a.market not in ("KR", "US"):
                raise SimInputError(f"unknown market for {a.asset}")
        if [s.turn for s in self.path] != list(range(1, self.settings.path_length + 1)):
            raise SimInputError("path must hold turns 1..path_length in order")
        for step in self.path:
            for a in self.pool:
                if step.local_returns.get(a.asset) is None:
                    raise SimInputError(f"turn {step.turn}: no return for {a.asset}")
                if a.currency != KRW and step.fx_change.get(a.currency) is None:
                    raise SimInputError(f"turn {step.turn}: no fx change for {a.currency}")


@dataclass(frozen=True)
class Verdict:
    accepted: bool
    code: str
    execution: "Execution | None" = None


@dataclass(frozen=True)
class Execution:
    index: int
    turn: int
    asset: str
    side: Side
    quantity: int
    price: Decimal
    notional: Decimal
    fee: Decimal
    realized_pnl: Decimal | None
    source: OrderSource
    formation_change: int | None


@dataclass(frozen=True)
class LedgerRow:
    type: LedgerType
    amount: Decimal
    balance_after: Decimal
    turn: int
    execution: int | None


@dataclass
class LineupSpan:
    """Mutable only to close the span (Java `Lineup.close`)."""

    asset: str
    position: Position
    start_turn: int
    end_turn: int | None


@dataclass(frozen=True)
class FormationChange:
    turn: int
    before: Formation
    after: Formation
    reason: str | None = None


@dataclass(frozen=True)
class TurnSnapshot:
    turn: int
    cash: Decimal
    holdings_value: Decimal
    total: Decimal
    goalkeeper: Decimal
    defender: Decimal
    midfielder: Decimal
    forward: Decimal
    drawdown: Decimal
