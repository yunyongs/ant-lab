"""받을 종목·구간 정의, 정규화 합본(norm) 쓰기, 출처 대조. 계산(월말 선택·수익률 반올림)은 SQL 몫이다.

여기의 월 수익률은 출처끼리 견주는 대조 전용이며 어떤 CSV에도 쓰이지 않는다. Decimal만 쓴다(float 금지).

출처 규칙 (단일 출처): 한 (종목 × 구간)은 한 출처에서만 받는다.
  국내 전 구간과 미국 구간 ②는 KIS, 미국 구간 ①(2006-12–2012-04)은 KIS 가 2007-08-20 이전을 주지 않아(P1 실측) 통째로 yfinance.
  KIS 가 줄 수 있는 겹치는 기간은 대조에만 쓴다.
"""
from __future__ import annotations

import csv
import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from kis import KisClient, KisError
from yf import YfFetcher

SEGMENT_1 = (date(2006, 12, 1), date(2012, 4, 30))
SEGMENT_2 = (date(2022, 12, 1), date(2026, 1, 2))
BASE_DATE = date(2026, 1, 2)
# P1 실측: KIS 미국 일별은 약 2007-08-20 이전이 빈 응답이다(날짜가 지나며 움직일 수 있다 — 재현 때 다시 확인).
US_KIS_FLOOR = date(2007, 8, 20)
# 받은 첫·끝 날짜가 요청 구간 끝에서 이만큼의 영업일(월–금) 안에 있어야 한다 (부분 응답을 조용히 저장하지 않는다)
EDGE_BDAYS = 5

# 표본 월 블록 (build_sample.sql 의 p_segment 와 같다). 대조는 이 달들만 본다.
SAMPLE_MONTH_RANGES = (("2007-01", "2012-04"), ("2023-01", "2025-12"))

# 해외 거래소 코드 (KIS 는 지금의 상장 거래소로 조회한다).
# SPY·GLD 는 NYSE Arca = AMS (P1 실측). TLT·SHY 는 AMS 가 아니라 NAS (T2 실측: AMS·NYS 는 전 기간 0행, NAS 는 행이 나온다).
EXCD = {
    "AAPL": "NAS", "MSFT": "NAS", "NVDA": "NAS", "QQQ": "NAS", "TLT": "NAS", "SHY": "NAS",
    "JNJ": "NYS", "KO": "NYS", "XOM": "NYS",
    "SPY": "AMS", "GLD": "AMS",
}
# KIS 에 구간 ① 일별이 없는 미국 종목 (T2 실측: NAS 로 BYMD 2014-12-31 이전은 0행, 2018-12-31 에는 행이 있다).
# 이 종목의 구간 ① 은 yfinance 단독이라 KIS 와 대조할 수 없다 (대조 보고에 NO_OVERLAP 으로 남긴다).
NO_KIS_OVERLAP = {("US", "TLT"), ("US", "SHY")}
# 구간 ①이 없는 종목(상장이 늦어 대체 수익률을 쓴다)
SEGMENT2_ONLY = {("KR", "360750"), ("KR", "411060")}
# 국내 ETF 는 원주가(FID_ORG_ADJ_PRC=1)로 받는다: 수정주가(0)는 분배금을 반영해 asset.return_basis=PRICE 선언과 어긋난다 (T2 실측).
# 분할·병합이 없는지는 yfinance Close(분할만 조정)와의 대조로 확인한다.
KR_ETF_RAW = {("KR", "069500"), ("KR", "091160"), ("KR", "360750"), ("KR", "411060")}
# 대체 수익률의 기초 (asset 목록에 없다): 구간 ①만 받는다
PROXY_BASES = [("US", "GLD")]  # SPY 는 asset 에 있다
PROXY_BASE_KEYS = {(m, t) for m, t in PROXY_BASES}

NORM_DAILY_HEADER = ["market", "ticker", "trade_date", "close", "close_price", "close_raw", "source"]
NORM_DIV_HEADER = ["market", "ticker", "ex_date", "amount", "source"]
NORM_SHARES_HEADER = ["market", "ticker", "as_of", "shares", "source"]


@dataclass(frozen=True)
class Segment:
    start: date
    end: date
    source: str  # 정본 출처: "KIS" | "YF"
    kis_from: date | None  # KIS 로 받는 시작일 (정본이면 start, yfinance 정본이면 KIS 가 줄 수 있는 첫날 = 대조 구간, KIS 에 없으면 None)


