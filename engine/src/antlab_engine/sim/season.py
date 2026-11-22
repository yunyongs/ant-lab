"""One season's state and rules: orders, ledger, holdings, lineup spans, turn prices, snapshots.

Java is the oracle. `OrderService.check` (source, pool, quantity, sell or buy checks, in that
order), `TurnPriceCalculator` and `TurnSnapshotCalculator` are mirrored step by step; money
arithmetic goes through `money` only. A refusal is a `Verdict` value that changes nothing; shape
errors raise before the season is touched, and a broken identity raises `SimInvariantError`.

Verdict codes are the `OrderRejection` names (the `check` / agent path). The REST route maps
several of them to INVALID_ARGUMENT (SOURCE_NOT_ALLOWED, QUANTITY_INVALID, SINGLE_ASSET_LIMIT);
parity compares through `OrderRejection`, not the HTTP code. Feed visibility beyond the turn and
sequence ranges needs the feed and is not checked.
"""

import decimal
from decimal import ROUND_HALF_UP, Context, Decimal

from . import money
from .model import (
    KRW,
    Execution,
    Formation,
    FormationChange,
    LedgerRow,
    LedgerType,
    LineupSpan,
    Order,
    Position,
    SeasonStatus,
    Side,
    SimInputError,
    SimInputs,
    SimInvariantError,
    SimStateError,
    SourceKind,
    TurnSnapshot,
    Verdict,
)

ACCEPTED = "ACCEPTED"
SHORT_MAX = 32767

_EXACT = Context(
    prec=decimal.MAX_PREC, Emax=decimal.MAX_EMAX, Emin=decimal.MIN_EMIN, traps=[decimal.Inexact]
)
_ROUND = Context(prec=200, rounding=ROUND_HALF_UP)
_ZERO_AVG = Decimal("0.0000")


def _step_factor(local_return: Decimal, fx_change: Decimal) -> Decimal:
    for name, value in (("return", local_return), ("fx change", fx_change)):
        if not value.is_finite() or value.as_tuple().exponent < -money.RATE_SCALE:  # type: ignore[operator]
            raise SimInputError(f"{name} is not a finite value with at most 8 decimals")
    if local_return < -1 or fx_change <= -1:
        raise SimInputError("return or fx change out of range")
    return _EXACT.multiply(_EXACT.add(Decimal(1), local_return), _EXACT.add(Decimal(1), fx_change))


