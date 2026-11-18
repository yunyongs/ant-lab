"""04-B: judgement v2 (RULE proposals for three mechanical classes, human queue, stale rows reopen the STOP)."""
import csv
from decimal import Decimal

import pytest

import compare_full as cf
from compare_full import AssetData, apply_judgments_v2, compare_asset, load_judgments_v2, propose, write_judgments
from tests.full_helpers import BASE, ft_kr, mrows, pct, yrows


def rows_for(kis, yf):
    return compare_asset(ft_kr(), AssetData(kis=kis, yf=yf), BASE)


def stops(rows):
    return {r["month"]: r for r in rows if r["kind"] == "CROSS" and r["verdict"] == "STOP"}


def flat(n=12):
    return ["100"] * n


def test_one_day_opposite_signs_and_two_month_product_within_10bp():
    kis = mrows("2000-01", pct(100, "0.01", "0.01", "0.01") + ["103.0301"] * 8)
    yf_closes = ["100", "101", "102.51", "103.0301"] + ["103.0301"] * 8  # only March's close is 0.5 % high
    rows = rows_for(kis, yrows("2000-01", yf_closes))
    assert set(stops(rows)) == {"2000-03", "2000-04"}
    assert stops(rows)["2000-03"]["signed"] * stops(rows)["2000-04"]["signed"] < 0
    proposals, queue = propose(rows)
    assert {(p["month"], p["reason_code"], p["proposed_by"]) for p in proposals} == {("2000-03", "ONE_DAY", "RULE"), ("2000-04", "ONE_DAY", "RULE")}
    assert not queue


def test_one_day_needs_the_two_month_product_within_10bp():
    kis = mrows("2000-01", ["100", "101", "102", "103"] + ["103"] * 8)
    # March end is 1.5 % high and April end 0.3 % high: opposite signs, but the two-month product is 0.3 % off
    yf = yrows("2000-01", ["100", "101", "103.5", "103.3"] + ["103"] * 8)
    rows = rows_for(kis, yf)
    proposals, queue = propose(rows)
    assert proposals == []
    assert {q["month"] for q in queue} >= {"2000-03"}


def test_same_sign_neighbours_are_a_step_and_never_auto_proposed():
    kis = mrows("2000-01", ["100", "101", "102.01"] + ["102.01"] * 9)
    yf = yrows("2000-01", ["100", "102", "104.04"] + ["104.04"] * 9)
    rows = rows_for(kis, yf)
    proposals, queue = propose(rows)
    assert proposals == [] and {q["hint"] for q in queue if q["verdict"] == "STOP"} == {"STEP"}


def test_unclassified_cell_goes_to_the_queue():
    kis = mrows("2000-01", ["100", "101"] + ["101"] * 10)
    yf = yrows("2000-01", ["100", "102.5"] + ["102.5"] * 10)  # only one return differs, the next is equal: no opposite neighbour
    proposals, queue = propose(rows_for(kis, yf))
    assert proposals == [] and [(q["month"], q["hint"]) for q in queue] == [("2000-02", "UNCLASSIFIED")]


def test_yfinance_missing_the_last_trading_day_and_extra_day():
    kis = mrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf = yrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf[1] = ("2000-02-28", "99", "99")  # yfinance lacks 02-29 and shows an earlier close
    rows = rows_for(kis, yf)
    p, q = propose(rows)
    assert {x["month"]: x["reason_code"] for x in p}["2000-02"] == "YF_MISSING_DAY"
    yf2 = yrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    kis2 = list(kis)
    kis2[1] = ("2000-02-28", "99")  # KIS ends a day earlier: yfinance has a later date
    p2, _ = propose(rows_for(kis2, yf2))
    assert {x["month"]: x["reason_code"] for x in p2}["2000-02"] == "YF_EXTRA_DAY"


def test_a_difference_above_500_bp_is_never_auto_proposed_even_with_different_dates():
    kis = mrows("2000-01", ["100", "101"] + ["101"] * 10)
    yf = yrows("2000-01", ["100", "110"] + ["110"] * 10)
    yf[1] = ("2000-02-28", "110", "110")
    proposals, queue = propose(rows_for(kis, yf))
    assert proposals == [] and [(q["month"], q["hint"]) for q in queue] == [("2000-02", "BIG_DIFF")]


