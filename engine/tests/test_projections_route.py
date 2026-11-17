import copy
import hashlib
import inspect
import logging
import re
import threading
from dataclasses import replace
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

import pytest
from fastapi.testclient import TestClient
from synthetic_months import month_range, sample_shape
from synthetic_snapshot import BASE_DATE, FULL_MONTHS, snapshot

from antlab_engine.api.projection_panel import panel_from_snapshot
from antlab_engine.api.projections import create_projection, history_digest, probability, ratio
from antlab_engine.app import create_app
from antlab_engine.bootstrap import stationary
from antlab_engine.engine_errors import HistoryUnavailable, PoolNotFound, UnsafeDatabaseRole
from antlab_engine.projection import Portfolio, derive_path_seeds, draw_paths, project
from antlab_engine.settings import Settings

YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
REQUIRED_KEYS = {"title", "status", "detail", "instance", "code"}
ASSETS = ["KR:A001", "KR:B002", "US:C003", "US:D004"]
MULTIPLE = re.compile(r"[0-9]{1,24}\.[0-9]{8}")
DRAWDOWN = re.compile(r"(0\.0{8}|-(0\.[0-9]{8}|1\.0{8}))")
PROBABILITY = re.compile(r"(0\.[0-9]{8}|1\.0{8})")
STEP = Decimal("1e-8")


class SnapshotReader:
    def __init__(self, shot=None, error: Exception | None = None):
        self.shot = snapshot(ASSETS) if shot is None else shot
        self.error = error
        self.calls: list[date] = []

    def read(self, base_date: date):
        self.calls.append(base_date)
        if self.error is not None:
            raise self.error
        return self.shot


def make_client(token: str, reader: SnapshotReader | None) -> TestClient:
    app = create_app(Settings(token), history_reader=reader)
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def reader() -> SnapshotReader:
    return SnapshotReader()


@pytest.fixture
def post(token, reader):
    client = make_client(token, reader)
    headers = {"X-Antlab-Engine-Token": token}

    def send(payload: dict, *, with_token: bool = True):
        return client.post("/v1/projections", json=payload, headers=headers if with_token else {})

    return send


def team(**changes) -> dict:
    value = {
        "key": "TEAM",
        "cash": "0.1",
        "weights": [
            {"asset": "KR:A001", "weight": "0.5"},
            {"asset": "US:C003", "weight": "0.4"},
        ],
    }
    return {**value, **changes}


def body(**changes) -> dict:
    value = {
        "seasonSeed": "14",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 60,
        "n": 100,
        "riskLimit": "-0.30",
        "portfolios": [team()],
    }
    return {**value, **changes}


def problem(response, status, code):
    assert response.status_code == status, response.text
    assert response.headers["content-type"] == "application/problem+json"
    payload = response.json()
    assert payload.keys() >= REQUIRED_KEYS
    assert (payload["status"], payload["code"]) == (status, code)
    return payload


def ok(response) -> dict:
    assert response.status_code == 200, response.text
    return response.json()


def test_the_route_is_synchronous_so_the_calculation_runs_in_the_thread_pool():
    assert not inspect.iscoroutinefunction(create_projection)


def test_r1_missing_or_wrong_token_is_401_and_reads_nothing(token, reader):
    client = make_client(token, reader)
    assert client.post("/v1/projections", json=body()).status_code == 401
    wrong = {"X-Antlab-Engine-Token": "x" * 40}
    response = client.post("/v1/projections", json=body(), headers=wrong)
    problem(response, 401, "UNAUTHENTICATED")
    assert reader.calls == []


