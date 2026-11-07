"""T2 마무리: 국내 ETF 원주가, 내부 대조 ROUND, 사람 판정 기록, norm 의 멈춤."""
import csv
from decimal import Decimal

import pytest

import fetch_p0_08 as cli
from pipeline import (JUDGMENT_HEADER, KR_ETF_RAW, Segment, Target, apply_judgments, blocking, internal_verdict,
                      load_judgments)


def row(verdict="STOP", bp="12.34", canonical="KIS", month="2008-10", kind="CROSS", ticker="005930"):
    return {"kind": kind, "market": "KR", "ticker": ticker, "segment": 1, "month": month, "basis": "PRICE",
            "canonical": canonical, "diff_bp": Decimal(bp), "verdict": verdict}


def write_j(path, rows):
    with path.open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(JUDGMENT_HEADER)
        w.writerows(rows)


def jrow(bp="12.34", canonical="KIS", month="2008-10", reason="하루짜리 종가 불일치, 정본 채택", decision="CANONICAL"):
    return ["KR", "005930", "1", month, bp, canonical, "ONE_DAY", reason, decision]


def test_kr_etf_requests_raw_price_and_others_adjusted():
    for t in ("069500", "091160", "360750", "411060"):
        assert not Target("KR", t, (), None).kis_adjusted and ("KR", t) in KR_ETF_RAW
    assert Target("KR", "005930", (), None).kis_adjusted
    assert Target("US", "SPY", (), "AMS").kis_adjusted  # 미국은 MODP=1


def test_internal_verdict_round_only_for_kr_within_one_won():
    assert internal_verdict("KR", Decimal("100"), Decimal("100")) == "EQUAL"
    assert internal_verdict("KR", Decimal("101"), Decimal("100")) == "ROUND"
    assert internal_verdict("KR", Decimal("102"), Decimal("100")) == "DIFF"
    assert internal_verdict("US", Decimal("100.01"), Decimal("100")) == "DIFF"


def test_missing_judgment_file_means_no_judgments(tmp_path):
    assert load_judgments(tmp_path / "none.csv") == {}


def test_judgment_turns_stop_into_judged(tmp_path):
    f = tmp_path / "j.csv"
    write_j(f, [jrow()])
    out, problems = apply_judgments([row()], load_judgments(f))
    assert out[0]["verdict"] == "JUDGED" and not problems and not blocking(out)


def test_stop_without_judgment_still_blocks(tmp_path):
    f = tmp_path / "j.csv"
    write_j(f, [jrow(month="2008-11")])
    out, problems = apply_judgments([row()], load_judgments(f))
    assert out[0]["verdict"] == "STOP" and blocking(out)
    assert any("쓰이지 않은 판정" in p for p in problems)


def test_stale_judgment_with_changed_diff_does_not_pass(tmp_path):
    f = tmp_path / "j.csv"
    write_j(f, [jrow(bp="12.35")])
    out, problems = apply_judgments([row()], load_judgments(f))
    assert out[0]["verdict"] == "STOP" and any("낡은 판정" in p for p in problems)


def test_judgment_needs_reason_and_canonical_decision(tmp_path):
    f = tmp_path / "j.csv"
    write_j(f, [jrow(reason=" ")])
    with pytest.raises(ValueError):
        load_judgments(f)
    write_j(f, [jrow(decision="OTHER")])
    with pytest.raises(ValueError):
        load_judgments(f)
    write_j(f, [jrow(), jrow()])
    with pytest.raises(ValueError):  # 중복
        load_judgments(f)


def test_norm_stops_on_unjudged_excess(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(cli, "JUDGMENTS", tmp_path / "none.csv")
    monkeypatch.setattr(cli, "load_targets", lambda p: [])
    monkeypatch.setattr(cli, "compare_all", lambda c, y, t: [row()])
    rc = cli.main(["norm"])
    assert rc == 1
    assert "norm 중단" in capsys.readouterr().err
    assert not list((tmp_path).glob("*"))  # norm 파일을 쓰지 않았다


def test_call_sites_request_raw_for_etf_and_adjusted_for_stock(tmp_path):
    """fetch_target·compare_target·write_norm 이 실제로 보내는 요청 매개변수: ETF 는 원주가("1"), 주식은 수정주가("0"). 기준일 실제 종가는 둘 다 "1"."""
    from datetime import date

    from kis import KisClient
    from pipeline import compare_target, fetch_target, write_norm
    from tests.fakes import FakeClock, FakeSession, Resp, cfg, token_body, weekdays
    from tests.test_coverage import yf_fetcher  # noqa: F401  (합성 yfinance)

    seg = Segment(date(2022, 12, 1), date(2023, 3, 31), "KIS", date(2022, 12, 1))
    etf, stock = Target("KR", "069500", (seg,), None), Target("KR", "005930", (seg,), None)
    days = [d for d in weekdays(date(2022, 12, 1), 200) if d <= date(2023, 3, 31)]

    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        lo, hi = k["params"]["FID_INPUT_DATE_1"], k["params"]["FID_INPUT_DATE_2"]
        rows = [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(1000 + i)} for i, d in enumerate(days) if lo <= f"{d:%Y%m%d}" <= hi]
        return Resp({"rt_cd": "0", "output2": sorted(rows, key=lambda r: r["stck_bsop_date"], reverse=True)[:100]})

    sess = FakeSession(h)
    cl = KisClient(cfg(tmp_path), sess, tmp_path / "raw", FakeClock())
    yf = yf_fetcher(tmp_path)

    def adj(ticker, base):
        return {c["params"]["FID_ORG_ADJ_PRC"] for c in sess.calls if "params" in c and c["params"]["FID_INPUT_ISCD"] == ticker
                and (c["params"]["FID_INPUT_DATE_1"] == c["params"]["FID_INPUT_DATE_2"]) == base}

    base = date(2023, 3, 31)
    for step in (lambda t: fetch_target(cl, t, base_date=base),
                 lambda t: compare_target(cl, yf, t),
                 lambda t: write_norm(cl, yf, [t], {}, tmp_path / "norm", base_date=base)):
        sess.calls.clear()
        cl.cache_hits = 0
        tmp_clear = tmp_path / "raw"
        for p in tmp_clear.glob("*.json"):
            p.unlink()  # 캐시를 비워 요청이 실제로 나가게 한다
        for t in (etf, stock):
            step(t)
        assert adj("069500", False) == {"1"} and adj("005930", False) == {"0"}, step
        if any(adj(t, True) for t in ("069500", "005930")):
            assert adj("069500", True) == {"1"} and adj("005930", True) == {"1"}
