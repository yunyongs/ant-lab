from datetime import date

import pandas as pd

from yf import YF_INTERVAL, YfFetcher


class FakeTicker:
    def __init__(self, sym):
        self.sym = sym
        self.dividends = pd.Series([0.44, 0.46], index=pd.to_datetime(["2011-08-09", "2011-11-08"]))

    def history(self, start, end, auto_adjust):
        assert auto_adjust is False
        return pd.DataFrame({"Close": [10.5, 11.25], "Adj Close": [9.75, 10.5]}, index=pd.to_datetime(["2024-01-31", "2024-02-29"]))


def test_interval_two_seconds_and_cache(tmp_path):
    t = {"now": 100.0}
    sleeps = []

    def sleep(s):
        sleeps.append(s)
        t["now"] += s
    y = YfFetcher(tmp_path, FakeTicker, sleep=sleep, now=lambda: t["now"])
    assert y.dividends("XOM") == [("2011-08-09", "0.44"), ("2011-11-08", "0.46")]
    y.dividends("KO")
    assert sleeps and abs(sleeps[0] - YF_INTERVAL) < 1e-9
    n = y.requests_made
    y.dividends("XOM")
    assert y.requests_made == n  # 파일 캐시
    assert y.closes("XOM", date(2024, 1, 1), date(2024, 3, 1)) == [("2024-01-31", "10.5"), ("2024-02-29", "11.25")]
