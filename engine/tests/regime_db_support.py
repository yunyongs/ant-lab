"""Loaders for the macro series tables in a scratch database (rows are built here, never copied)."""

import csv
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import psycopg
from db_support import REPO_ROOT

CATALOG_CSV = REPO_ROOT / "src" / "main" / "resources" / "collect" / "series-catalog.csv"
DEFAULT_OBSERVED = datetime(2024, 3, 1, 12, 0, tzinfo=timezone.utc)  # noqa: UP017 - explicit UTC


def load_catalog(admin: psycopg.Connection) -> int:
    """Insert the 16 catalog rows and one finished job run; returns the job run id."""
    with CATALOG_CSV.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            admin.execute(
                "INSERT INTO series (code, source, source_series_id, unit, frequency,"
                " release_lag_days, revisable) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                (
                    row["code"],
                    row["source"],
                    row["source_series_id"],
                    row["unit"],
                    row["frequency"],
                    int(row["release_lag_days"]),
                    row["revisable"] == "true",
                ),
            )
    started = DEFAULT_OBSERVED
    return admin.execute(
        "INSERT INTO job_run (job_name, run_trigger, status, started_at, finished_at,"
        " request_count, inserted_count, revised_count, unchanged_count, failed_target_count)"
        " VALUES ('FRED_SERIES', 'MANUAL', 'SUCCEEDED', %s, %s, 1, 0, 0, 0, 0) RETURNING id",
        (started, started + timedelta(minutes=1)),
    ).fetchone()[0]


def add(
    admin: psycopg.Connection,
    job_run_id: int,
    code: str,
    ref_date: date,
    value: str,
    *,
    realtime_start: date | None = None,
    available_on: date | None = None,
    observed_at: datetime = DEFAULT_OBSERVED,
    vintage_no: int = 1,
) -> None:
    admin.execute(
        "INSERT INTO series_observation (series_id, ref_date, value, realtime_start, available_on,"
        " observed_at, vintage_no, job_run_id)"
        " SELECT id, %s, %s, %s, %s, %s, %s, %s FROM series WHERE code = %s",
        (
            ref_date,
            Decimal(value),
            realtime_start,
            available_on,
            observed_at,
            vintage_no,
            job_run_id,
            code,
        ),
    )


def weekdays(start: date, count: int) -> list[date]:
    days, day = [], start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def load_realistic(admin: psycopg.Connection, job_run_id: int) -> None:
    """Small panel: back-filled US daily rows, ALFRED CPI vintages, ECOS lag-dated rows."""
    for i, day in enumerate(weekdays(date(2024, 1, 1), 40)):
        add(admin, job_run_id, "US_DGS2", day, f"{4.0 + i / 100:.2f}")
        add(admin, job_run_id, "US_T10Y3M", day, f"{-1.0 - i / 100:.2f}")
        add(admin, job_run_id, "US_VIX", day, f"{13.0 + i / 10:.2f}")
        add(admin, job_run_id, "KR_KTB3Y", day, f"{3.0 + i / 100:.2f}", available_on=day)
    for week in range(8):
        saturday = date(2024, 1, 6) + timedelta(weeks=week)
        add(admin, job_run_id, "US_ICSA", saturday, str(210000 + week * 1000))
    add(
        admin,
        job_run_id,
        "US_CPILFESL",
        date(2023, 12, 1),
        "320.100",
        realtime_start=date(2024, 1, 11),
    )
    add(
        admin,
        job_run_id,
        "US_CPILFESL",
        date(2023, 12, 1),
        "320.200",
        realtime_start=date(2024, 2, 13),
        vintage_no=2,
    )
    add(
        admin,
        job_run_id,
        "US_CPILFESL",
        date(2024, 1, 1),
        "321.300",
        realtime_start=date(2024, 2, 13),
    )
    add(admin, job_run_id, "KR_CPI", date(2023, 12, 1), "113.40", available_on=date(2024, 1, 7))
    add(admin, job_run_id, "US_SAHM", date(2023, 12, 1), "0.43")
    add(admin, job_run_id, "US_BAA10Y", date(2024, 1, 2), "1.95")
    add(admin, job_run_id, "US_DTWEXBGS", date(2024, 1, 2), "120.5")
    add(admin, job_run_id, "KR_USDKRW", date(2024, 1, 2), "1289.50", available_on=date(2024, 1, 2))
