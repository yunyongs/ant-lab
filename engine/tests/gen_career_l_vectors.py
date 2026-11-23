"""Writes the tracked career-score (L) vectors shared with the Java leaderboard test.

    uv run --frozen python tests/gen_career_l_vectors.py > tests/fixtures/career_l_vectors.json

L over the m newest counted season scores S (3 <= m <= 10): drop k = floor(m / 10) lowest and k
highest, mean of the rest, minus 0.5 x the sample standard deviation (n - 1) of all m; divisions
and the square root at 60 significant digits HALF_UP (Java `MoneyMath` score root), and L rounded
HALF_UP to 2 places once. For m = 10 the exact L equals `validation.composite.career_l`.
"""

import json
import sys
from decimal import Decimal

from antlab_engine.sim import money
from antlab_engine.sim.report import plain
from antlab_engine.validation.composite import L_SD_WEIGHT, sample_stddev

SCHEMA = "career-l-vectors/1"
VECTORS = [
    ("three-spread", ["50", "60", "70"]),
    ("three-equal-half-up", ["61.125", "61.125", "61.125"]),
    ("four", ["40.5", "55.25", "60", "72.125"]),
    (
        "five-long-decimals",
        [
            "58.123456789012345678901234567890",
            "41.987654321098765432109876543210",
            "63.5",
            "49.999999999999999999999999999999",
            "70.000000000000000000000000000001",
        ],
    ),
    ("six-ties", ["55", "55", "60", "60", "65", "65"]),
    ("seven", ["30", "45.5", "50", "52.25", "61", "75", "88.875"]),
    ("eight-low", ["5", "10", "12.5", "20", "22", "25", "30", "31.25"]),
    ("nine-negative-l", ["0", "0", "0", "0", "0", "0", "0", "0", "100"]),
    ("ten-trims-one-each-side", ["10", "55", "56", "57", "58", "59", "60", "61", "62", "95"]),
    ("ten-ties-at-the-ends", ["40", "40", "50", "52", "54", "56", "58", "60", "70", "70"]),
    ("ten-equal", ["47.5"] * 10),
]


def career(scores: list[Decimal]) -> dict[str, object]:
    m = len(scores)
    if not 3 <= m <= 10:
        raise ValueError("a career uses 3 to 10 seasons")
    k = m // 10
    kept = sorted(scores)[k : m - k]
    total = Decimal(0)
    for value in kept:
        total = money.EXACT.add(total, value)
    mean = money.divide_significant(total, Decimal(len(kept)))
    sd = sample_stddev(scores)
    exact = money.EXACT.subtract(mean, money.EXACT.multiply(L_SD_WEIGHT, sd))
    return {
        "m": m,
        "trimEachSide": k,
        "trimmedMean": plain(mean),
        "sampleSd": plain(sd),
        "lExact": plain(exact),
        "l": plain(money.round2(exact)),
    }


def document() -> dict[str, object]:
    vectors = []
    for name, values in VECTORS:
        scores = [Decimal(v) for v in values]
        vectors.append({"name": name, "seasons": values, **career(scores)})
    return {
        "schema": SCHEMA,
        "rule": " ".join(__doc__.split("\n\n")[2].split()),
        "vectors": vectors,
    }


if __name__ == "__main__":
    sys.stdout.write(json.dumps(document(), indent=2, ensure_ascii=False) + "\n")
