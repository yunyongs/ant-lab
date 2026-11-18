"""Helpers for the 04-B tests: synthetic month-end series, and a fake world fetched through the real batches."""
from __future__ import annotations

import shutil
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

import batch
from batch import FetchTarget, FULL_START
from tests.batch_helpers import Env, kr, us

BASE = date(2001, 1, 2)  # report months end 2000-12


def last_weekday(m: str) -> str:
    y, mo = int(m[:4]), int(m[5:7])
    d = date(y + (mo == 12), mo % 12 + 1, 1) - timedelta(days=1)
    while d.weekday() >= 5:
        d -= timedelta(days=1)
    return d.isoformat()


def month_list(first: str, n: int) -> list[str]:
    out, y, mo = [], int(first[:4]), int(first[5:7])
    for _ in range(n):
        out.append(f"{y}-{mo:02d}")
        y, mo = (y + 1, 1) if mo == 12 else (y, mo + 1)
    return out


def mrows(first: str, closes: list) -> list[tuple[str, str]]:
    """One month-end row per month from `first`."""
    return [(last_weekday(m), str(c)) for m, c in zip(month_list(first, len(closes)), closes)]


def yrows(first: str, closes: list, adj: list | None = None) -> list[tuple[str, str, str]]:
    adj = adj or closes
    return [(d, c, str(a)) for (d, c), a in zip(mrows(first, closes), adj)]


def ft_kr(ticker="005930", kis_from="2000-01", yf="005930.KS", order=1, basis="ADJUSTED") -> FetchTarget:
    return FetchTarget("KR", ticker, None, basis, batch._month_first(kis_from, "t"), yf, None, order)


def ft_us(ticker="SPY", kis_from="2000-01", cut=None, yf="SPY", order=1) -> FetchTarget:
    return FetchTarget("US", ticker, "AMS", "ADJUSTED", batch._month_first(kis_from, "t") if kis_from else None, yf,
                       batch._month_first(cut, "t") if cut else None, order)


def pct(base: Decimal | int, *rets: str) -> list[str]:
    """Closes that follow the given returns, e.g. pct(100, "0.01", "0.02")."""
    out, c = [str(base)], Decimal(base)
    for r in rets:
        c = c * (1 + Decimal(r))
        out.append(format(c, "f"))
    return out


def weekdays(a: date, b: date):
    d = a
    while d <= b:
        if d.weekday() < 5:
            yield d
        d += timedelta(days=1)


def write_yf(raw: Path, symbol: str, overrides: dict[str, tuple[str, str]] | None = None, base: date = date(2026, 1, 2),
             drop: set[str] | None = None, first: date = FULL_START) -> Path:
    """A flat yfinance price file (Close = Adj Close = 100) for the whole fetch range, with per-date overrides."""
    overrides, drop = overrides or {}, drop or set()
    f = raw / "yf" / f"yf_px_{symbol}_{FULL_START:%Y%m%d}_{base:%Y%m%d}.csv"
    f.parent.mkdir(parents=True, exist_ok=True)
    lines = ["trade_date,close,adj_close"]
    for d in weekdays(first, base):
        iso = d.isoformat()
        if iso in drop:
            continue
        c, a = overrides.get(iso, ("100", "100"))
        lines.append(f"{iso},{c},{a}")
    f.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return f


class World:
    """A fetched fake world: a KR stock and a US ETF swept, dripped and with yfinance files, in a repo layout under tmp_path."""

    def __init__(self, root: Path):
        self.env = Env(root)
        self.repo, self.raw = self.env.repo, self.env.raw
        self.kr = kr("005930", 1, "1995-12", yf="005930.KS")
        self.us = us("SPY", 2, "2007-08", "2007-12", "AMS", yf="SPY")
        self.targets = [self.kr, self.us]
        for kind in ("sweep", "drip"):
            r = self.env.run(kind, self.targets)
            assert r.stop_reason == "DONE", r
            self.env.next_day()
        write_yf(self.raw, "005930.KS")
        write_yf(self.raw, "SPY")


def clone_world(src: World, dest: Path) -> tuple[Path, Path]:
    """Copy the raw cache into a fresh repo so a test can change files freely. Returns (repo, raw)."""
    repo = dest / "repo"
    (repo / "data").mkdir(parents=True, exist_ok=True)
    raw = repo / "data" / "raw" / "mvp-04"
    shutil.copytree(src.raw, raw)
    return repo, raw
