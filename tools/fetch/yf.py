"""yfinance 보조 조회 (최소 범위: 미국 배당, 정본 구간 일별 Close·Adj Close, 대조용 종가). 개인 사용 · 요청 사이 2초.

yfinance 출력은 data/raw/p0-08/yf/ (추적 안 함)에만 쓴다. 키가 필요 없다.
"""
from __future__ import annotations

import csv
import time
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable

YF_INTERVAL = 2.0  # 요청 사이 간격(초)


class EmptySeries(RuntimeError, ValueError):
    """yfinance 가 행을 하나도 주지 않았다 (명령줄에서는 오류 종료 코드 2)."""


class YfFetcher:
    def __init__(self, raw_dir: Path, ticker_factory: Callable[[str], Any] | None = None,
                 sleep: Callable[[float], None] = time.sleep, now: Callable[[], float] = time.monotonic):
        self.raw_dir = Path(raw_dir)
        self._factory = ticker_factory
        self._sleep = sleep
        self._now = now
        self._last = None  # type: float | None
        self.requests_made = 0
        self.cache_hits = 0
        self.nan_rows_dropped = 0

    def _ticker(self, symbol: str) -> Any:
        if self._factory is None:
            import yfinance  # 지연 import: 단위 테스트와 check-env는 라이브러리를 부르지 않는다
            self._factory = yfinance.Ticker
        return self._factory(symbol)

    def _wait(self) -> None:
        if self._last is not None:
            gap = self._last + YF_INTERVAL - self._now()
            if gap > 0:
                self._sleep(gap)
        self._last = self._now()
        self.requests_made += 1

    def dividends(self, ticker: str) -> list[tuple[str, str]]:
        """(배당락일 YYYY-MM-DD, 주당 금액) 목록. 분할 조정된 값. 파일이 있으면 네트워크를 부르지 않는다."""
        f = self.raw_dir / f"yf_div_{ticker}.csv"
        if not f.is_file():
            self._wait()
            series = self._ticker(ticker).dividends
            rows = [(idx.strftime("%Y-%m-%d"), format(Decimal(str(val)), "f")) for idx, val in series.items()]
            self.raw_dir.mkdir(parents=True, exist_ok=True)
            with f.open("w", encoding="utf-8", newline="") as h:
                w = csv.writer(h, lineterminator="\n")
                w.writerow(["ex_date", "amount"])
                w.writerows(rows)
        with f.open(encoding="utf-8", newline="") as h:
            r = csv.reader(h)
            next(r)
            return [(a, b) for a, b in r]

    def series(self, symbol: str, start: date, end: date) -> list[tuple[str, str, str]]:
        """(날짜, Close, Adj Close) 목록. Close = 분할만 조정, Adj Close = 분할 + 배당 조정. end 날짜를 포함한다.

        파일(yf_px_*)이 있으면 네트워크를 부르지 않는다. 값이 없는(NaN) 행은 건너뛰고 nan_rows_dropped 에 센다.
        """
        f = self.raw_dir / f"yf_px_{symbol}_{start:%Y%m%d}_{end:%Y%m%d}.csv"
        if f.is_file():
            self.cache_hits += 1
        else:
            self._wait()
            # yfinance 의 end 는 제외라 하루를 더한다
            hist = self._ticker(symbol).history(start=start.isoformat(), end=(end + timedelta(days=1)).isoformat(), auto_adjust=False)
            if "Close" not in hist.columns or "Adj Close" not in hist.columns:
                raise RuntimeError(f"{symbol}: yfinance 응답에 Close·Adj Close 열이 없다 (열: {list(hist.columns)})")
            rows = []
            for idx, close, adj in zip(hist.index, hist["Close"], hist["Adj Close"]):
                if close != close or adj != adj:  # NaN
                    self.nan_rows_dropped += 1
                    continue
                rows.append((idx.strftime("%Y-%m-%d"), format(Decimal(str(close)), "f"), format(Decimal(str(adj)), "f")))
            if not rows:  # 빈 응답은 캐시에 쓰지 않는다 (쓰면 다음 실행이 빈 원본을 그대로 받는다)
                raise EmptySeries(f"{symbol} {start}~{end}: yfinance 가 받은 행이 0이다 — 심볼·기간 확인")
            self.raw_dir.mkdir(parents=True, exist_ok=True)
            with f.open("w", encoding="utf-8", newline="") as h:
                w = csv.writer(h, lineterminator="\n")
                w.writerow(["trade_date", "close", "adj_close"])
                w.writerows(rows)
        with f.open(encoding="utf-8", newline="") as h:
            r = csv.reader(h)
            next(r)
            return [(a, b, c) for a, b, c in r]

    def closes(self, symbol: str, start: date, end: date) -> list[tuple[str, str]]:
        """대조용 종가(Close, 분할만 조정)."""
        return [(d, c) for d, c, _ in self.series(symbol, start, end)]
