import json
import os
import stat
from datetime import date

import pytest

from kis import ForbiddenPath, KisClient, KisError, check_path, DOMESTIC_DAILY, OVERSEAS_DAILY
from tests.fakes import (FAKE_KEY, FAKE_SECRET, FAKE_TOKEN, FakeClock, FakeSession, Resp, cfg,
                         day_rows_domestic, token_body, weekdays)


def make(tmp_path, handler, **kw):
    clock = FakeClock()
    sess = FakeSession(handler)
    return KisClient(cfg(tmp_path, **kw), sess, tmp_path / "raw", clock), sess, clock


# -- 경로 허용 목록 ------------------------------------------------------------
@pytest.mark.parametrize("path", [
    "/uapi/domestic-stock/v1/trading/order-cash",
    "/uapi/overseas-stock/v1/trading/order",
    "/uapi/hashkey",
    "/uapi/domestic-stock/v1/trading/inquire-balance",
    "/uapi/domestic-stock/v1/quotations/../trading/order-cash",
    "/uapi/domestic-stock/v1/quotations/%2e%2e/trading/order-cash",
    "/uapi/domestic-stock/v1/quotations/",
    "/uapi/domestic-stock/v1/quotations//x",
    "/oauth2/revokeP",
    "uapi/domestic-stock/v1/quotations/x",
    "",
])
def test_forbidden_paths_rejected(path):
    with pytest.raises(ForbiddenPath):
        check_path(path)


@pytest.mark.parametrize("method,path,tr", [("POST", "/oauth2/tokenP", None), ("GET", DOMESTIC_DAILY, "FHKST03010100"),
                                            ("GET", OVERSEAS_DAILY, "HHDFS76240000")])
def test_allowed_paths(method, path, tr):
    assert check_path(path, method, tr) == path


@pytest.mark.parametrize("path", [
    "/uapi/domestic-stock/v1/quotations/inquire-price",           # 접두사 아래 다른 경로
    "/uapi/overseas-price/v1/quotations/price",
    DOMESTIC_DAILY + "x", DOMESTIC_DAILY + "/",
    DOMESTIC_DAILY.replace("-", " "), DOMESTIC_DAILY + " ", DOMESTIC_DAILY + "\t",
    DOMESTIC_DAILY + "\x00", DOMESTIC_DAILY.replace("itemchartprice", "item\\chartprice"),
    DOMESTIC_DAILY.upper(), "/oauth2/tokenP?x=1", "https://openapi.koreainvestment.com:9443/oauth2/tokenP",
])
def test_exact_match_only_zero_requests(tmp_path, path):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._send("get", path, headers={"tr_id": "FHKST03010100"})
    with pytest.raises(ForbiddenPath):
        cl._get(path, "FHKST03010100", {})
    assert sess.calls == []


@pytest.mark.parametrize("tr_id", ["TTTC0012U", "TTTC0011U", "VTTC0012U", "", "HHDFS76240000", "fhkst03010100"])
def test_allowed_path_with_wrong_tr_id_zero_requests(tmp_path, tr_id):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._get(DOMESTIC_DAILY, tr_id, {})
    assert sess.calls == []


def test_overseas_path_needs_its_own_tr_id(tmp_path):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._get(OVERSEAS_DAILY, "FHKST03010100", {})
    assert sess.calls == []


@pytest.mark.parametrize("method", ["put", "delete", "patch", "head", "options", "PUT"])
@pytest.mark.parametrize("path", ["/oauth2/tokenP", DOMESTIC_DAILY, OVERSEAS_DAILY])
def test_other_methods_zero_requests(tmp_path, method, path):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._send(method, path, headers={"tr_id": "FHKST03010100"})
    assert sess.calls == []


def test_method_path_pairs_are_exact(tmp_path):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._send("get", "/oauth2/tokenP")           # 토큰 경로는 POST 만
    with pytest.raises(ForbiddenPath):
        cl._send("post", DOMESTIC_DAILY, headers={"tr_id": "FHKST03010100"})  # 시세 경로는 GET 만
    assert sess.calls == []


def test_missing_close_field_is_clear_masked_error(tmp_path):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        return Resp({"rt_cd": "0", "output2": [{"stck_bsop_date": "20070102"}]})
    cl, _, _ = make(tmp_path, h)
    with pytest.raises(KisError) as e:
        cl.domestic_page("005930", date(2007, 1, 1), date(2007, 1, 31), True)
    assert "stck_clpr" in str(e.value) and FAKE_KEY not in str(e.value)


def test_forbidden_path_makes_zero_requests(tmp_path):
    cl, sess, _ = make(tmp_path, lambda m, u, k: Resp({}))
    with pytest.raises(ForbiddenPath):
        cl._get("/uapi/domestic-stock/v1/trading/order-cash", "TTTC0012U", {})
    with pytest.raises(ForbiddenPath):
        cl._send("post", "/uapi/hashkey", json={})
    assert sess.calls == []