def model_result(shot, payload: dict) -> dict:
    """The library result for the same request, built without the route and its adapter."""
    from antlab_engine.projection import ReturnPanel, ReturnRow

    month_of = {m.month_block_id: m.month_start for m in shot.months}
    fx = {c.month_block_id: c.fx_change for c in shot.fx}
    names = {a.asset_id: f"{a.market}:{a.ticker}" for a in shot.pool}
    rows = [
        ReturnRow(
            names[r.asset_id],
            month_of[r.month_block_id],
            r.local_return,
            fx[r.month_block_id] if names[r.asset_id][:2] == "US" else Decimal(0),
        )
        for r in shot.returns
    ]
    panel = ReturnPanel.from_rows(sorted(month_of.values()), sorted(names.values()), rows)
    books = [
        Portfolio(
            p["key"], {h["asset"]: Decimal(h["weight"]) for h in p["weights"]}, Decimal(p["cash"])
        )
        for p in payload["portfolios"]
    ]
    result = project(
        panel,
        date.fromisoformat(payload["baseDate"]),
        season_seed=int(payload["seasonSeed"]),
        horizon=payload["horizonTurns"],
        portfolios=books,
        risk_limit=Decimal(payload["riskLimit"]),
        n=payload["n"],
        summary_turns=payload.get("summaryTurns", [60, 120]),
    )

    def text(value: float) -> str:
        rounded = Decimal(float(value)).quantize(STEP, rounding=ROUND_HALF_UP)
        return f"{abs(rounded) if rounded == 0 else rounded:f}"

    out = []
    for book in result.portfolios:
        out.append(
            {
                "key": book.key,
                "bands": [
                    dict(zip(["p05", "p25", "p50", "p75", "p95"], map(text, row), strict=True))
                    | {"turn": i + 1}
                    for i, row in enumerate(book.bands)
                ],
                "summaries": [
                    {
                        "turn": s.turn,
                        "end": dict(
                            zip(
                                ["p05", "p25", "p50", "p75", "p95"],
                                map(text, s.end_quantiles),
                                strict=True,
                            )
                        ),
                        "mdd": dict(
                            zip(
                                ["p05", "p10", "p25", "p50", "p75", "p95"],
                                map(text, s.mdd_quantiles),
                                strict=True,
                            )
                        ),
                        "breachCount": s.breach_count,
                        "breachProbability": f"{Decimal(s.breach_count) / Decimal(result.n):.8f}",
                    }
                    for s in book.summaries
                ],
            }
        )
    return {"n": result.n, "horizonTurns": result.horizon, "portfolios": out}


@pytest.mark.parametrize("horizon", [60, 120])
def test_r2_the_route_equals_the_library_on_the_same_history(post, reader, horizon):
    market = {"key": "MARKET", "cash": "0", "weights": [{"asset": "US:D004", "weight": "1"}]}
    payload = body(horizonTurns=horizon, n=200, portfolios=[team(), market])
    got = ok(post(payload))
    expected = model_result(reader.shot, payload)
    assert got["portfolios"] == expected["portfolios"]
    assert (got["n"], got["horizonTurns"]) == (expected["n"], expected["horizonTurns"])
    assert (got["projectionVersion"], got["engineVersion"]) == ("m2-pj-v1", "m2-sb6-v1")


def test_r3_the_same_request_gives_the_same_bytes_and_other_seeds_differ(post):
    first = post(body()).content
    assert post(body()).content == first
    assert post(body(seasonSeed="15")).content != first
    assert post(body(seasonSeed="-14")).content != first


def test_r4_a_portfolio_is_unchanged_by_the_others_and_by_the_order_of_its_weights(post):
    alone = ok(post(body()))["portfolios"][0]
    baseline = {"key": "BASE", "cash": "0.5", "weights": [{"asset": "KR:A001", "weight": "0.5"}]}
    shuffled = team(weights=list(reversed(team()["weights"])))
    together = ok(post(body(portfolios=[baseline, shuffled])))["portfolios"]
    assert [p["key"] for p in together] == ["BASE", "TEAM"]
    assert together[1] == alone


def test_r5_the_season_path_is_never_one_of_the_projection_paths():
    shot = snapshot(ASSETS)
    panel = panel_from_snapshot(shot)
    held = ["KR:A001", "US:C003"]
    eligible = [panel.months[i] for i in panel.rows_with(held)]
    for season_seed in range(-60, 60):
        season = stationary.generate(eligible, BASE_DATE, 60, season_seed).months
        seeds = derive_path_seeds(season_seed, 100)
        paths = draw_paths(
            panel, BASE_DATE, seeds, 60, held, stationary.REPLAY_START, stationary.DEFAULT_PARAMS
        )
        drawn = {tuple(panel.months[int(i)] for i in row) for row in paths}
        assert tuple(season) not in drawn, season_seed


