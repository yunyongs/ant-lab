"""T1 보완 (P1 실측 반영): 부분 응답 검사, 캐시 이름(EXCD·MODP), probe 기본 구간, yfinance Adj Close, 월봉, 대조, 배당 가산 없음."""
import argparse
import csv
from datetime import date
from decimal import Decimal

import pandas as pd
import pytest

import fetch_p0_08 as cli
from kis import KisClient, KisError
from pipeline import (US_KIS_FLOOR, Segment, Target, check_edges, check_months, compare_target, fetch_target, fetch_yf,
                      month_window, write_norm)
from tests.fakes import FakeClock, FakeSession, Resp, cfg, token_body, weekdays
from yf import YfFetcher


def tg_kr(start=date(2022, 12, 1), end=date(2023, 1, 31)):
    return Target("KR", "005930", (Segment(start, end, "KIS", start),), None)


def rows_iso(days):
    return [{"date": d.isoformat(), "close": "1"} for d in days]


# -- ① 부분 응답 검사 ---------------------------------------------------------------------
def test_edges_ok_when_first_and_last_are_close_to_the_ends():
    days = weekdays(date(2022, 12, 1), 40)
    assert check_edges(rows_iso(days), tg_kr(), date(2022, 12, 1), days[-1]) is not None


def test_edges_rejects_late_first_date_partial_response():
    days = [d for d in weekdays(date(2022, 12, 1), 60) if d >= date(2022, 12, 20)]  # 앞 13영업일이 없다
    with pytest.raises(KisError) as e:
        check_edges(rows_iso(days), tg_kr(), date(2022, 12, 1), days[-1])
    assert "부분 응답" in str(e.value) and "시작보다" in str(e.value)


def test_edges_rejects_early_last_date():
    days = weekdays(date(2022, 12, 1), 20)
    with pytest.raises(KisError) as e:
        check_edges(rows_iso(days), tg_kr(), date(2022, 12, 1), date(2023, 1, 31))
    assert "끝보다" in str(e.value)


def test_edges_allows_five_business_days_but_not_six():
    start = date(2022, 12, 5)  # 월요일
    ok = [d for d in weekdays(start, 30) if d >= date(2022, 12, 12)]  # 5영업일 뒤 (월)
    check_edges(rows_iso(ok), tg_kr(), start, ok[-1])
    late = [d for d in weekdays(start, 30) if d >= date(2022, 12, 13)]  # 6영업일 뒤
    with pytest.raises(KisError):
        check_edges(rows_iso(late), tg_kr(), start, late[-1])


def test_edges_rejects_empty():
    with pytest.raises(KisError):
        check_edges([], tg_kr(), date(2022, 12, 1), date(2023, 1, 31))


def _client(tmp_path, handler):
    sess = FakeSession(handler)
    return KisClient(cfg(tmp_path), sess, tmp_path / "raw", FakeClock()), sess


def test_fetch_target_stops_on_partial_us_response(tmp_path):
    """미국 구간 ① 을 KIS 로 요청 시작(2007-08-20)보다 늦게 시작하는 응답은 조용히 받아들이지 않는다."""
    tg = Target("US", "SPY", (Segment(date(2006, 12, 1), date(2007, 12, 31), "YF", US_KIS_FLOOR),), "AMS")
    days = [d for d in weekdays(date(2007, 9, 14), 80) if d <= date(2007, 12, 31)]  # 한 달 가까이 늦게 시작

    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        return Resp({"rt_cd": "0", "output2": [{"xymd": f"{d:%Y%m%d}", "clos": "50"} for d in sorted(days, reverse=True)]})
    cl, _ = _client(tmp_path, h)
    with pytest.raises(KisError) as e:
        fetch_target(cl, tg, monthly=False)
    assert "부분 응답" in str(e.value)


def test_month_window_excludes_partial_last_month():
    assert month_window(date(2022, 12, 1), date(2026, 1, 2)) == (date(2022, 12, 1), date(2025, 12, 31))
    assert month_window(date(2006, 12, 1), date(2012, 4, 30)) == (date(2006, 12, 1), date(2012, 4, 30))
    assert month_window(date(2007, 8, 20), date(2012, 4, 30))[0] == date(2007, 8, 20)