@dataclass(frozen=True)
class Target:
    market: str
    ticker: str
    segments: tuple[Segment, ...]
    excd: str | None

    @property
    def kis_adjusted(self) -> bool:
        """KIS 일별·월봉 요청의 수정 여부. 국내 ETF 는 원주가(False), 나머지는 수정주가/MODP=1(True)."""
        return (self.market, self.ticker) not in KR_ETF_RAW

    @property
    def symbol(self) -> str:
        """yfinance 심볼."""
        return self.ticker if self.market == "US" else f"{self.ticker}.KS"


def _segment(window: tuple[date, date], market: str, ticker: str = "") -> Segment:
    start, end = window
    if market == "US" and window == SEGMENT_1:
        return Segment(start, end, "YF", None if (market, ticker) in NO_KIS_OVERLAP else US_KIS_FLOOR)
    return Segment(start, end, "KIS", start)


def load_targets(asset_csv: Path) -> list[Target]:
    out: list[Target] = []
    with Path(asset_csv).open(encoding="utf-8", newline="") as h:
        for r in csv.DictReader(h):
            m, t = r["market"], r["ticker"]
            wins = (SEGMENT_2,) if (m, t) in SEGMENT2_ONLY else (SEGMENT_1, SEGMENT_2)
            out.append(Target(m, t, tuple(_segment(w, m, t) for w in wins), EXCD.get(t) if m == "US" else None))
    for m, t in PROXY_BASES:
        out.append(Target(m, t, (_segment(SEGMENT_1, m, t),), EXCD[t]))
    return sorted(out, key=lambda x: (x.market, x.ticker))


# -- 영업일·부분 응답 검사 ------------------------------------------------------------
def _bdays_between(a: date, b: date) -> int:
    """a 와 b 사이(a 다음 날부터 b 까지)의 월–금 일수. a ≥ b 면 0."""
    n, d = 0, a
    while d < b:
        d += timedelta(days=1)
        if d.weekday() < 5:
            n += 1
    return n


def check_edges(rows: list[dict], tg: Target, start: date, end: date, n: int = EDGE_BDAYS) -> list[dict]:
    """일별 응답이 비었거나, 첫 날짜가 start + n 영업일보다 늦거나, 끝 날짜가 end − n 영업일보다 이르면 KisError.

    KIS 는 너무 먼 과거를 오류가 아니라 일부만/빈 응답으로 준다. 그 부분 응답이 조용히 저장되면 안 된다.
    """
    where = f"{tg.market}:{tg.ticker} {start}~{end}"
    if not rows:
        raise KisError(f"{where}: 받은 행이 0이다 — 필드 이름·종목 코드·거래소 코드·과거 깊이 확인")
    first = datetime.strptime(rows[0]["date"], "%Y%m%d" if "-" not in rows[0]["date"] else "%Y-%m-%d").date()
    last = datetime.strptime(rows[-1]["date"], "%Y%m%d" if "-" not in rows[-1]["date"] else "%Y-%m-%d").date()
    if _bdays_between(start, first) > n:
        raise KisError(f"{where}: 받은 첫 날짜가 {first} 로 구간 시작보다 {_bdays_between(start, first)}영업일 늦다 (허용 {n}) — 부분 응답")
    if _bdays_between(last, end) > n:
        raise KisError(f"{where}: 받은 끝 날짜가 {last} 로 구간 끝보다 {_bdays_between(last, end)}영업일 이르다 (허용 {n}) — 부분 응답")
    return rows


def require_rows(rows: list[dict], tg: Target, start: date, end: date) -> list[dict]:
    """(이전 이름) 일별 응답 검사. check_edges 와 같다."""
    return check_edges(rows, tg, start, end)


def month_window(a: date, b: date) -> tuple[date, date]:
    """월봉으로 받을 구간: a 부터 b 이전의 마지막 '꽉 찬 달'의 말일까지. 월봉 날짜는 그 달 마지막 거래일이라
    b 가 달 중간이면 그 달 막대는 b 보다 뒤 날짜이므로 요청에서 빠진다 (b 가 말일이면 b 의 달까지 포함)."""
    if (b + timedelta(days=1)).month != b.month:
        return a, b
    return a, b.replace(day=1) - timedelta(days=1)