# -- 토큰 ----------------------------------------------------------------------
def test_token_issued_cached_with_600_and_reused(tmp_path):
    def h(m, u, k):
        assert u.endswith("/oauth2/tokenP")
        return Resp(token_body())
    cl, sess, _ = make(tmp_path, h)
    assert cl.token() == FAKE_TOKEN
    f = tmp_path / "cache" / "token_real.json"
    assert stat.S_IMODE(os.stat(f).st_mode) == 0o600
    cl2, sess2, _ = make(tmp_path, h)
    cl2.clock = cl.clock
    assert cl2.token() == FAKE_TOKEN
    assert sess2.calls == []  # 캐시 히트 — 요청 0


def test_token_near_expiry_is_reissued(tmp_path):
    cl, sess, clock = make(tmp_path, lambda m, u, k: Resp(token_body()))
    (tmp_path / "cache").mkdir()
    (tmp_path / "cache" / "token_real.json").write_text(json.dumps({"access_token": "old", "expires_at": clock.t + 600}))
    assert cl.token() == FAKE_TOKEN
    assert len(sess.calls) == 1


def test_token_failure_retries_once_after_65s_then_fails_masked(tmp_path):
    cl, sess, clock = make(tmp_path, lambda m, u, k: Resp({"error_code": "EGW00133", "error_description": f"too many {FAKE_KEY}"}, 403))
    with pytest.raises(KisError) as e:
        cl.token()
    assert len(sess.calls) == 2 and 65 in clock.sleeps
    assert FAKE_KEY not in str(e.value) and FAKE_SECRET not in str(e.value)


# -- 호출 간격 · 재시도 ----------------------------------------------------------
def quote_handler(rows_by_call):
    state = {"n": 0}

    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        state["n"] += 1
        return rows_by_call(state["n"], k)
    return h


def test_headers_and_rate_spacing(tmp_path):
    days = weekdays(date(2024, 1, 1), 10)
    cl, sess, clock = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "0", "output2": day_rows_domestic(days)})), rps=2)
    cl.domestic_page("005930", days[0], days[-1], True)
    cl.domestic_page("000660", days[0], days[-1], True)
    q = [c for c in sess.calls if "quotations" in c["url"]]
    assert q[0]["headers"]["tr_id"] == "FHKST03010100"
    assert q[0]["headers"]["authorization"] == f"Bearer {FAKE_TOKEN}"
    assert q[0]["params"]["FID_ORG_ADJ_PRC"] == "0" and q[0]["params"]["FID_COND_MRKT_DIV_CODE"] == "J"
    assert any(abs(s - 0.5) < 1e-6 for s in clock.sleeps)  # rps=2 → 요청 사이 0.5초
    cl.domestic_page("005930", days[0], days[-1], False)
    assert sess.calls[-1]["params"]["FID_ORG_ADJ_PRC"] == "1"


def test_rate_limit_error_backoff_1_2_4_then_fail(tmp_path):
    cl, sess, clock = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "1", "msg_cd": "EGW00201", "msg1": "초당 거래건수 초과"}, 500)))
    cl.token()
    clock.sleeps.clear()
    with pytest.raises(KisError):
        cl.domestic_page("005930", date(2024, 1, 1), date(2024, 1, 31), True)
    waits = [s for s in clock.sleeps if s >= 1]
    assert waits[:3] == [1, 2, 4]
    assert len([c for c in sess.calls if "quotations" in c["url"]]) == 4


def test_rate_limit_error_then_success(tmp_path):
    days = weekdays(date(2024, 1, 1), 3)
    cl, sess, clock = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "1", "msg_cd": "EGW00201"}, 500) if n == 1 else Resp({"rt_cd": "0", "output2": day_rows_domestic(days)})))
    assert len(cl.domestic_page("005930", days[0], days[-1], True)) == 3


def test_api_error_message_masked(tmp_path):
    cl, _, _ = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "1", "msg_cd": "X", "msg1": f"bad appkey={FAKE_KEY} Bearer {FAKE_TOKEN}"}, 400)))
    with pytest.raises(KisError) as e:
        cl.domestic_page("005930", date(2024, 1, 1), date(2024, 1, 31), True)
    s = str(e.value)
    assert FAKE_KEY not in s and FAKE_TOKEN not in s and "FAKETOKEN" not in s


def test_connection_error_message_masked(tmp_path):
    def h(m, u, k):
        if u.endswith("/oauth2/tokenP"):
            return Resp(token_body())
        raise ConnectionError(f"failed {k['headers']['appsecret']} {k['headers']['authorization']}")
    cl, _, _ = make(tmp_path, h)
    with pytest.raises(KisError) as e:
        cl.domestic_page("005930", date(2024, 1, 1), date(2024, 1, 31), True)
    s = str(e.value)
    assert FAKE_SECRET not in s and FAKE_TOKEN not in s


