import csv
import os
import shutil
import subprocess
import time
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
from db_support import REPO_ROOT, engine_settings

from antlab_engine.history.reader import PostgresHistoryReader

pytestmark = [pytest.mark.db, pytest.mark.sample]

BASE_DATE = date(2026, 1, 2)
UPPER = date(2026, 1, 1)


def read_csv(directory: Path, name: str) -> list[dict[str, str]]:
    with (directory / name).open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


@pytest.fixture
def sample_db(fresh_db, tmp_path):
    sample_dir = Path(os.environ["ANTLAB_GOLDEN_SAMPLE_DIR"]).resolve()
    psql = shutil.which("psql")
    if psql is None:
        pytest.skip("psql is not installed")
    (tmp_path / "db").mkdir()
    (tmp_path / "db" / "sample").symlink_to(sample_dir, target_is_directory=True)
    params = fresh_db.admin_params
    result = subprocess.run(  # noqa: S603
        [
            psql,
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-h",
            params["host"],
            "-p",
            params["port"],
            "-U",
            params["user"],
            "-d",
            fresh_db.name,
            "-f",
            str(REPO_ROOT / "db" / "10_load_sample.sql"),
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr[-500:]
    return fresh_db, sample_dir


def test_h14_reads_the_sample_with_the_expected_counts(sample_db):
    db, sample_dir = sample_db
    started = time.perf_counter()
    snapshot = PostgresHistoryReader(engine_settings(db)).read(BASE_DATE)
    elapsed = time.perf_counter() - started

    months = [r["month_start"] for r in read_csv(sample_dir, "month_block.csv")]
    in_range = {m for m in months if date.fromisoformat(m) < UPPER}
    pool = {
        (r["market"], r["ticker"])
        for r in read_csv(sample_dir, "asset_position.csv")
        if r["base_date"] == BASE_DATE.isoformat()
    }
    returns = [
        r
        for r in read_csv(sample_dir, "asset_month_return.csv")
        if (r["market"], r["ticker"]) in pool and r["month_start"] in in_range
    ]
    fx = [r for r in read_csv(sample_dir, "fx_month.csv") if r["month_start"] in in_range]

    assert len(snapshot.months) == len(in_range) == 100
    assert {(a.market, a.ticker) for a in snapshot.pool} == pool and len(pool) == 20
    assert len(snapshot.returns) == len(returns)
    assert len(snapshot.fx) == len(fx)
    month_of = {m.month_block_id: m.month_start.isoformat() for m in snapshot.months}
    ticker_of = {a.asset_id: (a.market, a.ticker) for a in snapshot.pool}
    read_values = {
        (*ticker_of[r.asset_id], month_of[r.month_block_id]): str(r.local_return)
        for r in snapshot.returns
    }
    for row in (returns[0], returns[len(returns) // 2], returns[-1]):
        key = (row["market"], row["ticker"], row["month_start"])
        assert Decimal(read_values[key]) == Decimal(row["local_return"])
        assert read_values[key] == row["local_return"]
    assert elapsed < 5, f"read took {elapsed:.2f}s"
