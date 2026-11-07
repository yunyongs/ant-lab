"""P0-08 가격 내려받기 도구. 저장소 루트에서 실행한다.

  tools/fetch/.venv/bin/python tools/fetch/fetch_p0_08.py check-env   # .env 변수 이름별 있음/없음 (값 출력 없음)
  ... probe     # KIS 과거 깊이 실측 (4종목, 행 수와 가장 이른 날짜만 출력)
  ... fetch     # KIS 일별·월봉 전체 + (--yf) yfinance 일별·미국 배당
  ... compare   # KIS 와 yfinance 월 수익률 대조 + 판정 기록 적용 (원본 캐시만, 네트워크 없음)
  ... norm      # 원본 캐시 → data/raw/p0-08/norm/{daily,dividends,shares}.csv (네트워크 없음)

비밀값은 .env 에서만 읽고, 로그·오류 메시지에는 마스킹한다. 이 도구는 DB 에 접속하지 않고 파일만 쓴다.
"""
from __future__ import annotations

import argparse
import csv
import logging
import sys
from datetime import date
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import DEFAULT_ASSET_CSV, RAW_DIR, REPO_ROOT, SecretFilter, env_status, load_config, mask, read_env  # noqa: E402
from kis import KisClient, KisError  # noqa: E402
from pipeline import (  # noqa: E402
    SEGMENT_1, US_KIS_FLOOR, Target, apply_judgments, blocking, check_edges, compare_all, fetch_target,
    fetch_yf, load_judgments, load_targets, manifest_rows, manifest_rows_yf, write_norm,
)
from yf import YfFetcher  # noqa: E402

RAW = RAW_DIR  # 저장소 루트 기준 절대 경로 (실행 위치와 무관)
JUDGMENTS = RAW / "judgments.csv"  # 사람 판정 기록 (git 무시, 없으면 판정 없음)
REPORT_HEADER = ["kind", "market", "ticker", "segment", "month", "basis", "canonical", "diff_bp", "verdict"]
log = logging.getLogger("fetch_p0_08")


def _client(args: argparse.Namespace) -> tuple[KisClient, list[str]]:
    import requests  # 지연 import

    cfg = load_config(read_env(Path(args.env_file)), args.rps)
    secrets = cfg.secrets()
    for h in logging.getLogger().handlers:
        h.addFilter(SecretFilter(secrets))
    session = requests.Session()
    session.trust_env = False  # 환경 변수 프록시·.netrc 를 타지 않는다
    return KisClient(cfg, session, RAW / "kis"), secrets


class _NoNet:
    """네트워크를 막는 가짜 세션: 원본 캐시가 모자라면 오류가 된다 (compare·norm 용)."""

    def __getattr__(self, name):
        raise KisError("네트워크를 쓰지 않는 명령이다 (원본 캐시가 모자란다: 먼저 fetch --yf)")


def _offline() -> tuple[KisClient, YfFetcher]:
    cfg = load_config({"KIS_APP_KEY": "x", "KIS_APP_SECRET": "x"}, None)

    def no_yf(symbol: str):
        raise KisError("네트워크를 쓰지 않는 명령이다 (yfinance 원본 캐시가 모자란다: 먼저 fetch --yf)")

    return KisClient(cfg, _NoNet(), RAW / "kis", offline=True), YfFetcher(RAW / "yf", no_yf)


def cmd_check_env(args: argparse.Namespace) -> int:
    path = Path(args.env_file)
    print(f"{path}: {'있음' if path.is_file() else '없음'}")
    for name, st in env_status(read_env(path)).items():
        print(f"  {name}: {st}")
    return 0


def cmd_probe(args: argparse.Namespace) -> int:
    client, _ = _client(args)
    end_kr, end_us = date(2007, 1, 31), date(2007, 10, 31)
    plan = (("KR", "005930", None, SEGMENT_1[0], end_kr), ("KR", "069500", None, SEGMENT_1[0], end_kr),
            ("US", "SPY", "AMS", US_KIS_FLOOR, end_us), ("US", "GLD", "AMS", US_KIS_FLOOR, end_us))
    for market, ticker, excd, start, end in plan:
        rows = client.daily_range(market, ticker, start, end, adjusted=True, excd=excd)
        check_edges(rows, Target(market, ticker, (), excd), start, end)  # 비었거나 부분 응답이면 KisError
        print(f"{market}:{ticker} 행 {len(rows)}, 가장 이른 날짜 {rows[0]['date']}")  # 가격 값은 출력하지 않는다
    return 0