def test_check_months_detects_missing_month():
    tg = tg_kr()
    rows = [{"date": "2022-12-29", "close": "1"}, {"date": "2023-02-28", "close": "1"}]
    with pytest.raises(KisError) as e:
        check_months(rows, tg, date(2022, 12, 1), date(2023, 2, 28))
    assert "빠진 달 1개" in str(e.value) and "2023-01" in str(e.value)


# -- ② 캐시 파일 이름에 EXCD·MODP ----------------------------------------------------------
def test_overseas_cache_name_has_excd_and_modp(tmp_path):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        return Resp({"rt_cd": "0", "output2": [{"xymd": "20260102", "clos": "5"}]})
    cl, sess = _client(tmp_path, h)
    d = date(2026, 1, 2)
    cl.overseas_page("SPY", "AMS", d, True)
    cl.overseas_page("SPY", "NYS", d, True)   # 거래소만 다름 → 별도 요청
    cl.overseas_page("SPY", "AMS", d, False)  # 수정 여부만 다름 → 별도 요청
    cl.overseas_page("SPY", "AMS", d, True, period="M")  # 일/월 다름 → 별도 요청
    assert cl.requests_made == 4 + 1 and cl.cache_hits == 0  # 시세 4건 + 토큰 1건
    names = sorted(p.name for p in (tmp_path / "raw").glob("kis_us_*.json"))
    assert len(names) == 4 and len(set(names)) == 4
    assert any("_AMS_" in n and "modp1" in n and "_g0_" in n for n in names)
    assert any("_NYS_" in n for n in names) and any("modp0" in n for n in names) and any("_g2_" in n for n in names)
    cl.overseas_page("SPY", "AMS", d, True)  # 같은 요청은 캐시
    assert cl.requests_made == 4 + 1 and cl.cache_hits == 1


def test_domestic_cache_name_has_period_and_adjusted(tmp_path):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        return Resp({"rt_cd": "0", "output2": [{"stck_bsop_date": "20260102", "stck_clpr": "5"}]})
    cl, sess = _client(tmp_path, h)
    a, b = date(2026, 1, 2), date(2026, 1, 2)
    cl.domestic_page("005930", a, b, True)
    cl.domestic_page("005930", a, b, True, period="M")
    cl.domestic_page("005930", a, b, False)
    assert cl.requests_made == 3 + 1  # 시세 3건 + 토큰 1건
    q = [c["params"] for c in sess.calls if "params" in c]
    assert {x["FID_PERIOD_DIV_CODE"] for x in q} == {"D", "M"} and {x["FID_ORG_ADJ_PRC"] for x in q} == {"0", "1"}


def test_overseas_monthly_uses_gubn_2(tmp_path):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        return Resp({"rt_cd": "0", "output2": [{"xymd": "20120430", "clos": "5"}]})
    cl, sess = _client(tmp_path, h)
    cl.overseas_page("SPY", "AMS", date(2012, 4, 30), True, period="M")
    assert [c["params"]["GUBN"] for c in sess.calls if "params" in c] == ["2"]


# -- ③ probe 기본 구간 -----------------------------------------------------------------------
def test_probe_defaults_follow_measurement(tmp_path, monkeypatch, capsys):
    seen = []

    class FakeClient:
        def daily_range(self, market, ticker, start, end, adjusted, excd=None, period="D"):
            seen.append((market, ticker, start, end, excd))
            return [{"date": d.isoformat(), "close": "1"} for d in weekdays(start, 200) if d <= end]

    monkeypatch.setattr(cli, "_client", lambda a: (FakeClient(), []))
    assert cli.cmd_probe(argparse.Namespace()) == 0
    kr = [x for x in seen if x[0] == "KR"]
    us = [x for x in seen if x[0] == "US"]
    assert all(x[2] == date(2006, 12, 1) for x in kr)  # 국내는 구간 ① 시작부터
    assert all(x[2] == US_KIS_FLOOR and x[4] == "AMS" for x in us)  # 미국은 실측 하한부터
    out = capsys.readouterr().out
    assert "KR:005930" in out and "US:SPY" in out and "US:GLD" in out


def test_probe_stops_on_partial_response(monkeypatch):
    class FakeClient:
        def daily_range(self, market, ticker, start, end, adjusted, excd=None, period="D"):
            return [{"date": d.isoformat(), "close": "1"} for d in weekdays(start, 200) if d <= end and d >= date(2007, 9, 28)]

    monkeypatch.setattr(cli, "_client", lambda a: (FakeClient(), []))
    with pytest.raises(KisError):
        cli.cmd_probe(argparse.Namespace())


