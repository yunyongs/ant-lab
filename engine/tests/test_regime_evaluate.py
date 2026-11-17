"""Driver behaviour: schedule, month sample, EMA, carrying, display, quality (TB13–TB17)."""

from datetime import date

import pytest
from regime_synthetic import fingerprint, panel

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.regime import (
    DEFAULT_PARAMS,
    REGIME_VERSION,
    Direction,
    Phase,
    Pool,
    SeriesCode,
    evaluate,
    month_end_sample,
    weekly_schedule,
)
from antlab_engine.regime.evaluate import axis_display
from antlab_engine.regime.pit import PointInTime
from antlab_engine.regime.zscore import term_signals

S = SeriesCode
OBS = panel(8, 3)
DATES = weekly_schedule(date(2024, 1, 6), date(2025, 8, 9))
RUN = evaluate(OBS, DATES)


def test_the_version_is_pinned():
    assert REGIME_VERSION == "m2-rg-v1"


def test_weekly_schedule_returns_saturdays_only():
    days = weekly_schedule(date(2025, 1, 1), date(2025, 2, 1))
    assert all(d.weekday() == 5 for d in days)
    assert days[0] == date(2025, 1, 4) and days[-1] == date(2025, 2, 1)
    assert weekly_schedule(date(2025, 1, 4), date(2025, 1, 4)) == (date(2025, 1, 4),)
    assert weekly_schedule(date(2025, 1, 5), date(2025, 1, 10)) == ()
    assert weekly_schedule(date(2025, 2, 1), date(2025, 1, 1)) == ()
    with pytest.raises(InvalidArgument):
        weekly_schedule("2025-01-01", date(2025, 2, 1))  # type: ignore[arg-type]


def test_evaluation_dates_must_increase_strictly():
    for bad in ([date(2025, 1, 11), date(2025, 1, 4)], [date(2025, 1, 4)] * 2):
        with pytest.raises(InvalidArgument):
            evaluate(OBS, bad)
    with pytest.raises(InvalidArgument):
        evaluate(OBS, ["2025-01-04"])  # type: ignore[list-item]
    assert evaluate(OBS, []) == ()


def test_month_end_sample_is_the_last_step_inside_the_month():
    picked = month_end_sample(RUN, 2025, 3)
    assert picked is not None and picked.as_of == date(2025, 3, 29)
    assert month_end_sample(RUN, 2023, 3) is None
    sparse = evaluate(OBS, [date(2025, 3, 1), date(2025, 3, 8), date(2025, 4, 5)])
    assert month_end_sample(sparse, 2025, 3).as_of == date(2025, 3, 8)
    assert month_end_sample(sparse, 2025, 2) is None
    for month in (0, 13):
        with pytest.raises(InvalidArgument):
            month_end_sample(RUN, 2025, month)


def test_the_snapshot_repr_has_no_date():
    text = repr(RUN[-1])
    assert "2025" not in text and "as_of" not in text


def test_steps_are_one_based_and_the_snapshot_matches_the_term_signals():
    assert [s.step for s in RUN] == list(range(1, len(DATES) + 1))
    pit = PointInTime(OBS)
    assert RUN[-1].signals == term_signals(pit, DATES[-1])
    assert RUN[10].signals == term_signals(pit, DATES[10])


def test_the_ema_starts_at_the_first_raw_value_and_follows_the_half_life():
    first = next(s for s in RUN if s.r_raw is not None)
    assert first.r == first.r_raw
    alpha = 1 - 0.5 ** (1 / 4)
    assert DEFAULT_PARAMS.alpha == pytest.approx(alpha, abs=1e-15)
    for before, now in zip(RUN, RUN[1:], strict=False):
        if before.r is not None and now.r_raw is not None:
            assert now.r == pytest.approx(alpha * now.r_raw + (1 - alpha) * before.r, abs=1e-15)
        if before.g is not None and now.g_raw is not None:
            assert now.g == pytest.approx(alpha * now.g_raw + (1 - alpha) * before.g, abs=1e-15)


def test_probabilities_label_and_phase_are_consistent():
    done = [s for s in RUN if s.p is not None]
    assert done
    for s in done:
        assert sum(s.p) == pytest.approx(1.0, abs=1e-12)
        assert s.label is not None and s.rate_dir is not None and s.growth_dir is not None
        assert s.phase in set(Phase)
        assert s.display[next(iter(s.display))] is not None
    assert all(s.label_since_step <= s.step for s in done)


