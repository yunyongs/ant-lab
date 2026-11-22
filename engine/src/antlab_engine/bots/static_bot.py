"""`bot-static/1`: holds the start team of the random bot with the same index, never trades."""

from antlab_engine.sim.decisions import Unit

from .random_bot import build_team
from .rng import BotRng
from .view import BotView

VERSION = 1


class StaticBot:
    kind = "static"

    def __init__(self, index: int, seed: int) -> None:
        """`seed` is the random bot's seed of the same index, so both build the same team."""
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.params: dict[str, object] = {}
        self._rng = BotRng(seed)

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        return build_team(view, self._rng) if view.turn == 0 else ()