def cmd_fetch(args: argparse.Namespace) -> int:
    client, _ = _client(args)
    targets = load_targets(Path(args.asset_csv))
    for tg in targets:
        fetch_target(client, tg)
        print(f"{tg.market}:{tg.ticker} 완료")
    print(f"KIS 네트워크 요청 {client.requests_made}건, 캐시 사용 {client.cache_hits}건")
    if args.yf:
        yf = YfFetcher(RAW / "yf")
        for tg in targets:
            fetch_yf(yf, tg)
        print(f"yfinance 요청 {yf.requests_made}건, 캐시 사용 {yf.cache_hits}건, 값 없는 행 {yf.nan_rows_dropped}개 건너뜀")
    return 0


def _judged_compare(targets: list[Target]) -> tuple[list[dict], list[str]]:
    """원본 캐시만으로 대조를 돌리고 판정 기록을 적용한다. (행, 문제 목록)"""
    client, yf = _offline()
    rows, problems = apply_judgments(compare_all(client, yf, targets), load_judgments(JUDGMENTS))
    return rows, problems


def _report(rows: list[dict], problems: list[str]) -> int:
    out = RAW / "compare"
    out.mkdir(parents=True, exist_ok=True)
    with (out / "compare_report.csv").open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(REPORT_HEADER)
        for r in rows:
            w.writerow([r["kind"], r["market"], r["ticker"], r["segment"], r["month"], r["basis"], r["canonical"],
                        format(r["diff_bp"].quantize(Decimal("0.01")), "f"), r["verdict"]])
    counts: dict[str, int] = {}
    for r in rows:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    print(f"대조 {len(rows)}건: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    for p in problems:
        print(f"판정 기록 문제: {p}", file=sys.stderr)
    return 1 if blocking(rows) or problems else 0


def cmd_compare(args: argparse.Namespace) -> int:
    rows, problems = _judged_compare(load_targets(Path(args.asset_csv)))
    return _report(rows, problems)


def cmd_norm(args: argparse.Namespace) -> int:
    targets = load_targets(Path(args.asset_csv))
    rows, problems = _judged_compare(targets)
    stops = blocking(rows)
    if stops or problems:
        print(f"norm 중단: 판정 없는 STOP·DIFF {len(stops)}건, 판정 기록 문제 {len(problems)}건 (compare 를 돌려 확인)", file=sys.stderr)
        return 1
    client, yf = _offline()
    divs: dict[str, list[tuple[str, str]]] = {}
    for p in sorted((RAW / "yf").glob("yf_div_*.csv")):
        divs[p.stem.removeprefix("yf_div_")] = yf_div_rows(p)
    hashes = write_norm(client, yf, targets, divs, RAW / "norm")
    for name, h in hashes.items():
        print(f"{name} sha256={h}")
    with (RAW / "MANIFEST.csv").open("w", encoding="utf-8", newline="") as h:
        w = csv.writer(h, lineterminator="\n")
        w.writerow(["file", "source", "request", "rows", "sha256"])
        w.writerows(manifest_rows(RAW / "kis"))
        w.writerows(manifest_rows_yf(RAW / "yf"))
    return 0


def yf_div_rows(path: Path) -> list[tuple[str, str]]:
    with path.open(encoding="utf-8", newline="") as h:
        r = csv.reader(h)
        next(r)
        return [(a, b) for a, b in r]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)

    def add_common(p: argparse.ArgumentParser, top: bool) -> None:
        # 하위 명령 앞·뒤 어디에 써도 같은 결과: 하위 파서는 기본값을 SUPPRESS 로 두어 앞에서 준 값을 덮지 않는다
        d = (lambda v: v) if top else (lambda v: argparse.SUPPRESS)
        p.add_argument("--env-file", default=d(str(REPO_ROOT / ".env")), help="비밀값 .env 파일 경로 (기본 저장소 루트 .env)")
        p.add_argument("--asset-csv", default=d(str(DEFAULT_ASSET_CSV)), help="종목 목록 CSV 경로 (기본 db/sample/asset.csv)")
        p.add_argument("--rps", type=int, default=d(None), help="초당 KIS 요청 상한 (기본 4)")

    add_common(ap, True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("check-env", "probe", "norm", "compare"):
        add_common(sub.add_parser(name), False)
    f = sub.add_parser("fetch")
    add_common(f, False)
    f.add_argument("--yf", action="store_true", help="yfinance 일별(Close·Adj Close)과 미국 배당도 받는다")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        return {"check-env": cmd_check_env, "probe": cmd_probe, "fetch": cmd_fetch,
                "norm": cmd_norm, "compare": cmd_compare}[args.cmd](args)
    except (KisError, ValueError) as exc:
        print(mask(str(exc)), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