def test_r6_the_response_has_the_documented_shape_and_nothing_else(post):
    payload = ok(post(body(horizonTurns=120, n=100)))
    assert set(payload) == {
        "projectionVersion",
        "engineVersion",
        "n",
        "horizonTurns",
        "historyDigest",
        "portfolios",
    }
    book = payload["portfolios"][0]
    assert set(book) == {"key", "bands", "summaries"}
    assert [b["turn"] for b in book["bands"]] == list(range(1, 121))
    assert [s["turn"] for s in book["summaries"]] == [60, 120]
    assert set(book["summaries"][0]) == {"turn", "end", "mdd", "breachCount", "breachProbability"}
    assert set(book["bands"][0]) == {"turn", "p05", "p25", "p50", "p75", "p95"}
    text = str(payload)
    assert not YEAR_MONTH.search(text)
    names = set()

    def walk(node):
        if isinstance(node, dict):
            names.update(node)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(payload)
    assert not [n for n in names if re.search(r"(?i)won|amount|month|path|eligible|value", n)]


def test_r7_every_number_is_an_exact_eight_digit_decimal_string_and_ordered(post):
    payload = ok(post(body(horizonTurns=120, n=200)))
    n = payload["n"]
    for book in payload["portfolios"]:
        for band in book["bands"]:
            values = [band[k] for k in ("p05", "p25", "p50", "p75", "p95")]
            assert all(MULTIPLE.fullmatch(v) for v in values)
            assert [Decimal(v) for v in values] == sorted(Decimal(v) for v in values)
        for s in book["summaries"]:
            assert all(MULTIPLE.fullmatch(v) for v in s["end"].values())
            drawdowns = list(s["mdd"].values())
            assert all(DRAWDOWN.fullmatch(v) for v in drawdowns)
            assert [Decimal(v) for v in drawdowns] == sorted(Decimal(v) for v in drawdowns)
            assert PROBABILITY.fullmatch(s["breachProbability"])
            assert 0 <= s["breachCount"] <= n
            assert Decimal(s["breachProbability"]) == (Decimal(s["breachCount"]) / n).quantize(
                STEP, rounding=ROUND_HALF_UP
            )


def test_r8_summary_turns_are_capped_deduplicated_and_always_end_at_the_horizon(post):
    default = ok(post(body()))["portfolios"][0]["summaries"]
    assert [s["turn"] for s in default] == [60]
    custom = ok(post(body(summaryTurns=[30, 10, 10, 100])))["portfolios"][0]["summaries"]
    assert [s["turn"] for s in custom] == [10, 30, 60]
    assert custom[2] == default[0]


def test_r9_the_wire_formatting_rounds_half_up_once_and_never_shows_a_minus_zero():
    assert ratio(1 / 512) == "0.00195313"  # an exact tie at the ninth digit
    assert ratio(-1 / 512) == "-0.00195313"
    assert ratio(0.0) == ratio(-0.0) == ratio(-1e-12) == "0.00000000"
    assert ratio(1.5) == "1.50000000"
    assert ratio(-1.0) == "-1.00000000"
    assert ratio(123456789.123456789) == "123456789.12345679"
    for bad in (float("nan"), float("inf"), 1e24, -1e30):
        with pytest.raises(RuntimeError):
            ratio(bad)
    assert probability(1399, 2000) == "0.69950000"
    assert probability(1, 3) == "0.33333333"
    assert probability(2, 3) == "0.66666667"
    assert probability(1, 512) == "0.00195313"  # a tie at the ninth digit: half up, not half even
    assert probability(3, 2560) == "0.00117188"
    assert probability(0, 100) == "0.00000000"
    assert probability(100, 100) == "1.00000000"


