import csv
from datetime import date
from decimal import Decimal

import pytest

from kis import KisClient
from pipeline import (BASE_DATE, Segment, Target, compare_returns, fetch_target, load_targets, monthly_returns, sha256, write_norm)
from yf import YfFetcher
from tests.fakes import FakeClock, FakeSession, Resp, cfg, token_body

ASSET = "db/sample/asset.csv"


def test_targets_from_asset_csv():
    from pathlib import Path
    t = {(x.market, x.ticker): x for x in load_targets(Path(__file__).parents[3] / ASSET)}
    assert len(t) == 21 and ("US", "GLD") in t
    assert t[("US", "XOM")].segments[0].source == "YF" and t[("US", "XOM")].segments[1].source == "KIS"
    assert t[("KR", "005930")].segments[0].source == "KIS"
    assert len(t[("KR", "360750")].segments) == 1 and len(t[("KR", "005930")].segments) == 2
    assert t[("US", "XOM")].excd == "NYS" and t[("KR", "005930")].excd is None


def _no_yf(tmp_path):
    def boom(symbol):
        raise AssertionError("이 시험은 yfinance 를 부르지 않는다")
    return YfFetcher(tmp_path / "yf", ticker_factory=boom)


def _seg(a, b, source="KIS", kis_from=None):
    return Segment(a, b, source, kis_from or a)


def _client(tmp_path, adj_rows, raw_rows):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        p = k["params"]
        rows = raw_rows if p.get("FID_ORG_ADJ_PRC") == "1" else adj_rows
        return Resp({"rt_cd": "0", "output2": rows})
    sess = FakeSession(h)
    return KisClient(cfg(tmp_path), sess, tmp_path / "raw", FakeClock()), sess


def test_norm_roundtrip_and_zero_requests_second_time(tmp_path):
    adj = [{"stck_bsop_date": "20260102", "stck_clpr": "70000.50"}, {"stck_bsop_date": "20260101", "stck_clpr": "69000"},
           {"stck_bsop_date": "20251231", "stck_clpr": "68000"}]  # 표본 달 2025-12 가 정본에 있어야 한다
    raw = [{"stck_bsop_date": "20260102", "stck_clpr": "71000"}]
    tg = Target("KR", "005930", (_seg(date(2025, 12, 31), date(2026, 1, 2)),), None)
    cl, sess = _client(tmp_path, adj, raw)
    fetch_target(cl, tg, monthly=False)
    n = cl.requests_made
    assert n >= 2
    h1 = write_norm(cl, _no_yf(tmp_path), [tg], {}, tmp_path / "norm")
    assert cl.requests_made == n  # 정규화는 캐시만 읽는다
    rows = list(csv.DictReader((tmp_path / "norm" / "daily.csv").open(encoding="utf-8")))
    assert rows[-1] == {"market": "KR", "ticker": "005930", "trade_date": "2026-01-02", "close": "70000.50", "close_price": "", "close_raw": "71000", "source": "KIS"}
    assert rows[0]["close_raw"] == ""
    # 두 번째: 새 클라이언트 + 같은 캐시 → 요청 0, 해시 같음
    cl2, sess2 = _client(tmp_path, adj, raw)
    fetch_target(cl2, tg, monthly=False)
    h2 = write_norm(cl2, _no_yf(tmp_path), [tg], {}, tmp_path / "norm")
    assert sess2.calls == [] and h1 == h2
    assert (tmp_path / "norm" / "shares.csv").read_text().strip() == "market,ticker,as_of,shares,source"


def test_norm_rejects_bad_numbers(tmp_path):
    adj = [{"stck_bsop_date": "20260102", "stck_clpr": "0"}]
    tg = Target("KR", "005930", (_seg(date(2026, 1, 2), date(2026, 1, 2)),), None)
    cl, _ = _client(tmp_path, adj, adj)
    fetch_target(cl, tg, monthly=False)
    with pytest.raises(ValueError):
        write_norm(cl, _no_yf(tmp_path), [tg], {}, tmp_path / "norm")


def test_dividends_file(tmp_path):
    adj = [{"xymd": "20260102", "clos": "100"}]
    tg = Target("KR", "005930", (_seg(date(2026, 1, 2), date(2026, 1, 2)),), None)
    cl, _ = _client(tmp_path, [{"stck_bsop_date": "20260102", "stck_clpr": "5"}], [{"stck_bsop_date": "20260102", "stck_clpr": "5"}])
    fetch_target(cl, tg, monthly=False)
    write_norm(cl, _no_yf(tmp_path), [tg], {"XOM": [("2011-08-09", "0.44")]}, tmp_path / "norm")
    assert "US,XOM,2011-08-09,0.44,YF" in (tmp_path / "norm" / "dividends.csv").read_text()


def test_monthly_returns_decimal_and_gap_skips():
    rows = [("2024-01-30", "100"), ("2024-01-31", "110"), ("2024-02-28", "99"), ("2024-04-30", "120")]
    r = monthly_returns(rows)
    assert r == {"2024-02": Decimal("99") / Decimal("110") - 1}  # 3월 월말이 없어 4월은 건너뜀
    assert all(isinstance(v, Decimal) for v in r.values())


def test_compare_verdicts():
    a = {"2024-01": Decimal("0.0100"), "2024-02": Decimal("0.0200"), "2024-03": Decimal("0.0300"), "2024-04": Decimal("1")}
    b = {"2024-01": Decimal("0.01005"), "2024-02": Decimal("0.0205"), "2024-03": Decimal("0.0400")}
    v = {r["month"]: r["verdict"] for r in compare_returns(a, b)}
    assert v == {"2024-01": "MATCH", "2024-02": "WARN", "2024-03": "STOP"}


def test_synthetic_generator_is_deterministic_and_marked(tmp_path):
    from pathlib import Path
    from tests.synthetic import make
    asset = Path(__file__).parents[3] / ASSET
    make(tmp_path / "a", asset)
    make(tmp_path / "b", asset)
    for n in ("daily.csv", "dividends.csv", "shares.csv"):
        assert sha256(tmp_path / "a" / n) == sha256(tmp_path / "b" / n)
    rows = list(csv.DictReader((tmp_path / "a" / "daily.csv").open(encoding="utf-8")))
    assert {r["source"] for r in rows} == {"SYN"}
    assert not any("e" in r["close"].lower() or "." not in r["close"] for r in rows)


def test_fetch_stops_when_zero_rows(tmp_path):
    from kis import KisError
    cl, _ = _client(tmp_path, [], [])
    tg = Target("KR", "005930", (_seg(date(2022, 12, 1), date(2023, 1, 31)),), None)
    with pytest.raises(KisError) as e:
        fetch_target(cl, tg, monthly=False)
    assert "0이다" in str(e.value)


def test_probe_stops_when_zero_rows(tmp_path, monkeypatch):
    import argparse
    import fetch_p0_08 as f
    from kis import KisError
    cl, _ = _client(tmp_path, [], [])
    monkeypatch.setattr(f, "_client", lambda a: (cl, []))
    with pytest.raises(KisError):
        f.cmd_probe(argparse.Namespace())