def check_months(rows: list[dict], tg: Target, start: date, end: date) -> list[dict]:
    """월봉 응답이 start 달부터 end 달까지 빠진 달 없이 이어지는지 본다."""
    where = f"{tg.market}:{tg.ticker} 월봉 {start}~{end}"
    if not rows:
        raise KisError(f"{where}: 받은 행이 0이다")
    got = {r["date"][:7] for r in rows}
    want, y, m = [], start.year, start.month
    while (y, m) <= (end.year, end.month):
        want.append(f"{y}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    missing = [w for w in want if w not in got]
    if missing:
        raise KisError(f"{where}: 빠진 달 {len(missing)}개 (예: {missing[0]}) — 부분 응답")
    return rows


def required_canonical_months(seg: Segment) -> list[str]:
    """정본 구간에서 수익률 계산에 필요한 달: 구간 안의 표본 달 전부 + 각 달의 직전 달(정본 시리즈가 시작하는 달 이후일 때).

    대조 예정 달(expected_cross_months)과 별개다 — 대조 쪽 범위에 상관없이 정본 자체가 빠진 달 없이 이어져야 한다.
    """
    first = f"{(seg.kis_from if seg.source == 'KIS' else seg.start):%Y-%m}"
    lo, hi = f"{seg.start:%Y-%m}", f"{seg.end:%Y-%m}"
    need: set[str] = set()
    for m in sample_months():
        if lo <= m <= hi and m >= first:
            need.add(m)
            if _prev_month(m) >= first:
                need.add(_prev_month(m))
    return sorted(need)


def check_canonical_months(dates: list[str], tg: Target, seg: Segment) -> None:
    """정본 일별에 필요한 달이 모두 있는지 본다. 빠지면 KisError — 사람 판정으로 풀지 않는다(원본 다시 받기)."""
    got = {d[:7] for d in dates}
    missing = [m for m in required_canonical_months(seg) if m not in got]
    if missing:
        raise KisError(f"{tg.market}:{tg.ticker} 구간 {seg.start}~{seg.end} 정본({seg.source}) 일별에 빠진 달 {len(missing)}개 (예: {missing[0]}) — 부분 응답")


def fetch_target(client: KisClient, tg: Target, base_date: date = BASE_DATE, monthly: bool = True) -> None:
    """한 종목을 KIS 에서 받아 원본 캐시에 둔다: 구간별 수정 일별 종가 + 월봉(내부 대조) + 기준일 실제 종가.

    yfinance 정본 구간은 KIS 가 줄 수 있는 날짜부터(kis_from) 받는다 — 대조용이다.
    """
    for seg in tg.segments:
        if seg.kis_from is None:
            continue  # KIS 에 이 구간 일별이 없다 (NO_KIS_OVERLAP)
        a, b = seg.kis_from, seg.end
        kis_daily = check_edges(client.daily_range(tg.market, tg.ticker, a, b, adjusted=tg.kis_adjusted, excd=tg.excd), tg, a, b)
        if seg.source == "KIS":
            check_canonical_months([r["date"] for r in kis_daily], tg, seg)
        if monthly:
            ma, mb = month_window(a, b)
            check_months(client.daily_range(tg.market, tg.ticker, ma, mb, adjusted=tg.kis_adjusted, excd=tg.excd, period="M"), tg, ma, mb)
    if (tg.market, tg.ticker) not in PROXY_BASE_KEYS:
        check_edges(client.daily_range(tg.market, tg.ticker, base_date, base_date, adjusted=False, excd=tg.excd), tg, base_date, base_date)


def load_yf_rows(yf: YfFetcher, tg: Target, seg: Segment) -> list[tuple[str, str, str]]:
    """구간의 yfinance 일별 (날짜, Close, Adj Close). yfinance 가 정본인 구간은 끝점 검사(부분 응답 거부)를 한다.

    국내 대조용 시리즈는 듬성해도 되므로 끝점 검사 대신 대조에서 GAP 으로 본다.
    """
    rows = yf.series(tg.symbol, seg.start, seg.end)
    if seg.source == "YF":
        check_edges([{"date": d} for d, _, _ in rows], tg, seg.start, seg.end)
        check_canonical_months([d for d, _, _ in rows], tg, seg)
    return rows


def load_segment(client: KisClient, yf: YfFetcher, tg: Target, seg: Segment, both: bool) -> tuple[list[dict] | None, list[tuple[str, str, str]] | None]:
    """한 (종목 × 구간)의 일별 원본을 읽고 부분 응답 검사(끝점 + 정본의 필요한 달)를 건다.
    compare·norm 이 이 함수를 쓰고, fetch 는 같은 검사 함수(check_edges·check_canonical_months)를
    fetch_target(KIS)·load_yf_rows(yfinance, fetch_yf 경유)에서 건다 — 검사 내용은 같고 호출 경로만 다르다.

    돌려주는 값 (KIS 행 | None, yfinance 행 | None). 정본 출처는 항상 읽고, both 이면 대조 쪽도 읽는다.
    KIS 행은 kis_from 부터 구간 끝까지(없으면 None), yfinance 행은 (Close, Adj Close).
    """
    kis_rows = yf_rows = None
    if seg.kis_from is not None and (seg.source == "KIS" or both):
        kis_rows = check_edges(_kis_series(client, tg, seg), tg, seg.kis_from, seg.end)
        if seg.source == "KIS":
            check_canonical_months([r["date"] for r in kis_rows], tg, seg)
    if seg.source == "YF" or both:
        yf_rows = load_yf_rows(yf, tg, seg)
    return kis_rows, yf_rows


def fetch_yf(yf: YfFetcher, tg: Target) -> None:
    """한 종목의 구간별 yfinance 일별(Close·Adj Close)과 미국 배당을 받아 원본 캐시에 둔다."""
    for seg in tg.segments:
        load_yf_rows(yf, tg, seg)
    if tg.market == "US" and (tg.market, tg.ticker) not in PROXY_BASE_KEYS:
        yf.dividends(tg.ticker)


# -- 정규화 합본 ------------------------------------------------------------------------
def _check_decimal(text: str, where: str) -> str:
    try:
        v = Decimal(text)
    except Exception:
        raise ValueError(f"{where}: 숫자가 아니다 {text!r}") from None
    if not v.is_finite() or v <= 0:
        raise ValueError(f"{where}: 0 이하이거나 유한하지 않다 {text!r}")
    return text


def _kis_series(client: KisClient, tg: Target, seg: Segment) -> list[dict]:
    return client.daily_range(tg.market, tg.ticker, seg.kis_from, seg.end, adjusted=tg.kis_adjusted, excd=tg.excd)


def write_norm(client: KisClient, yf: YfFetcher, targets: list[Target], dividends: dict[str, list[tuple[str, str]]],
               norm_dir: Path, base_date: date = BASE_DATE) -> dict[str, str]:
    """원본 캐시에서 daily.csv / dividends.csv / shares.csv(머리글만) 를 쓴다. 파일 이름 → SHA-256 을 돌려준다.

    daily.csv 열: close = 수익률 계산 기준 종가(국내 수정 종가, 미국 KIS 는 MODP=1 종가, 미국 yfinance 구간은 Adj Close),
                  close_price = 배당 미조정(분할만 조정) 종가 — yfinance 구간에서만 채움(대체 수익률의 기초 SPY·GLD 용),
                  close_raw = 그날 실제 종가 (기준일 행만).
    KIS 가격에 배당을 더하는 경로는 없다: 미국 KIS 구간의 TOTAL 은 MODP=1 종가 그대로다.
    """
    norm_dir = Path(norm_dir)
    norm_dir.mkdir(parents=True, exist_ok=True)
    daily: list[list[str]] = []
    for tg in targets:
        raw = {}
        if (tg.market, tg.ticker) not in PROXY_BASE_KEYS:
            raw = {r["date"]: r["close"] for r in client.daily_range(tg.market, tg.ticker, base_date, base_date, adjusted=False, excd=tg.excd)}
        have: set[str] = set()
        for seg in tg.segments:
            kis_rows, yf_rows = load_segment(client, yf, tg, seg, both=False)
            if seg.source == "KIS":
                rows = [(r["date"], r["close"], "") for r in kis_rows]
            else:
                rows = [(d, adj, c) for d, c, adj in yf_rows]
            for d, close, price in rows:
                if not (seg.start <= date.fromisoformat(d) <= seg.end):
                    continue  # yfinance/KIS 가 구간 밖 날짜를 주면 쓰지 않는다
                where = f"{tg.market}:{tg.ticker} {d}"
                daily.append([tg.market, tg.ticker, d, _check_decimal(close, where),
                              _check_decimal(price, where) if price else "",
                              _check_decimal(raw[d], where) if d in raw else "", seg.source])
                have.add(d)
        missing = set(raw) - have
        if missing:
            raise ValueError(f"{tg.market}:{tg.ticker}: 기준일 실제 종가는 있는데 정본 종가 행이 없다 {sorted(missing)}")
    div_rows = [["US", t, d, _check_decimal(a, f"{t} 배당 {d}"), "YF"] for t in sorted(dividends) for d, a in dividends[t]]
    _write(norm_dir / "daily.csv", NORM_DAILY_HEADER, sorted(daily))
    _write(norm_dir / "dividends.csv", NORM_DIV_HEADER, sorted(div_rows))  # 참고용: build_sample.sql 은 읽지 않는다
    shares = norm_dir / "shares.csv"
    if not shares.exists():  # 상장주식수(§E)는 후속 단계. 머리글만 있으면 SQL 이 순위를 빈 칸으로 둔다
        _write(shares, NORM_SHARES_HEADER, [])
    return {p.name: sha256(p) for p in sorted(norm_dir.glob("*.csv"))}


def _write(path: Path, header: list[str], rows: list[list[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# -- 대조 --------------------------------------------------------------------------------
def month_end_closes(rows: list[tuple[str, str]]) -> dict[str, Decimal]:
    """(YYYY-MM-DD, 종가) → {YYYY-MM: 그 달 마지막 날짜의 종가}."""
    last: dict[str, tuple[str, str]] = {}
    for d, c in rows:
        m = d[:7]
        if m not in last or d > last[m][0]:
            last[m] = (d, c)
    return {m: Decimal(c) for m, (d, c) in last.items()}


def _prev_month(m: str) -> str:
    y, mo = int(m[:4]), int(m[5:7])
    return f"{y - 1}-12" if mo == 1 else f"{y}-{mo - 1:02d}"


def returns_from_month_ends(me: dict[str, Decimal]) -> dict[str, Decimal]:
    """직전 달 월말이 연속(바로 전 달)일 때만 월 수익률 = 월말/직전 월말 − 1."""
    return {m: c / me[_prev_month(m)] - 1 for m, c in me.items() if _prev_month(m) in me}


def monthly_returns(rows: list[tuple[str, str]]) -> dict[str, Decimal]:
    return returns_from_month_ends(month_end_closes(rows))


def sample_months() -> set[str]:
    out: set[str] = set()
    for a, b in SAMPLE_MONTH_RANGES:
        y, m = int(a[:4]), int(a[5:7])
        while f"{y}-{m:02d}" <= b:
            out.add(f"{y}-{m:02d}")
            y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


def expected_cross_months(seg: Segment) -> list[str]:
    """대조해야 하는 표본 달: 구간 안에 있고, 직전 달이 KIS 가 주는 첫 달(kis_from) 이후인 달 (수익률에 직전 월말이 필요하다)."""
    if seg.kis_from is None:
        return []
    lo, hi, first = f"{seg.start:%Y-%m}", f"{seg.end:%Y-%m}", f"{seg.kis_from:%Y-%m}"
    return sorted(m for m in sample_months() if lo <= m <= hi and _prev_month(m) >= first)


TOL_MATCH = Decimal("0.0001")
TOL_WARN = Decimal("0.0010")
BP = Decimal("10000")


def compare_returns(a: dict[str, Decimal], b: dict[str, Decimal]) -> list[dict]:
    """겹치는 달마다 차이와 판정(MATCH ≤1bp / WARN ≤10bp / STOP >10bp). 정렬된 달 순서."""
    out = []
    for m in sorted(set(a) & set(b)):
        diff = abs(a[m] - b[m])
        verdict = "MATCH" if diff <= TOL_MATCH else "WARN" if diff <= TOL_WARN else "STOP"
        out.append({"month": m, "diff": diff, "verdict": verdict})
    return out


def internal_verdict(market: str, bar: Decimal, daily: Decimal) -> str:
    """일별 월말 종가와 월봉 종가의 내부 대조. 같으면 EQUAL.

    국내 가격은 정수 원 단위라 분할 조정 가격을 정수로 반올림하는 자리에서 일봉과 월봉이 1원 어긋날 수 있다(T2 실측: 전부 1원).
    국내에서 차이가 1원 이하면 ROUND(허용), 그 밖의 차이는 DIFF(멈춤). 미국은 허용 없음.
    """
    if bar == daily:
        return "EQUAL"
    return "ROUND" if market == "KR" and abs(bar - daily) <= 1 else "DIFF"


# -- 사람 판정 (A.5 "사람이 본다") ------------------------------------------------------------
GAP_CODE = "CMP_GAP"  # 대조 불가(GAP) 칸의 판정 사유 코드. diff_bp 칸은 문자열 GAP
JUDGMENT_HEADER = ["market", "ticker", "segment", "month", "diff_bp", "canonical", "reason_code", "reason", "decision"]


def load_judgments(path: Path) -> dict[tuple[str, str, str, str], dict]:
    """판정 기록(git 무시 파일)을 읽는다. 키 = (market, ticker, segment, month). 파일이 없으면 빈 사전.

    decision 은 CANONICAL(그 칸의 정본 출처 값을 채택)만 허용하고, reason 이 비어 있으면 오류다.
    """
    p = Path(path)
    if not p.is_file():
        return {}
    out: dict[tuple[str, str, str, str], dict] = {}
    with p.open(encoding="utf-8", newline="") as h:
        rd = csv.DictReader(h)
        if rd.fieldnames != JUDGMENT_HEADER:
            raise ValueError(f"{p.name}: 머리글이 {JUDGMENT_HEADER} 와 다르다")
        for r in rd:
            key = (r["market"], r["ticker"], r["segment"], r["month"])
            if r["decision"] != "CANONICAL" or not r["reason"].strip() or key in out:
                raise ValueError(f"{p.name}: 판정 행 오류 {key} (decision=CANONICAL, reason 필수, 중복 불가)")
            if (r["reason_code"] == GAP_CODE) != (r["diff_bp"] == "GAP"):
                raise ValueError(f"{p.name}: 판정 행 오류 {key} ({GAP_CODE} 와 diff_bp=GAP 는 함께여야 한다)")
            out[key] = r
    return out


def apply_judgments(rows: list[dict], judgments: dict[tuple[str, str, str, str], dict]) -> tuple[list[dict], list[str]]:
    """STOP·GAP(CROSS) 행 중 판정 기록이 있고 정본이 같으며 차이가 맞으면 JUDGED 로 바꾼다.

    STOP: 차이 bp(소수 2자리)가 기록과 같아야 한다. GAP: 기록의 reason_code 가 CMP_GAP 이고 diff_bp 칸이 GAP 이어야 한다.
    GAP_CANONICAL(정본 쪽이 빈 달)은 판정으로 풀 수 없다. 판정이 있어도 맞지 않으면 그대로 두고(낡은 판정),
    쓰이지 않은 판정은 문제 목록에 돌려준다.
    """
    used: set[tuple[str, str, str, str]] = set()
    problems: list[str] = []
    out = []
    for r in rows:
        if r["verdict"] in ("STOP", "GAP") and r["kind"] == "CROSS":
            key = (r["market"], r["ticker"], str(r["segment"]), r["month"])
            j = judgments.get(key)
            if j is not None:
                if r["verdict"] == "STOP":
                    same = j["diff_bp"] == format(r["diff_bp"].quantize(Decimal("0.01")), "f") and j["reason_code"] != GAP_CODE
                else:
                    same = j["reason_code"] == GAP_CODE and j["diff_bp"] == "GAP"
                if same and j["canonical"] == r["canonical"]:
                    used.add(key)
                    r = {**r, "verdict": "JUDGED"}
                else:
                    problems.append(f"낡은 판정(차이·정본이 다르다) {key}")
        out.append(r)
    problems += [f"쓰이지 않은 판정 {k}" for k in sorted(set(judgments) - used)]
    return out, problems


def compare_all(client: KisClient, yf: YfFetcher, targets: list[Target]) -> list[dict]:
    rows: list[dict] = []
    for tg in targets:
        rows.extend(compare_target(client, yf, tg))
    return rows


def blocking(rows: list[dict]) -> list[dict]:
    return [r for r in rows if r["verdict"] in ("STOP", "DIFF", "GAP", "GAP_CANONICAL")]


def compare_target(client: KisClient, yf: YfFetcher, tg: Target) -> list[dict]:
    """한 종목의 (구간 × 달) 대조 행들. 가격 값은 담지 않는다 — 비율의 차이(bp)만.

    CROSS: KIS 일별 월 수익률 vs yfinance (국내 Close = 가격, 미국 Adj Close = 총수익률).
    INTERNAL: KIS 일별에서 고른 월말 종가 vs KIS 월봉 종가 (같은 출처라 같아야 한다) — 달마다 EQUAL/DIFF.
    """
    months = sample_months()
    out: list[dict] = []
    us = tg.market == "US"
    for i, seg in enumerate(tg.segments):
        seg_no = 1 if seg.start == SEGMENT_1[0] else 2
        if seg.kis_from is None:
            load_segment(client, yf, tg, seg, both=False)  # yfinance 단독 구간도 부분 응답이면 멈춘다
            out.append({"kind": "NO_OVERLAP", "market": tg.market, "ticker": tg.ticker, "segment": seg_no, "month": "",
                        "basis": "TOTAL" if us else "PRICE", "canonical": seg.source, "diff_bp": Decimal(0), "verdict": "NONE"})
            continue
        a, b = seg.kis_from, seg.end
        kis_raw, yf_raw = load_segment(client, yf, tg, seg, both=True)
        kis_rows = [(r["date"], r["close"]) for r in kis_raw]
        kis_ret = {m: v for m, v in monthly_returns(kis_rows).items() if m in months}
        yf_ret = {m: v for m, v in monthly_returns([(d, adj if us else c) for d, c, adj in yf_raw]).items() if m in months}
        basis = "TOTAL" if us else "PRICE"
        canon_ret, other_ret = (kis_ret, yf_ret) if seg.source == "KIS" else (yf_ret, kis_ret)
        for m in expected_cross_months(seg):
            row = {"kind": "CROSS", "market": tg.market, "ticker": tg.ticker, "segment": seg_no, "month": m,
                   "basis": basis, "canonical": seg.source}
            if m not in canon_ret:  # 정본 쪽이 비었다: 판정으로 풀 수 없는 차단
                out.append({**row, "diff_bp": Decimal(0), "verdict": "GAP_CANONICAL"})
            elif m not in other_ret:  # 대조 쪽만 비었다: 대조 불가 (판정 기록으로만 풀린다)
                out.append({**row, "diff_bp": Decimal(0), "verdict": "GAP"})
            else:
                (r,) = compare_returns({m: kis_ret[m]}, {m: yf_ret[m]})
                out.append({**row, "diff_bp": r["diff"] * BP, "verdict": r["verdict"]})
        # 내부 대조: 월봉
        ma, mb = month_window(a, b)
        bar_rows = check_months(client.daily_range(tg.market, tg.ticker, ma, mb, adjusted=tg.kis_adjusted, excd=tg.excd, period="M"), tg, ma, mb)
        bars = {r["date"][:7]: Decimal(r["close"]) for r in bar_rows}
        me = month_end_closes(kis_rows)
        for m in sorted(set(bars) & set(me)):
            if m not in months:
                continue
            out.append({"kind": "INTERNAL", "market": tg.market, "ticker": tg.ticker, "segment": seg_no, "month": m,
                        "basis": basis, "canonical": seg.source, "diff_bp": Decimal(0) if bars[m] == me[m] else abs(bars[m] / me[m] - 1) * BP,
                        "verdict": internal_verdict(tg.market, bars[m], me[m])})
    return out


def read_daily_norm(path: Path, market: str, ticker: str) -> list[tuple[str, str]]:
    with Path(path).open(encoding="utf-8", newline="") as h:
        return [(r["trade_date"], r["close"]) for r in csv.DictReader(h) if r["market"] == market and r["ticker"] == ticker]


def manifest_rows(raw_dir: Path, source: str = "KIS") -> list[list[str]]:
    rows = []
    for p in sorted(Path(raw_dir).glob("*.json")):
        doc = json.loads(p.read_text(encoding="utf-8"))
        n = len(doc["response"].get("output2") or [])
        rows.append([p.name, source, json.dumps(doc["request"], sort_keys=True, ensure_ascii=False), str(n), sha256(p)])
    return rows


def manifest_rows_yf(yf_dir: Path) -> list[list[str]]:
    rows = []
    for p in sorted([*Path(yf_dir).glob("yf_px_*.csv"), *Path(yf_dir).glob("yf_div_*.csv")]):
        n = max(0, len(p.read_text(encoding="utf-8").splitlines()) - 1)
        rows.append([p.name, "YF", p.name.removesuffix(".csv"), str(n), sha256(p)])
    return rows