# -- ④ yfinance Adj Close ----------------------------------------------------------------------
class TickerAdj:
    calls = []

    def __init__(self, sym):
        self.sym = sym

    def history(self, start, end, auto_adjust):
        TickerAdj.calls.append((self.sym, start, end, auto_adjust))
        idx = pd.to_datetime(["2012-04-27", "2012-04-30"])
        return pd.DataFrame({"Close": [140.25, 141.5], "Adj Close": [100.125, 101.0]}, index=idx)


class TickerFull(TickerAdj):
    """요청 구간의 모든 평일을 돌려준다(끝 날짜 행만 값이 다르다) — 끝점 검사를 통과하는 yfinance 응답."""

    def history(self, start, end, auto_adjust):
        from datetime import date as _d, timedelta
        a, b = _d.fromisoformat(start), _d.fromisoformat(end) - timedelta(days=1)
        days = [a + timedelta(days=i) for i in range((b - a).days + 1) if (a + timedelta(days=i)).weekday() < 5]
        close = [140.25] * (len(days) - 1) + [141.5]
        adj = [100.125] * (len(days) - 1) + [101.0]
        return pd.DataFrame({"Close": close, "Adj Close": adj}, index=pd.to_datetime([d.isoformat() for d in days]))


def test_yf_series_reads_close_and_adj_close_and_includes_end_day(tmp_path):
    TickerAdj.calls = []
    y = YfFetcher(tmp_path, TickerAdj, sleep=lambda s: None, now=lambda: 0.0)
    rows = y.series("SPY", date(2012, 4, 1), date(2012, 4, 30))
    assert rows == [("2012-04-27", "140.25", "100.125"), ("2012-04-30", "141.5", "101.0")]
    assert all(isinstance(Decimal(c), Decimal) and isinstance(Decimal(a), Decimal) for _, c, a in rows)
    sym, start, end, auto = TickerAdj.calls[0]
    assert end == "2012-05-01" and auto is False  # yfinance 의 end 는 제외라 하루를 더한다
    n = y.requests_made
    assert y.series("SPY", date(2012, 4, 1), date(2012, 4, 30)) == rows and y.requests_made == n and y.cache_hits == 1
    assert y.closes("SPY", date(2012, 4, 1), date(2012, 4, 30)) == [("2012-04-27", "140.25"), ("2012-04-30", "141.5")]


def test_yf_series_without_adj_close_column_fails(tmp_path):
    class NoAdj(TickerAdj):
        def history(self, start, end, auto_adjust):
            return pd.DataFrame({"Close": [1.0]}, index=pd.to_datetime(["2012-04-30"]))

    with pytest.raises(RuntimeError):
        YfFetcher(tmp_path, NoAdj, sleep=lambda s: None, now=lambda: 0.0).series("SPY", date(2012, 4, 1), date(2012, 4, 30))


# -- D2: KIS 가격에 배당을 더하는 경로가 없다 ---------------------------------------------------
def _flat_kis_us(tmp_path, days):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        p = k["params"]
        sel = [d for d in days if f"{d:%Y%m%d}" <= p["BYMD"]]
        return Resp({"rt_cd": "0", "output2": [{"xymd": f"{d:%Y%m%d}", "clos": "100.5" if p["MODP"] == "1" else "99"} for d in sorted(sel, reverse=True)[:100]]})
    return _client(tmp_path, h)


def test_norm_kis_us_segment_ignores_dividends(tmp_path):
    days = weekdays(date(2026, 1, 2) - pd.Timedelta(days=60).to_pytimedelta(), 60)
    days = [d for d in days if d <= date(2026, 1, 2)]
    cl, _ = _flat_kis_us(tmp_path, days)
    tg = Target("US", "XOM", (Segment(days[0], date(2026, 1, 2), "KIS", days[0]),), "NYS")
    fetch_target(cl, tg, monthly=False)

    def boom(sym):
        raise AssertionError("norm 은 yfinance 를 부르지 않는다")
    yf = YfFetcher(tmp_path / "yf", boom)
    write_norm(cl, yf, [tg], {"XOM": [("2025-12-10", "9999.99")]}, tmp_path / "norm")  # 터무니없이 큰 배당이 있어도
    rows = list(csv.DictReader((tmp_path / "norm" / "daily.csv").open(encoding="utf-8")))
    assert {r["close"] for r in rows} == {"100.5"}  # KIS MODP=1 종가 그대로 (배당 가산 없음)
    assert {r["source"] for r in rows} == {"KIS"} and {r["close_price"] for r in rows} == {""}


