import hashlib
import inspect
import logging
import re
from datetime import date
from fractions import Fraction

import pytest
from conftest import ENGINE_DIR
from fastapi.testclient import TestClient
from synthetic_months import BASE_DATE, half_recent, history, month_range, sample_shape
from test_vectors import read_vectors

from antlab_engine.api.paths import create_path
from antlab_engine.app import create_app
from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import HistoryUnavailable, PoolNotFound, UnsafeDatabaseRole
from antlab_engine.history.model import HistoryMonth, HistorySnapshot, SessionFacts
from antlab_engine.settings import Settings

STATIONARY_VECTORS = ENGINE_DIR / "tests" / "vectors" / "m2-sb6-v1_vectors.csv"
YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
REQUIRED_KEYS = {"title", "status", "detail", "instance", "code"}
LETTERS = {"A": "ALIGNED", "N": "NEAR", "F": "FREE", "J": "JOIN"}


def snapshot_of(
    months: list[date], base_date: date, labels: dict[date, str] | None = None
) -> HistorySnapshot:
    labels = labels or {}
    return HistorySnapshot(
        base_date=base_date,
        upper_bound=date(base_date.year, 1, 1),
        months=tuple(HistoryMonth(i + 1, m, labels.get(m)) for i, m in enumerate(months)),
        pool=(),
        returns=(),
        fx=(),
        session=SessionFacts(role="fake", read_only=True, isolation="repeatable read"),
    )


class FakeReader:
    """Returns the given months as they are (no range filter), so the route's own filter shows."""

    def __init__(
        self,
        months: list[date] | None = None,
        error: Exception | None = None,
        labels: dict[date, str] | None = None,
    ):
        self.months = sample_shape() if months is None else months
        self.error = error
        self.labels = labels
        self.calls: list[date] = []

    def read(self, base_date: date) -> HistorySnapshot:
        self.calls.append(base_date)
        if self.error is not None:
            raise self.error
        return snapshot_of(self.months, base_date, self.labels)


def make_client(
    token: str, reader: FakeReader | None, replay_start: date | None = None
) -> TestClient:
    settings = (
        Settings(token) if replay_start is None else Settings(token, replay_start=replay_start)
    )
    app = create_app(settings, history_reader=reader)
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def reader() -> FakeReader:
    return FakeReader()


@pytest.fixture
def paths(token, reader):
    client = make_client(token, reader)
    headers = {"X-Antlab-Engine-Token": token}

    def post(body: dict, *, with_token: bool = True):
        return client.post("/v1/paths", json=body, headers=headers if with_token else {})

    return post


def request(**changes) -> dict:
    body = {
        "engineVersion": "m2-sb6-v1",
        "seed": "14",
        "length": 60,
        "baseDate": BASE_DATE.isoformat(),
    }
    return {**body, **changes}


def problem(response, status, code):
    assert response.status_code == status, response.text
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body.keys() >= REQUIRED_KEYS
    assert (body["status"], body["code"]) == (status, code)
    return body


def test_the_route_is_synchronous_so_blocking_reads_run_in_the_thread_pool():
    assert not inspect.iscoroutinefunction(create_path)


def test_p2_p0_boot6_v1_reproduces_every_java_vector_row_over_http(paths):
    for seed, length, expected in read_vectors():
        response = paths(request(engineVersion="p0-boot6-v1", seed=str(seed), length=length))
        assert response.status_code == 200, response.text
        body = response.json()
        assert " ".join(body["months"]) == expected, f"seed={seed} length={length}"
        assert body["engineVersion"] == "p0-boot6-v1"
        assert body["blocks"] == [
            {
                "startTurn": t,
                "drawnLength": 6,
                "length": 6,
                "tier": "ALIGNED",
                "conditioned": False,
                "matched": False,
            }
            for t in range(0, length, 6)
        ]
        assert (body["overlapMonths"], body["overlapMet"], body["attempt"]) == (0, True, 0)


def decimal_string(value: Fraction) -> str:
    assert 10_000 % value.denominator == 0
    return f"{value.numerator * (10_000 // value.denominator) / 10_000:g}"


def stationary_rows() -> list[dict[str, str]]:
    lines = STATIONARY_VECTORS.read_text(encoding="utf-8").splitlines()
    names = lines[0].split(",")
    rows = [dict(zip(names, line.split(","), strict=True)) for line in lines[1:]]
    # the endpoint replays from 2007-01 only; the 1997-based history is library-only
    return [r for r in rows if r["history"] != "ext348"]


