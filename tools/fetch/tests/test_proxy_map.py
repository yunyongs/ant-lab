import csv
import shutil
from pathlib import Path

import pytest

import proxy_map as pm

REAL = Path(__file__).resolve().parents[3] / "db" / "full"
FILES = ("pool_proposal.csv", "asset.csv", "proxy_series.csv", "proxy_map.csv")


@pytest.fixture
def full(tmp_path):
    for n in FILES:
        shutil.copy(REAL / n, tmp_path / n)
    return tmp_path


def header(path: Path) -> list[str]:
    with open(path, encoding="utf-8", newline="") as h:
        return next(csv.reader(h))


def edit(path: Path, columns, fn):
    rows = pm.read_csv(path, columns)
    fn(rows)
    pm.write_csv(path, columns, rows)


def errors(full, strict=False):
    return pm.run_check(full, strict).errors


# -- the tracked files ----------------------------------------------------------------------------------
def test_tracked_map_covers_every_cell_and_passes_the_check():
    rep = pm.run_check(REAL)
    assert rep.errors == [] and rep.uncovered == []
    assert len(pm.load_assets(REAL)) == 180


def test_every_pool_asset_has_a_ladder():
    mapping = pm.read_csv(REAL / "proxy_map.csv", pm.MAP_COLUMNS)
    assert {(r["market"], r["ticker"]) for r in mapping} == {a.key for a in pm.load_assets(REAL)}


def test_generator_reproduces_the_tracked_files(full):
    (full / "proxy_map.csv").write_text("", encoding="utf-8")
    pm.generate(full)
    for n in ("proxy_map.csv", "asset.csv"):
        assert (full / n).read_bytes() == (REAL / n).read_bytes(), n


def test_no_stock_month_from_2007_is_a_proxy_month():
    rep = pm.run_check(REAL)
    for a in pm.load_assets(REAL):
        if a.asset_type == "STOCK":
            assert a.first_return() <= pm.MAIN_PERIOD_START, a.key


def test_pending_fees_warn_by_default_and_fail_in_strict_mode():
    rep = pm.run_check(REAL)
    assert rep.warnings and all("fee pending" in w for w in rep.warnings)
    assert any("fee pending" in e for e in pm.run_check(REAL, strict=True).errors)


def test_first_months_follow_the_measured_month_then_the_listing_month():
    a = {x.key: x for x in pm.load_assets(REAL)}
    assert a[("KR", "000660")].first_close() == ("1996-12", "SWEEP") and a[("KR", "000660")].first_return() == "1997-01"
    assert a[("US", "NVDA")].first_close() == ("1999-01", "YF")
    assert a[("US", "QQQ")].first_close() == ("1999-03", "LISTING")  # US ETF: yfinance not measured yet
    assert a[("US", "SPY")].first_close()[0] == "1995-12"  # listed in 1993, prices start at the window start
    assert a[("KR", "0019K0")].first_return() == "2025-04"


# -- the check must stop on an uncovered cell -------------------------------------------------------------
def test_an_uncovered_cell_fails(full):
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(first_month="1996-07") for r in rows if r["series_id"] == "KR_KOSPI"])
    rep = pm.run_check(full)
    assert rep.uncovered and ("KR", "000660", "1996-01") in rep.uncovered
    assert any(e.startswith("uncovered: ('KR', '000660') 1996-01..1996-06") for e in rep.errors)


def test_a_missing_ladder_and_an_unknown_series_fail(full):
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: rows.__delitem__(slice(0, 1)))
    assert any("has no ladder" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: rows[0].update(series_id="NOPE"))
    assert any("unknown series NOPE" in e for e in errors(full))


def test_a_series_that_ends_early_leaves_months_uncovered(full):
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(last_month="2000-12") for r in rows if r["series_id"] == "WB_GOLD_USD"])
    rep = pm.run_check(full)
    assert ("US", "GLD", "2001-01") in rep.uncovered and ("KR", "411060", "2001-01") in rep.uncovered