# -- 정본 yfinance 구간 정규화와 대조 -------------------------------------------------------------
def test_norm_yf_segment_uses_adj_close_and_keeps_price(tmp_path):
    seg = Segment(date(2012, 4, 1), date(2012, 4, 30), "YF", date(2012, 4, 20))
    tg = Target("US", "GLD", (seg,), "AMS")  # 대체 수익률 기초 — 기준일 실제 종가 없음

    yf = YfFetcher(tmp_path / "yf", TickerFull, sleep=lambda s: None, now=lambda: 0.0)
    cl, _ = _client(tmp_path, lambda m, u, k: pytest.fail("네트워크 호출"))
    write_norm(cl, yf, [tg], {}, tmp_path / "norm")
    rows = list(csv.DictReader((tmp_path / "norm" / "daily.csv").open(encoding="utf-8")))
    assert rows[-1] == {"market": "US", "ticker": "GLD", "trade_date": "2012-04-30", "close": "101.0",
                        "close_price": "141.5", "close_raw": "", "source": "YF"}


def test_compare_target_cross_and_internal(tmp_path):
    """KIS 일별과 yfinance 가 일치하는 합성 입력: CROSS 는 MATCH, 월봉이 월말과 같으면 INTERNAL 은 EQUAL, 다르면 DIFF."""
    days = [d for d in weekdays(date(2022, 12, 1), 800) if d <= date(2025, 12, 31)]
    price = {d: Decimal(1000 + 7 * i) for i, d in enumerate(days)}  # 단조 증가

    def kr_rows(lo, hi):
        return [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(price[d])} for d in sorted(price, reverse=True) if lo <= f"{d:%Y%m%d}" <= hi][:100]

    me = {}
    for d in days:
        me[d.strftime("%Y-%m")] = d  # 월말 거래일
    month_bonus = {"on": 0}

    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        p = k["params"]
        if p["FID_PERIOD_DIV_CODE"] == "M":
            bars = [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(price[d] + (month_bonus["on"] if month_bonus["on"] and d.strftime("%Y-%m") == "2023-05" else 0))}
                    for d in me.values() if p["FID_INPUT_DATE_1"][:6] <= f"{d:%Y%m%d}"[:6] <= p["FID_INPUT_DATE_2"][:6]]
            return Resp({"rt_cd": "0", "output2": sorted(bars, key=lambda r: r["stck_bsop_date"], reverse=True)})
        return Resp({"rt_cd": "0", "output2": kr_rows(p["FID_INPUT_DATE_1"], p["FID_INPUT_DATE_2"])})

    class T:
        def __init__(self, sym):
            pass

        def history(self, start, end, auto_adjust):
            idx = pd.to_datetime([d.isoformat() for d in days if start <= d.isoformat() < end])
            vals = [float(price[d.date()]) for d in idx]
            return pd.DataFrame({"Close": vals, "Adj Close": vals}, index=idx)

    tg = Target("KR", "005930", (Segment(date(2022, 12, 1), date(2025, 12, 31), "KIS", date(2022, 12, 1)),), None)
    cl, _ = _client(tmp_path, h)
    yf = YfFetcher(tmp_path / "yf", T, sleep=lambda s: None, now=lambda: 0.0)
    out = compare_target(cl, yf, tg)
    cross = [r for r in out if r["kind"] == "CROSS"]
    internal = [r for r in out if r["kind"] == "INTERNAL"]
    assert len(cross) == 36 and {r["verdict"] for r in cross} == {"MATCH"} and {r["basis"] for r in cross} == {"PRICE"}
    assert len(internal) == 36 and {r["verdict"] for r in internal} == {"EQUAL"}
    assert all(isinstance(r["diff_bp"], Decimal) for r in out)
    # 월봉 종가를 한 달만 어긋나게 하면 그 달이 DIFF 로 잡힌다 (새 캐시 폴더)
    month_bonus["on"] = 2
    cl2, _ = _client(tmp_path / "second", h)
    out2 = compare_target(cl2, yf, tg)
    assert [r["month"] for r in out2 if r["verdict"] == "DIFF"] == ["2023-05"]
    # 정확히 1원 차이는 국내 반올림으로 보고 ROUND(허용)
    month_bonus["on"] = 1
    cl3, _ = _client(tmp_path / "third", h)
    out3 = compare_target(cl3, yf, tg)
    assert [r["month"] for r in out3 if r["verdict"] == "ROUND"] == ["2023-05"]
    assert not [r for r in out3 if r["verdict"] == "DIFF"]


