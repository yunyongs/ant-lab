"""공통 도구: 비밀값 마스킹, .env 읽기, 설정.

비밀값(앱 키·시크릿·토큰)은 이 모듈을 거쳐서만 문자열이 된다. 로그와 예외 메시지에는 항상 mask()를 적용한다.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path

from dotenv import dotenv_values

MASK = "****"

# 저장소 루트(이 파일은 tools/fetch/common.py). 캐시·원본·norm 경로는 현재 작업 디렉터리가 아니라 여기를 기준으로 한다.
REPO_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = REPO_ROOT / "data" / "raw" / "p0-08"
DEFAULT_TOKEN_CACHE = REPO_ROOT / "data" / "cache" / "kis"
DEFAULT_ASSET_CSV = REPO_ROOT / "db" / "sample" / "asset.csv"

# .env 에서 읽는 변수 이름 (값은 어디에도 적지 않는다)
REQUIRED_ENV = ("KIS_APP_KEY", "KIS_APP_SECRET")
OPTIONAL_ENV = ("KIS_TOKEN_CACHE_DIR", "KIS_RPS")  # 도메인은 실전 고정이라 KIS_ENV 변수는 없다(남아 있으면 real 만 허용)

# 값을 몰라도 모양으로 가리는 패턴: 머리글 값, Bearer 토큰, JSON/쿼리의 키 이름 뒤 값
_PATTERNS = (
    re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-]+"),
    re.compile(r"(?i)((?:app[-_]?key|app[-_]?secret|access[-_]?token|token|secret|authorization)[\"']?\s*(?::|=|%3D)\s*[\"']?)[^\s\"',&}]+"),
)


def mask(text: object, secrets: list[str] | tuple[str, ...] = ()) -> str:
    """text 안의 비밀값을 ****로 바꾼다. 알려 준 값은 그대로, 나머지는 모양으로 가린다."""
    out = str(text)
    for s in secrets:
        if s and len(s) >= 4:
            out = out.replace(s, MASK)
    for p in _PATTERNS:
        out = p.sub(lambda m: m.group(1) + MASK, out)
    return out


class SecretFilter(logging.Filter):
    """로그 기록(메시지와 인자)에서 비밀값을 가린다."""

    def __init__(self, secrets: list[str]):
        super().__init__()
        self._secrets = secrets

    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = mask(record.getMessage(), self._secrets)
        record.args = ()
        if record.exc_info:  # log.exception / exc_info=True: 역추적 문자열도 가린다
            text = logging.Formatter().formatException(record.exc_info)
            record.exc_text = mask(text, self._secrets)
            record.exc_info = None
        elif record.exc_text:
            record.exc_text = mask(record.exc_text, self._secrets)
        return True


@dataclass(frozen=True)
class KisConfig:
    app_key: str = field(repr=False)
    app_secret: str = field(repr=False)
    token_cache_dir: Path
    rps: int

    def secrets(self) -> list[str]:
        return [self.app_key, self.app_secret]


def resolve_cache_dir(value: str | None) -> Path:
    """토큰 캐시 폴더. 상대 경로는 저장소 루트 기준으로 풀고, 저장소 밖이면 거부한다(.gitignore 가 닿지 않는다)."""
    if not value:
        return DEFAULT_TOKEN_CACHE
    p = Path(value)
    p = (p if p.is_absolute() else REPO_ROOT / p).resolve()
    if p != REPO_ROOT and REPO_ROOT not in p.parents:
        raise SystemExit("KIS_TOKEN_CACHE_DIR은 저장소 안의 폴더여야 한다")
    return p


def read_env(env_file: Path | None) -> dict[str, str]:
    """.env 파일을 읽어 dict 로 돌려준다 (os.environ 을 바꾸지 않는다). 파일이 없으면 빈 dict."""
    if env_file is None or not Path(env_file).is_file():
        return {}
    return {k: v for k, v in dotenv_values(env_file).items() if v is not None}


def env_status(values: dict[str, str]) -> dict[str, str]:
    """이름별 '있음'/'없음'만. 값은 돌려주지 않는다."""
    return {n: ("있음" if values.get(n) else "없음") for n in REQUIRED_ENV + OPTIONAL_ENV}


def load_config(values: dict[str, str], rps_override: int | None = None) -> KisConfig:
    missing = [n for n in REQUIRED_ENV if not values.get(n)]
    if missing:
        raise SystemExit("필수 변수가 없다: " + ", ".join(missing) + " (tools/fetch/README.md의 .env 작성법)")
    if values.get("KIS_ENV") not in (None, "", "real"):
        raise SystemExit("KIS_ENV는 더 쓰지 않는다 (실전 도메인 고정). 값이 남아 있으면 real 이어야 한다")
    rps = rps_override or int(values.get("KIS_RPS") or 4)
    if rps < 1:
        raise SystemExit("초당 건수는 1 이상이어야 한다")
    cache = resolve_cache_dir(values.get("KIS_TOKEN_CACHE_DIR"))
    return KisConfig(values["KIS_APP_KEY"], values["KIS_APP_SECRET"], cache, rps)


def dec(text: str) -> Decimal:
    """문자열 → Decimal. float 을 거치지 않는다."""
    return Decimal(text.strip())


def plain(d: Decimal) -> str:
    """지수 표기 없이 받은 자릿수 그대로."""
    return format(d, "f")
