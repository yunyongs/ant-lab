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


def test_kis_excd_filled_for_us_rows_only():
    for r in _rows():
        if r["market"] == "US":
            assert r["kis_excd"] in {"NAS", "NYS", "AMS"}, r["ticker"]
        else:
            assert r["kis_excd"] == "", r["ticker"]


SELECTED_COUNTS = {("KR", "STOCK"): 50, ("US", "STOCK"): 100}
ETF_SELECTED = 30
ASSET_COLUMNS = "market,ticker,name,asset_type,currency,group_code,listed_on,return_basis,proxy_index_name".split(",")


def _selected():
    return [r for r in _rows() if r["status"] == "SELECTED"]


def test_every_row_has_a_status_and_selected_counts_match_the_places():
    rows = _rows()
    assert all(r["status"] for r in rows)
    selected = Counter((r["market"], r["asset_type"]) for r in _selected())
    for key, n in SELECTED_COUNTS.items():
        assert selected[key] == n, key
    assert selected[("KR", "ETF")] + selected[("US", "ETF")] == ETF_SELECTED


def test_selected_rows_are_the_first_passing_rows_by_rank():
    rows = _rows()
    groups = {"KR_STOCK": lambda r: (r["market"], r["asset_type"]) == ("KR", "STOCK"),
              "US_STOCK": lambda r: (r["market"], r["asset_type"]) == ("US", "STOCK"),
              "ETF": lambda r: r["asset_type"] == "ETF"}
    for name, pick in groups.items():
        seen_reserve = False
        for r in sorted((r for r in rows if pick(r)), key=lambda r: int(r["rule_rank"])):
            if r["status"] == "FAILED_CHECK":
                continue
            if r["status"] == "RESERVE":
                seen_reserve = True
            else:
                assert not seen_reserve, (name, r["ticker"])


def test_failed_check_rows_carry_a_reason_and_stocks_have_a_2006_12_close():
    for r in _rows():
        if r["status"] == "FAILED_CHECK":
            assert "FAILED_CHECK:" in r["note"], r["ticker"]
        if r["status"] == "SELECTED" and r["asset_type"] == "STOCK":
            pending = "CHECK_PENDING: YF_PENDING" in r["note"]
            assert (r["has_2006_12"] == "Y") != pending, r["ticker"]
            if pending:
                assert r["has_2006_12"] == "" and r["yf_first_month"] == "", r["ticker"]
            assert r["kis_first_month"], r["ticker"]


def test_p0_08_assets_and_macro_etfs_are_selected():
    selected = {(r["market"], r["ticker"]) for r in _selected()}
    with open(SAMPLE / "asset.csv", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            assert (r["market"], r["ticker"]) in selected, r["ticker"]
    for ticker in MACRO_ETF_SECTION_3_2:
        assert ("KR", ticker) in selected, ticker


def test_asset_csv_matches_the_selection_and_the_sample_contract():
    with open(FULL / "asset.csv", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == ASSET_COLUMNS
        assets = list(reader)
    assert {(a["market"], a["ticker"]) for a in assets} == {(r["market"], r["ticker"]) for r in _selected()}
    assert len(assets) == 180
    keys = [(a["market"], a["ticker"].encode()) for a in assets]
    assert keys == sorted(keys)
    for a in assets:
        assert a["currency"] == ("KRW" if a["market"] == "KR" else "USD")
    with open(SAMPLE / "asset.csv", encoding="utf-8", newline="") as f:
        sample = {(r["market"], r["ticker"]): r for r in csv.DictReader(f)}
    by_key = {(a["market"], a["ticker"]): a for a in assets}
    for key, s in sample.items():
        assert {k: v for k, v in by_key[key].items() if k != "proxy_index_name"} == {k: v for k, v in s.items() if k != "proxy_index_name"}, key
        if s["proxy_index_name"]:
            assert by_key[key]["proxy_index_name"] == s["proxy_index_name"], key
    import proxy_map
    proxied = proxy_map.run_check(FULL).proxy_months  # MVP-03: a generic proxy name exactly for assets with a proxy month (plan B2)
    for a in assets:
        assert bool(a["proxy_index_name"]) == (proxied.get((a["market"], a["ticker"]), 0) > 0), a["ticker"]


def test_fetch_targets_load_and_follow_the_selection():
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from batch import load_fetch_targets

    targets = load_fetch_targets(FULL / "fetch_targets.csv")
    assert {(t.market, t.ticker) for t in targets} == {(r["market"], r["ticker"]) for r in _selected()}
    got = [(t.market, t.ticker) for t in targets]
    assert got[:4] == [("KR", "069500"), ("US", "SPY"), ("US", "QQQ"), ("US", "GLD")]
    pool = {(r["market"], r["ticker"]): r for r in _rows()}
    with open(SAMPLE / "asset.csv", encoding="utf-8", newline="") as f:
        p0 = {(r["market"], r["ticker"]) for r in csv.DictReader(f)}
    stage_of = {}
    for i, key in enumerate(got):
        row = pool[key]
        if i < 4:
            stage = 1
        elif key in p0:
            stage = 2
        else:
            stage = {("KR", "STOCK"): 3, ("KR", "ETF"): 4, ("US", "STOCK"): 5, ("US", "ETF"): 6}[(row["market"], row["asset_type"])]
        stage_of[key] = stage
    stages = [stage_of[k] for k in got]
    assert stages == sorted(stages)
    for stage in range(1, 7):
        keys = [k for k in got if stage_of[k] == stage]
        if stage == 2:
            ordered = sorted(keys, key=lambda k: (k[0], int(pool[k]["rule_rank"])))
        elif stage > 2:
            ordered = sorted(keys, key=lambda k: int(pool[k]["rule_rank"]))
        else:
            ordered = keys
        assert keys == ordered, stage
    for t in targets:
        row = pool[(t.market, t.ticker)]
        assert t.kis_basis == ("RAW" if (t.market == "KR" and row["asset_type"] == "ETF") else "ADJUSTED")
        assert t.kis_from.strftime("%Y-%m") == row["kis_first_month"]
        if t.market == "US":
            assert t.yf_canonical_to.strftime("%Y-%m") >= "2007-12"
