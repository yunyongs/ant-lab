"""Group means, renormalisation, F, S and the quality flag (TB12, TB13, TB15)."""

import pytest

from antlab_engine.regime import TERMS, TermSignal, TermStatus, combine_axes
from antlab_engine.regime.model import Group
from antlab_engine.regime.scoring import blend, group_scores, indicator_values, quality_ok

OK, STALE = TermStatus.OK, TermStatus.STALE


def signals(z=None, status=None):
    z, status = z or {}, status or {}
    return tuple(
        TermSignal(t.term_id, z.get(t.term_id, 0.0), status.get(t.term_id, OK)) for t in TERMS
    )


def test_an_indicator_is_the_mean_of_its_ok_terms():
    values = indicator_values(signals({"1a": 1.0, "1b": 3.0}))
    assert values["1"] == 2.0
    values = indicator_values(signals({"1a": 1.0, "1b": 3.0}, {"1b": STALE}))
    assert values["1"] == 1.0
    assert indicator_values(signals(status={"1a": STALE, "1b": STALE}))["1"] is None


def test_indicator_14_is_the_mean_of_its_available_parts():
    values = indicator_values(signals({"14US": 1.0, "14KR": 2.0}))
    assert values["14"] == 1.5
    assert indicator_values(signals({"14US": 1.0}, {"14KR": STALE}))["14"] == 1.0


def test_a_group_is_the_equal_weight_mean_of_the_available_indicators():
    scores = group_scores(signals({"1a": 1.0, "1b": 1.0, "2": 0.0, "5": 0.5, "6a": 1.0}))
    assert scores[Group.R_US] == 0.5
    assert scores[Group.G_US] == pytest.approx((0.5 + 0.5 + 0.0 + 0.0) / 4, abs=1e-15)
    stale = signals({"1a": 1.0, "1b": 1.0, "2": 0.0}, {"2": STALE})
    assert group_scores(stale)[Group.R_US] == 1.0  # indicator 2 excluded, 1 takes the weight


def test_a_whole_group_stale_gives_its_weight_to_the_other_region():
    kr = {"3a": STALE, "3b": STALE, "4a": STALE, "4b": STALE}
    scores = group_scores(signals({"1a": 0.6, "1b": 0.6, "2": 0.6}, kr))
    assert scores[Group.R_KR] is None
    r, _ = combine_axes(scores[Group.R_US], scores[Group.R_KR], 0.0, 0.0)
    assert r == pytest.approx(0.6, abs=1e-15)


def test_axis_weights_and_their_renormalisation():
    assert blend(1.0, 3.0, (0.5, 0.5)) == 2.0
    assert blend(1.0, 3.0, (0.4, 0.6)) == pytest.approx(2.2, abs=1e-15)
    assert blend(None, 3.0, (0.4, 0.6)) == 3.0
    assert blend(1.0, None, (0.4, 0.6)) == 1.0
    assert blend(None, None, (0.4, 0.6)) is None
    assert combine_axes(None, None, 1.0, None) == (None, 1.0)


def test_f_uses_only_the_three_named_terms():
    base = signals({"10a": 1.0, "11": 2.0, "12b": 3.0})
    assert group_scores(base)[Group.F] == 2.0
    moved = signals({"10a": 1.0, "11": 2.0, "12b": 3.0, "10b": 3.0, "12a": -3.0})
    assert group_scores(moved)[Group.F] == 2.0
    assert group_scores(signals({"10a": 1.0, "11": 2.0, "12b": -3.0}))[Group.F] == 0.0
    dropped = signals({"10a": 1.0, "11": 2.0, "12b": 3.0}, {"11": STALE})
    assert group_scores(dropped)[Group.F] == 2.0
    all_stale = signals(status={"10a": STALE, "11": STALE, "12b": STALE})
    assert group_scores(all_stale)[Group.F] is None


def test_s_is_indicator_13_alone():
    assert group_scores(signals({"13a": 1.0, "13b": 2.0}))[Group.S] == 1.5
    assert group_scores(signals({"13a": 1.0, "13b": 2.0}, {"13a": STALE}))[Group.S] == 2.0


def test_the_quality_flag_counts_stale_rate_and_growth_indicators():
    assert quality_ok({1, 2, 3, 4})
    assert not quality_ok({1, 2, 3, 4, 5})
    assert quality_ok({1, 2, 3, 4, 10, 11, 12, 13})  # the FX and sentiment indicators do not count
    assert quality_ok({1, 2, 3, 14}) and quality_ok({14})
    assert not quality_ok({5, 6, 7, 8, 9})
    assert quality_ok(set())
