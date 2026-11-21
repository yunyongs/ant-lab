"""Flatten the World Bank workbook into the CSV that `db/full/build_full.sql` reads (MVP-06 A). Offline, no database.

  PY=tools/fetch/.venv/bin/python
  $PY tools/fetch/aux_norm.py wb [--raw-dir data/raw/mvp-04] [--full db/full]

Reads `<raw>/aux/wb/CMO-Historical-Data-Monthly.xlsx` and writes `<raw>/aux/wb/monthly_prices.csv` with the header
`series,month,value`: one row per month with a value for each column named by a `WB` row of `proxy_series.csv`
(`series` = `source_code`, e.g. `Gold`; `month` = `YYYY-MM`; `value` is the 4-decimal month average). The SQL cannot read a
workbook, and the other aux files (FRED, ECOS, yfinance) are already CSV.
"""
from __future__ import annotations

import argparse
import csv
import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from common import REPO_ROOT  # noqa: E402
from kis import write_atomic  # noqa: E402
from proxy_map import FULL, SERIES_COLUMNS, read_csv  # noqa: E402
from proxy_sources import AuxError, read_pink_sheet  # noqa: E402

DEFAULT_RAW_DIR = REPO_ROOT / "data" / "raw" / "mvp-04"
WORKBOOK = "CMO-Historical-Data-Monthly.xlsx"
FLAT = "monthly_prices.csv"


def wb_columns(full: Path) -> list[str]:
    cols = [r["source_code"] for r in read_csv(full / "proxy_series.csv", SERIES_COLUMNS) if r["source"] == "WB"]
    if len(set(cols)) != len(cols):
        raise AuxError("proxy_series.csv names a World Bank column twice")
    return cols


def flatten_wb(raw: Path, full: Path = FULL) -> Path:
    cols = wb_columns(full)
    if not cols:
        raise AuxError("proxy_series.csv has no WB rows")
    book = Path(raw) / "aux" / "wb" / WORKBOOK
    if not book.is_file():
        raise AuxError(f"missing workbook {book.name}; run fetch_aux.py fetch --source WB first")
    data = read_pink_sheet(book, cols)
    rows = [(name, month, str(value)) for name in cols for month, value in sorted(data[name].items())]
    if not rows:
        raise AuxError("the workbook has no values for the requested columns")
    out = Path(raw) / "aux" / "wb" / FLAT
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(["series", "month", "value"])
    w.writerows(rows)
    write_atomic(out, buf.getvalue())
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("wb")
    w.add_argument("--raw-dir", default=str(DEFAULT_RAW_DIR))
    w.add_argument("--full", default=str(FULL))
    args = ap.parse_args(argv)
    try:
        out = flatten_wb(Path(args.raw_dir), Path(args.full))
    except AuxError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"wrote {out.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
