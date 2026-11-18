import csv
import re
from collections import Counter
from pathlib import Path

FULL = Path(__file__).resolve().parents[3] / "db" / "full"
SAMPLE = Path(__file__).resolve().parents[3] / "db" / "sample"

COLUMNS = (
    "market,ticker,name,asset_type,group_code,listed_on,listed_check,return_basis,rule,"
    "rule_rank,mcap_rank_public,mcap_asof,source_url,kis_excd,yf_symbol,kis_first_month,"
    "yf_first_month,has_2006_12,status,owner_decision,note"
).split(",")
TARGETS = {("KR", "STOCK"): 60, ("US", "STOCK"): 120}
ETF_TARGET = 36
MACRO_ETF_SECTION_3_2 = [
    "069500", "360750", "133690", "458730", "476850", "426030", "0019K0", "476760",
    "148070", "114260", "329750", "459580", "411060", "160580", "261220",
]


def _rows():
    with open(FULL / "pool_proposal.csv", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == COLUMNS
        return list(reader)


def test_columns_and_unique_keys():
    rows = _rows()
    keys = [(r["market"], r["ticker"]) for r in rows]
    assert len(keys) == len(set(keys))


def test_counts_meet_candidate_targets():
    rows = _rows()
    counts = Counter((r["market"], r["asset_type"]) for r in rows)
    for key, target in TARGETS.items():
        assert counts[key] >= target, key
    etfs = counts[("KR", "ETF")] + counts[("US", "ETF")]
    assert etfs >= ETF_TARGET


def test_allowed_values_and_formats():
    for r in _rows():
        assert r["market"] in {"KR", "US"}
        assert r["asset_type"] in {"STOCK", "ETF"}
        assert r["listed_check"] in {"CONFIRMED", "TO_CONFIRM"}
        assert r["return_basis"] == ("PRICE" if r["market"] == "KR" else "TOTAL")
        assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", r["listed_on"]), r["ticker"]
        assert r["source_url"].startswith("https://"), r["ticker"]
        assert r["yf_symbol"], r["ticker"]
        if r["market"] == "KR":
            assert len(r["ticker"]) == 6, r["ticker"]
        if r["status"]:
            assert r["status"] in {"SELECTED", "RESERVE", "FAILED_CHECK"}
        if r["owner_decision"]:
            assert r["owner_decision"] in {"DROP", "KEEP"}
        if r["has_2006_12"]:
            assert r["has_2006_12"] in {"Y", "N"}
        for col in ("kis_first_month", "yf_first_month"):
            if r[col]:
                assert re.fullmatch(r"\d{4}-\d{2}", r[col])


def test_stocks_listed_before_2007_and_cap_rank_only_for_stocks():
    for r in _rows():
        if r["asset_type"] == "STOCK":
            assert r["listed_on"] < "2007-01-01", r["ticker"]
            assert r["mcap_rank_public"].isdigit(), r["ticker"]
            assert r["mcap_asof"], r["ticker"]
        else:
            assert r["mcap_rank_public"] == "" and r["mcap_asof"] == "", r["ticker"]


def test_rule_ranks_unique_and_rows_sorted():
    rows = _rows()
    sort_key = [(r["market"], r["asset_type"], int(r["rule_rank"])) for r in rows]
    assert sort_key == sorted(sort_key)
    for asset_type in ("STOCK", "ETF"):
        for market in ("KR", "US"):
            ranks = [int(r["rule_rank"]) for r in rows
                     if r["market"] == market and r["asset_type"] == asset_type]
            assert len(ranks) == len(set(ranks))
    etf_ranks = sorted(int(r["rule_rank"]) for r in rows if r["asset_type"] == "ETF")
    assert etf_ranks == list(range(1, len(etf_ranks) + 1))
    for market, asset_type in TARGETS:
        ranks = sorted(int(r["rule_rank"]) for r in rows
                       if r["market"] == market and r["asset_type"] == asset_type)
        assert ranks == list(range(1, len(ranks) + 1))


def test_p0_08_assets_and_macro_etf_universe_are_present():
    keys = {(r["market"], r["ticker"]) for r in _rows()}
    with open(SAMPLE / "asset.csv", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            assert (r["market"], r["ticker"]) in keys, r["ticker"]
    for ticker in MACRO_ETF_SECTION_3_2:
        assert ("KR", ticker) in keys, ticker


def test_p0_08_rows_match_sample_asset_values():
    pool = {(r["market"], r["ticker"]): r for r in _rows()}
    with open(SAMPLE / "asset.csv", encoding="utf-8", newline="") as f:
        for s in csv.DictReader(f):
            r = pool[(s["market"], s["ticker"])]
            for col in ("name", "asset_type", "group_code", "listed_on", "return_basis"):
                assert r[col] == s[col], (s["ticker"], col)


def test_group_codes_exist_and_group_file_is_sorted():
    with open(FULL / "asset_group.csv", encoding="utf-8", newline="") as f:
        groups = list(csv.DictReader(f))
    codes = [g["code"] for g in groups]
    assert codes == sorted(codes, key=lambda c: c.encode())
    assert len(codes) == len(set(codes))
    assert {g["kind"] for g in groups} <= {"SECTOR", "ASSET_CLASS"}
    with open(SAMPLE / "asset_group.csv", encoding="utf-8", newline="") as f:
        for g in csv.DictReader(f):
            assert g in groups
    assert {r["group_code"] for r in _rows()} <= set(codes)


def test_no_price_like_column_is_tracked():
    banned = ("price", "close", "return", "volume")
    assert not [c for c in COLUMNS if any(b in c for b in banned) and c != "return_basis"]
