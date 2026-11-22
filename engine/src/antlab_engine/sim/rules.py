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
