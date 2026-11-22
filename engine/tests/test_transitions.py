import random
import re
from datetime import date

import pytest
from synthetic_labels import label_fixture
from synthetic_months import BASE_DATE, month_range

from antlab_engine.bootstrap.transitions import (
    POOLS,
    TransitionCounts,
    count_pairs,
    require_enough_labels,
    transition_counts,
)
from antlab_engine.engine_errors import InvalidArgument, RegimeLabelsMissing
from antlab_engine.regime import Pool

START = date(2007, 1, 1)


def hand_history() -> tuple[list[date], dict[date, str]]:
    labelled = {
        date(2006, 12, 1): "P1",  # before replay_start: never counted
        date(2007, 1, 1): "P1",
        date(2007, 2, 1): "P1",
        date(2007, 3, 1): "P2",
        date(2007, 4, 1): "P3",
        date(2007, 6, 1): "P3",  # 2007-05 is listed but unlabelled
        date(2007, 8, 1): "P4",  # 2007-07 is not listed at all
        date(2007, 9, 1): "P4",
        date(2007, 10, 1): "P1",
        date(2025, 12, 1): "P4",
        date(2026, 1, 1): "P1",  # base year: never counted
    }
    months = sorted({*labelled, date(2007, 5, 1), date(2007, 11, 1), date(2007, 12, 1)})
    assert len(months) == 14
    return months, labelled


def test_ta1_hand_history_gives_exact_counts():
    months, labels = hand_history()
    result = transition_counts(months, labels, BASE_DATE, START)
    assert result.counts == (
        (1, 1, 0, 0),
        (0, 0, 1, 0),
        (0, 0, 0, 0),
        (1, 0, 0, 1),
    )
    assert result.pairs == 5
    assert result.labelled_months == 9
    assert [result.row_total(i) for i in range(4)] == [2, 1, 0, 2]
    assert result.probability(0, 1) is not None
    assert result.probability(2, 0) is None


def test_ta2_input_order_does_not_change_the_counts():
    months, labels = hand_history()
    expected = transition_counts(months, labels, BASE_DATE, START)
    rng = random.Random(7)  # noqa: S311 - shuffling test input only
    for _ in range(20):
        shuffled = months[:]
        rng.shuffle(shuffled)
        items = list(labels.items())
        rng.shuffle(items)
        assert transition_counts(shuffled, dict(items), BASE_DATE, START) == expected


def test_ta8_labels_outside_the_replay_range_do_not_matter():
    months, labels = hand_history()
    expected = transition_counts(months, labels, BASE_DATE, START)
    changed = {**labels, date(2006, 12, 1): "P4", date(2026, 1, 1): "P3"}
    assert transition_counts(months, changed, BASE_DATE, START) == expected
    removed = {m: p for m, p in labels.items() if m.year not in (2006, 2026)}
    assert transition_counts(months, removed, BASE_DATE, START) == expected
    positive = {**labels, date(2007, 2, 1): "P3"}
    assert transition_counts(months, positive, BASE_DATE, START) != expected


def test_pairs_need_consecutive_months_in_the_range():
    months = month_range(date(2010, 1, 1), date(2010, 4, 1))
    labels = {m: "P2" for m in months}
    assert transition_counts(months, labels, BASE_DATE, START).counts[1][1] == 3
    gap = [m for m in months if m != date(2010, 3, 1)]
    assert (
        transition_counts(
            gap, {m: p for m, p in labels.items() if m in gap}, BASE_DATE, START
        ).counts[1][1]
        == 1
    )
    skip = {m: "P2" for m in months if m.month in (1, 3)}
    assert transition_counts(months, skip, BASE_DATE, START).pairs == 0


def test_pool_members_are_accepted_as_labels():
    months = month_range(date(2010, 1, 1), date(2010, 3, 1))
    labels = {m: Pool.P3 for m in months}
    assert transition_counts(months, labels, BASE_DATE, START).counts[2][2] == 2
    assert tuple(p.value for p in Pool) == POOLS


def test_ta9_minimum_labels_boundary():
    months, replay_start, full = label_fixture("persist")
    window = [m for m in months if replay_start <= m < date(2026, 1, 1)]
    for count, ok in ((23, False), (24, True)):
        counts = transition_counts(
            months, {m: full[m] for m in window[:count]}, BASE_DATE, replay_start
        )
        assert counts.labelled_months == count
        if ok:
            require_enough_labels(counts)
        else:
            with pytest.raises(RegimeLabelsMissing) as caught:
                require_enough_labels(counts)
            assert not re.search(r"\d{4}-\d{2}|P[1-4]", str(caught.value))
            assert caught.value.code == "REGIME_LABELS_MISSING"
    spaced = {m: full[m] for m in window[:48:2]}
    counts = transition_counts(months, spaced, BASE_DATE, replay_start)
    assert counts.labelled_months == 24
    assert counts.pairs == 0
    with pytest.raises(RegimeLabelsMissing):
        require_enough_labels(counts)


def test_ta11_invalid_labels_are_refused_without_month_text():
    months = month_range(date(2010, 1, 1), date(2010, 3, 1))
    cases: list[tuple[type[Exception], dict]] = [
        (InvalidArgument, {date(2010, 1, 2): "P1"}),
        (InvalidArgument, {date(2011, 1, 1): "P1"}),
        (InvalidArgument, {date(2010, 1, 1): "P5"}),
        (InvalidArgument, {date(2010, 1, 1): "p1"}),
        (TypeError, {date(2010, 1, 1): 1}),
        (TypeError, {"2010-01": "P1"}),
    ]
    for error, labels in cases:
        with pytest.raises(error) as caught:
            transition_counts(months, labels, BASE_DATE, START)
        assert not re.search(r"\d{4}-\d{2}|P[1-5]", str(caught.value))
    with pytest.raises(TypeError):
        transition_counts(months, [("2010-01", "P1")], BASE_DATE, START)  # type: ignore[arg-type]


def test_counts_repr_hides_the_matrix():
    result = count_pairs([1, 2], {1: 0, 2: 1})
    assert isinstance(result, TransitionCounts)
    assert "counts" not in repr(result)