INVALID = {
    "seed with a fraction": body(seasonSeed="1.0"),
    "seed above int64": body(seasonSeed="9223372036854775808"),
    "seed below int64": body(seasonSeed="-9223372036854775809"),
    "seed not a string": body(seasonSeed=14),
    "seed missing": {k: v for k, v in body().items() if k != "seasonSeed"},
    "bad base date": body(baseDate="2026-13-40"),
    "base date missing": {k: v for k, v in body().items() if k != "baseDate"},
    "horizon 12": body(horizonTurns=12),
    "horizon 24": body(horizonTurns=24),
    "horizon 61": body(horizonTurns=61),
    "horizon missing": {k: v for k, v in body().items() if k != "horizonTurns"},
    "n 99": body(n=99),
    "n 5001": body(n=5001),
    "n fractional": body(n=100.5),
    "risk limit zero": body(riskLimit="0"),
    "risk limit minus zero": body(riskLimit="-0.0"),
    "risk limit all zeros": body(riskLimit="-0.00000000"),
    "risk limit positive": body(riskLimit="0.1"),
    "risk limit minus one": body(riskLimit="-1.0"),
    "risk limit nine decimals": body(riskLimit="-0.123456789"),
    "risk limit a number": body(riskLimit=-0.3),
    "risk limit missing": {k: v for k, v in body().items() if k != "riskLimit"},
    "summary turns empty": body(summaryTurns=[]),
    "summary turn zero": body(summaryTurns=[0]),
    "summary turn 121": body(summaryTurns=[121]),
    "summary turn fractional": body(summaryTurns=[60.5]),
    "summary turns 21": body(summaryTurns=list(range(1, 22))),
    "no portfolios": body(portfolios=[]),
    "portfolios missing": {k: v for k, v in body().items() if k != "portfolios"},
    "seven portfolios": body(portfolios=[team(key=f"P{i}") for i in range(7)]),
    "duplicate portfolio key": body(portfolios=[team(), team()]),
    "lowercase key": body(portfolios=[team(key="team")]),
    "key of 33 characters": body(portfolios=[team(key="T" * 33)]),
    "cash above one": body(portfolios=[team(cash="1.1")]),
    "cash negative": body(portfolios=[team(cash="-0.1")]),
    "cash a number": body(portfolios=[team(cash=0.1)]),
    "cash thirteen decimals": body(portfolios=[team(cash="0.1000000000001")]),
    "weight a number": body(portfolios=[team(weights=[{"asset": "KR:A001", "weight": 0.9}])]),
    "weight negative": body(portfolios=[team(weights=[{"asset": "KR:A001", "weight": "-0.5"}])]),
    "weight in exponent form": body(
        portfolios=[team(weights=[{"asset": "KR:A001", "weight": "9e-1"}])]
    ),
    "asset repeated": body(
        portfolios=[
            team(
                weights=[
                    {"asset": "KR:A001", "weight": "0.5"},
                    {"asset": "KR:A001", "weight": "0.4"},
                ]
            )
        ]
    ),
    "asset repeated and the last weight completes the sum": body(
        portfolios=[
            team(
                weights=[
                    {"asset": "KR:A001", "weight": "0.1"},
                    {"asset": "KR:A001", "weight": "0.9"},
                ]
            )
        ]
    ),
    "asset in lowercase": body(portfolios=[team(weights=[{"asset": "kr:a001", "weight": "0.9"}])]),
    "asset without market": body(portfolios=[team(weights=[{"asset": "A001", "weight": "0.9"}])]),
    "201 holdings": body(
        portfolios=[
            team(weights=[{"asset": f"KR:T{i:03d}", "weight": "0"} for i in range(201)], cash="1")
        ]
    ),
    "extra holding field": body(
        portfolios=[team(weights=[{"asset": "KR:A001", "weight": "0.9", "won": "5"}])]
    ),
    "extra portfolio field": body(portfolios=[{**team(), "amount": "5"}]),
    "extra top field": {**body(), "attempts": 5},
    "weights plus cash short by 1e-8": body(portfolios=[team(cash="0.09999999")]),
    "weights plus cash over by 1e-9": body(portfolios=[team(cash="0.100000001")]),
    "weights plus cash of 0.9": body(portfolios=[team(cash="0")]),
}