def test_p3_m2_sb6_v1_reproduces_vector_rows_over_http(token):
    rows = stationary_rows()
    assert len(rows) >= 10
    seen_recent = set()
    for row in rows:
        months, _ = history(row["history"])
        recent = {"none": [], "half": half_recent(), "all": months}[row["recent"]]
        seen_recent.add(row["recent"])
        client = make_client(token, FakeReader(months))
        response = client.post(
            "/v1/paths",
            headers={"X-Antlab-Engine-Token": token},
            json=request(
                seed=row["seed"],
                length=int(row["length"]),
                maxOverlap=decimal_string(Fraction(row["max_overlap"])),
                recentMonths=sorted({f"{m:%Y-%m}" for m in recent}),
            ),
        )
        if response.status_code != 200:
            pytest.fail(f"{row['case']}: {response.text}")
        body = response.json()
        where = row["case"]
        assert " ".join(body["months"]) == row["months"], where
        blocks = " ".join(
            f"{b['startTurn']}:{b['length']}:"
            + next(k for k, v in LETTERS.items() if v == b["tier"])
            for b in body["blocks"]
        )
        assert blocks == row["blocks"], where
        assert str(body["overlapMonths"]) == row["overlap_months"], where
        assert str(body["attempt"]) == row["attempt"], where
        direct = stationary.generate(
            months,
            BASE_DATE,
            int(row["length"]),
            int(row["seed"]),
            recent_months=recent,
            max_overlap=Fraction(row["max_overlap"]),
        )
        assert body["blocks"] == [
            {
                "startTurn": b.start_turn,
                "drawnLength": b.drawn_length,
                "length": b.length,
                "tier": b.tier.value,
                "conditioned": False,
                "matched": False,
            }
            for b in direct.blocks
        ], where
    assert seen_recent == {"none", "half"}  # "all" rows exist for ext348 only


def test_the_route_reads_the_history_for_the_requested_base_date(paths, reader):
    assert paths(request(baseDate="2026-03-05")).status_code == 200
    assert reader.calls == [date(2026, 3, 5)]


@pytest.mark.parametrize("version", ["p0-boot6-v1", "m2-sb6-v1"])
def test_same_seed_gives_the_same_months_whatever_the_history_order(token, version):
    forward = sample_shape()
    outputs = set()
    for seed in range(40):
        results = []
        for months in (forward, forward[::-1], forward):
            client = make_client(token, FakeReader(months))
            body = client.post(
                "/v1/paths",
                headers={"X-Antlab-Engine-Token": token},
                json=request(engineVersion=version, seed=str(seed * 7919 - 100), length=24),
            ).json()
            results.append((body["months"], body["blocks"], body["historyDigest"]))
        assert results[0] == results[1] == results[2], f"seed index {seed}"
        outputs.add(tuple(results[0][0]))
    assert len(outputs) > 30


def test_m2_sb6_v1_honours_the_overlap_threshold_over_http(paths):
    recent = sorted({f"{m:%Y-%m}" for m in half_recent()})
    body = paths(request(recentMonths=recent, maxOverlap="0.5")).json()
    assert body["overlapMet"] is True
    assert body["overlapMonths"] <= 30
    everything = sorted({f"{m:%Y-%m}" for m in sample_shape()})
    infeasible = paths(request(recentMonths=everything, maxOverlap="0")).json()
    assert (infeasible["overlapMet"], infeasible["overlapMonths"]) == (False, 60)


def test_p4_missing_or_wrong_token_is_401(token, reader):
    client = make_client(token, reader)
    assert client.post("/v1/paths", json=request()).status_code == 401
    wrong = {"X-Antlab-Engine-Token": "x" * 40}
    problem(client.post("/v1/paths", json=request(), headers=wrong), 401, "UNAUTHENTICATED")
    assert reader.calls == []


