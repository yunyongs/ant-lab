"""Writes the tiny synthetic world for the build_full.sql tests (MVP-06 A).

  tools/fetch/.venv/bin/python -I tools/fetch/tests/make_build_full_fixture.py tools/fetch/tests/fixtures/build_full

All tickers, series values and prices are fake. The fixture covers 2006-09..2007-04 (the build gets
`-v first_month=2006-09-01 -v last_month=2007-04-01 -v base_date=2007-05-02`) with two assets of each failure class the build must
survive or stop on. Output layout: `map/` (the tracked-file stand-ins) and `raw/` (the git-ignored cache stand-in).
"""
from __future__ import annotations

import csv
import random
import sys
from decimal import Decimal
from pathlib import Path

MONTHS = ["2006-08", "2006-09", "2006-10", "2006-11", "2006-12", "2007-01", "2007-02", "2007-03", "2007-04"]
MID = {m: f"{m}-15" for m in MONTHS}
END = {"2006-08": "2006-08-31", "2006-09": "2006-09-29", "2006-10": "2006-10-31", "2006-11": "2006-11-30", "2006-12": "2006-12-29",
       "2007-01": "2007-01-31", "2007-02": "2007-02-28", "2007-03": "2007-03-30", "2007-04": "2007-04-30"}
BASE_DATE = "2007-05-02"
YF_WINDOW = "19951201_20260102"

ASSET_COLS = ["market", "ticker", "name", "asset_type", "currency", "group_code", "listed_on", "return_basis", "proxy_index_name"]
TARGET_COLS = ["market", "ticker", "kis_excd", "kis_basis", "kis_from", "yf_symbol", "yf_canonical_to", "order_no"]
SERIES_COLS = ["series_id", "source", "source_code", "currency", "basis", "observation", "first_month", "last_month", "formula",
               "terms_url", "terms_checked_on"]
MAP_COLS = ["market", "ticker", "priority", "series_id", "to_krw", "fee_applied", "annual_fee", "fee_source"]
POOL_COLS = ["market", "ticker", "name", "asset_type", "group_code", "listed_on", "listed_check", "return_basis", "rule", "rule_rank",
             "mcap_rank_public", "mcap_asof", "source_url", "kis_excd", "yf_symbol", "kis_first_month", "yf_first_month", "has_2006_12",
             "status", "owner_decision", "note"]
DAILY_COLS = ["market", "ticker", "trade_date", "close", "close_price", "close_raw", "source"]

URL = "https://example.org/terms"

