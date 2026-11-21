"""Auxiliary public-series clients for the proxy map (MVP-03 A): FRED CSV, ECOS and one World Bank file.

Read-only, no database. Every request is checked against an exact allow-list before it leaves, nothing follows a redirect,
and the ECOS key (part of the URL path) never reaches a log line or an exception message. Raw answers are cached under
`data/raw/mvp-04/aux/{fred,ecos,wb}/` (git-ignored); a cached file means zero requests. Values stay `Decimal`.
"""
from __future__ import annotations

import csv
import io
import json
import re
import zipfile
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Callable
from xml.etree import ElementTree as ET

from common import mask
from kis import Clock, write_atomic

FRED_BASE = "https://fred.stlouisfed.org"
FRED_PATH = "/graph/fredgraph.csv"
ECOS_BASE = "https://ecos.bok.or.kr"
WB_BASE = "https://thedocs.worldbank.org"
# The file name is stable, the document id changes with each monthly release: re-read it from
# https://www.worldbank.org/en/research/commodity-markets when this answers 404.
WB_FILE_PATH = "/en/doc/74e8be41ceb20fa0da750cda2f6b9e4e-0050012026/related/CMO-Historical-Data-Monthly.xlsx"

INTERVAL = {"FRED": 2.0, "ECOS": 1.0, "WB": 2.0}
DEFAULT_CAP = {"FRED": 20, "ECOS": 40, "WB": 2}
HARD_MAX_CAP = {"FRED": 20, "ECOS": 40, "WB": 2}
ECOS_PAGE_ROWS = 10000
ECOS_PAGE_CAP = 20
MAX_BODY_BYTES = 16 * 1024 * 1024

_FRED_ID = re.compile(r"^[A-Z0-9_]{1,25}$")
_ECOS_STAT = re.compile(r"^[0-9A-Z]{1,12}$")
_ECOS_ITEM = re.compile(r"^[0-9A-Za-z]{1,20}$")
_ECOS_KEY = re.compile(r"^[A-Za-z0-9]{8,64}$")
_ECOS_SEARCH = re.compile(
    r"^/api/StatisticSearch/[A-Za-z0-9]{8,64}/json/kr/\d{1,9}/\d{1,9}/[0-9A-Z]{1,12}/([DM])/(\d{6}|\d{8})/(\d{6}|\d{8})/[0-9A-Za-z]{1,20}$")
_ECOS_ITEMS = re.compile(r"^/api/StatisticItemList/[A-Za-z0-9]{8,64}/json/kr/1/\d{1,5}/[0-9A-Z]{1,12}$")
_WB_FILE = re.compile(r"^/en/doc/[0-9a-f]{32}-\d{10}/related/CMO-Historical-Data-Monthly\.xlsx$")
_MONTH = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


class AuxError(RuntimeError):
    """A request or an answer was refused. Messages never hold the ECOS key or a URL."""


class AuxBudgetExhausted(AuxError):
    """The request cap was reached; nothing was sent."""


class ForbiddenRequest(AuxError):
    """A request outside the allow-list."""


# -- allow-lists ------------------------------------------------------------------------
def check_fred(method: str, url: str, params: dict[str, str]) -> None:
    ok = (str(method).upper() == "GET" and url == FRED_BASE + FRED_PATH and _FRED_ID.fullmatch(params.get("id", ""))
          and set(params) <= {"id", "cosd", "coed"}
          and all(re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) for k, v in params.items() if k != "id"))
    if not ok:
        raise ForbiddenRequest("FRED request refused: only GET fredgraph.csv with id (and optional cosd, coed) is allowed")


def check_ecos(method: str, url: str, params: dict[str, str] | None = None) -> None:
    path = url[len(ECOS_BASE):] if url.startswith(ECOS_BASE) else None
    ok = str(method).upper() == "GET" and not params and path is not None and (
        (m := _ECOS_SEARCH.fullmatch(path)) is not None and len(m.group(2)) == len(m.group(3)) == (8 if m.group(1) == "D" else 6)
        or _ECOS_ITEMS.fullmatch(path) is not None)
    if not ok:
        raise ForbiddenRequest("ECOS request refused: not an exact StatisticSearch or StatisticItemList path (path not shown)")


def check_wb(method: str, url: str, params: dict[str, str] | None = None) -> None:
    path = url[len(WB_BASE):] if url.startswith(WB_BASE) else None
    if not (str(method).upper() == "GET" and not params and path is not None and _WB_FILE.fullmatch(path)):
        raise ForbiddenRequest("World Bank request refused: only the commodity price workbook is allowed")