def test_fetch_yf_counts_requests_and_skips_dividends_for_gld_and_kr(tmp_path):
    class T(TickerFull):
        dividends = pd.Series([0.5], index=pd.to_datetime(["2011-08-09"]))
    y = YfFetcher(tmp_path, T, sleep=lambda s: None, now=lambda: 0.0)
    seg = Segment(date(2012, 4, 1), date(2012, 4, 30), "YF", date(2012, 4, 20))
    fetch_yf(y, Target("US", "GLD", (seg,), "AMS"))
    assert y.requests_made == 1 and not (tmp_path / "yf_div_GLD.csv").exists()
    fetch_yf(y, Target("US", "XOM", (seg,), "NYS"))
    assert y.requests_made == 3 and (tmp_path / "yf_div_XOM.csv").exists()
    fetch_yf(y, Target("KR", "005930", (seg,), None))
    assert y.requests_made == 4  # 국내는 일별만 (배당 없음)


# -- T2 실측 반영: TLT·SHY 는 NAS, KIS 에 구간 ① 이 없다 --------------------------------------
def test_tlt_shy_use_nas_and_have_no_kis_overlap():
    from pathlib import Path
    from pipeline import EXCD, NO_KIS_OVERLAP, load_targets
    assert EXCD["TLT"] == "NAS" and EXCD["SHY"] == "NAS" and EXCD["SPY"] == "AMS" and EXCD["GLD"] == "AMS"
    t = {(x.market, x.ticker): x for x in load_targets(Path(__file__).parents[3] / "db/sample/asset.csv")}
    for k in NO_KIS_OVERLAP:
        s1, s2 = t[k].segments
        assert s1.source == "YF" and s1.kis_from is None and s2.source == "KIS" and s2.kis_from == s2.start
    assert t[("US", "SPY")].segments[0].kis_from == US_KIS_FLOOR


def test_fetch_and_compare_skip_kis_when_no_overlap(tmp_path):
    seg = Segment(date(2007, 1, 1), date(2007, 3, 31), "YF", None)
    tg = Target("US", "TLT", (seg,), "NAS")
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        assert k["params"]["BYMD"] == "20260102" and k["params"]["MODP"] == "0", "구간 ① 일별·월봉은 부르지 않는다"
        return Resp({"rt_cd": "0", "output2": [{"xymd": "20260102", "clos": "5"}]})
    cl, sess = _client(tmp_path, h)
    fetch_target(cl, tg)  # 기준일 실제 종가 한 건뿐
    assert len([c for c in sess.calls if "params" in c]) == 1

    yf = YfFetcher(tmp_path / "yf", TickerFull, sleep=lambda s: None, now=lambda: 0.0)
    out = compare_target(cl, yf, tg)
    assert [(r["kind"], r["verdict"]) for r in out] == [("NO_OVERLAP", "NONE")]


def test_yf_series_drops_nan_rows_and_counts_them(tmp_path):
    class NanTicker(TickerAdj):
        def history(self, start, end, auto_adjust):
            idx = pd.to_datetime(["2007-04-26", "2007-04-27", "2007-04-30"])
            return pd.DataFrame({"Close": [10.5, float("nan"), 11.0], "Adj Close": [10.5, float("nan"), 11.0]}, index=idx)

    y = YfFetcher(tmp_path, NanTicker, sleep=lambda s: None, now=lambda: 0.0)
    rows = y.series("091160.KS", date(2007, 4, 1), date(2007, 4, 30))
    assert [r[0] for r in rows] == ["2007-04-26", "2007-04-30"] and y.nan_rows_dropped == 1
