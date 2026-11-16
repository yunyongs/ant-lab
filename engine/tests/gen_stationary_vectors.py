"""Writes the tracked `m2-sb6-v1` vectors to stdout; run by hand and review the diff as data.

    uv run --frozen python tests/gen_stationary_vectors.py > tests/vectors/m2-sb6-v1_vectors.csv

Columns: case,history,seed,length,recent,max_overlap,months,blocks,overlap_months,attempt
history: sample | ext348 | gappy | tiny | two_januaries (see synthetic_months.history, which also
gives each history's replay start); the base date is synthetic_months.BASE_DATE.
recent: none (no months, max_overlap 1) | half (synthetic_months.half_recent, 1/2) | all (every
month of the history, 0).
months: YYYY-MM separated by spaces; blocks: start_turn:length:tier letter (A, N, F, J).
"""

from fractions import Fraction
from pathlib import Path

from synthetic_months import BASE_DATE, half_recent, history

from antlab_engine.bootstrap.stationary import generate

HEADER = "case,history,seed,length,recent,max_overlap,months,blocks,overlap_months,attempt"
LETTERS = {"ALIGNED": "A", "NEAR": "N", "FREE": "F", "JOIN": "J"}
SEEDS = [0, 14, -1, -(2**63), 2**63 - 1, 1234567]
THRESHOLDS = {"none": Fraction(1), "half": Fraction(1, 2), "all": Fraction(0)}
VECTOR_FILE = Path(__file__).resolve().parent / "vectors" / "m2-sb6-v1_vectors.csv"


def specs() -> list[tuple[str, int, int, str]]:
    """(history, seed, length, recent) in file order."""
    rows: list[tuple[str, int, int, str]] = []
    rows += [("sample", seed, 60, "none") for seed in SEEDS]
    rows += [("sample", seed, length, "none") for seed in (0, 14) for length in (12, 24)]
    rows += [("sample", seed, 60, "half") for seed in (0, 14)]
    rows += [("ext348", seed, 60, "none") for seed in SEEDS]
    rows += [("ext348", seed, 120, "none") for seed in SEEDS]
    rows += [("ext348", seed, 60, "half") for seed in (0, 14, -1, 1234567)]
    rows += [("ext348", seed, 120, "half") for seed in (0, 14)]
    rows += [("ext348", seed, 60, "all") for seed in (0, 14)]
    rows += [("gappy", seed, 24, "none") for seed in (0, 14, 1234567)]
    rows += [("gappy", seed, 12, "none") for seed in (0, 14)]
    rows += [("tiny", seed, 12, "none") for seed in (0, 14, 15, 1234567)]
    rows += [("two_januaries", seed, 12, "none") for seed in (0, 14, 19)]
    return rows


def render() -> str:
    lines = [HEADER]
    for number, (name, seed, length, recent) in enumerate(specs(), start=1):
        months, replay_start = history(name)
        recent_months = {"none": [], "half": half_recent(), "all": months}[recent]
        path = generate(
            months,
            BASE_DATE,
            length,
            seed,
            recent_months=recent_months,
            max_overlap=THRESHOLDS[recent],
            replay_start=replay_start,
        )
        threshold = str(THRESHOLDS[recent])
        text = " ".join(f"{m:%Y-%m}" for m in path.months)
        blocks = " ".join(f"{b.start_turn}:{b.length}:{LETTERS[b.tier]}" for b in path.blocks)
        lines.append(
            f"v{number:02d},{name},{seed},{length},{recent},{threshold},"
            f"{text},{blocks},{path.overlap_months},{path.attempt}"
        )
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    print(render(), end="")
