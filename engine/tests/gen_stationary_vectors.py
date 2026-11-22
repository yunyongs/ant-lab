"""Writes the tracked `m2-sb6-v1` vectors to stdout; run by hand and review the diff as data.

    uv run --frozen python tests/gen_stationary_vectors.py > tests/vectors/m2-sb6-v1_vectors.csv
    uv run --frozen python tests/gen_stationary_vectors.py conditional \
        > tests/vectors/m2-sbc6-v1_vectors.csv

The `conditional` variant writes the `m2-sbc6-v1` file: columns case,labels,seed,length,months,
blocks,overlap_months,attempt with the label fixtures of synthetic_labels (persist and patchy on
ext348, sample, tight24), no recent months; a block is
start_turn:length:tier letter:target (P1-P4 or -):matched (1 or 0).

Columns: case,history,seed,length,recent,max_overlap,months,blocks,overlap_months,attempt
history: sample | ext348 | gappy | tiny | two_januaries (see synthetic_months.history, which also
gives each history's replay start); the base date is synthetic_months.BASE_DATE.
recent: none (no months, max_overlap 1) | half (synthetic_months.half_recent, 1/2) | all (every
month of the history, 0).
months: YYYY-MM separated by spaces; blocks: start_turn:length:tier letter (A, N, F, J).
"""

import sys
from fractions import Fraction
from pathlib import Path

from synthetic_labels import label_fixture
from synthetic_months import BASE_DATE, half_recent, history

from antlab_engine.bootstrap.stationary import Block, generate

HEADER = "case,history,seed,length,recent,max_overlap,months,blocks,overlap_months,attempt"
LETTERS = {"ALIGNED": "A", "NEAR": "N", "FREE": "F", "JOIN": "J"}
SEEDS = [0, 14, -1, -(2**63), 2**63 - 1, 1234567]
THRESHOLDS = {"none": Fraction(1), "half": Fraction(1, 2), "all": Fraction(0)}
VECTOR_FILE = Path(__file__).resolve().parent / "vectors" / "m2-sb6-v1_vectors.csv"
CONDITIONAL_FILE = Path(__file__).resolve().parent / "vectors" / "m2-sbc6-v1_vectors.csv"
CONDITIONAL_HEADER = "case,labels,seed,length,months,blocks,overlap_months,attempt"
CONDITIONAL_SEEDS = [0, 14, -1, -(2**63), 2**63 - 1, 1234567]
CONDITIONAL_LENGTHS = (12, 24, 60, 120)
CONDITIONAL_FIXTURES = (
    ("persist", CONDITIONAL_LENGTHS, CONDITIONAL_SEEDS),
    ("patchy", CONDITIONAL_LENGTHS, CONDITIONAL_SEEDS),
    ("sample", (12, 24, 60), CONDITIONAL_SEEDS),
    ("tight24", (24,), [*range(6), 54, 323, 391]),  # 54, 323, 391 force a JOIN block
)


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


def conditional_block(block: Block) -> str:
    tier = LETTERS[block.tier]
    return f"{block.start_turn}:{block.length}:{tier}:{block.target or '-'}:{int(block.matched)}"


def render_conditional() -> str:
    lines = [CONDITIONAL_HEADER]
    number = 0
    for fixture, lengths, seeds in CONDITIONAL_FIXTURES:
        months, replay_start, labels = label_fixture(fixture)
        for length in lengths:
            for seed in seeds:
                number += 1
                path = generate(
                    months, BASE_DATE, length, seed, replay_start=replay_start, labels=labels
                )
                text = " ".join(f"{m:%Y-%m}" for m in path.months)
                blocks = " ".join(conditional_block(b) for b in path.blocks)
                lines.append(
                    f"c{number:02d},{fixture},{seed},{length},{text},{blocks},"
                    f"{path.overlap_months},{path.attempt}"
                )
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    print(render_conditional() if sys.argv[1:] == ["conditional"] else render(), end="")