def test_exactly_500_bp_is_still_a_rule_candidate():
    kis = mrows("2000-01", ["100", "100"] + ["100"] * 10)
    yf = yrows("2000-01", ["100", "105"] + ["105"] * 10)
    yf[1] = ("2000-02-28", "105", "105")
    proposals, _ = propose(rows_for(kis, yf))
    assert [p["reason_code"] for p in proposals] == ["YF_MISSING_DAY"]


def test_gap_and_gap_canonical_go_to_the_queue_and_are_never_proposed():
    closes = pct(100, *["0.01"] * 11)
    yf = [r for r in yrows("2000-01", closes) if not r[0].startswith("2000-04")]
    kis = [r for r in mrows("2000-01", closes) if not r[0].startswith("2000-08")]
    proposals, queue = propose(rows_for(kis, yf))
    assert proposals == []
    assert {(q["month"], q["verdict"]) for q in queue} == {("2000-04", "GAP"), ("2000-05", "GAP"), ("2000-08", "GAP_CANONICAL"), ("2000-09", "GAP_CANONICAL")}


def test_proposal_carries_the_b9_columns_with_signed_difference_and_both_dates():
    kis = mrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf = yrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf[1] = ("2000-02-28", "99", "99")
    (p, *_) = [x for x in propose(rows_for(kis, yf))[0] if x["month"] == "2000-02"]
    assert list(p) == cf.JUDGMENT_V2_HEADER
    assert (p["canonical"], p["decision"], p["kis_month_end"], p["yf_month_end"]) == ("KIS", "CANONICAL", "2000-02-29", "2000-02-28")
    assert Decimal(p["signed_diff_bp"]) == Decimal(p["signed_diff_bp"]).quantize(Decimal("0.01")) and Decimal(p["signed_diff_bp"]) != 0
    assert p["reason"] and cf.RULE_REASON["YF_MISSING_DAY"] == p["reason"]


# -- applying and reopening ------------------------------------------------------------------------
def one_stop():
    kis = mrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf = yrows("2000-01", ["100", "101", "102"] + ["102"] * 9)
    yf[1] = ("2000-02-28", "99", "99")
    return rows_for(kis, yf)


def judged(rows):
    p = [x for x in propose(rows)[0] if x["month"] == "2000-02"][0]
    return p, {("KR", "005930", "1", "2000-02"): p}


def test_a_valid_judgement_turns_the_stop_into_judged_and_changes_no_value():
    rows = one_stop()
    _, j = judged(rows)
    out, problems = apply_judgments_v2(rows, j)
    before = stops(rows)["2000-02"]
    after = [r for r in out if r["month"] == "2000-02" and r["kind"] == "CROSS"][0]
    assert after["verdict"] == "JUDGED" and not problems
    assert {k: v for k, v in after.items() if k != "verdict"} == {k: v for k, v in before.items() if k != "verdict"}


@pytest.mark.parametrize("field,value", [("diff_bp", "1.00"), ("signed_diff_bp", "-1.00"), ("kis_month_end", "2000-02-28"),
                                         ("yf_month_end", "2000-02-29"), ("canonical", "YF")])
def test_a_stale_judgement_reopens_the_stop(field, value):
    rows = one_stop()
    p, _ = judged(rows)
    out, problems = apply_judgments_v2(rows, {("KR", "005930", "1", "2000-02"): {**p, field: value}})
    assert [r for r in out if r["month"] == "2000-02" and r["kind"] == "CROSS"][0]["verdict"] == "STOP"
    assert any("stale judgement" in x for x in problems) and cf.blocking(out)


def test_unused_judgement_is_reported_and_does_not_hide_anything():
    rows = one_stop()
    p, _ = judged(rows)
    out, problems = apply_judgments_v2(rows, {("KR", "005930", "1", "2000-09"): {**p, "month": "2000-09"}})
    assert any("unused judgement" in x for x in problems) and stops(out)["2000-02"]["verdict"] == "STOP"


def test_gap_canonical_cannot_be_judged():
    closes = pct(100, *["0.01"] * 11)
    kis = [r for r in mrows("2000-01", closes) if not r[0].startswith("2000-08")]
    rows = rows_for(kis, yrows("2000-01", closes))
    j = {("KR", "005930", "1", "2000-08"): {**judged(one_stop())[0], "month": "2000-08", "reason_code": "CMP_GAP", "diff_bp": "GAP",
                                           "signed_diff_bp": "GAP", "kis_month_end": "", "yf_month_end": "2000-08-31", "proposed_by": "HUMAN"}}
    out, _ = apply_judgments_v2(rows, j)
    assert [r for r in out if r["month"] == "2000-08" and r["kind"] == "CROSS"][0]["verdict"] == "GAP_CANONICAL"