# (market, ticker, name, type, currency, group, basis, first KIS/YF month-end month, canonical YF to, missing months, rank)
ASSETS = [
    ("KR", "100001", "Fake stock A", "STOCK", "KRW", "FIN", "PRICE", "2006-08", None, [], "1"),
    ("KR", "100002", "Fake stock B", "STOCK", "KRW", "FIN", "PRICE", "2006-11", None, [], "2"),
    ("KR", "200001", "Fake bond ETF", "ETF", "KRW", "BOND", "PRICE", "2006-12", None, [], ""),
    ("KR", "200002", "Fake gold ETF", "ETF", "KRW", "GOLD", "PRICE", "2006-11", None, [], ""),
    ("KR", "200003", "Fake mix ETF", "ETF", "KRW", "MIX", "PRICE", "2006-12", None, [], ""),
    ("US", "SPY", "Fake index ETF", "ETF", "USD", "IDX", "TOTAL", "2006-08", "2006-12", [], ""),
    ("US", "TECH1", "Fake tech stock", "STOCK", "USD", "TECH", "TOTAL", "2006-08", "2006-12", ["2006-10"], "1"),
    ("US", "MIXE", "Fake mix ETF US", "ETF", "USD", "MIX", "TOTAL", "2006-12", None, [], ""),
]
# (series_id, source, code, currency, basis, observation, first, last, formula)
SERIES = [
    ("KR_KOSPI", "ECOS", "802Y001/0001000", "KRW", "PRICE", "MONTH_END", "2006-09", "2007-04", ""),
    ("US_SPX_PRICE", "YF", "^GSPC", "USD", "PRICE", "MONTH_END", "2006-09", "2007-04", ""),
    ("WB_GOLD_USD", "WB", "Gold", "USD", "PRICE", "MONTH_AVG", "2006-09", "2007-04", ""),
    ("WB_COPPER_USD", "WB", "Copper", "USD", "PRICE", "MONTH_AVG", "2006-10", "2007-04", ""),
    ("US_DGS10_YLD", "FRED", "DGS10", "USD", "RATE", "MONTH_END", "2006-08", "2007-04", ""),
    ("US_UST10Y_TR", "DERIVED", "", "USD", "TOTAL", "MONTH_END", "2006-10", "2007-04", "PAR_BOND_TR(US_DGS10_YLD,10)"),
    ("KR_CD_M_YLD", "ECOS", "721Y001/5020000", "KRW", "RATE", "MONTH_AVG", "2006-08", "2007-04", ""),
    ("KR_CD_ACCRUAL", "DERIVED", "", "KRW", "TOTAL", "MONTH_AVG", "2006-09", "2007-04", "CASH_ACCRUAL(KR_CD_M_YLD)"),
    ("MIX_SPX_CD", "DERIVED", "", "KRW", "TOTAL", "MONTH_END", "2006-09", "2007-04", "MIX_50_50_FX(US_SPX_PRICE,KR_CD_ACCRUAL)"),
    ("MIX_SPX_GOLD", "DERIVED", "", "USD", "TOTAL", "MONTH_END", "2006-10", "2007-04", "MIX_50_50(US_SPX_PRICE,WB_GOLD_USD)"),
    ("MIX_SPX_GOLD_70", "DERIVED", "", "USD", "TOTAL", "MONTH_END", "2006-09", "2007-04", "MIX_W(US_SPX_PRICE,WB_GOLD_USD,70)"),
    ("ASSET_US_SPY", "ASSET", "US:SPY", "USD", "TOTAL", "MONTH_END", "2006-09", "2007-04", ""),
]
# (market, ticker, priority, series, to_krw, fee_applied, fee, fee_source)
MAP = [
    ("KR", "100001", 1, "KR_KOSPI", "N", "N", "", ""),
    ("KR", "100002", 1, "KR_KOSPI", "N", "N", "", ""),
    ("KR", "200001", 1, "US_UST10Y_TR", "Y", "Y", "0.0015", "MACRO_ETF_3_2"),
    ("KR", "200001", 2, "KR_KOSPI", "N", "Y", "0.0015", "MACRO_ETF_3_2"),
    ("KR", "200002", 1, "WB_GOLD_USD", "Y", "Y", "0.0019", "MACRO_ETF_3_2"),
    ("KR", "200003", 1, "MIX_SPX_CD", "N", "Y", "0.0025", "MACRO_ETF_3_2"),
    ("US", "SPY", 1, "US_SPX_PRICE", "N", "Y", "0.0009", "MACRO_ETF_3_2"),
    ("US", "TECH1", 1, "ASSET_US_SPY", "N", "N", "", ""),
    ("US", "MIXE", 1, "MIX_SPX_GOLD", "N", "Y", "0.0012", "MACRO_ETF_3_2"),
    ("US", "MIXE", 2, "MIX_SPX_GOLD_70", "N", "Y", "0.0012", "MACRO_ETF_3_2"),
]


