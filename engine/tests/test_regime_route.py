"""POST /v1/regime/evaluate with a fake data source (TB1-TB11); no database, no network."""

import inspect
import json
import logging
import random
import re
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from regime_synthetic import raw, raw_panel

from antlab_engine.api.regime import evaluate_regime, fixed
from antlab_engine.api.regime_models import (
    INDICATOR_KEYS,
    RegimeMonth,
    RegimeRequest,
    RegimeResult,
)
from antlab_engine.app import create_app
from antlab_engine.engine_errors import HistoryUnavailable, InvalidArgument
from antlab_engine.regime import (
    LABEL_VERSION,
    REGIME_VERSION,
    RegimeInputs,
    SourceSeries,
    adapt,
    coverage,
    input_digest,
    label_months,
    pool_probabilities,
)
from antlab_engine.regime.model import Market
from antlab_engine.settings import Settings

D = date
HEADER = "X-Antlab-Engine-Token"
MONTH_TEXT = re.compile(r"(19|20)\d{2}-\d{2}")
ROWS = tuple(raw_panel(8, 3))
MONTHS = ["2021-06", "2022-06", "2023-06"]
REQUIRED_KEYS = {"title", "status", "detail", "instance", "code"}
FIXTURES = Path(__file__).resolve().parent / "fixtures"


class FakeSource:
    def __init__(self, rows=ROWS, calendars=None, error: Exception | None = None):
        self.rows = tuple(rows)
        self.calendars = {} if calendars is None else calendars
        self.error = error
        self.calls: list[date] = []

    def read(self, upper: date) -> RegimeInputs:
        self.calls.append(upper)
        if self.error is not None:
            raise self.error
        return RegimeInputs(tuple(r for r in self.rows if r.ref_date <= upper), self.calendars)


def make_app(token, source):
    return create_app(Settings(token), regime_source=source)


@pytest.fixture
def source() -> FakeSource:
    return FakeSource()


@pytest.fixture
def app_with_source(token, source):
    return make_app(token, source)


@pytest.fixture
def post(token, app_with_source):
    client = TestClient(app_with_source, raise_server_exceptions=False)

    def send(payload, *, with_token=True):
        headers = {HEADER: token} if with_token else {}
        return client.post("/v1/regime/evaluate", json=payload, headers=headers)

    return send


def body(**changes) -> dict:
    return {"labelVersion": LABEL_VERSION, "months": list(MONTHS), **changes}


def problem(response, status, code) -> dict:
    assert response.status_code == status, response.text
    assert response.headers["content-type"] == "application/problem+json"
    payload = response.json()
    assert payload.keys() >= REQUIRED_KEYS
    assert (payload["status"], payload["code"]) == (status, code)
    return payload


def ok(response) -> dict:
    assert response.status_code == 200, response.text
    assert response.headers["content-type"] == "application/json"
    return response.json()


def text(value: float, places: int) -> str:
    """The expected wire text, built apart from the route's helper."""
    step = Decimal(1).scaleb(-places)
    rounded = Decimal(value).quantize(step, rounding=ROUND_HALF_UP)
    return f"{(abs(rounded) if rounded == 0 else rounded):.{places}f}"


def expected_month(month) -> dict:
    def axis(a):
        return (
            None if a is None else {"direction": a.direction.value, "strength": text(a.strength, 8)}
        )

    probabilities = None
    if month.p is not None:
        probabilities = {f"p{i + 1}": text(x, 4) for i, x in enumerate(month.p)}
    return {
        "month": f"{month.month:%Y-%m}",
        "status": month.status.value,
        "axes": {a.value.lower(): axis(d) for a, d in month.axes.items()},
        "probabilities": probabilities,
        "label": None if month.label is None else month.label.value,
        "phase": None if month.phase is None else month.phase.value,
        "indicators": {k: None if z is None else text(z, 8) for k, z in month.indicator_z.items()},
        "quality": {
            "ok": month.quality_ok,
            "stale": list(month.stale),
            "unavailable": list(month.unavailable),
        },
    }


# --- TB1 golden ------------------------------------------------------------------------------


def test_the_route_is_synchronous_so_the_calculation_runs_in_the_thread_pool():
    assert not inspect.iscoroutinefunction(evaluate_regime)