# -- the shared request path ---------------------------------------------------------------
@dataclass
class AuxClient:
    """One HTTP path for the three sources: allow-list, interval, request cap, no redirect, masked errors."""

    session: Any
    raw_dir: Path
    clock: Clock = field(default_factory=Clock)
    ecos_key: str = field(default="", repr=False)
    max_requests: dict[str, int] = field(default_factory=lambda: dict(DEFAULT_CAP))
    on_send: Callable[[str], None] | None = None
    requests_made: dict[str, int] = field(default_factory=lambda: {"FRED": 0, "ECOS": 0, "WB": 0})
    cache_hits: int = 0
    _last: dict[str, float] = field(default_factory=dict)

    def _m(self, text: object) -> str:
        return mask(text, [self.ecos_key] if self.ecos_key else [])

    def _get(self, source: str, url: str, params: dict[str, str] | None = None) -> bytes:
        check = {"FRED": lambda: check_fred("GET", url, params or {}), "ECOS": lambda: check_ecos("GET", url, params),
                 "WB": lambda: check_wb("GET", url, params)}[source]
        check()
        if self.requests_made[source] >= min(self.max_requests[source], HARD_MAX_CAP[source]):
            raise AuxBudgetExhausted(f"{source} request cap {self.max_requests[source]} reached; nothing sent")
        if self.on_send is not None:
            self.on_send(source)
        last = self._last.get(source)
        if last is not None:
            gap = last + INTERVAL[source] - self.clock.now()
            if gap > 0:
                self.clock.sleep(gap)
        self._last[source] = self.clock.now()
        self.requests_made[source] += 1
        try:
            resp = self.session.get(url, params=params, timeout=60, allow_redirects=False)
        except Exception as exc:  # the framework message carries the URL (and the ECOS key)
            raise AuxError(self._m(f"{source} request failed: {type(exc).__name__}")) from None
        status = getattr(resp, "status_code", None)
        if status != 200:
            raise AuxError(self._m(f"{source} answered HTTP {status}" + (" (redirect not followed)" if status and 300 <= status < 400 else "")))
        body = resp.content
        if len(body) > MAX_BODY_BYTES:
            raise AuxError(f"{source} answer is larger than {MAX_BODY_BYTES} bytes")
        return body

    # -- FRED ---------------------------------------------------------------------------
    def fred_csv(self, series_id: str) -> Path:
        """Whole-history keyless CSV of one FRED series. A parseable cached file means no request."""
        f = Path(self.raw_dir) / "fred" / f"{series_id}.csv"
        if f.is_file() and _try(read_fred_csv, f, series_id) is not None:
            self.cache_hits += 1
            return f
        body = self._get("FRED", FRED_BASE + FRED_PATH, {"id": series_id}).decode("utf-8", "replace")
        write_atomic(f, body.replace("\r\n", "\n"))
        if _try(read_fred_csv, f, series_id) is None:
            _discard(f)
            raise AuxError(f"FRED {series_id}: the answer is not a series CSV with header observation_date,{series_id}")
        return f

    # -- ECOS ---------------------------------------------------------------------------
    def _ecos_url(self, kind: str, *tail: str) -> str:
        if not _ECOS_KEY.fullmatch(self.ecos_key):
            raise AuxError("ECOS key missing or malformed (set ECOS_API_KEY in .env)")
        return "/".join([ECOS_BASE, "api", kind, self.ecos_key, "json", "kr", *tail])

    def ecos_items(self, stat: str) -> Path:
        """StatisticItemList of one table (item code, name, cycle, first and last period)."""
        if not _ECOS_STAT.fullmatch(stat):
            raise AuxError("ECOS statistic code must be 1-12 capital letters or digits")
        f = Path(self.raw_dir) / "ecos" / f"items_{stat}.json"
        if f.is_file() and _try(read_ecos_items, f) is not None:
            self.cache_hits += 1
            return f
        body = self._get("ECOS", self._ecos_url("StatisticItemList", "1", "1000", stat))
        write_atomic(f, body.decode("utf-8", "replace"))
        if _try(read_ecos_items, f) is None:
            _discard(f)
            raise AuxError(f"ECOS items {stat}: the answer has no item list")
        return f

    def ecos_series(self, stat: str, item: str, cycle: str, start: str, end: str) -> Path:
        """One statistic item as `time,value` rows (cycle D: YYYYMMDD bounds, M: YYYYMM). Paged; written only when complete."""
        if not (_ECOS_STAT.fullmatch(stat) and _ECOS_ITEM.fullmatch(item) and cycle in ("D", "M")):
            raise AuxError("ECOS statistic, item or cycle is malformed")
        width = 8 if cycle == "D" else 6
        if not (len(start) == len(end) == width and start.isdigit() and end.isdigit() and start <= end):
            raise AuxError("ECOS range does not fit the cycle")
        f = Path(self.raw_dir) / "ecos" / f"{stat}_{item}_{cycle}.csv"
        if f.is_file() and _try(read_ecos_csv, f) is not None:
            self.cache_hits += 1
            return f
        rows: list[tuple[str, str]] = []
        offset = 0
        for _ in range(ECOS_PAGE_CAP):
            body = self._get("ECOS", self._ecos_url("StatisticSearch", str(offset + 1), str(offset + ECOS_PAGE_ROWS), stat, cycle, start, end, item))
            try:
                doc = json.loads(body)
            except ValueError:
                raise AuxError(f"ECOS {stat}/{item}: the answer is not JSON") from None
            code = _ecos_result(doc)
            if code == "INFO-200" and offset == 0:
                raise AuxError(f"ECOS {stat}/{item}: no data in the range (INFO-200)")
            if code is not None:
                raise AuxError(f"ECOS {stat}/{item}: the service answered {code}")
            block = doc.get("StatisticSearch")
            if not isinstance(block, dict) or not isinstance(block.get("row"), list) or not isinstance(block.get("list_total_count"), int):
                raise AuxError(f"ECOS {stat}/{item}: the answer has no row list")
            for r in block["row"]:
                if r.get("STAT_CODE") != stat or r.get("ITEM_CODE1") != item:
                    raise AuxError(f"ECOS {stat}/{item}: a row belongs to another statistic or item")
                value = (r.get("DATA_VALUE") or "").strip()
                if value:
                    rows.append((str(r.get("TIME", "")), value))
            offset += len(block["row"])
            if offset >= block["list_total_count"]:
                break
            if not block["row"]:
                raise AuxError(f"ECOS {stat}/{item}: an empty page before the announced count")
        else:
            raise AuxError(f"ECOS {stat}/{item}: paging stopped after {ECOS_PAGE_CAP} pages")
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(["time", "value"])
        w.writerows(rows)
        write_atomic(f, buf.getvalue())
        if _try(read_ecos_csv, f) is None:
            _discard(f)
            raise AuxError(f"ECOS {stat}/{item}: the rows are not dated numbers")
        return f

    # -- World Bank ---------------------------------------------------------------------
    def wb_pink_sheet(self, path: str = WB_FILE_PATH) -> Path:
        f = Path(self.raw_dir) / "wb" / "CMO-Historical-Data-Monthly.xlsx"
        if f.is_file() and _try(read_pink_sheet, f, ["Gold"]) is not None:
            self.cache_hits += 1
            return f
        body = self._get("WB", WB_BASE + path)
        f.parent.mkdir(parents=True, exist_ok=True)
        tmp = f.with_name(f.name + ".tmp")
        tmp.write_bytes(body)
        tmp.replace(f)
        if _try(read_pink_sheet, f, ["Gold"]) is None:
            _discard(f)
            raise AuxError("World Bank: the answer is not the commodity price workbook (no Monthly Prices sheet with a Gold column)")
        return f


