"""04-B: CROSS and INTERNAL compare for arbitrary segments (pure, on month-end series)."""
from decimal import Decimal

import pytest

import compare_full as cf
from compare_full import AssetData, compare_asset
from tests.full_helpers import BASE, ft_kr, ft_us, mrows, pct, yrows


def cross(rows, seg=None):
    return [r for r in rows if r["kind"] == "CROSS" and (seg is None or r["segment"] == seg)]


def kr_data(kis, yf, bars=None):
    return AssetData(kis=kis, yf=yf, bars=bars)


def test_first_return_month_is_the_month_after_the_first_kis_month():
    closes = pct(100, *["0.01"] * 11)
    rows = compare_asset(ft_kr(), kr_data(mrows("2000-01", closes), yrows("2000-01", closes)), BASE)
    c = cross(rows)
    assert [r["month"] for r in c][0] == "2000-02" and len(c) == 11 and {r["verdict"] for r in c} == {"MATCH"}
    assert all(r["segment"] == 1 and r["canonical"] == "KIS" and r["basis"] == "PRICE" for r in c)


def test_every_return_month_up_to_the_last_report_month_is_expected():
    closes = pct(100, *["0.01"] * 5)  # data ends 2000-06, report ends 2000-12
    rows = compare_asset(ft_kr(), kr_data(mrows("2000-01", closes), yrows("2000-01", closes)), BASE)
    verdicts = {r["month"]: r["verdict"] for r in cross(rows)}
    assert verdicts["2000-06"] == "MATCH" and verdicts["2000-07"] == "GAP_CANONICAL" and verdicts["2000-12"] == "GAP_CANONICAL"
    assert "2001-01" not in verdicts  # the base-date year is a price, not a return month


@pytest.mark.parametrize("yf_close,verdict", [("101.01", "MATCH"), ("101.0101", "WARN"), ("101.10", "WARN"), ("101.1001", "STOP"), ("101.11", "STOP")])
def test_thresholds_one_bp_match_ten_bp_warn_above_stop(yf_close, verdict):
    kis = mrows("2000-01", ["100", "101"] + ["101"] * 10)
    yf = yrows("2000-01", ["100", yf_close] + [yf_close] * 10)
    rows = compare_asset(ft_kr(), kr_data(kis, yf), BASE)
    assert {r["month"]: r["verdict"] for r in cross(rows)}["2000-02"] == verdict


def test_diff_is_unsigned_and_signed_is_kis_minus_yf_in_bp():
    kis = mrows("2000-01", ["100", "101"] + ["101"] * 10)
    yf = yrows("2000-01", ["100", "101.5"] + ["101.5"] * 10)
    (r,) = [x for x in cross(compare_asset(ft_kr(), kr_data(kis, yf), BASE)) if x["month"] == "2000-02"]
    assert r["diff_bp"] == Decimal("50") and r["signed"] == Decimal("-50") and r["verdict"] == "STOP"


def test_other_source_missing_a_month_inside_its_range_is_a_gap():
    closes = pct(100, *["0.01"] * 11)
    yf = [r for r in yrows("2000-01", closes) if not r[0].startswith("2000-04")]
    v = {r["month"]: r["verdict"] for r in cross(compare_asset(ft_kr(), kr_data(mrows("2000-01", closes), yf), BASE))}
    assert v["2000-04"] == "GAP" and v["2000-05"] == "GAP"  # the month after needs April's month-end too
    assert v["2000-03"] == "MATCH" and v["2000-06"] == "MATCH"


def test_canonical_source_missing_a_month_is_gap_canonical_not_gap():
    closes = pct(100, *["0.01"] * 11)
    kis = [r for r in mrows("2000-01", closes) if not r[0].startswith("2000-04")]
    v = {r["month"]: r["verdict"] for r in cross(compare_asset(ft_kr(), kr_data(kis, yrows("2000-01", closes)), BASE))}
    assert v["2000-04"] == "GAP_CANONICAL" and v["2000-05"] == "GAP_CANONICAL"


def test_months_before_the_other_source_starts_are_not_gaps():
    closes = pct(100, *["0.01"] * 11)
    rows = compare_asset(ft_kr(), kr_data(mrows("2000-01", closes), yrows("2000-04", closes[3:])), BASE)
    v = {r["month"]: r["verdict"] for r in cross(rows)}
    assert "2000-02" not in v and "2000-04" not in v and v["2000-05"] == "MATCH"


def test_no_other_source_data_gives_one_no_overlap_row():
    closes = pct(100, *["0.01"] * 11)
    rows = compare_asset(ft_kr(yf=None), kr_data(mrows("2000-01", closes), None), BASE)
    assert [(r["kind"], r["verdict"], r["segment"]) for r in rows] == [("NO_OVERLAP", "NONE", 1)]


