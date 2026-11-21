"""Proxy map for the MVP history (MVP-03 A): ladder rules, generator and the coverage check.

`db/full/proxy_series.csv` lists the public series, `db/full/proxy_map.csv` gives every pool asset an ordered fallback
list. A month without an actual return takes the lowest-priority series that has that month; a month that no series
covers stops the build. This module owns the rules that write the map and the check that the build runs first.
No network, no database, no price value: only month labels and series names.

  python3 tools/fetch/proxy_map.py check [--strict]    # exit 1 on any error; --strict also fails on pending ETF fees
  python3 tools/fetch/proxy_map.py generate            # rewrite proxy_map.csv and the proxy_index_name column of asset.csv
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import REPO_ROOT  # noqa: E402

FULL = REPO_ROOT / "db" / "full"
START, END = "1996-01", "2025-12"  # warm-up start and last replay month
MAIN_PERIOD_START = "2007-01"  # a stock month from here on is never a proxy month (H7)
FIRST_CLOSE_FLOOR = "1995-12"  # prices are fetched from this month; the first return month is the next one

SERIES_COLUMNS = ["series_id", "source", "source_code", "currency", "basis", "observation", "first_month", "last_month",
                  "formula", "terms_url", "terms_checked_on"]
MAP_COLUMNS = ["market", "ticker", "priority", "series_id", "to_krw", "fee_applied", "annual_fee", "fee_source"]
ASSET_COLUMNS = ["market", "ticker", "name", "asset_type", "currency", "group_code", "listed_on", "return_basis", "proxy_index_name"]
SOURCES = {"YF", "FRED", "ECOS", "WB", "ASSET", "DERIVED"}
BASES = {"PRICE", "TOTAL", "RATE"}  # RATE = a yield that only feeds a DERIVED row; the map may not name it
OBSERVATIONS = {"MONTH_END", "MONTH_AVG"}
FEE_SOURCES = {"MACRO_ETF_3_2", "PENDING"}
FORMULAS = {"PAR_BOND_TR": 2, "CASH_ACCRUAL": 1, "MIX_50_50": 2, "MIX_50_50_FX": 2, "MIX_W": 3}  # name -> number of arguments (the maturity counts)

_MONTH = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_FORMULA = re.compile(r"^([A-Z0-9_]+)\(([A-Za-z0-9_,]*)\)$")


def next_month(m: str) -> str:
    y, mo = int(m[:4]), int(m[5:7])
    return f"{y + (mo == 12)}-{1 if mo == 12 else mo + 1:02d}"


def months(a: str, b: str) -> list[str]:
    out, m = [], a
    while m <= b:
        out.append(m)
        m = next_month(m)
    return out


# -- ladder rules --------------------------------------------------------------------------------------------
# Fee ratios (annual) are the funetf values of docs/MACRO_ETF.md section 3.2 (2026-10-30). ETFs outside that table stay PENDING:
# no issuer page was read for them, and a fee is never typed from memory.
MACRO_ETF_FEE = {
    "069500": "0.0015", "360750": "0.000068", "133690": "0.000068", "458730": "0.0001", "476850": "0.005",
    "426030": "0.008", "0019K0": "0.0025", "476760": "0.0005", "148070": "0.0005", "114260": "0.0015",
    "329750": "0.003", "459580": "0.0002", "411060": "0.0019", "160580": "0.0083", "261220": "0.0035",
}
KR_ETF_LADDER = {
    "069500": ["KR_KOSPI"],
    "091160": ["KR_KOSPI"],
    "360750": ["US_SPX_PRICE"],
    "133690": ["US_NDX_PRICE"],
    "426030": ["US_NDX_PRICE"],
    "458730": ["US_SPX_PRICE"],
    "476850": ["KR_KOSPI"],
    "0019K0": ["MIX_NDX_KRW_CD91_50"],
    "476760": ["US_UST20Y_TR"],
    "148070": ["KR_KTB10Y_TR", "KR_KTB10Y_FROM_KTB5Y_M_TR"],
    "114260": ["KR_KTB3Y_TR", "KR_KTB3Y_M_TR"],
    "329750": ["US_TBILL3M_ACCRUAL"],
    "459580": ["KR_CD91_ACCRUAL"],
    "411060": ["WB_GOLD_USD"],
    "160580": ["WB_COPPER_USD"],
    "261220": ["YF_USO_PRICE", "WB_WTI_USD"],
}
US_ETF_LADDER = {
    "SPY": ["US_SPX_PRICE"], "QQQ": ["US_NDX_PRICE"], "TLT": ["US_UST20Y_TR"], "IEF": ["US_UST10Y_TR"], "SHY": ["US_UST2Y_TR"],
    "GLD": ["WB_GOLD_USD"], "IWM": ["US_RUT_PRICE"], "EFA": ["EAFE_PROXY_70_30_TR"], "EEM": ["YF_VEIEX_TR"],
    "XLE": ["ASSET_US_SPY"], "XLF": ["ASSET_US_SPY"], "XLV": ["ASSET_US_SPY"], "XLP": ["ASSET_US_SPY"], "XLK": ["US_NDX_PRICE"],
}
US_TECH_GROUPS = {"TECH", "SEMICONDUCTOR"}

PROXY_NAME = {  # generic display names (no proper nouns); "(원화 환산)" is added when the series is converted to won
    "KR_KOSPI": "한국 대형주 지수", "US_SPX_PRICE": "미국 대형주 지수", "ASSET_US_SPY": "미국 대형주 지수",
    "US_NDX_PRICE": "미국 기술·성장주 지수", "ASSET_US_QQQ": "미국 기술·성장주 지수", "US_RUT_PRICE": "미국 소형주 지수",
    "US_UST20Y_TR": "미국 장기 국채", "US_UST10Y_TR": "미국 중기 국채", "US_UST2Y_TR": "미국 단기 국채",
    "US_TBILL3M_ACCRUAL": "미국 단기 금리", "KR_KTB3Y_TR": "한국 국채 3년", "KR_KTB10Y_TR": "한국 국채 10년",
    "KR_CD91_ACCRUAL": "한국 단기 금리", "WB_GOLD_USD": "금 가격", "WB_COPPER_USD": "구리 가격", "WB_WTI_USD": "원유 가격",
    "MIX_NDX_KRW_CD91_50": "미국 기술주·국내 단기 금리 혼합",
    "YF_VEIEX_TR": "신흥국 주식 지수", "EAFE_PROXY_70_30_TR": "선진국(미국 제외) 주식 지수", "YF_USO_PRICE": "원유 선물 가격",
}


def hedged(name: str) -> bool:
    return "(H)" in name


@dataclass(frozen=True)
class Asset:
    market: str
    ticker: str
    name: str
    asset_type: str
    currency: str
    group_code: str
    listed_on: str
    return_basis: str
    kis_first_month: str
    yf_first_month: str

    @property
    def key(self) -> tuple[str, str]:
        return (self.market, self.ticker)

    def first_close(self) -> tuple[str, str]:
        """(first month with a month-end close, how it is known). SWEEP and YF are measured; LISTING is derived from the
        listing date and waits for the file that measures it (yfinance run for US ETFs and pending US stocks)."""
        listed = self.listed_on[:7]
        if self.market == "KR":
            month, how = (self.kis_first_month, "SWEEP") if self.kis_first_month else (listed, "LISTING")
        elif self.asset_type == "STOCK" and self.yf_first_month:
            month, how = self.yf_first_month, "YF"
        else:
            month, how = listed, "LISTING"
        return max(month, FIRST_CLOSE_FLOOR), how

    def first_return(self) -> str:
        return next_month(self.first_close()[0])


def load_assets(full: Path = FULL) -> list[Asset]:
    """The 180 SELECTED rows of the pool: asset.csv columns plus the measured first months of pool_proposal.csv."""
    with (full / "pool_proposal.csv").open(encoding="utf-8", newline="") as h:
        pool = {(r["market"], r["ticker"]): r for r in csv.DictReader(h) if r["status"] == "SELECTED"}
    with (full / "asset.csv").open(encoding="utf-8", newline="") as h:
        rd = csv.DictReader(h)
        if rd.fieldnames != ASSET_COLUMNS:
            raise ValueError("asset.csv: unexpected columns")
        rows = list(rd)
    if {(r["market"], r["ticker"]) for r in rows} != set(pool):
        raise ValueError("asset.csv and the SELECTED rows of pool_proposal.csv name different assets")
    return [Asset(r["market"], r["ticker"], r["name"], r["asset_type"], r["currency"], r["group_code"], r["listed_on"], r["return_basis"],
                  pool[(r["market"], r["ticker"])]["kis_first_month"], pool[(r["market"], r["ticker"])]["yf_first_month"]) for r in rows]


def ladder(a: Asset) -> list[str]:
    if a.asset_type == "STOCK":
        if a.market == "KR":
            return ["KR_KOSPI"]
        return ["ASSET_US_QQQ", "US_NDX_PRICE"] if a.group_code in US_TECH_GROUPS else ["ASSET_US_SPY"]
    table = KR_ETF_LADDER if a.market == "KR" else US_ETF_LADDER
    if a.ticker not in table:
        raise ValueError(f"no ladder rule for ETF {a.market}:{a.ticker}; add it to the rule table and to the README")
    return table[a.ticker]


def read_csv(path: Path, columns: list[str]) -> list[dict[str, str]]:
    with Path(path).open(encoding="utf-8", newline="") as h:
        rd = csv.DictReader(h)
        if rd.fieldnames != columns:
            raise ValueError(f"{Path(path).name}: columns must be {','.join(columns)}")
        rows = list(rd)
    if any(None in r or None in r.values() for r in rows):
        raise ValueError(f"{Path(path).name}: a row has the wrong number of fields")
    return rows


def series_by_id(series: list[dict[str, str]]) -> dict[str, dict[str, str]]:
    return {s["series_id"]: s for s in series}


def build_map(assets: list[Asset], series: list[dict[str, str]]) -> list[dict[str, str]]:
    """Map rows for every asset: ladder order, `to_krw` where a USD series fills a won asset (not for a hedged name), fee for ETFs."""
    sid = series_by_id(series)
    out = []
    for a in sorted(assets, key=lambda x: (x.market, x.ticker.encode())):
        for i, s in enumerate(ladder(a), start=1):
            if s not in sid:
                raise ValueError(f"{a.key}: unknown series {s}")
            krw = a.currency == "KRW" and sid[s]["currency"] == "USD" and not hedged(a.name)
            etf = a.asset_type == "ETF"
            fee = MACRO_ETF_FEE.get(a.ticker, "") if etf and a.market == "KR" else ""
            out.append({"market": a.market, "ticker": a.ticker, "priority": str(i), "series_id": s, "to_krw": "Y" if krw else "N",
                        "fee_applied": "Y" if etf else "N", "annual_fee": fee,
                        "fee_source": ("MACRO_ETF_3_2" if fee else "PENDING") if etf else ""})
    return out


def proxy_name(a: Asset, rows: list[dict[str, str]]) -> str:
    first = next(r for r in rows if r["priority"] == "1")
    name = PROXY_NAME[first["series_id"]]
    return name + "(원화 환산)" if first["to_krw"] == "Y" else name


def write_csv(path: Path, columns: list[str], rows: list[dict[str, str]]) -> None:
    with Path(path).open("w", encoding="utf-8", newline="") as h:
        w = csv.DictWriter(h, fieldnames=columns, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def generate(full: Path = FULL) -> None:
    assets = load_assets(full)
    series = read_csv(full / "proxy_series.csv", SERIES_COLUMNS)
    rows = build_map(assets, series)
    write_csv(full / "proxy_map.csv", MAP_COLUMNS, rows)
    by_asset: dict[tuple[str, str], list[dict[str, str]]] = {}
    for r in rows:
        by_asset.setdefault((r["market"], r["ticker"]), []).append(r)
    proxied = run_check(full).proxy_months  # a name is exposed only for an asset that has at least one proxy month (plan B2, erd 5.3)
    cur = read_csv(full / "asset.csv", ASSET_COLUMNS)
    for r in cur:
        a = next(x for x in assets if x.key == (r["market"], r["ticker"]))
        r["proxy_index_name"] = proxy_name(a, by_asset[a.key]) if proxied.get(a.key) else ""
    write_csv(full / "asset.csv", ASSET_COLUMNS, cur)


# -- the check -----------------------------------------------------------------------------------------------
@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    uncovered: list[tuple[str, str, str]] = field(default_factory=list)  # (market, ticker, month)
    proxy_months: dict[tuple[str, str], int] = field(default_factory=dict)  # per asset
    by_series: dict[str, int] = field(default_factory=dict)  # proxy months filled per series (first rung that has the month wins)
    basis_mismatch: dict[tuple[str, str], list[str]] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors


def _dec(text: str) -> Decimal | None:
    try:
        d = Decimal(text)
    except InvalidOperation:
        return None
    return d if d.is_finite() else None


def check_series(series: list[dict[str, str]], assets: list[Asset], rep: Report) -> None:
    ids = [s["series_id"] for s in series]
    if len(ids) != len(set(ids)):
        rep.errors.append("proxy_series: duplicate series_id")
    sid = series_by_id(series)
    by_key = {a.key: a for a in assets}
    for s in series:
        i = s["series_id"]
        if not re.fullmatch(r"[A-Z0-9_]{3,40}", i):
            rep.errors.append(f"series {i}: id shape")
        if s["source"] not in SOURCES:
            rep.errors.append(f"series {i}: source {s['source']!r}")
        if s["currency"] not in ("KRW", "USD"):
            rep.errors.append(f"series {i}: currency {s['currency']!r}")
        if s["basis"] not in BASES:
            rep.errors.append(f"series {i}: basis {s['basis']!r}")
        if s["observation"] not in OBSERVATIONS:
            rep.errors.append(f"series {i}: observation {s['observation']!r}")
        if not (_MONTH.fullmatch(s["first_month"]) and _MONTH.fullmatch(s["last_month"])) or s["first_month"] > s["last_month"]:
            rep.errors.append(f"series {i}: first_month/last_month")
            continue
        external = s["source"] in ("YF", "FRED", "ECOS", "WB")
        if external:
            if not s["source_code"] or not s["terms_url"].startswith("https://") or not _DATE.fullmatch(s["terms_checked_on"]):
                rep.errors.append(f"series {i}: an external series needs source_code, terms_url (https) and terms_checked_on")
        elif s["terms_url"] or s["terms_checked_on"]:
            rep.errors.append(f"series {i}: ASSET and DERIVED rows carry no terms (their inputs do)")
        if (s["source"] == "DERIVED") != bool(s["formula"]):
            rep.errors.append(f"series {i}: formula is required for DERIVED rows and only for them")
        if s["source"] == "DERIVED":
            _check_derived(s, sid, rep)
        if s["source"] == "ASSET":
            m = re.fullmatch(r"(KR|US):([A-Z0-9.]+)", s["source_code"])
            a = by_key.get((m.group(1), m.group(2))) if m else None
            if a is None:
                rep.errors.append(f"series {i}: source_code must name a pool asset (MARKET:TICKER)")
            else:
                if s["first_month"] != max(a.first_return(), START) or s["last_month"] != END:
                    rep.errors.append(f"series {i}: ASSET first_month must be the asset's first actual return month {a.first_return()} and last {END}")
                if (s["currency"], s["basis"]) != (a.currency, a.return_basis):
                    rep.errors.append(f"series {i}: ASSET currency/basis differ from the asset")


def _check_derived(s: dict[str, str], sid: dict[str, dict[str, str]], rep: Report) -> None:
    i = s["series_id"]
    m = _FORMULA.fullmatch(s["formula"])
    if not m or m.group(1) not in FORMULAS:
        rep.errors.append(f"series {i}: unknown formula {s['formula']!r}")
        return
    args = m.group(2).split(",") if m.group(2) else []
    if len(args) != FORMULAS[m.group(1)]:
        rep.errors.append(f"series {i}: {m.group(1)} takes {FORMULAS[m.group(1)]} arguments")
        return
    inputs = [a for a in args if not a.isdigit()]
    if m.group(1) == "PAR_BOND_TR" and not (args[1].isdigit() and 1 <= int(args[1]) <= 30):
        rep.errors.append(f"series {i}: PAR_BOND_TR needs a maturity of 1-30 years")
    if m.group(1) == "MIX_W" and not (args[2].isdigit() and 1 <= int(args[2]) <= 99):
        rep.errors.append(f"series {i}: MIX_W needs a whole percent weight of 1-99 as its third argument")
    missing = [a for a in inputs if a not in sid]
    if missing:
        rep.errors.append(f"series {i}: unknown input {missing}")
        return
    kind = m.group(1)
    if kind in ("PAR_BOND_TR", "CASH_ACCRUAL"):
        src = sid[inputs[0]]
        if src["basis"] != "RATE":
            rep.errors.append(f"series {i}: {kind} reads a RATE series")
        earliest = max(next_month(src["first_month"]), START)
    else:
        if any(sid[a]["basis"] == "RATE" for a in inputs):
            rep.errors.append(f"series {i}: {kind} mixes return series, not yields")
        if kind == "MIX_50_50_FX" and [sid[a]["currency"] for a in inputs] != ["USD", "KRW"]:
            rep.errors.append(f"series {i}: MIX_50_50_FX takes a USD series first and a KRW series second")
        earliest = max(sid[a]["first_month"] for a in inputs)
    if s["first_month"] < earliest:
        rep.errors.append(f"series {i}: first_month {s['first_month']} is before its inputs allow ({earliest})")
    if s["last_month"] > min(sid[a]["last_month"] for a in inputs):
        rep.errors.append(f"series {i}: last_month is after an input's last month")
    cur = {sid[a]["currency"] for a in inputs}
    if kind == "MIX_50_50_FX":
        if s["currency"] != "KRW":
            rep.errors.append(f"series {i}: MIX_50_50_FX gives a won series")
    elif cur != {s["currency"]}:
        rep.errors.append(f"series {i}: a derived series has the currency of its inputs")


def check_map(mapping: list[dict[str, str]], series: list[dict[str, str]], assets: list[Asset], rep: Report, strict: bool) -> None:
    sid = series_by_id(series)
    by_key = {a.key: a for a in assets}
    per: dict[tuple[str, str], list[dict[str, str]]] = {}
    for r in mapping:
        k = (r["market"], r["ticker"])
        if k not in by_key:
            rep.errors.append(f"map: {k} is not a pool asset")
            continue
        per.setdefault(k, []).append(r)
    for a in assets:
        rows = per.get(a.key, [])
        if not rows:
            rep.errors.append(f"map: {a.key} has no ladder")
            continue
        try:
            prio = [int(r["priority"]) for r in rows]
        except ValueError:
            rep.errors.append(f"map: {a.key} priority is not an integer")
            continue
        if sorted(prio) != list(range(1, len(rows) + 1)):
            rep.errors.append(f"map: {a.key} priorities must be 1..{len(rows)} without gaps or repeats")
        if len({r["series_id"] for r in rows}) != len(rows):
            rep.errors.append(f"map: {a.key} names a series twice")
        for r in rows:
            s = sid.get(r["series_id"])
            if s is None:
                rep.errors.append(f"map: {a.key} names unknown series {r['series_id']}")
                continue
            if s["basis"] == "RATE":
                rep.errors.append(f"map: {a.key} names a yield input ({r['series_id']}); name its derived series")
            if s["source"] == "ASSET" and s["source_code"] == f"{a.market}:{a.ticker}":
                rep.errors.append(f"map: {a.key} uses its own returns as proxy")
            if r["to_krw"] not in ("Y", "N") or r["fee_applied"] not in ("Y", "N"):
                rep.errors.append(f"map: {a.key} to_krw and fee_applied are Y or N")
            wants = a.currency == "KRW" and s["currency"] == "USD" and not hedged(a.name)
            if a.currency == "USD" and s["currency"] == "KRW":
                rep.errors.append(f"map: {a.key} cannot take a won series")
            elif r["to_krw"] != ("Y" if wants else "N"):
                rep.errors.append(f"map: {a.key} to_krw must be {'Y' if wants else 'N'} for {r['series_id']}")
            if r["fee_applied"] != ("Y" if a.asset_type == "ETF" else "N"):
                rep.errors.append(f"map: {a.key} fee_applied is Y exactly for ETFs")
            if a.asset_type == "ETF":
                fee = _dec(r["annual_fee"]) if r["annual_fee"] else None
                if r["fee_source"] not in FEE_SOURCES:
                    rep.errors.append(f"map: {a.key} fee_source")
                elif (r["fee_source"] == "PENDING") != (not r["annual_fee"]):
                    rep.errors.append(f"map: {a.key} annual_fee is blank exactly when fee_source is PENDING")
                elif r["annual_fee"] and (fee is None or not Decimal(0) < fee < Decimal("0.05")):
                    rep.errors.append(f"map: {a.key} annual_fee must be a ratio between 0 and 0.05")
            elif r["annual_fee"] or r["fee_source"]:
                rep.errors.append(f"map: {a.key} a stock has no fee")
            if s["basis"] in ("PRICE", "TOTAL") and s["basis"] != a.return_basis:
                rep.basis_mismatch.setdefault(a.key, []).append(r["series_id"])


def check_coverage(mapping: list[dict[str, str]], series: list[dict[str, str]], assets: list[Asset], rep: Report, strict: bool) -> None:
    sid = series_by_id(series)
    for a in assets:
        rows = sorted((r for r in mapping if (r["market"], r["ticker"]) == a.key and r["series_id"] in sid and r["priority"].isdigit()),
                      key=lambda r: int(r["priority"]))
        first = a.first_return()
        gap = [m for m in months(START, END) if m < first]
        if a.asset_type == "STOCK" and first > MAIN_PERIOD_START:
            rep.errors.append(f"pool: stock {a.key} has no actual return in {MAIN_PERIOD_START}..{first} (main period must be actual)")
        if a.asset_type == "STOCK" and any(m >= MAIN_PERIOD_START for m in gap):
            continue
        rep.proxy_months[a.key] = len(gap)
        for m in gap:
            hit = next((r for r in rows if sid[r["series_id"]]["first_month"] <= m <= sid[r["series_id"]]["last_month"]), None)
            if hit is None:
                rep.uncovered.append((a.market, a.ticker, m))
            else:
                rep.by_series[hit["series_id"]] = rep.by_series.get(hit["series_id"], 0) + 1
                if hit["fee_applied"] == "Y" and not hit["annual_fee"]:
                    msg = f"fee pending: {a.key} has proxy months but no annual_fee (read the issuer page, see README)"
                    if msg not in rep.warnings and msg not in rep.errors:
                        (rep.errors if strict else rep.warnings).append(msg)
    if rep.uncovered:
        by_asset: dict[tuple[str, str], list[str]] = {}
        for mk, tk, m in rep.uncovered:
            by_asset.setdefault((mk, tk), []).append(m)
        for k, ms in sorted(by_asset.items()):
            rep.errors.append(f"uncovered: {k} {ms[0]}..{ms[-1]} ({len(ms)} months) has no proxy series")


def run_check(full: Path = FULL, strict: bool = False) -> Report:
    rep = Report()
    assets = load_assets(full)
    series = read_csv(full / "proxy_series.csv", SERIES_COLUMNS)
    mapping = read_csv(full / "proxy_map.csv", MAP_COLUMNS)
    check_series(series, assets, rep)
    check_map(mapping, series, assets, rep, strict)
    check_coverage(mapping, series, assets, rep, strict)
    return rep


def summary(rep: Report, full: Path = FULL) -> list[str]:
    assets = load_assets(full)
    by_group: dict[str, list[int]] = {}
    for a in assets:
        n = rep.proxy_months.get(a.key)
        if n:
            by_group.setdefault(f"{a.market} {a.asset_type}", []).append(n)
    lines = [f"assets {len(assets)}; with proxy months {sum(len(v) for v in by_group.values())}; proxy cells {sum(rep.proxy_months.values())} of {len(assets) * len(months(START, END))}"]
    for g, v in sorted(by_group.items()):
        lines.append(f"  {g}: {len(v)} assets, {sum(v)} cells, max {max(v)} months")
    for s, n in sorted(rep.by_series.items(), key=lambda kv: -kv[1]):
        lines.append(f"  series {s}: {n} cells")
    lines.append(f"uncovered cells: {len(rep.uncovered)}; basis mismatches: {len(rep.basis_mismatch)} assets")
    return lines


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check")
    c.add_argument("--strict", action="store_true", help="fail on pending ETF fees (the build gate)")
    c.add_argument("--full", default=str(FULL))
    g = sub.add_parser("generate")
    g.add_argument("--full", default=str(FULL))
    args = ap.parse_args(argv)
    if args.cmd == "generate":
        generate(Path(args.full))
        return 0
    rep = run_check(Path(args.full), args.strict)
    print("\n".join(summary(rep, Path(args.full))))
    for w in rep.warnings:
        print(f"warning: {w}")
    for e in rep.errors:
        print(f"error: {e}", file=sys.stderr)
    return 0 if rep.ok else 1


if __name__ == "__main__":
    sys.exit(main())