def _try(fn: Callable[..., Any], *args: Any) -> Any:
    try:
        return fn(*args)
    except (AuxError, OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError, InvalidOperation):
        return None


def _discard(f: Path) -> None:
    """A file that failed validation must not be read as a cache hit: truncate-rename it away."""
    bad = f.with_name(f.name + ".bad")
    f.replace(bad)


def _ecos_result(doc: Any) -> str | None:
    if not isinstance(doc, dict):
        raise AuxError("ECOS answer is not a JSON object")
    r = doc.get("RESULT")
    if isinstance(r, dict) and isinstance(r.get("CODE"), str):
        return r["CODE"]
    return None


# -- parsers (offline) -----------------------------------------------------------------------
def _dec(text: str) -> Decimal:
    d = Decimal(text.strip())
    if not d.is_finite():
        raise ValueError("not finite")
    return d


def read_fred_csv(path: Path, series_id: str) -> list[tuple[date, Decimal]]:
    """Dated values of a fredgraph CSV; FRED's `.` (no value) rows are skipped."""
    with Path(path).open(encoding="utf-8", newline="") as h:
        rd = csv.reader(h)
        head = next(rd)
        if head not in (["observation_date", series_id], ["DATE", series_id]):
            raise AuxError(f"FRED {series_id}: unexpected header")
        out = []
        for row in rd:
            if len(row) != 2:
                raise AuxError(f"FRED {series_id}: malformed row")
            if row[1].strip() in (".", ""):
                continue
            out.append((date.fromisoformat(row[0]), _dec(row[1])))
    if not out:
        raise AuxError(f"FRED {series_id}: no values")
    return out


