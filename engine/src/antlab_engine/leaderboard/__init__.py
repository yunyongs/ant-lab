"""Manager-score parts of one finished season against its random-bot reference (AG-06, PLAN 6.9).

The engine ranks a season's return and risk metrics among `REFERENCE_SIZE` random bots that play
the season's own months with the season's own cash, costs and limits, and returns the percentiles
and the three percentile parts. Java stores them and applies the weights; nothing here is stored.
"""

from antlab_engine.bots.random_bot import VERSION as RANDOM_VERSION

REFERENCE_VERSION = "ref69-v1"
REFERENCE_SIZE = 200
REFERENCE_KIND = "random"
ANTLAB_REF_MASTER = 69
BOT_VERSION = f"{REFERENCE_KIND}/{RANDOM_VERSION}"
