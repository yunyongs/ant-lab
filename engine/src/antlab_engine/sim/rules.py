"""Money rule constants mirrored from Java `MoneyMath`; the vector file proves they agree."""

from decimal import Decimal

MAX_MONEY = Decimal("999999999999999.9999")
INT_MAX = 2**31 - 1
INT_MIN = -(2**31)

PAD_SCALE = 40
SHARE_SCALE = 12
SCORE_WORK_SCALE = 30
SCORE_ROOT_PRECISION = 60

RATE_PRECISION = 12
RATE_SCALE = 8

NOT_STARTED_BELOW_TURN = 3
FORMATION_REASON_MAX = 200

# Report and position constants (Java `ReportRules`; the parity fixture header proves they agree).
SEASON_MIN_MONTHS = 6
SEASON_MIN_UP = 3
SEASON_MIN_DOWN = 3
ANNUAL_TURNS = 12

BAND = Decimal("0.01")
TOP_RATIO = Decimal("0.20")

BENCH_KR_MARKET = "KR"
BENCH_KR_TICKER = "069500"
BENCH_US_MARKET = "US"
BENCH_US_TICKER = "SPY"
BENCH_KR = f"{BENCH_KR_MARKET}:{BENCH_KR_TICKER}"
BENCH_US = f"{BENCH_US_MARKET}:{BENCH_US_TICKER}"
BENCH_KR_WEIGHT = Decimal("0.5")
BENCH_US_WEIGHT = Decimal("0.5")

NUDGE_SOURCES = frozenset({"RANKING", "FEED", "ALERT"})
PLATFORM_SOURCES = frozenset({"RANKING", "ALERT"})

GOALKEEPER_DOWN_MAX = Decimal("0.2")
DEFENDER_DOWN_BELOW = Decimal("0.6")
BREAKOUT_UP_ABOVE = Decimal("1.3")
BREAKOUT_MDD_FACTOR = Decimal("1.5")
DEFENSIVE_UP_MIN = Decimal("1.0")
DEFENSIVE_DOWN_BELOW = Decimal("0.8")
TARGET_UP_ABOVE = Decimal("1.1")
TARGET_DOWN_MAX = Decimal("1.2")

# Discipline (Java `DisciplineRules`) and score (`SeasonScoreCalculator`) thresholds.
DROP_PERCENT = 8
RISE_PERCENT = 8
SCORE_VERSION = "s63-v3"