@pytest.mark.parametrize("case", INVALID, ids=list(INVALID))
def test_r10_invalid_requests_are_400_before_the_history_is_read(post, reader, case):
    payload = problem(post(INVALID[case]), 400, "INVALID_ARGUMENT")
    assert reader.calls == []
    assert not YEAR_MONTH.search(str(payload))


def test_r10_malformed_json_is_400(token, reader):
    client = make_client(token, reader)
    response = client.post(
        "/v1/projections",
        content=b"{no",
        headers={"X-Antlab-Engine-Token": token, "content-type": "application/json"},
    )
    problem(response, 400, "INVALID_ARGUMENT")
    assert reader.calls == []


def test_r10_the_boundary_values_are_accepted(post):
    edge = body(seasonSeed="-9223372036854775808", riskLimit="-0.99999999", n=100)
    assert post(edge).status_code == 200
    edge = body(seasonSeed="9223372036854775807", riskLimit="-0.00000001", n=5000)
    assert post(edge).status_code == 200
    all_cash = team(cash="1", weights=[])
    result = ok(post(body(portfolios=[all_cash])))["portfolios"][0]
    assert {b["p50"] for b in result["bands"]} == {"1.00000000"}
    assert result["summaries"][0]["breachCount"] == 0


def test_r11_an_asset_outside_the_pool_is_400_after_one_read(post, reader):
    payload = body(portfolios=[team(weights=[{"asset": "US:ZZZ9", "weight": "0.9"}])])
    answer = problem(post(payload), 400, "INVALID_ARGUMENT")
    assert reader.calls == [BASE_DATE]
    assert not YEAR_MONTH.search(answer["detail"])


def test_r12_the_route_reads_the_history_for_the_requested_base_date_once(post, reader):
    assert post(body(baseDate="2026-03-05")).status_code == 200
    assert reader.calls == [date(2026, 3, 5)]


def test_r13_a_history_that_cannot_build_the_paths_is_422(token):
    short = snapshot(ASSETS, months=sample_shape())
    client = make_client(token, SnapshotReader(short))
    headers = {"X-Antlab-Engine-Token": token}
    long_one = client.post("/v1/projections", json=body(horizonTurns=120), headers=headers)
    answer = problem(long_one, 422, "PATH_TOO_LONG")
    assert not YEAR_MONTH.search(answer["detail"])
    assert client.post("/v1/projections", json=body(), headers=headers).status_code == 200


def test_r13_a_held_asset_with_too_few_returns_is_422(token):
    months = FULL_MONTHS[:60]
    thin = frozenset(("US:C003", m) for m in FULL_MONTHS if m not in months)
    client = make_client(token, SnapshotReader(snapshot(ASSETS, skip=thin)))
    response = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    problem(response, 422, "PATH_TOO_LONG")


def test_r13_an_asset_without_any_return_is_422(token):
    bare = frozenset(("US:C003", m) for m in FULL_MONTHS)
    client = make_client(token, SnapshotReader(snapshot(ASSETS, skip=bare)))
    response = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    problem(response, 422, "PATH_TOO_LONG")


def test_r14_reader_failures_map_to_http(token):
    cases = [
        (PoolNotFound("No pool is stored for the base date."), 404, "POOL_NOT_FOUND"),
        (HistoryUnavailable("The history database is not available."), 503, "HISTORY_UNAVAILABLE"),
        (UnsafeDatabaseRole("role antlab_x is a superuser"), 500, "INTERNAL_ERROR"),
        (RuntimeError("boom 2012-04"), 500, "INTERNAL_ERROR"),
    ]
    headers = {"X-Antlab-Engine-Token": token}
    for error, status, code in cases:
        client = make_client(token, SnapshotReader(error=error))
        response = client.post("/v1/projections", json=body(), headers=headers)
        answer = problem(response, status, code)
        if status == 500:
            assert answer["detail"] == "The engine could not process the request."
            assert "superuser" not in response.text and "2012-04" not in response.text