def _write(path: Path, header: list[str], rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def _walk(rng: random.Random, start: Decimal, n: int, step: str = "0.04") -> list[Decimal]:
    out, v = [], start
    for _ in range(n):
        v = (v * (1 + Decimal(str(round(rng.uniform(-1, 1), 4))) * Decimal(step))).quantize(Decimal("0.0001"))
        out.append(v)
    return out


def write_world(root: Path) -> None:
    rng = random.Random(20061121)
    mp, raw = root / "map", root / "raw"
    _write(mp / "asset.csv", ASSET_COLS, [(a[0], a[1], a[2], a[3], a[4], a[5], "2000-01-03", a[6], "") for a in ASSETS])
    _write(mp / "fetch_targets.csv", TARGET_COLS,
           [(a[0], a[1], "" if a[0] == "KR" else "AMS", "RAW" if a[3] == "ETF" and a[0] == "KR" else "ADJUSTED",
             a[7], a[1] + (".KS" if a[0] == "KR" else ""), a[8] or "", i + 1) for i, a in enumerate(ASSETS)])
    _write(mp / "proxy_series.csv", SERIES_COLS,
           [(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], URL if s[1] in ("YF", "FRED", "ECOS", "WB") else "",
             "2026-11-21" if s[1] in ("YF", "FRED", "ECOS", "WB") else "") for s in SERIES])
    _write(mp / "proxy_map.csv", MAP_COLS, MAP)
    _write(mp / "pool_proposal.csv", POOL_COLS,
           [(a[0], a[1], a[2], a[3], a[5], "2000-01-03", "CONFIRMED", a[6], "RULE", i + 1, a[10], "2026-11-01", URL, "", "", a[7], "", "Y", "SELECTED", "", "")
            for i, a in enumerate(ASSETS)])

    # daily closes: two rows per month (mid and last trading day); the last row is the month-end
    daily: list[tuple] = []
    for a in ASSETS:
        market, ticker, _n, _t, _c, _g, _b, first, yf_to, missing, _r = a
        months = [m for m in MONTHS if m >= first and m not in missing]
        start = Decimal(str(rng.randint(20, 90)))
        levels = _walk(rng, start, 2 * len(months))
        base_row = None
        for i, m in enumerate(months):
            for j, day in enumerate((MID[m], END[m])):
                px = levels[2 * i + j]
                if market == "KR":
                    daily.append((market, ticker, day, str(px), "", "", "KIS"))
                    continue
                if yf_to and m <= yf_to:
                    daily.append((market, ticker, day, str(px), str((px * Decimal("1.01")).quantize(Decimal("0.0001"))), "", "YF"))
                if not yf_to or m >= "2006-12":  # the KIS rows overlap the YF rows in 2006-12 on purpose, at another price level
                    kp = (px * Decimal("1.1")).quantize(Decimal("0.0001")) if yf_to else px
                    daily.append((market, ticker, day, str(kp), "", "", "KIS"))
        raw_px = (levels[-1] * Decimal("1.003")).quantize(Decimal("0.0001"))
        daily.append((market, ticker, BASE_DATE, str(raw_px), "", str(raw_px), "KIS"))
    daily.sort(key=lambda r: (r[0], r[1], r[2], r[6]))
    _write(raw / "norm" / "daily.csv", DAILY_COLS, daily)

    # FRED
    fx = _walk(rng, Decimal("950.0"), 2 * len(MONTHS), "0.01")
    rows = []
    for i, m in enumerate(MONTHS):
        rows.append((MID[m], str(fx[2 * i])))
        if m == "2006-11":  # the last day has no observation: the month-end value is the day before
            rows += [("2006-11-29", str(fx[2 * i + 1])), (END[m], ".")]
        else:
            rows.append((END[m], "" if m == "2007-02" else str(fx[2 * i + 1])))  # FRED writes a holiday as an empty cell
    rows.append((BASE_DATE, str(fx[-1])))
    _write(raw / "aux" / "fred" / "DEXKOUS.csv", ["observation_date", "DEXKOUS"], rows)
    y = _walk(rng, Decimal("4.5"), 2 * len(MONTHS), "0.03")
    _write(raw / "aux" / "fred" / "DGS10.csv", ["observation_date", "DGS10"],
           [(d, "" if d == END["2006-10"] else str(y[2 * i + j])) for i, m in enumerate(MONTHS) for j, d in enumerate((MID[m], END[m]))])
    # ECOS
    k = _walk(rng, Decimal("1350.5"), 2 * len(MONTHS), "0.03")
    _write(raw / "aux" / "ecos" / "802Y001_0001000_D.csv", ["time", "value"],
           [(d.replace("-", ""), str(k[2 * i + j])) for i, m in enumerate(MONTHS) for j, d in enumerate((MID[m], END[m]))])
    cd = _walk(rng, Decimal("4.2"), len(MONTHS), "0.02")
    _write(raw / "aux" / "ecos" / "721Y001_5020000_M.csv", ["time", "value"], [(m.replace("-", ""), str(cd[i])) for i, m in enumerate(MONTHS)])
    # yfinance index: ^GSPC
    g = _walk(rng, Decimal("1300.0"), 2 * len(MONTHS), "0.03")
    _write(raw / "yf" / f"yf_px_^GSPC_{YF_WINDOW}.csv", ["trade_date", "close", "adj_close"],
           [(d, str(g[2 * i + j]), str((g[2 * i + j] * Decimal("0.9")).quantize(Decimal("0.0001")))) for i, m in enumerate(MONTHS) for j, d in enumerate((MID[m], END[m]))])
    # World Bank, flattened
    gold = _walk(rng, Decimal("620.0"), len(MONTHS), "0.04")
    _write(raw / "aux" / "wb" / "monthly_prices.csv", ["series", "month", "value"], [("Gold", m, str(gold[i])) for i, m in enumerate(MONTHS)] + [("Copper", m, str(Decimal("7000") + 10 * i) + ".0000") for i, m in enumerate(MONTHS)])


if __name__ == "__main__":
    write_world(Path(sys.argv[1]))
