"""Shared setup for the batch tests: a fake repository layout, a targets file, a fake KIS and fake yfinance."""
from __future__ import annotations

import hashlib
from datetime import date
from pathlib import Path

import pandas as pd

import batch
from batch import FetchTarget, TARGETS_HEADER
from tests.fake_kis import FakeKis, clock_at
from tests.fakes import FakeSession, cfg


def make_repo(tmp_path: Path) -> tuple[Path, Path]:
    repo = tmp_path / "repo"
    (repo / "data").mkdir(parents=True, exist_ok=True)
    return repo, repo / "data" / "raw" / "mvp-04"


def kr(ticker="005930", order=1, kis_from="1995-12", basis="ADJUSTED", yf=None) -> FetchTarget:
    return FetchTarget("KR", ticker, None, basis, batch._month_first(kis_from, "t"), yf, None, order)


def us(ticker="SPY", order=1, kis_from="2007-08", cut="2007-12", excd="AMS", yf=None) -> FetchTarget:
    return FetchTarget("US", ticker, excd, "ADJUSTED", batch._month_first(kis_from, "t"), yf, batch._month_first(cut, "t"), order)


def write_targets(path: Path, rows: list[list[str]]) -> Path:
    path.write_text("\n".join([",".join(TARGETS_HEADER)] + [",".join(r) for r in rows]) + "\n", encoding="utf-8")
    return path


class Env:
    """One test world: repo layout, fake KIS, session, clock."""

    def __init__(self, tmp_path: Path, listings=None, clock=None):
        self.repo, self.raw = make_repo(tmp_path)
        self.fake = FakeKis(listings)
        self.session = FakeSession(self.fake.handler)
        self.clock = clock or clock_at()
        self.cfg = cfg(tmp_path, rps=1)

    def run(self, kind, targets, **kw):
        return batch.run_kis_batch(kind, targets, cfg=self.cfg, session=self.session, raw_dir=self.raw,
                                   repo_root=self.repo, clock=self.clock, **kw)

    def next_day(self, hh=21):
        self.clock.t += 86400 - ((self.clock.t - 0) % 86400) + 0  # move on, then pin to the window below
        from tests.fake_kis import kst_epoch
        from datetime import datetime, timezone, timedelta
        d = datetime.fromtimestamp(self.clock.t, timezone(timedelta(hours=9)))
        self.clock.t = kst_epoch(d.year, d.month, d.day, hh)

    def ledger(self):
        return batch.Ledger(self.raw / "ledger.csv").rows()


def tree_hashes(root: Path, suffix=".json") -> dict[str, str]:
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(Path(root).glob(f"*{suffix}"))}


class YfTicker:
    calls: list[str] = []

    def __init__(self, sym):
        self.sym = sym

    def history(self, start, end, auto_adjust):
        YfTicker.calls.append(self.sym)
        if self.sym.startswith("EMPTY"):
            return pd.DataFrame({"Close": [], "Adj Close": []}, index=pd.to_datetime([]))
        if self.sym.startswith("BOOM"):
            raise RuntimeError("boom")
        return pd.DataFrame({"Close": [10.5, 11.25], "Adj Close": [9.75, 10.5]}, index=pd.to_datetime(["2024-01-31", "2024-02-29"]))