def test_cmp_gap_row_resolves_a_gap_cell_when_dates_match():
    closes = pct(100, *["0.01"] * 11)
    yf = [r for r in yrows("2000-01", closes) if not r[0].startswith("2000-04")]
    rows = rows_for(mrows("2000-01", closes), yf)
    cell = [r for r in rows if r["month"] == "2000-04" and r["kind"] == "CROSS"][0]
    row = {"market": "KR", "ticker": "005930", "segment": "1", "month": "2000-04", "diff_bp": "GAP", "canonical": "KIS",
           "reason_code": "CMP_GAP", "reason": "yfinance lacks the month", "decision": "CANONICAL", "signed_diff_bp": "GAP",
           "kis_month_end": cell["kis_end"], "yf_month_end": "", "proposed_by": "HUMAN"}
    out, problems = apply_judgments_v2(rows, {("KR", "005930", "1", "2000-04"): row})
    assert [r for r in out if r["month"] == "2000-04" and r["kind"] == "CROSS"][0]["verdict"] == "JUDGED" and not problems


# -- the file ------------------------------------------------------------------------------------------
def test_judgments_roundtrip_and_sorted_deterministically(tmp_path):
    p, _ = judged(one_stop())
    other = {**p, "month": "2000-01"}
    f = tmp_path / "judgments.csv"
    write_judgments(f, [p, other])
    first = f.read_bytes()
    write_judgments(f, [other, p])
    assert f.read_bytes() == first and first.decode().splitlines()[1].split(",")[3] == "2000-01"
    assert set(load_judgments_v2(f)) == {("KR", "005930", "1", "2000-02"), ("KR", "005930", "1", "2000-01")}


def write_rows(f, rows, header=None):
    with f.open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(header or cf.JUDGMENT_V2_HEADER)
        w.writerows(rows)


@pytest.mark.parametrize("change", [{"proposed_by": "ROBOT"}, {"decision": "OTHER"}, {"reason": " "}, {"reason_code": "STEP"},
                                    {"reason_code": "CMP_GAP"}, {"diff_bp": "GAP"}])
def test_bad_judgement_rows_are_refused(tmp_path, change):
    p, _ = judged(one_stop())
    write_rows(tmp_path / "j.csv", [[{**p, **change}[k] for k in cf.JUDGMENT_V2_HEADER]])
    with pytest.raises(ValueError):
        load_judgments_v2(tmp_path / "j.csv")


def test_duplicate_row_and_old_header_are_refused(tmp_path):
    p, _ = judged(one_stop())
    r = [p[k] for k in cf.JUDGMENT_V2_HEADER]
    write_rows(tmp_path / "j.csv", [r, r])
    with pytest.raises(ValueError):
        load_judgments_v2(tmp_path / "j.csv")
    write_rows(tmp_path / "old.csv", [], header=cf.JUDGMENT_V2_HEADER[:9])
    with pytest.raises(ValueError, match="header"):
        load_judgments_v2(tmp_path / "old.csv")


def test_no_file_means_no_judgements(tmp_path):
    assert load_judgments_v2(tmp_path / "none.csv") == {}


# -- Codex audit fixes ---------------------------------------------------------------------------------------
def test_step_is_decided_before_the_date_and_one_day_rules():
    kis = mrows("2000-01", pct(100, "0.01", "0.01", "0.01") + ["103.0301"] * 8)
    # signed differences +20, +20, -20 bp in February, March, April; the last two months' product matches
    yf = yrows("2000-01", pct(100, "0.008", "0.008", "0.012") + ["103.0301"] * 8)
    rows = rows_for(kis, yf)
    proposals, queue = propose(rows)
    assert not [p for p in proposals if p["month"] in ("2000-02", "2000-03")]
    assert {q["month"]: q["hint"] for q in queue}["2000-03"] == "STEP"
    # a STEP cell stays a STEP even when its month-end dates differ
    yf2 = list(yf)
    yf2[2] = ("2000-03-30", yf2[2][1], yf2[2][2])
    p2, q2 = propose(rows_for(kis, yf2))
    assert not [p for p in p2 if p["month"] == "2000-03"] and {q["month"]: q["hint"] for q in q2}["2000-03"] == "STEP"


