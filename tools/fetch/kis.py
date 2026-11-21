"""KIS Open API 시세 조회 클라이언트 (시세 전용).

안전 장치
- 허용 목록(정확 일치): 토큰 발급 POST 1개와 시세 GET 2개(경로+TR ID)만 부른다. 그 밖의 메서드·경로·TR ID는 요청 전에 거부한다.
- 리다이렉트를 따라가지 않는다(3xx는 오류). 요청 머리글·본문이 다른 호스트·경로로 가지 못하게 한다.
- 비밀값 마스킹: 예외 메시지에 앱 키·시크릿·토큰이 들어가지 않는다.
- 토큰 캐시(data/cache/kis/token_real.json, 권한 600), 호출 간격, 재시도.
- 응답 원본 캐시: 같은 요청의 파일이 있으면 네트워크를 부르지 않는다.
"""
from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable

from common import KisConfig, mask

BASE_URL = "https://openapi.koreainvestment.com:9443"  # 실전 도메인 고정 (모의 도메인 없음)

TOKEN_PATH = "/oauth2/tokenP"
DOMESTIC_DAILY = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
OVERSEAS_DAILY = "/uapi/overseas-price/v1/quotations/dailyprice"
TR_DOMESTIC_DAILY = "FHKST03010100"
TR_OVERSEAS_DAILY = "HHDFS76240000"

# 정확 일치만 허용: (메서드, 경로) 3개, 시세 경로마다 TR ID 1개. 접두사·단어 목록은 쓰지 않는다.
ALLOWED = frozenset({("POST", TOKEN_PATH), ("GET", DOMESTIC_DAILY), ("GET", OVERSEAS_DAILY)})
ALLOWED_TR = {DOMESTIC_DAILY: TR_DOMESTIC_DAILY, OVERSEAS_DAILY: TR_OVERSEAS_DAILY}
_PATH_CHARS = re.compile(r"^[A-Za-z0-9/_-]+$")

TOKEN_REUSE_SECONDS = 3600  # 남은 시간이 이보다 길면 캐시 토큰을 다시 쓴다
TOKEN_RETRY_WAIT = 65  # 토큰 재발급은 1분에 1회
RATE_LIMIT_CODE = "EGW00201"
RATE_RETRY_WAITS = (1, 2, 4)
CONNECTION_RETRY_WAITS = (2, 5, 10)  # GET only: retries after a failure where no HTTP response arrived
DOMESTIC_PAGE = 100
OVERSEAS_PAGE = 100


class KisError(RuntimeError):
    """KIS 호출 실패. 메시지는 항상 마스킹되어 있다. reason 은 배치 중단 사유 분류다."""

    def __init__(self, *args: object, reason: str = "ERROR"):
        super().__init__(*args)
        self.reason = reason


class BudgetExhausted(KisError):
    """요청 상한에 닿았다. 네트워크 요청은 나가지 않았다."""


class ForbiddenPath(KisError):
    """허용 목록 밖의 경로 (주문·계좌 등)."""


def check_path(path: str, method: str = "GET", tr_id: str | None = None) -> str:
    """(메서드, 경로[, TR ID])가 허용 목록과 정확히 일치할 때만 경로를 돌려주고, 아니면 ForbiddenPath.

    경로 문자는 영문·숫자·/·_·- 뿐이다(공백·탭·NUL·백슬래시·%·?·. 거부). 시세 경로는 TR ID도 맞아야 한다.
    """
    if not isinstance(path, str) or not _PATH_CHARS.fullmatch(path):
        raise ForbiddenPath(f"허용되지 않는 경로 모양: {path!r}")
    if (str(method).upper(), path) not in ALLOWED:
        raise ForbiddenPath(f"허용 목록 밖의 요청: {str(method).upper()} {path!r}")
    if path in ALLOWED_TR and tr_id != ALLOWED_TR[path]:
        raise ForbiddenPath(f"허용되지 않는 TR ID: {path!r}")
    return path


def durable_sync(fd: int) -> None:
    """Flush to the drive where the platform can (macOS fsync does not); plain fsync elsewhere."""
    try:
        import fcntl

        fcntl.fcntl(fd, fcntl.F_FULLFSYNC)
        return
    except (ImportError, AttributeError, OSError):
        pass
    os.fsync(fd)


