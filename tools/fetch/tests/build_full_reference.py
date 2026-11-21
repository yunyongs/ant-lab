"""Independent Python computation of the build_full.sql outputs from the same input files (MVP-06 A parity test).

It reads the map files and the raw cache the way the SQL does, but computes every value with `Decimal` and the reference
formulas of `tools/fetch/derived.py`, so a disagreement points at one of the two. Rounding once to 8 decimals, half away from zero.
"""
from __future__ import annotations

import csv
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import derived
from proxy_map import months as month_range

D = Decimal
R8 = D("0.00000001")


def prev_month(m: str) -> str:
    y, mo = int(m[:4]), int(m[5:7])
    return f"{y - (mo == 1)}-{12 if mo == 1 else mo - 1:02d}"


def _rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as h:
        return list(csv.DictReader(h))


def _month_end(rows: list[tuple[str, D]]) -> dict[str, D]:
    best: dict[str, tuple[str, D]] = {}
    for d, v in rows:
        m = d[:7]
        if m not in best or d > best[m][0]:
            best[m] = (d, v)
    return {m: v for m, (d, v) in best.items()}


def compute(mapdir: Path, rawdir: Path, first: str, last: str, yf_window: str = "19951201_20260102") -> dict[tuple[str, str, str], tuple[D, str, str]]:
    """{(market, ticker, month): (return unrounded, kind, series_id)}"""
    assets = _rows(mapdir / "asset.csv")
    targets = {(r["market"], r["ticker"]): r for r in _rows(mapdir / "fetch_targets.csv")}
    series = {r["series_id"]: r for r in _rows(mapdir / "proxy_series.csv")}
    ladder = defaultdict(list)
    for r in _rows(mapdir / "proxy_map.csv"):
        ladder[(r["market"], r["ticker"])].append(r)
    months = month_range(first, last)

    fxo = [(r["observation_date"], D(r["DEXKOUS"])) for r in _rows(rawdir / "aux" / "fred" / "DEXKOUS.csv") if r["DEXKOUS"] not in (".", "")]
    fxme = _month_end(fxo)
    fx = {m: fxme[m] / fxme[prev_month(m)] - 1 for m in months if m in fxme and prev_month(m) in fxme}

    daily = defaultdict(list)
    for r in _rows(rawdir / "norm" / "daily.csv"):
        daily[(r["market"], r["ticker"], r["source"])].append((r["trade_date"], D(r["close"])))
    me = {k: _month_end(v) for k, v in daily.items()}

    def src(a: dict[str, str], m: str) -> str:
        cut = targets[(a["market"], a["ticker"])]["yf_canonical_to"]
        return "YF" if a["market"] == "US" and cut and m <= cut else "KIS"

    actual: dict[tuple[str, str, str], D] = {}
    for a in assets:
        for m in months:
            cl = me.get((a["market"], a["ticker"], src(a, m)), {})
            if m in cl and prev_month(m) in cl:
                actual[(a["market"], a["ticker"], m)] = cl[m] / cl[prev_month(m)] - 1

    levels: dict[str, dict[str, D]] = {}
    for s in series.values():
        sid, code = s["series_id"], s["source_code"]
        if s["source"] == "YF":
            rows = _rows(rawdir / "yf" / f"yf_px_{code}_{yf_window}.csv")
            col = "adj_close" if s["basis"] == "TOTAL" else "close"
            levels[sid] = _month_end([(r["trade_date"], D(r[col])) for r in rows])
        elif s["source"] == "FRED":
            levels[sid] = _month_end([(r["observation_date"], D(r[code])) for r in _rows(rawdir / "aux" / "fred" / f"{code}.csv") if r[code] not in (".", "")])
        elif s["source"] == "ECOS":
            stat, item = code.split("/")
            if s["observation"] == "MONTH_AVG":
                f = rawdir / "aux" / "ecos" / f"{stat}_{item}_M.csv"
                levels[sid] = {r["time"][:4] + "-" + r["time"][4:]: D(r["value"]) for r in _rows(f)}
            else:
                f = rawdir / "aux" / "ecos" / f"{stat}_{item}_D.csv"
                levels[sid] = _month_end([(r["time"][:4] + "-" + r["time"][4:6] + "-" + r["time"][6:], D(r["value"])) for r in _rows(f)])
        elif s["source"] == "WB":
            levels[sid] = {r["month"]: D(r["value"]) for r in _rows(rawdir / "aux" / "wb" / "monthly_prices.csv") if r["series"] == code}

    rets: dict[str, dict[str, D]] = {}
    for sid, s in series.items():
        if s["source"] in ("YF", "FRED", "ECOS", "WB") and s["basis"] != "RATE":
            lv = levels[sid]
            rets[sid] = {m: derived.level_return(lv[prev_month(m)], lv[m]) for m in months if m in lv and prev_month(m) in lv}
        elif s["source"] == "ASSET":
            mk, tk = s["source_code"].split(":")
            rets[sid] = {m: r for (a_mk, a_tk, m), r in actual.items() if (a_mk, a_tk) == (mk, tk)}

    import re

    todo = [s for s in series.values() if s["source"] == "DERIVED"]
    while todo:
        s = todo.pop(0)
        name, args = re.fullmatch(r"([A-Z0-9_]+)\((.*)\)", s["formula"]).groups()
        args = args.split(",")
        if name in ("PAR_BOND_TR", "CASH_ACCRUAL"):
            lv = levels[args[0]]
            ms = [m for m in months if m in lv and prev_month(m) in lv]
            if name == "PAR_BOND_TR":
                rets[s["series_id"]] = {m: derived.par_bond_return(lv[prev_month(m)] / 100, lv[m] / 100, int(args[1])) for m in ms}
            else:
                rets[s["series_id"]] = {m: derived.cash_accrual(lv[prev_month(m)] / 100) for m in ms}
            continue
        if not all(a in rets for a in args[:2]):
            todo.append(s)
            continue
        a, b = rets[args[0]], rets[args[1]]
        common = [m for m in months if m in a and m in b]
        if name == "MIX_50_50":
            rets[s["series_id"]] = {m: derived.mix_50_50(a[m], b[m]) for m in common}
        elif name == "MIX_W":
            rets[s["series_id"]] = {m: derived.mix_weighted(a[m], b[m], int(args[2])) for m in common}
        else:
            rets[s["series_id"]] = {m: derived.mix_50_50_fx(a[m], b[m], fx[m]) for m in common if m in fx}

    out: dict[tuple[str, str, str], tuple[D, str, str]] = {}
    for a in assets:
        key = (a["market"], a["ticker"])
        for m in months:
            if (*key, m) in actual:
                out[(*key, m)] = (actual[(*key, m)], "ACTUAL", "")
                continue
            for row in sorted(ladder[key], key=lambda r: int(r["priority"])):
                sr = series[row["series_id"]]
                if sr["first_month"] <= m <= sr["last_month"]:
                    r = rets[row["series_id"]][m]  # a KeyError here is a gap: the SQL stops too
                    if row["to_krw"] == "Y":
                        r = derived.to_krw(r, fx[m])
                    if row["fee_applied"] == "Y":
                        r = derived.etf_proxy_return(r, D(row["annual_fee"]))
                    out[(*key, m)] = (r, "PROXY_INDEX", row["series_id"])
                    break
    return out


def fx_changes(rawdir: Path, months: list[str]) -> dict[str, D]:
    fxo = [(r["observation_date"], D(r["DEXKOUS"])) for r in _rows(rawdir / "aux" / "fred" / "DEXKOUS.csv") if r["DEXKOUS"] not in (".", "")]
    me = _month_end(fxo)
    return {m: me[m] / me[prev_month(m)] - 1 for m in months}


def r8(x: D) -> str:
    return str(x.quantize(R8, rounding=ROUND_HALF_UP))