INVALID = {
    "seed with a fraction": request(seed="1.0"),
    "seed above int64": request(seed="9223372036854775808"),
    "seed below int64": request(seed="-9223372036854775809"),
    "seed not a string": request(seed=14),
    "seed empty": request(seed=""),
    "conditional": request(conditional=True),
    "conditional p0": request(engineVersion="p0-boot6-v1", conditional=True),
    "conditional version without the switch": request(engineVersion="m2-sbc6-v1"),
    "conditional version switched off": request(engineVersion="m2-sbc6-v1", conditional=False),
    "p0 with overlap": request(engineVersion="p0-boot6-v1", maxOverlap="0.5"),
    "p0 with recent months": request(engineVersion="p0-boot6-v1", recentMonths=["2020-01"]),
    "duplicate recent month": request(recentMonths=["2020-01", "2020-01"]),
    "month 13": request(recentMonths=["2024-13"]),
    "month not a string": request(recentMonths=[202401]),
    "too many recent months": request(
        recentMonths=[f"{2000 + i // 12}-{i % 12 + 1:02d}" for i in range(4001)]
    ),
    "overlap above 1": request(maxOverlap="1.5"),
    "overlap negative": request(maxOverlap="-0.5"),
    "overlap five decimals": request(maxOverlap="0.12345"),
    "length 36": request(length=36),
    "unknown version": request(engineVersion="m9-x-v1"),
    "bad base date": request(baseDate="2026-13-40"),
    "extra field": {**request(), "attempts": 256},
    "base date as number": request(baseDate=1767312000),
    "conditional as string": request(conditional="yes"),
    "conditional as number": request(conditional=1),
    "conditional false as string": request(conditional="no"),
    "conditional false as number": request(conditional=0),
    "missing seed": {k: v for k, v in request().items() if k != "seed"},
}


@pytest.mark.parametrize("case", INVALID, ids=list(INVALID))
def test_p4_invalid_requests_are_400_without_reading_history(paths, reader, case):
    body = problem(paths(INVALID[case]), 400, "INVALID_ARGUMENT")
    assert reader.calls == []
    assert not YEAR_MONTH.search(body["detail"])


def test_the_boundary_seeds_are_accepted(paths):
    for seed in ("-9223372036854775808", "9223372036854775807", "-0", "0007"):
        assert paths(request(seed=seed, length=12)).status_code == 200, seed


def test_malformed_json_is_400(token, reader):
    client = make_client(token, reader)
    response = client.post(
        "/v1/paths",
        content=b"{no",
        headers={"X-Antlab-Engine-Token": token, "content-type": "application/json"},
    )
    problem(response, 400, "INVALID_ARGUMENT")


@pytest.mark.parametrize(
    ("version", "length"), [("m2-sb6-v1", 120), ("p0-boot6-v1", 120)], ids=["m2", "p0"]
)
def test_p4_a_path_the_history_cannot_build_is_422_path_too_long(paths, version, length):
    body = problem(paths(request(engineVersion=version, length=length)), 422, "PATH_TOO_LONG")
    assert not YEAR_MONTH.search(body["detail"])


def test_p5_reader_failures_map_to_http(token):
    cases = [
        (PoolNotFound("No pool is stored for the base date."), 404, "POOL_NOT_FOUND"),
        (HistoryUnavailable("The history database is not available."), 503, "HISTORY_UNAVAILABLE"),
        (UnsafeDatabaseRole("role antlab_x is a superuser"), 500, "INTERNAL_ERROR"),
        (RuntimeError("boom"), 500, "INTERNAL_ERROR"),
    ]
    for error, status, code in cases:
        client = make_client(token, FakeReader(error=error))
        response = client.post(
            "/v1/paths", json=request(), headers={"X-Antlab-Engine-Token": token}
        )
        body = problem(response, status, code)
        if isinstance(error, UnsafeDatabaseRole | RuntimeError):
            assert body["detail"] == "The engine could not process the request."
            assert "superuser" not in response.text and "antlab_x" not in response.text
    assert (
        problem(
            make_client(
                token, FakeReader(error=PoolNotFound("No pool is stored for the base date."))
            ).post("/v1/paths", json=request(), headers={"X-Antlab-Engine-Token": token}),
            404,
            "POOL_NOT_FOUND",
        )["detail"]
        == "No pool is stored for the base date."
    )


def test_p5_without_a_configured_history_the_route_is_503(token):
    client = make_client(token, None)
    response = client.post("/v1/paths", json=request(), headers={"X-Antlab-Engine-Token": token})
    problem(response, 503, "HISTORY_UNAVAILABLE")


