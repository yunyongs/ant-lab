"""대조 범위와 부분 응답: 예정된 달이 한쪽에 없으면 GAP, compare·norm 도 끝점 검사를 한다 (합성 입력)."""
import csv
from datetime import date
from decimal import Decimal

import pandas as pd
import pytest

from kis import KisClient, KisError
from pipeline import (JUDGMENT_HEADER, Segment, Target, apply_judgments, blocking, compare_target, expected_cross_months,
                      fetch_yf, load_judgments, write_norm)
from tests.fakes import FakeClock, FakeSession, Resp, cfg, token_body, weekdays
from yf import YfFetcher

SEG = Segment(date(2022, 12, 1), date(2023, 3, 31), "KIS", date(2022, 12, 1))
TG = Target("KR", "005930", (SEG,), None)
DAYS = [d for d in weekdays(date(2022, 12, 1), 200) if d <= date(2023, 3, 31)]
PRICE = {d: 1000 + 3 * i for i, d in enumerate(DAYS)}  # 합성 정수 가격


def kis_client(tmp_path, last=None, skip_month=None):
    keep = [d for d in DAYS if (last is None or d <= last) and (skip_month is None or f"{d:%Y-%m}" != skip_month)]

    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        p = k["params"]
        lo, hi = p["FID_INPUT_DATE_1"], p["FID_INPUT_DATE_2"]
        rows = [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(PRICE[d])} for d in sorted(keep, reverse=True) if lo <= f"{d:%Y%m%d}" <= hi]
        return Resp({"rt_cd": "0", "output2": rows[:100]})
    return KisClient(cfg(tmp_path), FakeSession(h), tmp_path / "raw", FakeClock())


def yf_fetcher(tmp_path, days=None):
    keep = DAYS if days is None else days

    class T:
        def __init__(self, sym):
            pass

        def history(self, start, end, auto_adjust):
            vals = [float(PRICE[d]) for d in keep]
            return pd.DataFrame({"Close": vals, "Adj Close": vals}, index=pd.to_datetime([d.isoformat() for d in keep]))
    return YfFetcher(tmp_path / "yf", T, sleep=lambda s: None, now=lambda: 0.0)


def cross(rows):
    return [r for r in rows if r["kind"] == "CROSS"]


def write_j(path, rows):
    with path.open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(JUDGMENT_HEADER)
        w.writerows(rows)


def gap_j(month, canonical="KIS"):
    return ["KR", "005930", "2", month, "GAP", canonical, "CMP_GAP", "yfinance 월말 거래일 누락으로 대조 불가 — 정본 유지", "CANONICAL"]


def test_expected_cross_months_counts_on_real_segments():
    from pipeline import SEGMENT_1, SEGMENT_2, US_KIS_FLOOR
    assert len(expected_cross_months(Segment(*SEGMENT_1, "KIS", SEGMENT_1[0]))) == 64
    assert len(expected_cross_months(Segment(*SEGMENT_1, "YF", US_KIS_FLOOR))) == 56
    assert len(expected_cross_months(Segment(*SEGMENT_2, "KIS", SEGMENT_2[0]))) == 36
    assert expected_cross_months(Segment(*SEGMENT_1, "YF", None)) == []
    assert expected_cross_months(SEG) == ["2023-01", "2023-02", "2023-03"]


def test_full_overlap_has_no_gap(tmp_path):
    out = cross(compare_target(kis_client(tmp_path), yf_fetcher(tmp_path), TG))
    assert [r["verdict"] for r in out] == ["MATCH"] * 3


def test_empty_yf_cache_gives_gap_rows_and_blocks_until_judged(tmp_path):
    (tmp_path / "yf").mkdir()
    (tmp_path / "yf" / "yf_px_005930.KS_20221201_20230331.csv").write_text("trade_date,close,adj_close\n", encoding="utf-8")
    rows = compare_target(kis_client(tmp_path), yf_fetcher(tmp_path), TG)
    assert [r["verdict"] for r in cross(rows)] == ["GAP"] * 3 and blocking(rows)
    f = tmp_path / "j.csv"
    write_j(f, [gap_j(m) for m in ("2023-01", "2023-02", "2023-03")])
    out, problems = apply_judgments(rows, load_judgments(f))
    assert [r["verdict"] for r in cross(out)] == ["JUDGED"] * 3 and not problems and not blocking(out)
    # 달이 다른 판정은 쓰이지 않은 판정이고, 맞는 판정이 없는 GAP 은 그대로 막는다
    write_j(f, [gap_j("2023-01"), gap_j("2023-02"), gap_j("2023-04")])
    out, problems = apply_judgments(rows, load_judgments(f))
    assert blocking(out) and any("쓰이지 않은 판정" in p and "2023-04" in p for p in problems)


def test_yf_missing_middle_month_is_gap(tmp_path):
    days = [d for d in DAYS if f"{d:%Y-%m}" != "2023-02"]
    out = cross(compare_target(kis_client(tmp_path), yf_fetcher(tmp_path, days), TG))
    assert {r["month"]: r["verdict"] for r in out} == {"2023-01": "MATCH", "2023-02": "GAP", "2023-03": "GAP"}


