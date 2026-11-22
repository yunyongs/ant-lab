"""Per-order flags of a finished season for the process-item overlap (M3-06).

An order of turns 1..T-1 is flagged by the same rules the score items use: `noise` when it
follows the turn's signals, `discipline` when it is a panic sell or a chase buy, `nudge` when it
cites a feed row or a board. The amounts are exact whole won; their sums equal the item inputs of
`sim.behaviour` and `sim.report` (the parity tests). `eventDirection` counts orders in the
direction of an event turn's net stated reaction (sell when negative, buy when positive) and
`eventCaught` those of them that the discipline item already counts. Pure: no files, no database.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal

from antlab_engine.sim import behaviour, money
from antlab_engine.sim.behaviour import TurnSignals
from antlab_engine.sim.model import Side
from antlab_engine.sim.rules import NUDGE_SOURCES
from antlab_engine.sim.score import discipline_facts_of
from antlab_engine.sim.season import Season

_X = money.EXACT
_ZERO = Decimal(0)

FLAG_NAMES = (
    "traded",
    "noise",
    "discipline",
    "nudge",
    "both",
    "nudgeNoise",
    "nudgeDiscipline",
    "eventDirection",
    "eventCaught",
)


@dataclass(frozen=True)
class TradeFlags:
    """Whole-won sums over the orders of turns 1..T-1."""

    traded: int
    noise: int
    discipline: int
    nudge: int
    both: int
    nudge_noise: int
    nudge_discipline: int
    event_direction: int
    event_caught: int

    def to_wire(self) -> dict[str, str]:
        return {
            "traded": str(self.traded),
            "noise": str(self.noise),
            "discipline": str(self.discipline),
            "nudge": str(self.nudge),
            "both": str(self.both),
            "nudgeNoise": str(self.nudge_noise),
            "nudgeDiscipline": str(self.nudge_discipline),
            "eventDirection": str(self.event_direction),
            "eventCaught": str(self.event_caught),
        }


def event_reactions(feed: Mapping[str, object] | None) -> dict[int, Decimal]:
    """Net stated reaction per turn from the events of a world's feed facts (none: empty)."""
    net: dict[int, Decimal] = {}
    if feed is None:
        return net
    events = feed.get("events")
    if not isinstance(events, Sequence):
        return net
    for event in events:
        reaction = event["reaction"]
        if reaction is not None:
            turn = event["turn"]
            net[turn] = _X.add(net.get(turn, _ZERO), Decimal(reaction))
    return net


def _whole(value: Decimal) -> int:
    return int(money.exact_won(value))


def flags_of(
    season: Season,
    signals: Sequence[TurnSignals] | None,
    reactions: Mapping[int, Decimal] | None = None,
) -> TradeFlags:
    """Flags of the orders of turns 1..T-1 of a finished season; turn 0 (team building) is out."""
    report_turn = season.current_turn
    pointed = {s.turn: s for s in signals or ()}
    panic_turns = behaviour.panic_turns_of(discipline_facts_of(season))
    totals = dict.fromkeys(FLAG_NAMES, _ZERO)
    for e in season.executions:
        if not 1 <= e.turn < report_turn:
            continue
        buy = e.side is Side.BUY
        signal = pointed.get(e.turn)
        noise = signal is not None and e.asset in (signal.up if buy else signal.down)
        previous, price = (
            season.turn_prices[e.turn - 1][e.asset],
            season.turn_prices[e.turn][e.asset],
        )
        if buy:
            discipline = behaviour.rose(previous, price)
        else:
            bound = e.formation_change
            discipline = behaviour.dropped(previous, price) or (
                bound is not None and season.formation_changes[bound].turn in panic_turns
            )
        nudge = e.source.kind.value in NUDGE_SOURCES
        net = (reactions or {}).get(e.turn, _ZERO)
        direction = net > 0 if buy else net < 0
        amount = e.notional
        for name, hit in (
            ("traded", True),
            ("noise", noise),
            ("discipline", discipline),
            ("nudge", nudge),
            ("both", noise and discipline),
            ("nudgeNoise", nudge and noise),
            ("nudgeDiscipline", nudge and discipline),
            ("eventDirection", direction),
            ("eventCaught", direction and discipline),
        ):
            if hit:
                totals[name] = _X.add(totals[name], amount)
    return TradeFlags(*(_whole(totals[name]) for name in FLAG_NAMES))