def test_r14_without_a_configured_history_the_route_is_503(token):
    client = make_client(token, None)
    response = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    problem(response, 503, "HISTORY_UNAVAILABLE")


def test_r15_a_stored_history_that_breaks_the_panel_rules_is_a_generic_500(token):
    shot = snapshot(ASSETS)
    bad = replace(shot, returns=(replace(shot.returns[0], local_return=Decimal("-1.5")),))
    client = make_client(token, SnapshotReader(bad))
    response = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    answer = problem(response, 500, "INTERNAL_ERROR")
    assert answer["detail"] == "The engine could not process the request."
    unknown_block = replace(shot, returns=(replace(shot.returns[0], month_block_id=99999),))
    client = make_client(token, SnapshotReader(unknown_block))
    response = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    problem(response, 500, "INTERNAL_ERROR")


def test_r16_failures_and_logs_do_not_leak_history_months(token, caplog):
    caplog.set_level(logging.DEBUG)
    headers = {"X-Antlab-Engine-Token": token}
    texts = []
    for error in (None, UnsafeDatabaseRole("role is unsafe near 2012-04")):
        client = make_client(token, SnapshotReader(error=error))
        for horizon in (120, 60):
            response = client.post(
                "/v1/projections", json=body(horizonTurns=horizon), headers=headers
            )
            texts.append(response.text)
    assert all(not YEAR_MONTH.search(t) for t in texts)
    assert not YEAR_MONTH.search(caplog.text)


def digest_of(token, shot, **changes) -> str:
    client = make_client(token, SnapshotReader(shot))
    response = client.post(
        "/v1/projections", json=body(**changes), headers={"X-Antlab-Engine-Token": token}
    )
    return ok(response)["historyDigest"]


def test_r17_the_digest_follows_the_eligible_months_and_the_held_factors(token):
    reference = digest_of(token, snapshot(ASSETS))
    assert re.fullmatch(r"[0-9a-f]{64}", reference)
    march = date(2015, 3, 1)
    assert digest_of(token, snapshot(ASSETS, changes={("KR:A001", march): "0.01"})) != reference
    assert digest_of(token, snapshot(ASSETS, changes={("US:C003", march): "0.01"})) != reference
    # an asset the portfolios do not hold, and months outside the replay window, do not count
    assert digest_of(token, snapshot(ASSETS, changes={("KR:B002", march): "0.01"})) == reference
    assert digest_of(token, snapshot(ASSETS, skip=frozenset({("KR:B002", march)}))) == reference
    outside = {("KR:A001", date(2006, 12, 1)): "9", ("US:C003", date(2026, 1, 1)): "9"}
    assert digest_of(token, snapshot(ASSETS, changes=outside)) == reference
    # a missing cell of a held asset removes the month from the eligible set
    assert digest_of(token, snapshot(ASSETS, skip=frozenset({("KR:A001", march)}))) != reference
    held_other = body(portfolios=[team(weights=[{"asset": "KR:A001", "weight": "0.9"}])])
    other = digest_of(token, snapshot(ASSETS), portfolios=held_other["portfolios"])
    assert other != reference