def test_canonical_side_gap_stops_compare_even_before_judgment(tmp_path):
    # 정본(KIS) 쪽 한 달 누락은 GAP_CANONICAL 행이 아니라 오류로 멈춘다 — 판정으로 풀 수 없다
    with pytest.raises(KisError) as e:
        compare_target(kis_client(tmp_path, skip_month="2023-02"), yf_fetcher(tmp_path), TG)
    assert "2023-02" in str(e.value)


def test_gap_canonical_row_still_cannot_be_judged(tmp_path):
    rows = [{"kind": "CROSS", "market": "KR", "ticker": "005930", "segment": 2, "month": "2023-02", "basis": "PRICE",
             "canonical": "KIS", "diff_bp": Decimal(0), "verdict": "GAP_CANONICAL"}]
    f = tmp_path / "j.csv"
    write_j(f, [gap_j("2023-02")])
    out, problems = apply_judgments(rows, load_judgments(f))
    assert blocking(out)


def test_gap_judgment_code_and_diff_must_go_together(tmp_path):
    f = tmp_path / "j.csv"
    bad = gap_j("2023-01")
    bad[4] = "12.34"
    write_j(f, [bad])
    with pytest.raises(ValueError):
        load_judgments(f)
    other = gap_j("2023-01")
    other[6] = "ONE_DAY"
    write_j(f, [other])
    with pytest.raises(ValueError):
        load_judgments(f)


def test_stop_row_is_not_resolved_by_gap_judgment(tmp_path):
    row = {"kind": "CROSS", "market": "KR", "ticker": "005930", "segment": 1, "month": "2023-01", "basis": "PRICE",
           "canonical": "KIS", "diff_bp": Decimal("12.34"), "verdict": "STOP"}
    f = tmp_path / "j.csv"
    write_j(f, [gap_j("2023-01")])
    out, problems = apply_judgments([row], load_judgments(f))
    assert out[0]["verdict"] == "STOP" and problems


def test_us_yf_canonical_segment_empty_series_stops_fetch_and_norm(tmp_path):
    seg = Segment(date(2022, 12, 1), date(2023, 3, 31), "YF", None)
    tg = Target("US", "GLD", (seg,), "AMS")  # 대체 수익률 기초: 기준일 실제 종가 요청 없음

    class Empty:
        def __init__(self, sym):
            pass

        def history(self, start, end, auto_adjust):
            return pd.DataFrame({"Close": [], "Adj Close": []}, index=pd.to_datetime([]))
    yf = YfFetcher(tmp_path / "yf", Empty, sleep=lambda s: None, now=lambda: 0.0)
    with pytest.raises(ValueError):
        fetch_yf(yf, tg)
    assert not list((tmp_path / "yf").glob("yf_px_*"))  # 빈 응답은 캐시에 쓰지 않는다
    # 이미 머리글만 있는 캐시가 있어도 norm 은 쓰지 않고 멈춘다
    (tmp_path / "yf").mkdir(exist_ok=True)
    (tmp_path / "yf" / "yf_px_GLD_20221201_20230331.csv").write_text("trade_date,close,adj_close\n", encoding="utf-8")
    with pytest.raises(KisError):
        write_norm(kis_client(tmp_path), yf, [tg], {}, tmp_path / "norm")
    assert not (tmp_path / "norm" / "daily.csv").exists()


def test_us_yf_canonical_segment_cut_short_stops_norm(tmp_path):
    seg = Segment(date(2022, 12, 1), date(2023, 3, 31), "YF", None)
    tg = Target("US", "GLD", (seg,), "AMS")  # 대체 수익률 기초: 기준일 실제 종가 요청 없음
    yf = yf_fetcher(tmp_path, [d for d in DAYS if d <= date(2023, 2, 28)])
    with pytest.raises(KisError) as e:
        write_norm(kis_client(tmp_path), yf, [tg], {}, tmp_path / "norm")
    assert "부분 응답" in str(e.value) and not (tmp_path / "norm" / "daily.csv").exists()


def test_kis_cache_ending_early_stops_compare_and_norm(tmp_path):
    cl = kis_client(tmp_path, last=DAYS[-11])  # 구간 끝보다 10영업일 이르게 끝난다
    with pytest.raises(KisError) as e:
        compare_target(cl, yf_fetcher(tmp_path), TG)
    assert "부분 응답" in str(e.value)
    with pytest.raises(KisError):
        write_norm(cl, yf_fetcher(tmp_path), [TG], {}, tmp_path / "norm")
    assert not (tmp_path / "norm" / "daily.csv").exists()


def test_offline_client_never_reads_token_file(tmp_path, monkeypatch):
    """오프라인 명령(compare·norm)은 캐시가 모자라도 토큰 파일을 열지 않는다."""
    import builtins
    import fetch_p0_08 as cli
    opened = []
    real_open = builtins.open
    monkeypatch.setattr(builtins, "open", lambda f, *a, **k: (opened.append(str(f)), real_open(f, *a, **k))[1])
    real_read = cli.Path.read_text
    monkeypatch.setattr(cli.Path, "read_text", lambda self, *a, **k: (opened.append(str(self)), real_read(self, *a, **k))[1])
    monkeypatch.setattr(cli, "RAW", tmp_path / "raw")
    client, _ = cli._offline()
    with pytest.raises(KisError):
        client.domestic_page("005930", date(2023, 1, 1), date(2023, 1, 31), True)
    assert client.requests_made == 0 and not any("token" in o for o in opened)