def test_tb1_every_field_equals_the_direct_library_result_after_wire_rounding(post, source):
    result = ok(post(body()))
    adapted = adapt([r for r in ROWS if r.ref_date <= D(2023, 6, 30)])  # what the source returns
    direct = label_months(adapted.observations, [D(2021, 6, 1), D(2022, 6, 1), D(2023, 6, 1)])
    assert result["labelVersion"] == LABEL_VERSION
    assert result["regimeVersion"] == REGIME_VERSION
    assert result["months"] == [expected_month(m) for m in direct]
    assert result["inputDigest"] == input_digest(adapted.observations, {})
    counts = coverage(adapted, direct)
    assert result["coverage"] == {
        "evaluated": counts.evaluated,
        "carried": counts.carried,
        "noData": counts.no_data,
        "indicators": dict(counts.indicators),
        "ruleDatedRows": {c.value: n for c, n in counts.rule_dated_rows.items()},
        "dtwexLinked": counts.dtwex_linked,
    }
    assert source.calls == [D(2023, 6, 30)]
    assert all(m["status"] == "EVALUATED" for m in result["months"])
    RegimeResult.model_validate(result)


def test_tb1_a_carried_month_has_no_label_and_equals_the_library(post):
    result = ok(post(body(months=["2024-06", "2030-01"])))
    month = result["months"][1]
    assert month["status"] == "CARRIED"
    assert (month["probabilities"], month["label"], month["phase"]) == (None, None, None)
    assert month["axes"]["rate"] is None and month["axes"]["growth"] is None
    assert result["coverage"]["carried"] == 1
    direct = label_months(adapt(ROWS).observations, [D(2024, 6, 1), D(2030, 1, 1)])
    assert result["months"] == [expected_month(m) for m in direct]


def test_tb1_months_before_any_data_have_no_value(post):
    month = ok(post(body(months=["1990-01"])))["months"][0]
    assert month["status"] == "NO_DATA"
    assert month["probabilities"] is None and month["label"] is None
    assert set(month["indicators"]) == set(INDICATOR_KEYS)
    assert all(v is None for v in month["indicators"].values())


def post_with(token, source, payload=None) -> dict:
    client = TestClient(make_app(token, source))
    response = client.post("/v1/regime/evaluate", json=payload or body(), headers={HEADER: token})
    return ok(response)


def test_the_digest_covers_the_calendars_even_when_no_row_changes(token):
    # a Saturday holiday moves no usable date, so only the digest's own calendar lines differ
    weekend = FakeSource(calendars={Market.US: frozenset({D(2021, 5, 29)})})
    plain, other = post_with(token, FakeSource()), post_with(token, weekend)
    assert plain["months"] == other["months"]
    assert plain["inputDigest"] != other["inputDigest"]


def test_calendars_feed_the_adapter_the_labels_and_the_digest_alike(token):
    holidays = {Market.US: frozenset({D(2021, 5, 31), D(2022, 5, 30), D(2023, 5, 29)})}
    result = post_with(token, FakeSource(calendars=holidays))
    used = [r for r in ROWS if r.ref_date <= D(2023, 6, 30)]
    adapted = adapt(used, holidays)
    direct = label_months(
        adapted.observations, [D(2021, 6, 1), D(2022, 6, 1), D(2023, 6, 1)], calendars=holidays
    )
    assert result["months"] == [expected_month(m) for m in direct]
    assert result["inputDigest"] == input_digest(adapted.observations, holidays)
    assert result["inputDigest"] != post_with(token, FakeSource())["inputDigest"]


def test_calendars_can_change_the_labels_themselves(token):
    every_weekday = {
        Market.US: frozenset(D(2023, 5, d) for d in range(1, 32) if D(2023, 5, d).weekday() < 5)
    }
    plain = post_with(token, FakeSource(), body(months=["2023-06"]))
    held = post_with(token, FakeSource(calendars=every_weekday), body(months=["2023-06"]))
    direct = label_months(
        adapt([r for r in ROWS if r.ref_date <= D(2023, 6, 30)], every_weekday).observations,
        [D(2023, 6, 1)],
        calendars=every_weekday,
    )
    assert held["months"] == [expected_month(m) for m in direct]
    assert held["months"] != plain["months"]


# --- TB2 rounding ----------------------------------------------------------------------------


def test_tb2_probabilities_round_to_four_digits_and_stay_near_one():
    rng = random.Random(7)  # noqa: S311
    for _ in range(2000):
        p = pool_probabilities(rng.uniform(-3, 3), rng.uniform(-3, 3))
        wire = [fixed(x, Decimal("1e-4")) for x in p]
        assert all(re.fullmatch(r"(0\.[0-9]{4}|1\.0000)", w) for w in wire)
        assert abs(sum(Decimal(w) for w in wire) - 1) <= Decimal("0.0002")


