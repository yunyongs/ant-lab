"""Fake KIS server for batch tests: deterministic weekday/month-end rows, injectable failures."""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from kis import DOMESTIC_DAILY, OVERSEAS_DAILY
from tests.fakes import FakeClock, Resp, token_body

KST = timezone(timedelta(hours=9))


def kst_epoch(y, m, d, hh, mm=0) -> float:
    return datetime(y, m, d, hh, mm, tzinfo=KST).timestamp()


def clock_at(y=2026, m=11, d=19, hh=21, mm=0) -> FakeClock:
    c = FakeClock()
    c.t = kst_epoch(y, m, d, hh, mm)
    return c


def _rows(period: str, first: date, cursor_end: date, floor: date) -> list[date]:
    lo = max(first, floor)
    days = [lo + timedelta(days=i) for i in range((cursor_end - lo).days + 1)] if cursor_end >= lo else []
    days = [d for d in days if d.weekday() < 5]
    if period == "M":
        last: dict[tuple[int, int], date] = {}
        for d in days:
            last[(d.year, d.month)] = d
        days = sorted(last.values())
    return sorted(days, reverse=True)[:100]


class FakeKis:
    def __init__(self, listings: dict[str, date] | None = None):
        self.listings = listings or {}
        self.tokens = 0
        self.gets = 0
        self.fail_at: dict[int, str] = {}  # 1-based GET number -> failure kind
        self.requested: list[dict] = []

    def handler(self, method, url, kw):
        if method == "post":
            self.tokens += 1
            return Resp(token_body())
        self.gets += 1
        kind = self.fail_at.get(self.gets)
        if kind == "reset":
            raise ConnectionError("Connection aborted.", ConnectionResetError(54, "Connection reset by peer"))
        if kind == "rate":
            return Resp({"msg_cd": "EGW00201", "rt_cd": "1"}, 500)
        if kind == "500":
            return Resp({"rt_cd": "1", "msg_cd": "X"}, 500)
        if kind == "rt":
            return Resp({"rt_cd": "1", "msg_cd": "X", "msg1": "bad"}, 200)
        if kind == "redirect":
            return Resp({}, 302)
        if kind == "nonjson":
            class Bad:
                status_code = 200

                def json(self):
                    raise ValueError("not json")
            return Bad()
        p = kw["params"]
        self.requested.append({"url": url, **p})
        if url.endswith(DOMESTIC_DAILY):
            t = p["FID_INPUT_ISCD"]
            start = datetime.strptime(p["FID_INPUT_DATE_1"], "%Y%m%d").date()
            end = datetime.strptime(p["FID_INPUT_DATE_2"], "%Y%m%d").date()
            ds = _rows(p["FID_PERIOD_DIV_CODE"], start, end, self.listings.get(t, date(1990, 1, 1)))
            return Resp({"rt_cd": "0", "output2": [{"stck_bsop_date": f"{d:%Y%m%d}", "stck_clpr": "100"} for d in ds]})
        assert url.endswith(OVERSEAS_DAILY)
        t = p["SYMB"]
        by = datetime.strptime(p["BYMD"], "%Y%m%d").date()
        period = "M" if p["GUBN"] == "2" else "D"
        ds = _rows(period, date(1990, 1, 1), by, self.listings.get(t, date(1990, 1, 1)))
        return Resp({"rt_cd": "0", "output2": [{"xymd": f"{d:%Y%m%d}", "clos": "100"} for d in ds]})
