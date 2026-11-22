"""Synthetic regime labels for the conditional bootstrap tests. Every label is made up."""

from collections.abc import Sequence
from datetime import date

from synthetic_months import history, month_range

from antlab_engine.bootstrap.splitmix64 import SplitMix64
from antlab_engine.bootstrap.transitions import POOLS

LABEL_SEED = 20261123


def chain_labels(months: Sequence[date], seed: int = LABEL_SEED, move_in_ten: int = 1) -> dict:
    """Cyclic four-state chain: each step moves to the next pool with probability move_in_ten/10."""
    rng = SplitMix64(seed)
    state = rng.next_int(4)
    labels: dict[date, str] = {}
    for month in sorted(months):
        labels[month] = POOLS[state]
        if rng.next_int(10) < move_in_ten:
            state = (state + 1) % 4
    return labels


def label_fixture(name: str) -> tuple[list[date], date, dict[date, str]]:
    """(months, replay_start, labels) of a named fixture on the ext348 history.

    persist: a persistent chain over every month, including the two months outside the replay range.
    patchy: the same chain without labels before 2002 and without every seventh month after.
    sample: a chain over the sample history (reaches the NEAR and FREE tiers).
    tight24: a chain over exactly 24 months, so a 24-turn path must join blocks.
    """
    months, replay_start = history("ext348")
    full = chain_labels(months)
    if name == "persist":
        return months, replay_start, full
    if name == "patchy":
        kept = {
            m: p
            for i, (m, p) in enumerate(sorted(full.items()))
            if m >= date(2002, 1, 1) and i % 7 != 0
        }
        return months, replay_start, kept
    if name == "sample":
        sample, start = history("sample")
        return sample, start, chain_labels(sample)
    if name == "tight24":
        tight = month_range(date(2010, 1, 1), date(2011, 12, 1))
        return tight, date(2007, 1, 1), chain_labels(tight)
    raise KeyError(name)