def test_tb2_extremes_and_strengths():
    assert fixed(1e-9, Decimal("1e-4")) == "0.0000"
    assert fixed(1 - 1e-9, Decimal("1e-4")) == "1.0000"
    assert fixed(-0.0, Decimal("1e-8")) == "0.00000000"
    assert fixed(-1e-12, Decimal("1e-8")) == "0.00000000"
    assert fixed(0.41250000004, Decimal("1e-8")) == "0.41250000"
    assert fixed(-1.5, Decimal("1e-8")) == "-1.50000000"
    rng = random.Random(3)  # noqa: S311
    for _ in range(2000):
        wire = fixed(abs(rng.gauss(0, 2)), Decimal("1e-8"))
        assert re.fullmatch(r"[0-9]{1,4}\.[0-9]{8}", wire) and not wire.startswith("-")


def test_tb2_exact_ties_round_half_up_not_half_even():
    step = Decimal("1e-4")
    assert fixed(0.03125, step) == "0.0313"
    assert fixed(0.15625, step) == "0.1563"
    assert fixed(-0.03125, step) == "-0.0313"
    assert fixed(0.5, Decimal("1e-0")) == "1"


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), -float("inf"), 1e4, -1e4, 1e30])
def test_tb2_a_value_outside_the_wire_range_is_refused(bad):
    with pytest.raises(RuntimeError):
        fixed(bad, Decimal("1e-8"))


# --- TB3 contract fixtures -------------------------------------------------------------------


def test_tb3_the_fixtures_parse_with_the_engine_models():
    request = json.loads((FIXTURES / "regime_request.json").read_text(encoding="utf-8"))
    result = json.loads((FIXTURES / "regime_result.json").read_text(encoding="utf-8"))
    RegimeRequest.model_validate(request)
    parsed = RegimeResult.model_validate(result)
    assert parsed.labelVersion == request["labelVersion"]
    assert [m.month for m in parsed.months] == request["months"]


def test_tb3_a_broken_result_fixture_is_refused():
    result = json.loads((FIXTURES / "regime_result.json").read_text(encoding="utf-8"))
    result["months"][0]["probabilities"]["p1"] = "0.27310000"
    with pytest.raises(ValidationError):
        RegimeResult.model_validate(result)


# --- TB4 auth --------------------------------------------------------------------------------


def test_tb4_a_missing_or_wrong_token_is_401_and_reads_nothing(token, source, post):
    assert post(body(), with_token=False).status_code == 401
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: "x" * 40})
    problem(response, 401, "UNAUTHENTICATED")
    assert source.calls == []


# --- TB5 request checks ----------------------------------------------------------------------

BAD_BODIES = {
    "empty list": body(months=[]),
    "481 months": body(
        months=[f"{1980 + i // 12}-{i % 12 + 1:02d}" for i in range(481)],
    ),
    "month 13": body(months=["2024-13"]),
    "month 00": body(months=["2024-00"]),
    "one digit month": body(months=["2024-1"]),
    "a full date": body(months=["2024-01-01"]),
    "year 1899": body(months=["1899-12"]),
    "year 2100": body(months=["2100-01"]),
    "unsorted": body(months=["2022-02", "2022-01"]),
    "duplicate": body(months=["2022-01", "2022-01"]),
    "unknown version": body(labelVersion="m2-rl-v2"),
    "missing version": {"months": ["2022-01"]},
    "missing months": {"labelVersion": LABEL_VERSION},
    "extra field": body(extra=1),
    "months not a list": body(months="2022-01"),
    "month is a number": body(months=[202201]),
    "version is a number": body(labelVersion=1),
    "null months": body(months=None),
}


@pytest.mark.parametrize("name", list(BAD_BODIES))
def test_tb5_a_bad_request_is_400_before_any_data_is_read(name, token, source):
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=BAD_BODIES[name], headers={HEADER: token})
    problem(response, 400, "INVALID_ARGUMENT")
    assert source.calls == []


@pytest.mark.parametrize("name", list(BAD_BODIES))
def test_tb5_a_bad_request_is_400_even_without_a_data_source(name, token):
    client = TestClient(make_app(token, None), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=BAD_BODIES[name], headers={HEADER: token})
    problem(response, 400, "INVALID_ARGUMENT")


def test_tb5_a_body_that_is_not_json_or_not_an_object_is_400(token, source):
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    headers = {HEADER: token, "content-type": "application/json"}
    for content in (b"not json", b"[]", b""):
        response = client.post("/v1/regime/evaluate", content=content, headers=headers)
        problem(response, 400, "INVALID_ARGUMENT")
    assert source.calls == []