def test_an_approved_rule_row_is_rechecked_against_the_current_neighbours():
    kis = mrows("2000-01", pct(100, "0.01", "0.01", "0.01") + ["103.0301"] * 8)
    yf = yrows("2000-01", ["100", "101", "102.51", "103.0301"] + ["103.0301"] * 8)
    rows = rows_for(kis, yf)
    approved = {(p["market"], p["ticker"], p["segment"], p["month"]): p for p in propose(rows)[0]}
    assert set(m[3] for m in approved) == {"2000-03", "2000-04"}
    out, problems = apply_judgments_v2(rows, approved)
    assert not problems and not cf.blocking(out)
    # April's yfinance value changes after approval: March's own difference and dates are unchanged
    changed = rows_for(kis, yrows("2000-01", ["100", "101", "102.51", "103.5"] + ["103.0301"] * 8))
    assert stops(changed)["2000-03"]["diff_bp"] == stops(rows)["2000-03"]["diff_bp"]
    out2, problems2 = apply_judgments_v2(changed, approved)
    assert [r for r in out2 if r["month"] == "2000-03" and r["kind"] == "CROSS"][0]["verdict"] == "STOP"
    assert any("stale judgement" in p for p in problems2)


def test_a_human_row_does_not_need_a_rule_class():
    rows = one_stop()
    p, _ = judged(rows)
    human = {**p, "proposed_by": "HUMAN", "reason_code": "STEP"}
    out, problems = apply_judgments_v2(rows, {("KR", "005930", "1", "2000-02"): human})
    assert [r for r in out if r["month"] == "2000-02" and r["kind"] == "CROSS"][0]["verdict"] == "JUDGED" and not problems


def test_cut_month_returns_each_come_from_the_same_source(tmp_path):
    from decimal import Decimal as D
    from compare_full import asset_norm_rows, month_ends, _returns
    from tests.full_helpers import ft_us

    ft = ft_us(kis_from="2000-01", cut="2000-06")
    yf = yrows("2000-01", ["100", "100", "100", "100", "100", "110"] + ["110"] * 6)  # June 10 % in yfinance
    kis = mrows("2000-06", ["105", "115.5"] + ["115.5"] * 4)  # KIS June close differs from yfinance June
    data = AssetData(kis=kis, yf=yf)
    rows = asset_norm_rows(ft, data, BASE)
    by = {s: [(r[2], r[3]) for r in rows if r[6] == s] for s in ("YF", "KIS")}
    yf_ret = _returns(month_ends(by["YF"], "t"), "2000-12")
    kis_ret = _returns(month_ends(by["KIS"], "t"), "2000-12")
    assert yf_ret["2000-06"] == D("0.10") and "2000-07" not in yf_ret   # December-style cut return: YF May -> YF June
    assert kis_ret["2000-07"] == D("0.10") and "2000-06" not in kis_ret  # first KIS return: KIS June -> KIS July
    assert by["YF"][-1][0][:7] == "2000-06" and by["KIS"][0][0][:7] == "2000-06"


# -- QA boundaries ---------------------------------------------------------------------------------------------
@pytest.mark.parametrize("last_ret,hint", [("0.0091", "UNCLASSIFIED"), ("0.0090", "UNCLASSIFIED"), ("0.0089", "STEP")])
def test_step_needs_a_same_sign_neighbour_above_10_bp(last_ret, hint):
    kis = mrows("2000-01", pct(100, "0.01", "0.01") + ["102.01"] * 9)
    yf_closes = pct(100, "0.005", last_ret)
    yf = yrows("2000-01", yf_closes + [yf_closes[-1]] * 9)  # February differs by 50 bp, March by 9 / 10 / 11 bp
    proposals, queue = propose(rows_for(kis, yf))
    assert proposals == [] and {q["month"]: q["hint"] for q in queue}["2000-02"] == hint


@pytest.mark.parametrize("c3,rule", [("100.0999", "ONE_DAY"), ("100.1", "ONE_DAY"), ("100.1001", None)])
def test_one_day_two_month_product_boundary_is_10_bp_inclusive(c3, rule):
    # yfinance February 102.4 and March c3 against a flat KIS: the two-month product differs by 9.99 / 10.00 / 10.01 bp
    kis = mrows("2000-01", ["100"] * 12)
    yf = yrows("2000-01", ["100", "102.4", c3] + [c3] * 9)
    proposals, _ = propose(rows_for(kis, yf))
    assert {p["month"]: p["reason_code"] for p in proposals}.get("2000-02") == rule