def test_series_edges_are_inclusive_on_both_sides(full):
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(last_month="2004-11") for r in rows if r["series_id"] == "WB_GOLD_USD"])
    assert ("US", "GLD", "2004-11") not in pm.run_check(full).uncovered  # the last gap month is the series' last month
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(last_month="2004-10") for r in rows if r["series_id"] == "WB_GOLD_USD"])
    assert ("US", "GLD", "2004-11") in pm.run_check(full).uncovered
    shutil.copy(REAL / "proxy_series.csv", full / "proxy_series.csv")
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(first_month="1996-01") for r in rows if r["series_id"] == "KR_KOSPI"])
    assert ("KR", "000660", "1996-01") not in pm.run_check(full).uncovered  # first gap month equals the series' first month


def test_proxy_month_counts_stop_at_the_month_before_the_first_actual_return():
    rep = pm.run_check(REAL)
    assert rep.proxy_months[("US", "QQQ")] == 39  # 1996-01..1999-03; 1999-04 is the first actual return
    assert rep.proxy_months[("KR", "0019K0")] == 351  # 1996-01..2025-03
    assert rep.proxy_months[("KR", "000660")] == 12  # the whole warm-up year


def test_the_lowest_priority_series_with_the_month_wins():
    rep = pm.run_check(REAL)
    assert rep.by_series["KR_KTB3Y_M_TR"] == 35  # 114260: 1996-01..1998-11, before the daily government yield starts (1998-11 -> first return 1998-12)
    assert rep.by_series["KR_KTB10Y_FROM_KTB5Y_M_TR"] == 60  # 148070: 1996-01..2000-12
    assert rep.by_series["KR_KTB3Y_TR"] == 128 and rep.by_series["KR_KTB10Y_TR"] == 130


def test_a_stock_without_actual_returns_in_the_main_period_fails(full):
    edit(full / "pool_proposal.csv", header(REAL / "pool_proposal.csv"),
         lambda rows: [r.update(kis_first_month="2008-03") for r in rows if (r["market"], r["ticker"]) == ("KR", "000100")])
    assert any("main period must be actual" in e and "000100" in e for e in errors(full))


def test_the_ladder_cannot_use_a_rate_input_or_the_assets_own_returns(full):
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(series_id="US_DGS10_YLD") for r in rows if (r["market"], r["ticker"]) == ("US", "IEF")])
    assert any("yield input" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(series_id="ASSET_US_SPY") for r in rows if (r["market"], r["ticker"]) == ("US", "SPY")])
    assert any("its own returns" in e for e in errors(full))


def test_currency_conversion_and_fee_rules(full):
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(to_krw="N") for r in rows if (r["market"], r["ticker"]) == ("KR", "360750")])
    assert any("to_krw must be Y" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(to_krw="Y") for r in rows if (r["market"], r["ticker"]) == ("KR", "261220")])  # hedged
    assert any("to_krw must be N" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(fee_applied="N") for r in rows if (r["market"], r["ticker"]) == ("KR", "069500")])
    assert any("fee_applied is Y exactly for ETFs" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(annual_fee="0.5") for r in rows if (r["market"], r["ticker"]) == ("KR", "069500")])
    assert any("ratio between 0 and 0.05" in e for e in errors(full))
    shutil.copy(REAL / "proxy_map.csv", full / "proxy_map.csv")
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(annual_fee="0.001", fee_source="MACRO_ETF_3_2") for r in rows if (r["market"], r["ticker"]) == ("US", "QQQ")])
    assert not any("QQQ" in e for e in errors(full))  # a filled fee clears the pending state
    assert not any("('US', 'QQQ')" in w for w in pm.run_check(full, strict=True).errors)


def test_priorities_must_be_contiguous(full):
    edit(full / "proxy_map.csv", pm.MAP_COLUMNS, lambda rows: [r.update(priority="3") for r in rows if (r["market"], r["ticker"], r["series_id"]) == ("KR", "114260", "KR_KTB3Y_M_TR")])
    assert any("priorities must be 1..2" in e for e in errors(full))