def test_tb5_the_limits_themselves_are_accepted(token):
    months = [f"{1980 + i // 12}-{i % 12 + 1:02d}" for i in range(480)]
    RegimeRequest.model_validate(body(months=months))
    RegimeRequest.model_validate(body(months=["1900-01", "2099-12"]))


# --- TB6 no source ---------------------------------------------------------------------------


def test_tb6_without_a_source_the_answer_is_503_history_unavailable(token):
    client = TestClient(make_app(token, None), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token})
    payload = problem(response, 503, "HISTORY_UNAVAILABLE")
    assert not MONTH_TEXT.search(payload["detail"])


def test_tb6_a_failing_source_is_503_and_its_message_never_leaks(token, caplog):
    caplog.set_level(logging.DEBUG)
    source = FakeSource(error=RuntimeError("secret detail 2021-06 value 7777.5"))
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token})
    problem(response, 503, "HISTORY_UNAVAILABLE")
    assert "secret" not in response.text and "7777" not in response.text
    assert "secret" not in caplog.text and "7777" not in caplog.text


def test_tb6_a_source_that_reports_unavailable_keeps_its_status(token):
    source = FakeSource(error=HistoryUnavailable("the database is down"))
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token})
    problem(response, 503, "HISTORY_UNAVAILABLE")


# --- TB7 one at a time -----------------------------------------------------------------------


def test_tb7_a_second_evaluation_is_busy_and_says_when_to_retry(app_with_source, post, source):
    assert app_with_source.state.regime_slot.acquire(blocking=False)
    try:
        response = post(body())
        problem(response, 503, "ENGINE_BUSY")
        assert response.headers["retry-after"] == "1"
        assert source.calls == []
    finally:
        app_with_source.state.regime_slot.release()
    ok(post(body()))


def slot_is_free(app) -> bool:
    free = app.state.regime_slot.acquire(blocking=False)
    if free:
        app.state.regime_slot.release()
    return free


def test_tb7_the_slot_is_released_after_success_and_after_every_failure(
    token, app_with_source, post, monkeypatch
):
    ok(post(body()))
    assert slot_is_free(app_with_source)

    source = FakeSource(rows=[*ROWS, raw(SourceSeries.KR_USDKRW, DUPLICATE_DAY, "1")])
    bad_app = make_app(token, source)
    client = TestClient(bad_app, raise_server_exceptions=False)
    problem(
        client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token}),
        422,
        "REGIME_DATA_INVALID",
    )
    assert slot_is_free(bad_app)

    failing_app = make_app(token, FakeSource(error=RuntimeError("x")))
    client = TestClient(failing_app, raise_server_exceptions=False)
    problem(
        client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token}),
        503,
        "HISTORY_UNAVAILABLE",
    )
    assert slot_is_free(failing_app)

    def boom(*args, **kwargs):
        raise RuntimeError("unexpected")

    monkeypatch.setattr("antlab_engine.api.regime.label_months", boom)
    response = post(body())
    problem(response, 500, "INTERNAL_ERROR")
    assert slot_is_free(app_with_source)


# --- TB8 unusable rows -----------------------------------------------------------------------

DUPLICATE_DAY = D(2022, 3, 2)  # a weekday inside the requested range
assert any(r.source is SourceSeries.KR_USDKRW and r.ref_date == DUPLICATE_DAY for r in ROWS)


def bad_rows():
    yield "duplicate daily row", [raw(SourceSeries.KR_USDKRW, DUPLICATE_DAY, "1")], "KR_USDKRW"
    yield (
        "publication before reference",
        [raw(SourceSeries.US_DGS2, D(2021, 3, 5), "1", D(2021, 3, 1))],
        "US_DGS2",
    )
    yield (
        "claims not on a Saturday",
        [raw(SourceSeries.US_ICSA, D(2021, 3, 3), "1")],
        None,
    )
    yield (
        "monthly row not on day 1",
        [raw(SourceSeries.KR_CPI, D(2021, 3, 2), "1")],
        "KR_CPI",
    )
    yield (
        "link overflow",
        [
            raw(SourceSeries.FRED_DTWEXM, D(1999, 1, 4), "1E+999990"),
            raw(SourceSeries.US_DTWEXBGS, D(1999, 1, 4), "1E-999990"),
            raw(SourceSeries.US_DTWEXBGS, D(1999, 1, 5), "1E+999990"),
        ],
        None,
    )


@pytest.mark.parametrize("name,extra,series", [(n, e, s) for n, e, s in bad_rows()])
def test_tb8_unusable_rows_are_422_with_a_series_and_no_date_or_value(name, extra, series, token):
    source = FakeSource(rows=[*ROWS, *extra])
    client = TestClient(make_app(token, source), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token})
    payload = problem(response, 422, "REGIME_DATA_INVALID")
    if series is not None:
        assert series in payload["detail"]
    assert not MONTH_TEXT.search(response.text)
    assert "E+" not in response.text and "999990" not in response.text


