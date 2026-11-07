"""리다이렉트를 따라가지 않는다(3xx 는 오류) — 가짜 세션과 실제 requests.Session 둘 다."""
from datetime import date

import pytest
import requests
from requests.adapters import BaseAdapter

from kis import DOMESTIC_DAILY, TOKEN_PATH, KisClient, KisError
from tests.fakes import FAKE_KEY, FAKE_SECRET, FAKE_TOKEN, FakeClock, FakeSession, Resp, cfg, token_body


def _ok_handler(m, u, k):
    if u.endswith(TOKEN_PATH):
        return Resp(token_body())
    if "overseas" in u:
        return Resp({"rt_cd": "0", "output2": [{"xymd": "20070102", "clos": "1"}]})
    return Resp({"rt_cd": "0", "output2": [{"stck_bsop_date": "20070102", "stck_clpr": "1"}]})


def _client(tmp_path, handler):
    sess = FakeSession(handler)
    return KisClient(cfg(tmp_path), sess, tmp_path / "raw", FakeClock()), sess


def test_every_call_forbids_redirects(tmp_path):
    cl, sess = _client(tmp_path, _ok_handler)
    cl.domestic_page("005930", date(2007, 1, 1), date(2007, 1, 31), True)
    cl.overseas_page("SPY", "AMS", date(2007, 1, 31), True)
    assert [c["method"] for c in sess.calls] == ["post", "get", "get"]  # 토큰 POST, 국내 GET, 해외 GET
    assert all(c["allow_redirects"] is False for c in sess.calls)


def test_caller_cannot_enable_redirects(tmp_path):
    cl, sess = _client(tmp_path, _ok_handler)
    cl._send("post", TOKEN_PATH, json={}, allow_redirects=True)
    assert sess.calls[0]["allow_redirects"] is False


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
def test_get_redirect_is_error_one_request(tmp_path, status):
    def h(m, u, k):
        if u.endswith(TOKEN_PATH):
            return Resp(token_body())
        return Resp({}, status)
    cl, sess = _client(tmp_path, h)
    with pytest.raises(KisError) as e:
        cl.domestic_page("005930", date(2007, 1, 1), date(2007, 1, 31), True)
    assert f"HTTP {status}" in str(e.value)
    assert len(sess.calls) == 2  # 토큰 1 + 시세 1, 따라가지 않음
    assert FAKE_KEY not in str(e.value) and FAKE_SECRET not in str(e.value) and FAKE_TOKEN not in str(e.value)


def test_token_post_redirect_is_error_one_request(tmp_path):
    cl, sess = _client(tmp_path, lambda m, u, k: Resp({}, 307))
    with pytest.raises(KisError) as e:
        cl.token()
    assert "리다이렉트" in str(e.value)
    assert len(sess.calls) == 1
    assert FAKE_KEY not in str(e.value) and FAKE_SECRET not in str(e.value)


class _RedirectAdapter(BaseAdapter):
    """첫 요청에 리다이렉트를 돌려주고, 받은 요청을 모두 기록한다 (소켓 없음)."""

    def __init__(self, status, location):
        super().__init__()
        self.status, self.location, self.seen = status, location, []

    def send(self, request, **kwargs):
        self.seen.append(request)
        resp = requests.Response()
        resp.status_code = self.status
        resp.headers["Location"] = self.location
        resp.url = request.url
        resp.request = request
        resp._content = b"{}"
        resp.raw = None
        return resp

    def close(self):
        pass


@pytest.mark.parametrize("location", [
    "https://external.invalid/steal",
    "https://openapi.koreainvestment.com:9443/uapi/domestic-stock/v1/trading/inquire-balance",
])
@pytest.mark.parametrize("status,method", [(302, "get"), (307, "post")])
def test_real_requests_session_does_not_follow(tmp_path, status, method, location):
    adapter = _RedirectAdapter(status, location)
    session = requests.Session()
    session.mount("https://", adapter)
    cl = KisClient(cfg(tmp_path), session, tmp_path / "raw", FakeClock())
    with pytest.raises(KisError) as e:
        if method == "post":
            cl.token()
        else:
            cl._token = FAKE_TOKEN
            cl._get(DOMESTIC_DAILY, "FHKST03010100", {})
    assert len(adapter.seen) == 1
    assert "invalid" not in str(e.value) and FAKE_KEY not in str(e.value) and FAKE_SECRET not in str(e.value)