def test_us_two_segments_yfinance_through_the_cut_then_kis():
    ft = ft_us(kis_from="2000-01", cut="2000-06")
    kis = mrows("2000-06", pct(100, *["0.01"] * 6))  # KIS daily starts in the cut month (the drip start)
    yf = yrows("2000-01", pct(100, *["0.01"] * 11), adj=pct(100, *["0.01"] * 11))
    rows = compare_asset(ft, AssetData(kis=kis, yf=yf), BASE)
    seg1 = [r for r in rows if r["segment"] == 1 and r["kind"] != "INTERNAL"]
    seg2 = cross(rows, 2)
    assert [r["kind"] for r in seg1] == ["NO_OVERLAP"] and seg1[0]["canonical"] == "YF"  # KIS has no month before the cut month
    assert seg2[0]["month"] == "2000-07" and seg2[0]["canonical"] == "KIS" and seg2[0]["basis"] == "TOTAL"
    assert {r["verdict"] for r in seg2} == {"MATCH"} and [r["month"] for r in seg2][-1] == "2000-12"


def test_us_compares_adjusted_close_and_kr_compares_close():
    kis = mrows("2000-01", ["100", "101"] + ["101"] * 10)
    yf = yrows("2000-01", ["100", "101"] + ["101"] * 10, adj=["100", "103"] + ["103"] * 10)  # dividends in Adj Close only
    us_row = [r for r in cross(compare_asset(ft_us(), AssetData(kis=kis, yf=yf), BASE)) if r["month"] == "2000-02"][0]
    kr_row = [r for r in cross(compare_asset(ft_kr(), kr_data(kis, yf), BASE)) if r["month"] == "2000-02"][0]
    assert us_row["verdict"] == "STOP" and kr_row["verdict"] == "MATCH"


def test_a_series_that_gives_no_segment_is_an_error():
    with pytest.raises(ValueError, match="no canonical segment"):
        compare_asset(ft_kr(), kr_data(mrows("2000-12", ["100"]), None), BASE)  # first return month would be 2001-01


def test_non_positive_close_is_an_error():
    with pytest.raises(ValueError, match="0 이하|finite|유한"):
        compare_asset(ft_kr(), kr_data(mrows("2000-01", ["100", "0"] + ["1"] * 10), yrows("2000-01", ["100"] * 12)), BASE)


# -- INTERNAL ----------------------------------------------------------------------------------
def internal(rows):
    return {r["month"]: r["verdict"] for r in rows if r["kind"] == "INTERNAL"}


def test_internal_equal_round_diff_for_kr():
    kis = mrows("2000-01", ["100", "100", "100", "100"] + ["100"] * 8)
    bars = mrows("2000-01", ["100", "101", "102", "100"] + ["100"] * 8)
    v = internal(compare_asset(ft_kr(), kr_data(kis, yrows("2000-01", ["100"] * 12), bars), BASE))
    assert (v["2000-01"], v["2000-02"], v["2000-03"], v["2000-04"]) == ("EQUAL", "ROUND", "DIFF", "EQUAL")


def test_internal_us_has_no_rounding_allowance():
    kis = mrows("2000-01", ["100.00"] * 12)
    bars = mrows("2000-01", ["100.01"] + ["100.00"] * 11)
    v = internal(compare_asset(ft_us(), AssetData(kis=kis, yf=yrows("2000-01", ["100"] * 12), bars=bars), BASE))
    assert v["2000-01"] == "DIFF" and v["2000-02"] == "EQUAL"


def test_internal_missing_bar_is_blocking_including_leading_months():
    kis = mrows("2000-01", ["100"] * 12)
    bars = [b for b in mrows("2000-03", ["100"] * 10) if not b[0].startswith("2000-06")]
    rows = compare_asset(ft_kr(), kr_data(kis, yrows("2000-01", ["100"] * 12), bars), BASE)
    v = internal(rows)
    assert v["2000-06"] == "NO_BAR" and v["2000-01"] == v["2000-02"] == "NO_BAR" and v["2000-03"] == "EQUAL"
    assert [r for r in cf.blocking(rows) if r["kind"] == "INTERNAL"][-1]["month"] == "2000-06"


def test_duplicate_partial_bar_at_a_page_boundary_uses_the_latest_dated_bar_and_counts_months_once():
    kis = mrows("2000-01", ["100", "110"] + ["110"] * 10)
    bars = [("2000-01-31", "100"), ("2000-02-11", "104"), ("2000-02-29", "110")] + mrows("2000-03", ["110"] * 10)
    assert len(cf.bar_by_month(bars, "t")) == 12
    assert internal(compare_asset(ft_kr(), kr_data(kis, yrows("2000-01", ["100"] * 12), bars), BASE))["2000-02"] == "EQUAL"


def test_internal_months_after_the_last_report_month_are_not_compared():
    kis = mrows("2000-01", ["100"] * 13)  # runs into 2001-01
    bars = mrows("2000-01", ["100"] * 12)
    assert "2001-01" not in internal(compare_asset(ft_kr(), kr_data(kis, yrows("2000-01", ["100"] * 13), bars), BASE))


def test_blocking_verdicts():
    rows = [{"verdict": v} for v in ("MATCH", "WARN", "STOP", "DIFF", "GAP", "GAP_CANONICAL", "NO_BAR", "JUDGED", "EQUAL", "ROUND", "NONE")]
    assert {r["verdict"] for r in cf.blocking(rows)} == {"STOP", "DIFF", "GAP", "GAP_CANONICAL", "NO_BAR"}