def write_atomic(path: Path, text: str) -> None:
    """임시 이름에 쓰고 이름을 바꾼다: 중간에 끊겨도 반쪽 파일이 최종 이름으로 남지 않는다."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8", newline="") as h:
        h.write(text)
        h.flush()
        os.fsync(h.fileno())
    os.replace(tmp, path)


@dataclass
class Clock:
    """시간과 대기를 바꿔 끼울 수 있게 한 것 (테스트는 가짜를 넣는다)."""

    now: Callable[[], float] = time.time
    sleep: Callable[[float], None] = time.sleep


def kis_symbol(ticker: str) -> str:
    """KIS spells US class shares with a slash (BRK/B); tracked tickers use a dot (BRK.B)."""
    return ticker.replace(".", "/")


class KisClient:
    def __init__(self, cfg: KisConfig, session: Any, raw_dir: Path, clock: Clock | None = None, offline: bool = False):
        self.cfg = cfg
        self._session = session
        self.raw_dir = Path(raw_dir)
        self.clock = clock or Clock()
        self.base = BASE_URL
        self.offline = offline  # True 면 원본 캐시에 없는 요청은 토큰 파일도 읽지 않고 오류가 된다 (compare·norm)
        self.requests_made = 0  # 네트워크 요청 횟수 (캐시 히트 시 늘지 않는다)
        self.cache_hits = 0  # 응답 원본 캐시를 쓴 횟수
        self._last_call = 0.0
        self._token: str | None = None
        self.max_requests: int | None = None  # 이 값에 닿으면 다음 요청은 보내지 않고 BudgetExhausted
        self.token_retry = True  # False 면 토큰 발급 실패 때 다시 시도하지 않는다 (배치: 하루 발급 1회)
        self.token_guard: Callable[[], None] | None = None  # 토큰 발급 요청 직전에 부른다 (거부하면 예외)
        self.on_send: Callable[[], None] | None = None  # called after the budget check, before each request leaves (journal, window)
        self.stamp_day: Callable[[], str] | None = None  # 있으면 응답 원본 파일에 받은 날(KST)을 적는다

    # -- 마스킹 ---------------------------------------------------------
    def _m(self, text: object) -> str:
        secrets = self.cfg.secrets() + ([self._token] if self._token else [])
        return mask(text, secrets)

    def _fail(self, message: str, reason: str = "ERROR") -> KisError:
        return KisError(self._m(message), reason=reason)

    # -- 호출 간격 --------------------------------------------------------
    def _throttle(self) -> None:
        gap = 1.0 / self.cfg.rps
        wait = self._last_call + gap - self.clock.now()
        if wait > 0:
            self.clock.sleep(wait)
        self._last_call = self.clock.now()

    def _refresh_session(self) -> None:
        """Drop pooled connections so a retry opens a fresh one (a stale keep-alive socket resets on reuse)."""
        close = getattr(self._session, "close", None)
        if callable(close):
            try:
                close()
            except Exception:
                pass

    def _send(self, method: str, path: str, **kwargs: Any) -> Any:
        tr_id = (kwargs.get("headers") or {}).get("tr_id")
        check_path(path, method, tr_id)
        retry_waits = CONNECTION_RETRY_WAITS if method == "get" else ()
        kwargs.pop("allow_redirects", None)  # 호출부가 바꿀 수 없다
        attempt = 0
        while True:
            if self.max_requests is not None and self.requests_made >= self.max_requests:
                raise BudgetExhausted(self._m(f"요청 상한 {self.max_requests}건에 닿았다 — 더 보내지 않는다"), reason="BUDGET")
            self._throttle()
            if self.on_send is not None:
                self.on_send()  # after the wait: nothing leaves after the window closes
            self.requests_made += 1
            try:
                resp = getattr(self._session, method)(self.base + path, timeout=30, allow_redirects=False, **kwargs)
                break
            except Exception as exc:  # requests 예외 메시지에 URL·머리글이 들어갈 수 있다
                if attempt >= len(retry_waits):
                    raise self._fail(f"{method.upper()} {path} 연결 실패: {type(exc).__name__}: {exc}", "CONNECTION") from None
                self.clock.sleep(retry_waits[attempt])
                attempt += 1
                self._refresh_session()
        status = getattr(resp, "status_code", 0)
        if isinstance(status, int) and 300 <= status < 400:
            raise self._fail(f"{method.upper()} {path}: 리다이렉트 응답 HTTP {status} — 따라가지 않는다", "REDIRECT")
        return resp

    # -- 토큰 ---------------------------------------------------------------
    def _token_file(self) -> Path:
        return self.cfg.token_cache_dir / "token_real.json"

    def token(self) -> str:
        if self._token:
            return self._token
        f = self._token_file()
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
            if data["expires_at"] - self.clock.now() > TOKEN_REUSE_SECONDS and data["access_token"]:
                self._token = data["access_token"]
                return self._token
        except (OSError, ValueError, KeyError, TypeError):
            pass
        self._token = self._issue_token(retry=True)
        return self._token

    def _issue_token(self, retry: bool) -> str:
        body = {"grant_type": "client_credentials", "appkey": self.cfg.app_key, "appsecret": self.cfg.app_secret}
        if self.token_guard is not None:
            self.token_guard()
        resp = self._send("post", TOKEN_PATH, json=body, headers={"content-type": "application/json"})
        payload = self._json(resp)
        tok = payload.get("access_token")
        if resp.status_code != 200 or not tok:
            if retry and self.token_retry:
                self.clock.sleep(TOKEN_RETRY_WAIT)
                return self._issue_token(retry=False)
            raise self._fail(f"토큰 발급 실패: HTTP {resp.status_code} {payload.get('msg_cd', '')} {payload.get('error_code', '')}", "TOKEN")
        ttl = int(payload.get("expires_in") or 0)
        self._write_token(tok, self.clock.now() + ttl)
        return tok

    def _write_token(self, tok: str, expires_at: float) -> None:
        d = self.cfg.token_cache_dir
        d.mkdir(parents=True, exist_ok=True)
        f = self._token_file()
        tmp = f.with_name(f.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as h:
            json.dump({"access_token": tok, "expires_at": expires_at}, h)
            h.flush()
            os.fsync(h.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, f)

    def _json(self, resp: Any) -> dict:
        try:
            out = resp.json()
        except Exception:
            raise self._fail(f"JSON이 아닌 응답: HTTP {getattr(resp, 'status_code', '?')}", "NON_JSON") from None
        if not isinstance(out, dict):
            raise self._fail("응답이 객체가 아니다", "NON_JSON")
        return out

    # -- 시세 요청 (재시도 포함) ------------------------------------------------
    def _get(self, path: str, tr_id: str, params: dict[str, str]) -> dict:
        check_path(path, "GET", tr_id)
        if self.offline:
            raise self._fail("네트워크를 쓰지 않는 명령이다 (원본 캐시가 모자란다: 먼저 fetch --yf)")
        headers = {
            "content-type": "application/json; charset=utf-8",
            "authorization": f"Bearer {self.token()}",
            "appkey": self.cfg.app_key,
            "appsecret": self.cfg.app_secret,
            "tr_id": tr_id,
            "custtype": "P",
        }
        for attempt in range(len(RATE_RETRY_WAITS) + 1):
            resp = self._send("get", path, headers=headers, params=params)
            payload = self._json(resp)
            if payload.get("msg_cd") == RATE_LIMIT_CODE:
                if attempt < len(RATE_RETRY_WAITS):
                    self.clock.sleep(RATE_RETRY_WAITS[attempt])
                    continue
                raise self._fail(f"호출 한도 초과가 계속된다 ({RATE_LIMIT_CODE})", "RATE_LIMIT")
            if resp.status_code != 200 or payload.get("rt_cd") not in (None, "0"):
                raise self._fail(f"{path} 실패: HTTP {resp.status_code} rt_cd={payload.get('rt_cd')} msg_cd={payload.get('msg_cd')} {payload.get('msg1', '')}",
                                 "HTTP" if resp.status_code != 200 else "RT_CD")
            return payload
        raise self._fail("재시도 소진")

    # -- 응답 원본 캐시 -----------------------------------------------------------
    def _cached(self, name: str, meta: dict, call: Callable[[], dict]) -> dict:
        f = self.raw_dir / f"{name}.json"
        try:
            hit = json.loads(f.read_text(encoding="utf-8"))["response"]
            if isinstance(hit, dict):
                self.cache_hits += 1
                return hit
        except (OSError, ValueError, KeyError, TypeError):
            pass  # 없거나 읽을 수 없는 파일은 없는 것과 같다
        body = call()
        doc: dict = {"request": meta, "response": body}
        if self.stamp_day is not None:
            doc["fetched_day"] = self.stamp_day()
        write_atomic(f, json.dumps(doc, ensure_ascii=False, sort_keys=True, indent=1))
        return body

    # -- 일별 시세 ----------------------------------------------------------------
    def domestic_page(self, ticker: str, start: date, end: date, adjusted: bool, period: str = "D") -> list[dict]:
        meta = {"api": "domestic", "ticker": ticker, "start": start.isoformat(), "end": end.isoformat(), "adjusted": adjusted, "period": period}
        name = f"kis_kr_{ticker}_{period}_{start:%Y%m%d}_{end:%Y%m%d}_{'adj' if adjusted else 'raw'}"
        params = {
            "FID_COND_MRKT_DIV_CODE": "J",
            "FID_INPUT_ISCD": ticker,
            "FID_INPUT_DATE_1": f"{start:%Y%m%d}",
            "FID_INPUT_DATE_2": f"{end:%Y%m%d}",
            "FID_PERIOD_DIV_CODE": period,  # D = 일, M = 월
            "FID_ORG_ADJ_PRC": "0" if adjusted else "1",  # 0 = 수정주가, 1 = 원주가 (이름과 반대)
        }
        body = self._cached(name, meta, lambda: self._get(DOMESTIC_DAILY, TR_DOMESTIC_DAILY, params))
        return self._rows(body, "stck_bsop_date", "stck_clpr", ticker)

    def overseas_page(self, ticker: str, excd: str, by_date: date, adjusted: bool, period: str = "D") -> list[dict]:
        gubn = {"D": "0", "M": "2"}[period]  # GUBN: 0 = 일, 1 = 주, 2 = 월
        meta = {"api": "overseas", "ticker": ticker, "excd": excd, "bymd": by_date.isoformat(), "adjusted": adjusted, "period": period}
        name = f"kis_us_{ticker}_{excd}_g{gubn}_{by_date:%Y%m%d}_modp{1 if adjusted else 0}"
        params = {
            "AUTH": "",
            "EXCD": excd,
            "SYMB": kis_symbol(ticker),
            "GUBN": gubn,
            "BYMD": f"{by_date:%Y%m%d}",
            "MODP": "1" if adjusted else "0",
        }
        body = self._cached(name, meta, lambda: self._get(OVERSEAS_DAILY, TR_OVERSEAS_DAILY, params))
        return self._rows(body, "xymd", "clos", ticker)

    def _rows(self, body: dict, date_key: str, close_key: str, ticker: str) -> list[dict]:
        """output2 에서 (날짜, 종가)를 뽑는다. 날짜 필드는 있는데 종가 필드가 없으면 명확한 오류(필드 이름 어긋남)."""
        out = []
        for r in body.get("output2") or []:
            if not r.get(date_key):
                continue
            if close_key not in r:
                raise self._fail(f"{ticker}: 응답 행에 종가 필드({close_key})가 없다 — 필드 이름 확인 필요(P1 probe)")
            out.append({"date": r[date_key], "close": r[close_key]})
        return out

    def daily_range(self, market: str, ticker: str, start: date, end: date, adjusted: bool, excd: str | None = None,
                    period: str = "D") -> list[dict]:
        """start–end 일별 종가를 연속 조회로 모두 받아 날짜 오름차순으로 돌려준다.

        국내는 (start, 커서) 구간을 100건씩, 해외는 커서 날짜부터 과거로 받는다.
        다음 커서 = 이번에 받은 가장 이른 날짜의 전날. 진행이 없으면 멈춘다.
        """
        got: dict[str, str] = {}
        seen_months: set[tuple[int, int]] = set()
        cursor = end
        for _ in range(500):  # 무한 반복 방지 상한
            if market == "KR":
                rows = self.domestic_page(ticker, start, cursor, adjusted, period)
            else:
                if not excd:
                    raise self._fail(f"{ticker}: 거래소 코드(EXCD)가 없다")
                rows = self.overseas_page(ticker, excd, cursor, adjusted, period)
            if not rows:
                break
            for r in rows:
                d = datetime.strptime(r["date"], "%Y%m%d").date()
                if start <= d <= end:
                    prior = got.get(d.isoformat())
                    if prior is not None and prior != r["close"]:
                        raise self._fail(f"{ticker} {d}: 같은 날짜에 다른 종가가 온다")
                    got[d.isoformat()] = r["close"]
            earliest = min(datetime.strptime(r["date"], "%Y%m%d").date() for r in rows)
            # 월봉의 날짜는 그 달의 말일 근처라 달 단위로 견준다 (시작이 1일이어도 그 달의 봉이 있으면 끝)
            reached = (earliest.year, earliest.month) <= (start.year, start.month) if period == "M" else earliest <= start
            if reached or earliest > cursor:
                break
            if market == "KR" and len(rows) < DOMESTIC_PAGE:
                break
            # US monthly: each page after the first starts with a partial bar for the cursor day, and past the
            # start of KIS history every page is only that bar. A page that adds no new month ends the sweep;
            # a short page that does add one (mid-history) keeps paging.
            if market != "KR" and period == "M":
                page_months = {(int(r["date"][:4]), int(r["date"][4:6])) for r in rows}
                if page_months <= seen_months:
                    break
                seen_months |= page_months
            nxt = earliest - timedelta(days=1)
            if nxt >= cursor:
                break
            cursor = nxt
        if period == "M":
            # The partial bar at a page boundary shares its month with the full bar on the neighbouring page.
            # The later date is the month-end bar (the partial one is cut at the cursor day), so it wins.
            by_month: dict[str, str] = {}
            for d in sorted(got):
                by_month[d[:7]] = d
            got = {d: got[d] for d in by_month.values()}
        return [{"date": d, "close": got[d]} for d in sorted(got)]
