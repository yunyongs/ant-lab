"""네트워크 없는 가짜 세션·응답. 시험용 키는 모양이 뻔한 가짜 문자열이다 (실제 값이 아니다)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from common import KisConfig  # noqa: E402
from kis import Clock  # noqa: E402

FAKE_KEY = "FAKEKEY-abcdefghijklmnop"
FAKE_SECRET = "FAKESECRET-qrstuvwxyz0123456789"
FAKE_TOKEN = "FAKETOKEN.eyJhbGciOi.signature"


class Resp:
    def __init__(self, body: dict, status: int = 200):
        self._b, self.status_code = body, status

    def json(self):
        return self._b


class FakeSession:
    """요청을 기록하고, 응답 만드는 함수로 답한다."""

    def __init__(self, handler):
        self.handler = handler
        self.calls: list[dict] = []

    def _do(self, method, url, **kw):
        self.calls.append({"method": method, "url": url, **kw})
        return self.handler(method, url, kw)

    def get(self, url, **kw):
        return self._do("get", url, **kw)

    def post(self, url, **kw):
        return self._do("post", url, **kw)


class FakeClock(Clock):
    def __init__(self):
        self.t = 1_000_000.0
        self.sleeps: list[float] = []
        super().__init__(now=lambda: self.t, sleep=self._sleep)

    def _sleep(self, s: float) -> None:
        self.sleeps.append(s)
        self.t += s


def cfg(tmp_path, rps=4) -> KisConfig:
    return KisConfig(FAKE_KEY, FAKE_SECRET, Path(tmp_path) / "cache", rps)


def token_body():
    return {"access_token": FAKE_TOKEN, "expires_in": 86400, "token_type": "Bearer"}


def day_rows_domestic(days: list[date], close="100"):
    return [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": str(close)} for d in sorted(days, reverse=True)]


def weekdays(start: date, n: int) -> list[date]:
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out
