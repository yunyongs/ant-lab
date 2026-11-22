"""`bot-newsmix/1`: the disciplined bot that acts as the news bot on a share q of its turns.

Probe for the score-weight validation (M3-03): q = 0 is the disciplined bot and q = 100 the news
bot after turn 0. From turn 1 a coin decides each turn whether the news rules or the disciplined
rules produce the orders. The coin is an integer draw from a stream of its own (derived from the
bot seed, never the shared bot stream), taken every turn from turn 1 whatever the outcome, so the
disciplined start team and the news bot's rules see exactly the draws they would see alone, and
the acting turns at a larger q include those at a smaller q.
"""

from antlab_engine.sim.decisions import Unit

from .disciplined_bot import DisciplinedBot
from .news_bot import NewsBot
from .rng import BotRng
from .view import BotView

VERSION = 1
KIND_PREFIX = "newsmix-"
COIN_SIDES = 100


class NewsMixBot:
    def __init__(self, index: int, seed: int, coin_seed: int, percent: int) -> None:
        if not 0 <= percent <= 100:
            raise ValueError("newsmix percent must be 0..100")
        self.kind = f"{KIND_PREFIX}{percent}"
        self.index = index
        self.seed = seed
        self.version = VERSION
        self.percent = percent
        self.params: dict[str, object] = {"newsPercent": percent}
        self._coin = BotRng(coin_seed)
        self._disciplined = DisciplinedBot(index, seed)
        self._news = NewsBot(index, seed)

    def decide(self, view: BotView) -> tuple[Unit, ...]:
        if view.turn == 0:
            return self._disciplined.decide(view)
        acts_as_news = self._coin.below(COIN_SIDES) < self.percent
        return (self._news if acts_as_news else self._disciplined).decide(view)