def read_ecos_items(path: Path) -> list[dict[str, str]]:
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    block = doc.get("StatisticItemList")
    if not isinstance(block, dict) or not isinstance(block.get("row"), list) or not block["row"]:
        raise AuxError("ECOS item list missing")
    if block.get("list_total_count") != len(block["row"]):
        raise AuxError("ECOS item list is paged; the whole list is needed")
    return [{k: str(r.get(k, "")) for k in ("ITEM_CODE", "ITEM_NAME", "CYCLE", "START_TIME", "END_TIME", "DATA_CNT")} for r in block["row"]]


def read_ecos_csv(path: Path) -> list[tuple[str, Decimal]]:
    with Path(path).open(encoding="utf-8", newline="") as h:
        rd = csv.reader(h)
        if next(rd) != ["time", "value"]:
            raise AuxError("ECOS file header")
        out = [(t, _dec(v)) for t, v in rd]
    if not out or any(not t.isdigit() or len(t) not in (6, 8) for t, _ in out):
        raise AuxError("ECOS file rows")
    return out


_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
       "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}


def read_pink_sheet(path: Path, names: list[str]) -> dict[str, dict[str, Decimal]]:
    """Month-average series from the sheet `Monthly Prices`, by exact column title (e.g. `Gold`, `Copper`, `Crude oil, WTI`).

    The workbook is read with the standard library only. Values are rounded to 4 decimals (the sheet stores binary-float text).
    Months look like `1996M01`; the answer is keyed `YYYY-MM`. `…` and `..` (no value) rows are skipped.
    """
    with zipfile.ZipFile(path) as z:
        wb = ET.fromstring(z.read("xl/workbook.xml"))
        rid = next((s.get(f"{{{_NS['r']}}}id") for s in wb.find("m:sheets", _NS) if s.get("name") == "Monthly Prices"), None)
        if rid is None:
            raise AuxError("no Monthly Prices sheet")
        rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        target = next(r.get("Target") for r in rels if r.get("Id") == rid)
        shared = [("".join(t.text or "" for t in si.iter(f"{{{_NS['m']}}}t"))) for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", _NS)]
        sheet = ET.fromstring(z.read("xl/" + target.lstrip("/").removeprefix("xl/")))
    rows = sheet.find("m:sheetData", _NS).findall("m:row", _NS)

    def cells(r: ET.Element) -> dict[str, str]:
        out = {}
        for c in r.findall("m:c", _NS):
            v = c.find("m:v", _NS)
            if v is not None:
                out[re.match(r"[A-Z]+", c.get("r")).group()] = shared[int(v.text)] if c.get("t") == "s" else v.text
        return out

    header = next((cells(r) for r in rows[:12] if any(v == "Gold" for v in cells(r).values())), None)
    if header is None:
        raise AuxError("no header row with a Gold column")
    cols = {}
    for n in names:
        hit = [k for k, v in header.items() if v == n]
        if len(hit) != 1:
            raise AuxError(f"column {n!r} found {len(hit)} times")
        cols[n] = hit[0]
    out: dict[str, dict[str, Decimal]] = {n: {} for n in names}
    for r in rows:
        c = cells(r)
        m = re.fullmatch(r"(\d{4})M(\d{2})", c.get("A", ""))
        if not m:
            continue
        for n, col in cols.items():
            raw = c.get(col)
            if raw is None or raw in ("…", "..", "-", ""):
                continue
            out[n][f"{m.group(1)}-{m.group(2)}"] = _dec(raw).quantize(Decimal("0.0001"))
    return out


# -- spans (first and last month with a value) -------------------------------------------------
def month_of(text: str) -> str:
    """`YYYYMMDD`, `YYYYMM`, `YYYY-MM-DD` or `YYYY-MM` to `YYYY-MM`."""
    t = text.strip()
    if re.fullmatch(r"\d{8}|\d{6}", t):
        return f"{t[:4]}-{t[4:6]}"
    if re.fullmatch(r"\d{4}-\d{2}(-\d{2})?", t):
        return t[:7]
    raise ValueError(f"unreadable period {text!r}")


def span(months: list[str]) -> tuple[str, str]:
    if not months or not all(_MONTH.fullmatch(m) for m in months):
        raise ValueError("no months")
    return min(months), max(months)