def test_tb8_a_source_that_builds_a_bad_row_is_422_too(token):
    class Source:
        def read(self, upper):
            raise InvalidArgument("a raw observation value must be a finite Decimal")

    client = TestClient(make_app(token, Source()), raise_server_exceptions=False)
    response = client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token})
    problem(response, 422, "REGIME_DATA_INVALID")


# --- TB9 no leak -----------------------------------------------------------------------------


def test_tb9_logs_and_problem_bodies_carry_no_month_date_or_value(token, caplog):
    caplog.set_level(logging.DEBUG)
    marker = "7777.5"
    marked = [raw(SourceSeries.KR_USDKRW, DUPLICATE_DAY, marker)]
    cases = [
        (FakeSource(), body(), 200),
        (FakeSource(rows=[*ROWS, *marked]), body(), 422),
        (FakeSource(error=RuntimeError(f"2021-06 {marker}")), body(), 503),
        (None, body(), 503),
        (FakeSource(), body(months=["2024-13"]), 400),
    ]
    for source, payload, status in cases:
        client = TestClient(make_app(token, source), raise_server_exceptions=False)
        response = client.post("/v1/regime/evaluate", json=payload, headers={HEADER: token})
        assert response.status_code == status
        if status != 200:
            assert not MONTH_TEXT.search(response.text), status
            assert marker not in response.text
            assert "2024-13" not in response.text
    assert not MONTH_TEXT.search(caplog.text)
    assert not re.search(r"\d{4}-\d{2}-\d{2}", caplog.text)
    assert marker not in caplog.text


def test_tb9_the_route_module_never_logs_or_prints():
    source = (Path(__file__).resolve().parents[1] / "src/antlab_engine/api/regime.py").read_text(
        encoding="utf-8"
    )
    assert "logging" not in source and "print(" not in source


# --- TB10 determinism ------------------------------------------------------------------------


def test_tb10_the_same_request_gives_identical_bytes(post):
    first = post(body())
    second = post(body())
    assert first.content == second.content


def test_tb10_a_subset_equals_the_same_months_of_the_full_request(post):
    full = ok(post(body()))["months"]
    for index, month in enumerate(MONTHS):
        assert ok(post(body(months=[month])))["months"] == [full[index]]
    assert ok(post(body(months=[MONTHS[0], MONTHS[2]])))["months"] == [full[0], full[2]]


def test_tb10_row_order_does_not_change_the_answer(token):
    shuffled = list(ROWS)
    random.Random(5).shuffle(shuffled)  # noqa: S311
    answers = []
    for rows in (ROWS, shuffled):
        client = TestClient(make_app(token, FakeSource(rows=rows)))
        answers.append(
            client.post("/v1/regime/evaluate", json=body(), headers={HEADER: token}).content
        )
    assert answers[0] == answers[1]


# --- TB11 response model ---------------------------------------------------------------------


def valid_month() -> dict:
    result = json.loads((FIXTURES / "regime_result.json").read_text(encoding="utf-8"))
    return result["months"][0]


def test_tb11_a_consistent_month_is_accepted():
    RegimeMonth.model_validate(valid_month())


@pytest.mark.parametrize(
    "mutate",
    [
        lambda m: m.update(label=None),
        lambda m: m.update(phase=None),
        lambda m: m.update(probabilities=None),
        lambda m: m.update(status="CARRIED"),
        lambda m: m.update(status="NO_DATA"),
        lambda m: m["axes"].update(rate={"direction": "UP"}),
        lambda m: m["axes"].update(rate={"strength": "0.10000000"}),
        lambda m: m["axes"].update(rate={"direction": "SIDEWAYS", "strength": "0.10000000"}),
        lambda m: m["indicators"].pop("14KR"),
        lambda m: m["indicators"].update({"15": "0.00000000"}),
        lambda m: m["indicators"].update({"1": "0.5"}),
        lambda m: m["indicators"].update({"1": 0.5}),
        lambda m: m["probabilities"].update(p1="0.27"),
        lambda m: m["probabilities"].update(p1="1.5000"),
        lambda m: m.update(label="P5"),
        lambda m: m.update(month="1997-13"),
    ],
)
def test_tb11_an_inconsistent_month_is_refused(mutate):
    month = valid_month()
    mutate(month)
    with pytest.raises(ValidationError):
        RegimeMonth.model_validate(month)