def test_series_rows_are_validated(full):
    def mut(rows):
        for r in rows:
            if r["series_id"] == "US_NDX_PRICE":
                r["terms_url"] = ""
            if r["series_id"] == "KR_KTB3Y_TR":
                r["first_month"] = "1998-06"  # before its yield input starts (1998-11)
            if r["series_id"] == "US_UST2Y_TR":
                r["formula"] = "PAR_BOND_TR(US_DGS2_YLD,99)"
            if r["series_id"] == "ASSET_US_QQQ":
                r["first_month"] = "1999-03"
            if r["series_id"] == "KR_CD91_ACCRUAL":
                r["formula"] = "MAGIC(KR_CD91_YLD)"
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, mut)
    errs = errors(full)
    assert any("US_NDX_PRICE" in e and "terms_url" in e for e in errs)
    assert any("KR_KTB3Y_TR" in e and "before its inputs allow (1998-12)" in e for e in errs)
    assert any("US_UST2Y_TR" in e and "1-30 years" in e for e in errs)
    assert any("ASSET_US_QQQ" in e and "first actual return month 1999-04" in e for e in errs)
    assert any("KR_CD91_ACCRUAL" in e and "unknown formula" in e for e in errs)


def test_unknown_etf_has_no_ladder_rule(full):
    edit(full / "pool_proposal.csv", header(REAL / "pool_proposal.csv"),
         lambda rows: [r.update(status="SELECTED") for r in rows if r["ticker"] == "XLU"])
    edit(full / "asset.csv", pm.ASSET_COLUMNS, lambda rows: rows.append({"market": "US", "ticker": "XLU", "name": "x", "asset_type": "ETF", "currency": "USD", "group_code": "UTILITIES",
                                                                       "listed_on": "1998-12-22", "return_basis": "TOTAL", "proxy_index_name": ""}))
    rows = sorted(pm.read_csv(full / "asset.csv", pm.ASSET_COLUMNS), key=lambda r: (r["market"], r["ticker"].encode()))
    pm.write_csv(full / "asset.csv", pm.ASSET_COLUMNS, rows)
    with pytest.raises(ValueError, match="no ladder rule for ETF US:XLU"):
        pm.generate(full)


def test_first_wins_counts_each_proxy_cell_once():
    rep = pm.run_check(REAL)
    assert sum(rep.by_series.values()) == sum(rep.proxy_months.values())


def test_month_arithmetic():
    assert pm.next_month("1996-12") == "1997-01" and pm.next_month("2025-11") == "2025-12"
    assert pm.months("1996-11", "1997-02") == ["1996-11", "1996-12", "1997-01", "1997-02"]


def test_pre_1998_korean_bonds_use_monthly_government_yields_not_corporate_ones():
    series = pm.series_by_id(pm.read_csv(REAL / "proxy_series.csv", pm.SERIES_COLUMNS))
    assert not [i for i in series if "AA3Y" in i]
    for i, code in (("KR_KTB3Y_M_YLD", "721Y001/5020000"), ("KR_KTB5Y_M_YLD", "721Y001/5040000")):
        assert (series[i]["source_code"], series[i]["basis"], series[i]["observation"]) == (code, "RATE", "MONTH_AVG")
    mapping = pm.read_csv(REAL / "proxy_map.csv", pm.MAP_COLUMNS)
    ladders = {k: [r["series_id"] for r in mapping if (r["market"], r["ticker"]) == k] for k in (("KR", "114260"), ("KR", "148070"))}
    assert ladders[("KR", "114260")] == ["KR_KTB3Y_TR", "KR_KTB3Y_M_TR"]
    assert ladders[("KR", "148070")] == ["KR_KTB10Y_TR", "KR_KTB10Y_FROM_KTB5Y_M_TR"]