# -- 수정 R2: C2-a 월봉 누락, C2-b 정본 누락 ------------------------------------------------
def kis_client_monthly(tmp_path, bars):
    """일별은 정상, 월봉(period=M)은 bars(달 목록)만 준다. bars=[] 이면 빈 응답."""
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        p = k["params"]
        lo, hi = p["FID_INPUT_DATE_1"], p["FID_INPUT_DATE_2"]
        if p.get("FID_PERIOD_DIV_CODE") == "M":
            rows = []
            for ym in sorted(bars, reverse=True):
                last = max(d for d in DAYS if f"{d:%Y-%m}" == ym)
                rows.append({"stck_bsop_date": f"{last:%Y%m%d}", "stck_clpr": str(PRICE[last])})
            return Resp({"rt_cd": "0", "output2": rows})
        rows = [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(PRICE[d])} for d in sorted(DAYS, reverse=True) if lo <= f"{d:%Y%m%d}" <= hi]
        return Resp({"rt_cd": "0", "output2": rows[:100]})
    return KisClient(cfg(tmp_path), FakeSession(h), tmp_path / "raw", FakeClock())


@pytest.mark.parametrize("bars", [[], ["2022-12", "2023-01", "2023-03"]])
def test_empty_or_gapped_monthly_bars_stop_compare_and_norm(tmp_path, monkeypatch, capsys, bars):
    import fetch_p0_08 as cli
    cl = kis_client_monthly(tmp_path, bars)
    with pytest.raises(KisError) as e:
        compare_target(cl, yf_fetcher(tmp_path), TG)
    assert "월봉" in str(e.value)
    # 명령줄: compare·norm 모두 0 이 아닌 종료 코드, norm 출력 0건
    monkeypatch.setattr(cli, "RAW", tmp_path / "out")
    monkeypatch.setattr(cli, "JUDGMENTS", tmp_path / "none.csv")
    monkeypatch.setattr(cli, "load_targets", lambda p: [TG])
    monkeypatch.setattr(cli, "_offline", lambda: (cl, yf_fetcher(tmp_path)))
    for cmd in ("compare", "norm"):
        assert cli.main([cmd]) != 0
    assert not (tmp_path / "out" / "norm").exists()


def _us_yf_tg(start=date(2006, 12, 1), end=date(2007, 6, 30)):
    return Target("US", "GLD", (Segment(start, end, "YF", None),), "AMS")  # 기준일 실제 종가 요청 없는 종목


def _yf_without(tmp_path, tg, skip):
    seg = tg.segments[0]
    days = [d for d in weekdays(seg.start, 400) if d <= seg.end and f"{d:%Y-%m}" != skip]

    class T:
        def __init__(self, sym):
            pass

        def history(self, start, end, auto_adjust):
            vals = [100.0 + i for i in range(len(days))]
            return pd.DataFrame({"Close": vals, "Adj Close": vals}, index=pd.to_datetime([d.isoformat() for d in days]))

        dividends = pd.Series([], dtype=float)
    return YfFetcher(tmp_path / "yf", T, sleep=lambda s: None, now=lambda: 0.0)


@pytest.mark.parametrize("skip", ["2007-02", "2007-04"])
def test_yf_canonical_missing_month_stops_fetch_compare_norm_even_without_overlap(tmp_path, skip):
    tg = _us_yf_tg()  # kis_from 없음 = NO_OVERLAP 경로. 예전에는 끝점만 보고 통과했다
    cl = kis_client(tmp_path)
    for run in (lambda: fetch_yf(_yf_without(tmp_path, tg, skip), tg),
                lambda: compare_target(cl, _yf_without(tmp_path, tg, skip), tg),
                lambda: write_norm(cl, _yf_without(tmp_path, tg, skip), [tg], {}, tmp_path / "norm")):
        with pytest.raises(KisError) as e:
            run()
        assert skip in str(e.value)
    assert not (tmp_path / "norm" / "daily.csv").exists()


def test_yf_canonical_segment_missing_prev_month_stops_compare(tmp_path):
    # 대조 구간이 있는 정본(yfinance) 구간: 첫 표본 달(2023-01)의 직전 달(2022-12)이 빠지면 멈춘다 (SPY 구간 ① 꼴)
    tg = Target("KR", "005930", (Segment(date(2022, 12, 1), date(2023, 3, 31), "YF", date(2022, 12, 1)),), None)
    with pytest.raises(KisError) as e:
        compare_target(kis_client(tmp_path), _yf_without(tmp_path, tg, "2022-12"), tg)
    assert "2022-12" in str(e.value)


def test_complete_yf_canonical_passes(tmp_path):
    tg = _us_yf_tg()
    fetch_yf(_yf_without(tmp_path, tg, "none"), tg)