# -- 연속 조회 · 캐시 -------------------------------------------------------------
def test_domestic_continuation_100_per_page(tmp_path):
    days = weekdays(date(2023, 1, 2), 230)  # 3쪽 (100 + 100 + 30)
    start, end = days[0], days[-1]

    def rows(n, k):
        lo, hi = k["params"]["FID_INPUT_DATE_1"], k["params"]["FID_INPUT_DATE_2"]
        sel = [d for d in days if lo <= f"{d:%Y%m%d}" <= hi]
        return Resp({"rt_cd": "0", "output2": [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(1000 + i)}
                                              for i, d in sorted(enumerate(sel), key=lambda x: -x[0])[:100]]})
    cl, sess, _ = make(tmp_path, quote_handler(rows))
    out = cl.daily_range("KR", "005930", start, end, adjusted=True)
    assert [r["date"] for r in out] == [d.isoformat() for d in days]
    assert len([c for c in sess.calls if "quotations" in c["url"]]) == 3


def test_overseas_continuation_by_bymd(tmp_path):
    days = weekdays(date(2023, 1, 2), 150)

    def rows(n, k):
        cur = k["params"]["BYMD"]
        sel = [d for d in days if f"{d:%Y%m%d}" <= cur]
        return Resp({"rt_cd": "0", "output2": [{"xymd": f"{d:%Y%m%d}", "clos": "50.25"} for d in sorted(sel, reverse=True)[:100]]})
    cl, sess, _ = make(tmp_path, quote_handler(rows))
    out = cl.daily_range("US", "XOM", days[0], days[-1], adjusted=True, excd="NYS")
    assert len(out) == 150 and out[0]["date"] == days[0].isoformat()
    first = [c for c in sess.calls if "quotations" in c["url"]][0]
    assert first["params"]["MODP"] == "1" and first["params"]["EXCD"] == "NYS" and first["params"]["AUTH"] == ""


def test_conflicting_duplicate_close_rejected(tmp_path):
    days = weekdays(date(2024, 1, 1), 2)
    rows = [{"stck_bsop_date": f"{days[0]:%Y%m%d}", "stck_clpr": "1"}, {"stck_bsop_date": f"{days[0]:%Y%m%d}", "stck_clpr": "2"}]
    cl, _, _ = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "0", "output2": rows})))
    with pytest.raises(KisError):
        cl.daily_range("KR", "005930", days[0], days[1], adjusted=True)


def test_cache_hit_makes_zero_requests(tmp_path):
    days = weekdays(date(2024, 1, 1), 20)
    h = quote_handler(lambda n, k: Resp({"rt_cd": "0", "output2": day_rows_domestic(days)}))
    cl, sess, _ = make(tmp_path, h)
    first = cl.daily_range("KR", "005930", days[0], days[-1], adjusted=True)
    assert cl.requests_made >= 1
    cl2, sess2, _ = make(tmp_path, h)
    second = cl2.daily_range("KR", "005930", days[0], days[-1], adjusted=True)
    assert sess2.calls == [] and cl2.requests_made == 0
    assert first == second


def test_raw_cache_file_has_no_secrets(tmp_path):
    days = weekdays(date(2024, 1, 1), 5)
    cl, _, _ = make(tmp_path, quote_handler(lambda n, k: Resp({"rt_cd": "0", "output2": day_rows_domestic(days)})))
    cl.daily_range("KR", "005930", days[0], days[-1], adjusted=True)
    for p in (tmp_path / "raw").glob("*.json"):
        t = p.read_text()
        assert FAKE_KEY not in t and FAKE_SECRET not in t and FAKE_TOKEN not in t


def test_token_written_under_repo_root_even_from_other_cwd(tmp_path, monkeypatch):
    import shutil
    from common import REPO_ROOT, load_config
    other = tmp_path / "elsewhere"
    other.mkdir()
    monkeypatch.chdir(other)
    rel = "data/cache/_pytest_token_check"
    conf = load_config({"KIS_APP_KEY": FAKE_KEY, "KIS_APP_SECRET": FAKE_SECRET, "KIS_TOKEN_CACHE_DIR": rel})
    target = REPO_ROOT / rel
    try:
        cl = KisClient(conf, FakeSession(lambda m, u, k: Resp(token_body())), tmp_path / "raw", FakeClock())
        cl.token()
        assert (target / "token_real.json").is_file()
        assert list(other.rglob("*")) == []          # 실행 위치 밑에는 아무것도 안 생긴다
    finally:
        shutil.rmtree(target, ignore_errors=True)


def test_path_chars_check_is_overlapping_defense():
    # _PATH_CHARS 는 정확 일치 검사와 겹친 방어다. ALLOWED 를 바꾸지 않고도 숨은 문자가 붙은 허용 경로를 거부해야 한다.
    from kis import DOMESTIC_DAILY, ForbiddenPath, check_path

    for bad in (DOMESTIC_DAILY + "\x00", DOMESTIC_DAILY + " ", DOMESTIC_DAILY + "%2e", DOMESTIC_DAILY + "?x=1"):
        with pytest.raises(ForbiddenPath, match="경로 모양"):
            check_path(bad)