def test_the_blended_etf_converts_only_its_equity_half():
    mapping = [r for r in pm.read_csv(REAL / "proxy_map.csv", pm.MAP_COLUMNS) if r["ticker"] == "0019K0"]
    assert [(r["series_id"], r["to_krw"]) for r in mapping] == [("MIX_NDX_KRW_CD91_50", "N")]
    series = pm.series_by_id(pm.read_csv(REAL / "proxy_series.csv", pm.SERIES_COLUMNS))
    assert series["MIX_NDX_KRW_CD91_50"]["currency"] == "KRW" and series["MIX_NDX_KRW_CD91_50"]["formula"] == "MIX_50_50_FX(US_NDX_PRICE,KR_CD91_ACCRUAL)"


def test_the_fx_mix_needs_a_usd_leg_first_and_a_won_leg_second(full):
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(formula="MIX_50_50_FX(KR_CD91_ACCRUAL,US_NDX_PRICE)") for r in rows if r["series_id"] == "MIX_NDX_KRW_CD91_50"])
    assert any("USD series first and a KRW series second" in e for e in errors(full))
    shutil.copy(REAL / "proxy_series.csv", full / "proxy_series.csv")
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(currency="USD") for r in rows if r["series_id"] == "MIX_NDX_KRW_CD91_50"])
    assert any("gives a won series" in e for e in errors(full))


def test_a_proxy_name_is_exposed_exactly_for_assets_with_proxy_months():
    rep = pm.run_check(REAL)
    for a in pm.read_csv(REAL / "asset.csv", pm.ASSET_COLUMNS):
        has = rep.proxy_months.get((a["market"], a["ticker"]), 0) > 0
        assert bool(a["proxy_index_name"]) == has, a["ticker"]
    assert sum(1 for a in pm.read_csv(REAL / "asset.csv", pm.ASSET_COLUMNS) if a["proxy_index_name"]) == 71


def test_monthly_average_yield_inputs_are_accepted_and_a_daily_rate_is_still_month_end_or_average(full):
    assert not any("observation" in e for e in errors(full))
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(observation="WEEKLY") for r in rows if r["series_id"] == "KR_KTB3Y_M_YLD"])
    assert any("observation 'WEEKLY'" in e for e in errors(full))


def test_index_fund_and_futures_fund_proxies_fill_eem_efa_and_the_crude_etf():
    rep = pm.run_check(REAL)
    mapping = pm.read_csv(REAL / "proxy_map.csv", pm.MAP_COLUMNS)
    ladder = lambda t: [(r["series_id"], r["to_krw"]) for r in mapping if r["ticker"] == t]
    assert ladder("EEM") == [("YF_VEIEX_TR", "N")] and ladder("EFA") == [("EAFE_PROXY_70_30_TR", "N")]
    assert ladder("261220") == [("YF_USO_PRICE", "N"), ("WB_WTI_USD", "N")]  # hedged: no conversion; futures fund first, spot before it exists
    assert rep.by_series["YF_VEIEX_TR"] == 88 and rep.by_series["EAFE_PROXY_70_30_TR"] == 68  # 1996-01..2003-04, 1996-01..2001-08
    assert rep.by_series["YF_USO_PRICE"] == 128 and rep.by_series["WB_WTI_USD"] == 124  # 2006-05..2016-12, 1996-01..2006-04


def test_the_weighted_mix_formula_is_validated(full):
    edit(full / "proxy_series.csv", pm.SERIES_COLUMNS, lambda rows: [r.update(formula="MIX_W(YF_VEURX_TR,YF_VPACX_TR,100)") for r in rows if r["series_id"] == "EAFE_PROXY_70_30_TR"])
    assert any("whole percent weight" in e for e in errors(full))


def test_ecos_rows_carry_the_data_go_kr_terms_url():
    for s in pm.read_csv(REAL / "proxy_series.csv", pm.SERIES_COLUMNS):
        if s["source"] == "ECOS":
            assert s["terms_url"] == "https://www.data.go.kr/data/15059632/openapi.do", s["series_id"]