def test_p6_failures_and_logs_do_not_leak_history_months(token, caplog):
    caplog.set_level(logging.DEBUG)
    leaky = UnsafeDatabaseRole("role is unsafe near 2012-04")
    outputs = []
    for error in (None, leaky):
        client = make_client(token, FakeReader(error=error))
        for length in (120, 60):
            response = client.post(
                "/v1/paths",
                json=request(length=length),
                headers={"X-Antlab-Engine-Token": token},
            )
            if response.status_code != 200:
                outputs.append(response.text)
            else:
                months = response.json()["months"]
    assert outputs
    for text in outputs:
        assert not YEAR_MONTH.search(text)
    assert not YEAR_MONTH.search(caplog.text)
    assert not any(month in caplog.text for month in months)


def test_p7_the_digest_tracks_the_replayable_months_only(paths, token):
    def digest(months: list[date], **changes) -> str:
        client = make_client(token, FakeReader(months))
        response = client.post(
            "/v1/paths",
            json=request(length=12, **changes),
            headers={"X-Antlab-Engine-Token": token},
        )
        assert response.status_code == 200
        return response.json()["historyDigest"]

    base = sample_shape()
    reference = digest(base)
    assert re.fullmatch(r"[0-9a-f]{64}", reference)
    replayable = sorted(m for m in base if date(2007, 1, 1) <= m < date(2026, 1, 1))
    text = "\n".join(["m2-sb6-v1", *(f"{m:%Y-%m}" for m in replayable)])
    assert reference == hashlib.sha256(text.encode()).hexdigest()
    assert digest(base[::-1]) == reference
    assert digest([*base, date(2026, 2, 1), date(2027, 5, 1)]) == reference
    assert digest([*base, date(2006, 11, 1)]) == reference  # below the replay start
    assert digest([m for m in base if m != date(2023, 5, 1)]) != reference
    assert digest(base, engineVersion="p0-boot6-v1") != reference


def test_months_come_back_only_as_year_month_and_no_pool_data(paths):
    body = paths(request(length=12)).json()
    assert set(body) == {
        "engineVersion",
        "months",
        "blocks",
        "overlapMonths",
        "overlapMet",
        "attempt",
        "historyDigest",
    }
    assert all(re.fullmatch(r"\d{4}-\d{2}", m) for m in body["months"])
    assert len(body["months"]) == 12


def test_no_month_of_the_base_year_or_later_comes_back(token):
    months = month_range(date(2007, 1, 1), date(2027, 12, 1))  # the base year is fully present
    client = make_client(token, FakeReader(months))
    headers = {"X-Antlab-Engine-Token": token}
    for version in ("p0-boot6-v1", "m2-sb6-v1"):
        for length in (12, 60):
            for seed in range(50):
                body = client.post(
                    "/v1/paths",
                    headers=headers,
                    json=request(engineVersion=version, seed=str(seed), length=length),
                ).json()
                assert len(body["months"]) == length
                assert all(m < "2026-01" for m in body["months"]), (version, length, seed)


def test_a_decimal_threshold_is_exact_over_http(token):
    months = month_range(date(2007, 1, 1), date(2025, 12, 1))
    recent_dates = month_range(date(2007, 1, 1), date(2016, 12, 1))
    recent = [f"{m:%Y-%m}" for m in recent_dates]
    client = make_client(token, FakeReader(months))
    overlaps = set()
    for seed in range(60):
        expected = stationary.generate(
            months,
            BASE_DATE,
            60,
            seed,
            recent_months=recent_dates,
            max_overlap=Fraction(7, 10),
        )
        body = client.post(
            "/v1/paths",
            headers={"X-Antlab-Engine-Token": token},
            json=request(seed=str(seed), recentMonths=recent, maxOverlap="0.7"),
        ).json()
        assert (body["overlapMonths"], body["overlapMet"], body["attempt"]) == (
            expected.overlap_months,
            expected.overlap_met,
            expected.attempt,
        )
        assert " ".join(body["months"]) == " ".join(f"{m:%Y-%m}" for m in expected.months)
        overlaps.add(body["overlapMonths"])
    assert overlaps  # the float mutation is pinned by test_source_rules (no float in api/paths.py)


@pytest.mark.parametrize("version", ["p0-boot6-v1", "m2-sb6-v1"])
def test_a_bad_request_is_400_even_without_a_configured_history(token, version):
    client = make_client(token, None)
    response = client.post(
        "/v1/paths",
        json=request(engineVersion=version, seed="1.0"),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(response, 400, "INVALID_ARGUMENT")
    response = client.post(
        "/v1/paths",
        json=request(engineVersion=version, seed="9223372036854775808"),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(response, 400, "INVALID_ARGUMENT")