class Season:
    """A season in progress; `place_order` and `advance` are the only mutators."""

    def __init__(self, inputs: SimInputs, *, strict: bool = False) -> None:
        self.inputs = inputs
        self.settings = inputs.settings
        self.strict = strict
        self.status = SeasonStatus.IN_PROGRESS
        self.current_turn = 0
        self.formation: Formation = self.settings.start_formation
        self.formation_changes: list[FormationChange] = []
        self.abandoned_turn: int | None = None

        self._pool = {a.asset: a for a in inputs.pool}
        s = self.settings
        self._rates = {
            m: money.fee_rate(m, s.fee_rate_kr, s.fee_rate_us, s.fx_spread_rate)
            for m in {a.market for a in inputs.pool}
        }
        self._initial = {
            a.asset: money.initial_price(a.base_price, a.base_fx_rate) for a in inputs.pool
        }
        if any(price <= 0 for price in self._initial.values()):
            raise SimInputError("a turn 0 price is not positive")
        self._factor = {a.asset: Decimal(1) for a in inputs.pool}
        self.turn_prices: list[dict[str, Decimal]] = [dict(self._initial)]

        cash = money.exact_won(s.initial_cash)
        self.cash = cash
        self.ledger: list[LedgerRow] = [LedgerRow(LedgerType.DEPOSIT, cash, cash, 0, None)]
        self.executions: list[Execution] = []
        self.holdings: dict[str, tuple[int, Decimal]] = {}
        self.spans: list[LineupSpan] = []
        self._open: dict[str, LineupSpan] = {}
        self._net_fills: dict[str, int] = {}
        self.turns: list[TurnSnapshot] = []
        self._peak = cash

    @property
    def path_length(self) -> int:
        return self.settings.path_length

    def place_order(self, order: Order) -> Verdict:
        if self.status is not SeasonStatus.IN_PROGRESS:
            raise SimStateError("the season is not accepting orders")
        # Java OrderService.check: source, pool, quantity, then the sell or buy branch.
        if order.source.kind is SourceKind.BENCH:
            return self._refuse("SOURCE_NOT_ALLOWED")
        ref = order.source
        if ref.kind in (SourceKind.FEED, SourceKind.ALERT) and (
            ref.feed_turn > self.current_turn or ref.feed_seq > SHORT_MAX
        ):
            # Only the range part of Java's visibility rule: item existence needs the feed.
            return self._refuse("SOURCE_NOT_VISIBLE")
        asset = self._pool.get(order.asset)
        if asset is None:
            return self._refuse("ASSET_NOT_IN_POOL")
        price = self.turn_prices[self.current_turn][order.asset]
        rate = self._rates[asset.market]
        if order.quantity < 1:
            return self._refuse("QUANTITY_INVALID")
        if order.side is Side.SELL:
            verdict = self._sell(order, price, rate)
        else:
            verdict = self._buy(order, price, rate)
        if verdict.accepted:
            self._check_asset(order.asset)
            if self.strict:
                self.check_invariants()
        return verdict

    def advance(self) -> TurnSnapshot:
        """Move to the next turn: prices, snapshot, then the turn counter (FINISHED on the last)."""
        if self.status is not SeasonStatus.IN_PROGRESS:
            raise SimStateError("the season is not in progress")
        nxt = self.current_turn + 1
        step = self.inputs.path[nxt - 1]
        prices: dict[str, Decimal] = {}
        for a in self.inputs.pool:
            fx = Decimal(0) if a.currency == KRW else step.fx_change[a.currency]
            self._factor[a.asset] = _EXACT.multiply(
                self._factor[a.asset], _step_factor(step.local_returns[a.asset], fx)
            )
            product = _EXACT.multiply(self._initial[a.asset], self._factor[a.asset])
            price = product.quantize(Decimal(1), context=_ROUND)
            if price <= 0:
                raise SimInputError("a computed turn price is not positive")
            prices[a.asset] = price
        self.turn_prices.append(prices)
        snapshot = self._snapshot(nxt, prices)
        self._verify_snapshot(snapshot, prices)
        self.turns.append(snapshot)
        self.current_turn = nxt
        if nxt == self.path_length:
            self.status = SeasonStatus.FINISHED
        self.check_invariants()
        return snapshot

    def check_invariants(self) -> None:
        """PLAN §5.2 identities, recomputed from the ledger and fills; a break raises."""
        balance = Decimal(0)
        for row in self.ledger:
            balance += row.amount
            if row.balance_after != balance:
                raise SimInvariantError("ledger chain broken")
        if balance != self.cash:
            raise SimInvariantError("cash is not the ledger sum")
        net: dict[str, int] = {}
        for e in self.executions:
            net[e.asset] = net.get(e.asset, 0) + (e.quantity if e.side is Side.BUY else -e.quantity)
        held = {a: q for a, (q, _) in self.holdings.items() if q}
        if {a: q for a, q in net.items() if q} != held:
            raise SimInvariantError("holdings are not the sum of fills")
        for asset in self.holdings:
            self._check_asset(asset)
        open_count: dict[str, int] = {}
        for span in self.spans:
            if span.end_turn is None:
                open_count[span.asset] = open_count.get(span.asset, 0) + 1
            elif span.end_turn < span.start_turn:
                raise SimInvariantError("a span ends before it starts")
        if set(open_count) != set(held) or any(n != 1 for n in open_count.values()):
            raise SimInvariantError("open spans do not match holdings")
        if {a: s for a, s in self._open.items()} != {
            s.asset: s for s in self.spans if s.end_turn is None
        }:
            raise SimInvariantError("open span index is stale")
        if self.turns:
            last = self.turns[-1]
            if last.total != last.cash + last.holdings_value:
                raise SimInvariantError("snapshot total is not cash plus holdings")
            if last.goalkeeper != last.total - last.defender - last.midfielder - last.forward:
                raise SimInvariantError("goalkeeper is not the residual")

    def _verify_snapshot(self, snap: TurnSnapshot, prices: dict[str, Decimal]) -> None:
        """Recompute the snapshot from fills and spans by a different route than `_snapshot`."""
        fills: dict[str, int] = {}
        for e in self.executions:
            fills[e.asset] = fills.get(e.asset, 0) + (
                e.quantity if e.side is Side.BUY else -e.quantity
            )
        value = sum((prices[a] * q for a, q in fills.items()), Decimal(0))
        parts = {"D": Decimal(0), "M": Decimal(0), "F": Decimal(0)}
        for span in self.spans:
            if span.end_turn is None and span.start_turn < snap.turn:
                key = {Position.DEFENDER: "D", Position.MIDFIELDER: "M"}.get(span.position, "F")
                if span.position is not Position.GOALKEEPER:
                    parts[key] += prices[span.asset] * fills[span.asset]
        if (
            snap.cash != self.cash
            or snap.holdings_value != value
            or snap.total != self.cash + value
        ):
            raise SimInvariantError("snapshot cash, holdings value or total is wrong")
        if (snap.defender, snap.midfielder, snap.forward) != (parts["D"], parts["M"], parts["F"]):
            raise SimInvariantError("snapshot position values are wrong")
        if snap.goalkeeper != snap.total - parts["D"] - parts["M"] - parts["F"]:
            raise SimInvariantError("goalkeeper is not the residual")

    def _refuse(self, code: str) -> Verdict:
        return Verdict(False, code)

    def _buy(self, order: Order, price: Decimal, rate: Decimal) -> Verdict:
        quantity = order.quantity
        # The cash screen comes before the fee so a huge quantity cannot overflow the fee.
        if _EXACT.multiply(price, Decimal(quantity)) > self.cash:
            return self._refuse("INSUFFICIENT_CASH")
        notional = money.valuation(price, quantity)
        fee = money.fee(price, quantity, rate)
        if not money.affordable(self.cash, notional, fee):
            return self._refuse("INSUFFICIENT_CASH")
        held, avg = self.holdings.get(order.asset, (0, _ZERO_AVG))
        open_span = self._open.get(order.asset)
        if (held > 0) != (open_span is not None):
            raise SimInvariantError("holding and lineup span disagree")
        if open_span is None and order.position is None:
            return self._refuse("POSITION_REQUIRED")
        if open_span and order.position is not None and order.position != open_span.position:
            return self._refuse("POSITION_CONFLICT")
        if self._exceeds_limit(order.asset, held, price, quantity, notional, fee):
            return self._refuse("SINGLE_ASSET_LIMIT")

        new_avg = money.avg_price(held, avg, quantity, price)
        execution = self._record(order, price, notional, fee, None)
        self.holdings[order.asset] = (held + quantity, new_avg)
        self._post(LedgerType.BUY, -notional, fee, execution.index)
        if open_span is None:
            span = LineupSpan(order.asset, order.position, self.current_turn, None)
            self.spans.append(span)
            self._open[order.asset] = span
        return Verdict(True, ACCEPTED, execution)

    def _sell(self, order: Order, price: Decimal, rate: Decimal) -> Verdict:
        quantity = order.quantity
        held, avg = self.holdings.get(order.asset, (0, _ZERO_AVG))
        if held < quantity:
            return self._refuse("INSUFFICIENT_HOLDING")
        open_span = self._open.get(order.asset)
        if open_span is None:
            raise SimInvariantError("holding and lineup span disagree")
        notional = money.valuation(price, quantity)
        fee = money.fee(price, quantity, rate)
        pnl = money.realized_pnl(price, avg, quantity)
        execution = self._record(order, price, notional, fee, pnl)
        remaining = held - quantity
        self.holdings[order.asset] = (remaining, avg if remaining else _ZERO_AVG)
        self._post(LedgerType.SELL, notional, fee, execution.index)
        if remaining == 0:
            open_span.end_turn = self.current_turn
            del self._open[order.asset]
        return Verdict(True, ACCEPTED, execution)

    def _record(
        self, order: Order, price: Decimal, notional: Decimal, fee: Decimal, pnl: Decimal | None
    ) -> Execution:
        execution = Execution(
            len(self.executions),
            self.current_turn,
            order.asset,
            order.side,
            order.quantity,
            price,
            notional,
            fee,
            pnl,
            order.source,
            self._change_id(),
        )
        self.executions.append(execution)
        delta = order.quantity if order.side is Side.BUY else -order.quantity
        self._net_fills[order.asset] = self._net_fills.get(order.asset, 0) + delta
        return execution

    def _change_id(self) -> int | None:
        """Index of the last formation change made this turn (Java `OrderService.changeId`)."""
        for index in range(len(self.formation_changes) - 1, -1, -1):
            if self.formation_changes[index].turn == self.current_turn:
                return index
        return None

    def _post(self, kind: LedgerType, signed: Decimal, fee: Decimal, execution: int) -> None:
        self._append(kind, signed, execution)
        if fee > 0:
            self._append(LedgerType.FEE, -fee, execution)

    def _append(self, kind: LedgerType, amount: Decimal, execution: int) -> None:
        self.cash = self.cash + amount
        if self.cash < 0:
            raise SimInvariantError("cash went negative")
        self.ledger.append(LedgerRow(kind, amount, self.cash, self.current_turn, execution))

    def _exceeds_limit(
        self,
        asset: str,
        held: int,
        price: Decimal,
        quantity: int,
        notional: Decimal,
        fee: Decimal,
    ) -> bool:
        limit = self.settings.single_asset_limit
        if limit is None:
            return False
        prices = self.turn_prices[self.current_turn]
        asset_value = _EXACT.multiply(price, Decimal(held + quantity))
        total = _EXACT.add(_EXACT.subtract(_EXACT.subtract(self.cash, notional), fee), asset_value)
        for key, (qty, _) in self.holdings.items():
            if key != asset:
                total = _EXACT.add(total, _EXACT.multiply(prices[key], Decimal(qty)))
        return asset_value > _EXACT.multiply(total, limit)

    def _check_asset(self, asset: str) -> None:
        held = self.holdings.get(asset, (0, _ZERO_AVG))[0]
        if held != self._net_fills.get(asset, 0) or (held > 0) != (asset in self._open):
            raise SimInvariantError("holding does not match fills or span")
        if self.ledger[-1].balance_after != self.cash:
            raise SimInvariantError("cash is not the last ledger balance")

    def _snapshot(self, turn: int, prices: dict[str, Decimal]) -> TurnSnapshot:
        declared = {a: s.position for a, s in self._open.items() if s.start_turn < turn}
        holdings_value = Decimal(0)
        by_position = {"D": Decimal(0), "M": Decimal(0), "F": Decimal(0)}
        for asset, (quantity, _) in self.holdings.items():
            value = money.valuation(prices[asset], quantity)
            holdings_value += value
            position = declared.get(asset)
            if position is Position.DEFENDER:
                by_position["D"] += value
            elif position is Position.MIDFIELDER:
                by_position["M"] += value
            elif position is not None and position.is_forward:
                by_position["F"] += value
        total = money.total_value(self.cash, [holdings_value])
        goalkeeper = total - by_position["D"] - by_position["M"] - by_position["F"]
        self._peak = max(self._peak, total)
        return TurnSnapshot(
            turn,
            self.cash,
            holdings_value,
            total,
            goalkeeper,
            by_position["D"],
            by_position["M"],
            by_position["F"],
            money.drawdown(total, self._peak),
        )