def test_r17_the_digest_does_not_depend_on_the_order_of_the_snapshot(token):
    shot = snapshot(ASSETS)
    shuffled = replace(
        shot,
        months=shot.months[::-1],
        returns=shot.returns[::-1],
        fx=shot.fx[::-1],
        pool=shot.pool[::-1],
    )
    assert digest_of(token, shuffled) == digest_of(token, shot)
    client = make_client(token, SnapshotReader(shuffled))
    again = client.post("/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token})
    first = make_client(token, SnapshotReader(shot)).post(
        "/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token}
    )
    assert again.content == first.content


def test_r17_the_digest_text_is_the_documented_one():
    shot = snapshot(ASSETS)
    panel = panel_from_snapshot(shot)
    used = ["KR:A001", "US:C003"]
    lines = ["m2-pj-v1", "m2-sb6-v1", " ".join(used)]
    for row in panel.rows_with(used):
        month = panel.months[int(row)]
        if date(2007, 1, 1) <= month < date(2026, 1, 1):
            factors = " ".join(repr(float(panel.factors[row, panel.assets.index(a)])) for a in used)
            lines.append(f"{month:%Y-%m} {factors}")
    expected = hashlib.sha256("\n".join(lines).encode()).hexdigest()
    assert history_digest(panel, used, BASE_DATE) == expected
    assert len(lines) == 3 + 228


def test_r18_months_beyond_the_base_date_and_before_the_replay_start_change_nothing(token):
    outside = {
        (asset, month): "9.0" for asset in ASSETS for month in (date(2006, 12, 1), date(2026, 1, 1))
    }
    plain = make_client(token, SnapshotReader(snapshot(ASSETS))).post(
        "/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token}
    )
    odd = make_client(token, SnapshotReader(snapshot(ASSETS, changes=outside))).post(
        "/v1/projections", json=body(), headers={"X-Antlab-Engine-Token": token}
    )
    assert plain.status_code == odd.status_code == 200
    assert odd.content == plain.content


def test_r19_a_zero_weight_asset_with_no_history_does_not_restrict_the_months(token):
    gone = frozenset(("US:D004", m) for m in FULL_MONTHS)
    headers = {"X-Antlab-Engine-Token": token}
    zero = team(weights=[*team()["weights"], {"asset": "US:D004", "weight": "0"}])
    with_zero = make_client(token, SnapshotReader(snapshot(ASSETS, skip=gone))).post(
        "/v1/projections", json=body(portfolios=[zero]), headers=headers
    )
    plain = make_client(token, SnapshotReader(snapshot(ASSETS))).post(
        "/v1/projections", json=body(), headers=headers
    )
    assert with_zero.status_code == 200
    assert with_zero.json()["portfolios"] == plain.json()["portfolios"]
    assert with_zero.json()["historyDigest"] == plain.json()["historyDigest"]


def test_r20_the_adapter_applies_the_currency_move_to_us_assets_only():
    shot = snapshot(ASSETS)
    panel = panel_from_snapshot(shot)
    fx = {c.month_block_id: c.fx_change for c in shot.fx}
    block = {m.month_start: m.month_block_id for m in shot.months}
    month = date(2012, 6, 1)
    for asset in shot.pool:
        key = f"{asset.market}:{asset.ticker}"
        local = next(
            r.local_return
            for r in shot.returns
            if r.asset_id == asset.asset_id and r.month_block_id == block[month]
        )
        move = fx[block[month]] if asset.market == "US" else Decimal(0)
        expected = float((1 + local) * (1 + move))
        got = panel.factors[panel.months.index(month), panel.assets.index(key)]
        assert got == expected, key
    # a US cell without a currency move stays empty; an odd ticker is not an asset
    no_fx = replace(shot, fx=tuple(c for c in shot.fx if c.month_block_id != block[month]))
    cut = panel_from_snapshot(no_fx)
    row = cut.months.index(month)
    assert cut.present[row, cut.assets.index("KR:A001")]
    assert not cut.present[row, cut.assets.index("US:C003")]
    odd = replace(shot, pool=(*shot.pool, replace(shot.pool[0], asset_id=99, ticker="lower")))
    assert "KR:lower" not in panel_from_snapshot(odd).assets


def test_r21_the_request_schema_has_no_money_field():
    schema = copy.deepcopy(create_app(Settings("t" * 40)).openapi())
    names = set(schema["components"]["schemas"])
    assert {"ProjectionRequest", "ProjectionResult", "Holding"} <= names
    fields = {
        field
        for name in names
        if name.startswith(("Projection", "Holding", "EndQ", "Mdd"))
        for field in schema["components"]["schemas"][name].get("properties", {})
    }
    assert not [f for f in fields if re.search(r"(?i)won|amount|price|month", f)]


def test_r22_the_replay_start_is_the_endpoints_floor():
    panel = panel_from_snapshot(snapshot(ASSETS))
    assert panel.months[0] == date(2006, 12, 1)
    assert date(2007, 1, 1) == stationary.REPLAY_START
    assert month_range(date(2007, 1, 1), date(2025, 12, 1))[0] == stationary.REPLAY_START


def test_r23_the_defaults_are_2000_paths_and_summaries_at_60_and_120(post):
    payload = {k: v for k, v in body(horizonTurns=120).items() if k not in ("n", "summaryTurns")}
    answer = ok(post(payload))
    assert answer["n"] == 2000
    assert [s["turn"] for s in answer["portfolios"][0]["summaries"]] == [60, 120]
    assert 0 <= answer["portfolios"][0]["summaries"][0]["breachCount"] <= 2000


def test_r17_the_digest_follows_every_portfolio_not_only_the_first(token):
    shot = snapshot(ASSETS)
    march = date(2015, 3, 1)
    first = team()
    second = {"key": "OTHER", "cash": "0.5", "weights": [{"asset": "KR:B002", "weight": "0.5"}]}
    plain = digest_of(token, shot, portfolios=[first, second])
    changed = snapshot(ASSETS, changes={("KR:B002", march): "0.01"})
    assert digest_of(token, changed, portfolios=[first, second]) != plain
    assert digest_of(token, changed, portfolios=[first]) == digest_of(
        token, shot, portfolios=[first]
    )
    assert digest_of(token, shot, portfolios=[second, first]) == plain


class GatedReader(SnapshotReader):
    """Blocks inside `read` until released, so a projection stays in flight."""

    def __init__(self):
        super().__init__()
        self.entered = threading.Event()
        self.release = threading.Event()

    def read(self, base_date):
        self.entered.set()
        assert self.release.wait(10)
        return super().read(base_date)


def test_r24_projections_in_flight_are_limited_and_the_slot_is_always_released(token):
    gate = GatedReader()
    app = create_app(Settings(token), history_reader=gate, max_projections=1)
    client = TestClient(app, raise_server_exceptions=False)
    headers = {"X-Antlab-Engine-Token": token}
    answers = []
    worker = threading.Thread(
        target=lambda: answers.append(client.post("/v1/projections", json=body(), headers=headers))
    )
    worker.start()
    assert gate.entered.wait(10)
    busy = client.post("/v1/projections", json=body(), headers=headers)
    payload = problem(busy, 503, "ENGINE_BUSY")
    assert not YEAR_MONTH.search(str(payload))
    # a request that never reaches the calculation is not turned away because of the limit
    wrong_sum = body(portfolios=[team(cash="0")])
    for bad in (wrong_sum, body(riskLimit="-0.0"), body(seasonSeed="9223372036854775808")):
        problem(client.post("/v1/projections", json=bad, headers=headers), 400, "INVALID_ARGUMENT")
    assert client.get("/healthz").status_code == 200
    gate.release.set()
    worker.join(10)
    assert answers[0].status_code == 200
    assert client.post("/v1/projections", json=body(), headers=headers).status_code == 200


def test_r24_a_failing_projection_gives_its_slot_back(token):
    failing = SnapshotReader(error=HistoryUnavailable("down"))
    app = create_app(Settings(token), history_reader=failing, max_projections=1)
    client = TestClient(app, raise_server_exceptions=False)
    headers = {"X-Antlab-Engine-Token": token}
    for _ in range(3):
        problem(
            client.post("/v1/projections", json=body(), headers=headers), 503, "HISTORY_UNAVAILABLE"
        )
    failing.error = RuntimeError("boom")
    for _ in range(3):
        problem(client.post("/v1/projections", json=body(), headers=headers), 500, "INTERNAL_ERROR")
    failing.error = None
    assert client.post("/v1/projections", json=body(), headers=headers).status_code == 200


def test_r24_the_default_limit_is_a_small_number():
    from antlab_engine.app import MAX_PROJECTIONS

    assert 1 <= MAX_PROJECTIONS <= 4
    app = create_app(Settings("t" * 40))
    held = [app.state.projection_slots.acquire(blocking=False) for _ in range(MAX_PROJECTIONS + 2)]
    assert held == [True] * MAX_PROJECTIONS + [False] * 2
