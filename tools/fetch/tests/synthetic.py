"""합성(가짜) norm 파일 생성기 — 파이프라인 기계 동작 시험 전용.

고정 시드 임의 보행이다. 실제 가격이 아니며 source 열은 SYN 이다. 만든 파일로 build_sample.sql 을 돌릴 때는
-v allow_synthetic=1 이 있어야 하고, 만든 CSV는 실제 DB 에 적재하지 않는다. float 을 쓰지 않는다 (정수 보행 → Decimal).

사용 (저장소 루트):  tools/fetch/.venv/bin/python tools/fetch/tests/synthetic.py data/raw/p0-08/norm
"""
from __future__ import annotations

import csv
import random
import sys
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline import BASE_DATE, NORM_DAILY_HEADER, load_targets  # noqa: E402

SEED = 20260107


def _weekdays(a: date, b: date):
    d = a
    while d <= b:
        if d.weekday() < 5:
            yield d
        d += timedelta(days=1)


def make(out_dir: Path, asset_csv: Path) -> None:
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)
    daily, divs, shares = [], [], []
    targets = load_targets(asset_csv)
    for tg in targets:
        level = 10000 + rng.randrange(0, 90000)  # 1/100 단위 정수 가격
        for seg in tg.segments:
            a, b = seg.start, seg.end
            level = max(level, 1000)
            for d in _weekdays(a, b):
                level = max(500, level + rng.randrange(-250, 251) * level // 10000)  # 일 ±2.5% 이내의 정수 보행
                close = Decimal(level).scaleb(-2)
                raw = format(close, "f") if d == BASE_DATE and tg.ticker != "GLD" else ""
                price = format(close, "f") if seg.source == "YF" else ""  # 미국 구간 ① 은 가격 열도 채운다 (대체 수익률 기초)
                daily.append([tg.market, tg.ticker, d.isoformat(), format(close, "f"), price, raw, "SYN"])
        if tg.market == "US" and tg.ticker not in ("GLD", "QQQ", "NVDA"):
            for y in range(2007, 2012):
                for mo in (2, 5, 8, 11):
                    divs.append(["US", tg.ticker, f"{y}-{mo:02d}-{9 + rng.randrange(0, 10):02d}", format(Decimal(20 + rng.randrange(0, 60)).scaleb(-2), "f"), "SYN"])
        if (tg.market, tg.ticker) not in {("US", "GLD")}:
            shares.append([tg.market, tg.ticker, "2025-12-31", str(1_000_000 + rng.randrange(0, 9_000_000)), "SYN"])
    # 순위 후보(표본 밖) 시장당 2종목: 일별 기준일 행만
    for market, tick in (("KR", "999001"), ("KR", "999002"), ("US", "ZZZA"), ("US", "ZZZB")):
        close = Decimal(5000 + rng.randrange(0, 5000)).scaleb(-2)
        daily.append([market, tick, BASE_DATE.isoformat(), format(close, "f"), "", format(close, "f"), "SYN"])
        shares.append([market, tick, "2025-12-31", str(5_000_000 + rng.randrange(0, 5_000_000)), "SYN"])
    for name, header, rows in (
        ("daily.csv", NORM_DAILY_HEADER, daily),
        ("dividends.csv", ["market", "ticker", "ex_date", "amount", "source"], divs),
        ("shares.csv", ["market", "ticker", "as_of", "shares", "source"], shares),
    ):
        with (out_dir / name).open("w", encoding="utf-8", newline="") as h:
            w = csv.writer(h, lineterminator="\n")
            w.writerow(header)
            w.writerows(sorted(rows))


if __name__ == "__main__":
    make(Path(sys.argv[1]), Path(sys.argv[2]) if len(sys.argv) > 2 else Path("db/sample/asset.csv"))