def test_a_step_without_raw_rate_data_carries_the_ema_and_freezes_the_confirmation():
    cutoff = date(2025, 3, 1)
    gone = {S.US_DGS2, S.US_CPILFESL, S.KR_KTB3Y, S.KR_CPI}
    cut = [o for o in OBS if not (o.series in gone and o.ref_date > cutoff)]
    run = evaluate(cut, DATES)
    late = [s for s in run if s.as_of > date(2025, 7, 1)]
    assert late and all(s.r_raw is None and s.carried for s in late)
    assert len({s.r for s in late}) == 1 and late[0].r is not None  # the EMA is carried
    assert len({s.rate_dir for s in late}) == 1
    assert all(s.r_us is None and s.r_kr is None for s in late)
    assert all(s.g_raw is not None for s in late)
    assert not any(s.carried for s in run if s.as_of <= date(2025, 3, 15))


def test_a_series_that_never_has_data_gives_no_axis_and_no_label():
    cut = [o for o in OBS if o.series not in {S.US_ICSA, S.US_SAHM, S.US_T10Y3M, S.US_BAA10Y}]
    cut = [o for o in cut if o.series not in {S.KR_CLI, S.KR_EXPORTS, S.KR_CORP_AA}]
    run = evaluate(cut, DATES[-3:])
    assert all(s.g is None and s.p is None and s.label is None and s.phase is None for s in run)
    assert all(s.r is not None and s.rate_dir is not None for s in run)
    assert run[-1].display[next(a for a in run[-1].display if a.value == "GROWTH")] is None


def test_the_quality_flag_follows_the_stale_indicators():
    assert RUN[-1].stale == () and RUN[-1].quality_ok
    kr = {S.KR_KTB3Y, S.KR_CPI, S.KR_CLI, S.KR_EXPORTS, S.KR_CORP_AA}
    cut = [o for o in OBS if not (o.series in kr and o.ref_date > date(2025, 1, 1))]
    last = evaluate(cut, DATES)[-1]
    assert set(last.stale) >= {"3", "4", "8", "9", "14"} and not last.quality_ok
    assert list(last.stale) == sorted(last.stale, key=int)
    one = [o for o in OBS if not (o.series is S.KR_CPI and o.ref_date > date(2025, 1, 1))]
    assert evaluate(one, DATES[-1:])[-1].quality_ok


def test_a_stale_region_gives_the_axis_to_the_other_region():
    kr = {S.KR_KTB3Y, S.KR_CPI, S.KR_CLI, S.KR_EXPORTS, S.KR_CORP_AA}
    cut = [o for o in OBS if not (o.series in kr and o.ref_date > date(2025, 1, 1))]
    last = evaluate(cut, DATES)[-1]
    assert last.r_kr is None and last.g_kr is None
    assert last.r_raw == last.r_us and last.g_raw == last.g_us


def test_the_display_is_three_state_with_a_strict_band():
    assert axis_display(0.25, 0.25).direction is Direction.NEUTRAL
    assert axis_display(-0.25, 0.25).direction is Direction.NEUTRAL
    assert axis_display(0.2501, 0.25).direction is Direction.UP
    assert axis_display(-0.2501, 0.25).direction is Direction.DOWN
    assert axis_display(-0.7, 0.25).strength == 0.7
    assert axis_display(None, 0.25) is None


def test_the_display_uses_the_smoothed_scores_for_r_and_g_and_raw_for_f_and_s():
    last = RUN[-1]
    by_name = {a.value: d for a, d in last.display.items()}
    assert by_name["RATE"].strength == abs(last.r)
    assert by_name["GROWTH"].strength == abs(last.g)
    assert by_name["FX"].strength == abs(last.f)
    assert by_name["SENTIMENT"].strength == abs(last.s)


def test_f_is_the_mean_of_its_three_terms_in_a_real_run():
    last = RUN[-1]
    z = {s.term_id: s.z for s in last.signals if s.z is not None}
    assert last.f == pytest.approx((z["10a"] + z["11"] + z["12b"]) / 3, abs=1e-15)


def test_input_order_does_not_change_any_output():
    shuffled = list(reversed(OBS))
    again = evaluate(shuffled, DATES[:20])
    assert [fingerprint(s) for s in again] == [fingerprint(s) for s in RUN[:20]]


def test_pool_enum_order_matches_the_probability_tuple():
    assert [p.value for p in Pool] == ["P1", "P2", "P3", "P4"]
    s = next(x for x in RUN if x.p is not None)
    assert s.phase is not None


def test_the_phase_uses_the_probability_of_the_label_not_the_largest_one():
    from antlab_engine.regime import phase_of

    steps = [s for s in evaluate(OBS, weekly_schedule(date(2021, 1, 2), DATES[-1])) if s.p]
    for s in steps:
        assert s.phase is phase_of(s.p[list(Pool).index(s.label)])
    apart = [s for s in steps if s.phase is not phase_of(max(s.p))]
    assert apart  # the panel has steps where the two readings differ, so this test can tell
