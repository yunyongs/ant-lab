"""POST /v1/percentiles: the route, its validation, its errors and its response shape."""

import copy
import inspect
import re
import threading
from datetime import date
from decimal import Decimal

import numpy as np
import pytest
from fastapi.testclient import TestClient
from synthetic_snapshot import BASE_DATE, FULL_MONTHS, snapshot
from test_projections_route import ASSETS, GatedReader, SnapshotReader, problem

from antlab_engine.api.percentiles import create_percentile
from antlab_engine.api.projection_panel import panel_from_snapshot
from antlab_engine.api.projections import history_digest
from antlab_engine.app import create_app
from antlab_engine.engine_errors import HistoryUnavailable, PoolNotFound
from antlab_engine.projection import Portfolio, project, rank
from antlab_engine.settings import Settings

URL = "/v1/percentiles"
YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
KEYS = {
    "projectionVersion",
    "engineVersion",
    "n",
    "horizonTurns",
    "historyDigest",
    "endMultiple",
    "sharpeDiff",
}


def team(**changes) -> dict:
    value = {
        "cash": "0.1",
        "weights": [
            {"asset": "KR:A001", "weight": "0.5"},
            {"asset": "US:C003", "weight": "0.4"},
        ],
    }
    return {**value, **changes}


def bench(**changes) -> dict:
    value = {"cash": "0.5", "weights": [{"asset": "KR:B002", "weight": "0.5"}]}
    return {**value, **changes}


def body(**changes) -> dict:
    value = {
        "seasonSeed": "14",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 24,
        "n": 100,
        "team": team(),
        "benchmark": bench(),
        "observed": {"endMultiple": "1.05", "sharpeDiff": "0.02"},
    }
    return {**value, **changes}


def observed(end="1.05", diff="0.02") -> dict:
    return {"endMultiple": end, "sharpeDiff": diff}


def client_for(token, reader, **kwargs) -> TestClient:
    app = create_app(Settings(token), history_reader=reader, **kwargs)
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def reader() -> SnapshotReader:
    return SnapshotReader()


@pytest.fixture
def post(token, reader):
    client = client_for(token, reader)
    headers = {"X-Antlab-Engine-Token": token}

    def send(payload: dict, *, with_token: bool = True):
        return client.post(URL, json=payload, headers=headers if with_token else {})

    return send


def ok(response) -> dict:
    assert response.status_code == 200, response.text
    return response.json()


def books(shot=None):
    panel = panel_from_snapshot(shot or snapshot(ASSETS))
    mine = Portfolio(
        "TEAM",
        {"KR:A001": Decimal("0.5"), "US:C003": Decimal("0.4")},
        Decimal("0.1"),
    )
    other = Portfolio("BENCH", {"KR:B002": Decimal("0.5")}, Decimal("0.5"))
    return panel, mine, other


def library_values(horizon=24, n=100, seed=14):
    panel, mine, other = books()
    result = project(
        panel,
        BASE_DATE,
        season_seed=seed,
        horizon=horizon,
        portfolios=[mine, other],
        risk_limit=Decimal("-0.3"),
        n=n,
    )
    return [p.end_values for p in result.portfolios]


def test_the_route_is_synchronous_so_the_calculation_runs_in_the_thread_pool():
    assert not inspect.iscoroutinefunction(create_percentile)


def test_e1_the_same_request_gives_the_same_bytes_and_another_seed_differs(post):
    first = post(body()).content
    assert post(body()).content == first
    assert post(body(seasonSeed="15")).content != first
    assert post(body(seasonSeed="-14")).content != first


def test_e2_the_counts_equal_the_library_and_a_brute_force_count(post):
    ends = np.sort(library_values()[0][:, -1])
    middle = (float(ends[49]) + float(ends[50])) / 2
    wire = format(Decimal(middle).quantize(Decimal("1e-12")), "f")  # 12 decimals at most
    assert ends[49] < float(wire) < ends[50]
    answer = ok(post(body(observed=observed(end=wire))))
    assert answer["endMultiple"] == {"below": 50, "equal": 0}
    panel, mine, other = books()
    expected = rank(
        panel,
        BASE_DATE,
        season_seed=14,
        horizon=24,
        team=mine,
        benchmark=other,
        observed_end_multiple=Decimal("1.05"),
        observed_sharpe_diff=Decimal("0.02"),
        n=100,
    )
    got = ok(post(body()))
    assert got["endMultiple"] == {
        "below": expected.end_multiple.below,
        "equal": expected.end_multiple.equal,
    }
    assert got["sharpeDiff"] == {
        "below": expected.sharpe_diff.below,
        "equal": expected.sharpe_diff.equal,
    }


def test_e3_values_outside_the_distribution_rank_at_the_ends(post):
    low = ok(post(body(observed=observed(end="0", diff="-100000"))))
    assert low["endMultiple"] == {"below": 0, "equal": 0}
    assert low["sharpeDiff"] == {"below": 0, "equal": 0}
    high = ok(post(body(observed=observed(end="999999999999.999999999999", diff="999999"))))
    assert high["endMultiple"] == {"below": 100, "equal": 0}
    assert high["sharpeDiff"] == {"below": 100, "equal": 0}


def test_e4_an_all_cash_team_ranks_the_flat_value_exactly(post):
    cash = {"cash": "1", "weights": []}
    answer = ok(post(body(team=cash, benchmark=cash, observed=observed("1", "0"))))
    assert answer["endMultiple"] == {"below": 0, "equal": 100}
    assert answer["sharpeDiff"] == {"below": 0, "equal": 100}


def test_e5_a_team_equal_to_the_benchmark_has_difference_zero_on_every_path(post):
    answer = ok(post(body(benchmark=team(), observed=observed(diff="0"))))
    assert answer["sharpeDiff"] == {"below": 0, "equal": 100}


def test_e6_the_season_path_is_never_among_the_paths_the_route_draws(post, monkeypatch):
    import importlib

    from antlab_engine.projection import derive_path_seeds

    module = importlib.import_module("antlab_engine.projection.rank")
    seen = []
    real = module.draw_paths

    def spy(panel, base_date, seeds, *args, **kwargs):
        seen.append(tuple(seeds))
        return real(panel, base_date, seeds, *args, **kwargs)

    monkeypatch.setattr(module, "draw_paths", spy)
    for season_seed in (-3, 0, 14):
        ok(post(body(seasonSeed=str(season_seed))))
        assert seen[-1] == derive_path_seeds(season_seed, 100)
        assert season_seed not in seen[-1]


@pytest.mark.parametrize("horizon", [12, 24, 60, 120])
def test_e7_the_four_horizons_are_accepted(post, horizon):
    answer = ok(post(body(horizonTurns=horizon)))
    assert answer["horizonTurns"] == horizon


@pytest.mark.parametrize("horizon", [0, 6, 36, 48, 121, 60.0, "60", True])
def test_e7_other_horizons_are_400(post, reader, horizon):
    problem(post(body(horizonTurns=horizon)), 400, "INVALID_ARGUMENT")
    assert reader.calls == []


def test_e8_a_missing_or_wrong_token_is_401_and_reads_nothing(token, reader):
    client = client_for(token, reader)
    assert client.post(URL, json=body()).status_code == 401
    wrong = {"X-Antlab-Engine-Token": "x" * 40}
    problem(client.post(URL, json=body(), headers=wrong), 401, "UNAUTHENTICATED")
    assert reader.calls == []


def test_e8_an_unknown_asset_is_400_after_one_read(post, reader):
    unknown = team(weights=[{"asset": "US:ZZZ9", "weight": "0.9"}])
    answer = problem(post(body(team=unknown)), 400, "INVALID_ARGUMENT")
    assert reader.calls == [BASE_DATE]
    assert not YEAR_MONTH.search(answer["detail"])
    reader.calls.clear()
    problem(post(body(benchmark=unknown)), 400, "INVALID_ARGUMENT")
    assert reader.calls == [BASE_DATE]


def hold(*pairs, cash="0.1") -> dict:
    return {"cash": cash, "weights": [{"asset": a, "weight": w} for a, w in pairs]}


INVALID = {
    "duplicated asset": body(team=hold(("KR:A001", "0.5"), ("KR:A001", "0.4"))),
    "duplicated asset, last weight completes the sum": body(
        team=hold(("KR:A001", "0.1"), ("KR:A001", "0.9"), cash="0")
    ),
    "duplicated asset in the benchmark": body(
        benchmark=hold(("KR:B002", "0.25"), ("KR:B002", "0.25"), cash="0.5")
    ),
    "duplicated asset whose weights sum to one": body(
        team=hold(("KR:A001", "0"), ("KR:A001", "1"), cash="0")
    ),
    "weights plus cash of 0.9": body(team=team(cash="0")),
    "weights plus cash short by 1e-8": body(team=team(cash="0.09999999")),
    "weights plus cash over by 1e-9": body(team=team(cash="0.100000001")),
    "benchmark weights not summing to one": body(benchmark=bench(cash="0.4")),
    "cash above one": body(team=team(cash="1.1")),
    "cash negative": body(team=team(cash="-0.1")),
    "cash a number": body(team=team(cash=0.1)),
    "weight a number": body(team=hold(("KR:A001", 0.9))),
    "weight in exponent form": body(team=hold(("KR:A001", "9e-1"))),
    "weight negative": body(team=hold(("KR:A001", "-0.5"))),
    "asset in lowercase": body(team=hold(("kr:a001", "0.9"))),
    "201 holdings": body(
        team=hold(*[(f"KR:T{i:03d}", "0") for i in range(201)], cash="1"),
    ),
    "team missing": {k: v for k, v in body().items() if k != "team"},
    "benchmark missing": {k: v for k, v in body().items() if k != "benchmark"},
    "observed missing": {k: v for k, v in body().items() if k != "observed"},
    "team with a key": body(team={**team(), "key": "TEAM"}),
    "extra field inside a team holding": body(
        team={**team(), "weights": [{"asset": "KR:A001", "weight": "0.9", "won": "5"}]}
    ),
    "extra field inside a benchmark holding": body(
        benchmark={**bench(), "weights": [{"asset": "KR:B002", "weight": "0.5", "won": "5"}]}
    ),
    "extra field in the team": body(team={**team(), "amount": "5"}),
    "extra field in the benchmark": body(benchmark={**bench(), "amount": "5"}),
    "extra observed field": body(observed={**observed(), "won": "1"}),
    "extra top field": {**body(), "attempts": 5},
    "end multiple a number": body(observed=observed(end=1.05)),
    "end multiple negative": body(observed=observed(end="-1")),
    "end multiple in exponent form": body(observed=observed(end="1e0")),
    "end multiple thirteen decimals": body(observed=observed(end="1.0000000000001")),
    "end multiple thirteen integer digits": body(observed=observed(end="1" + "0" * 12)),
    "end multiple empty": body(observed=observed(end="")),
    "end multiple NaN": body(observed=observed(end="NaN")),
    "sharpe difference a number": body(observed=observed(diff=0.02)),
    "sharpe difference seven integer digits": body(observed=observed(diff="1000000")),
    "sharpe difference thirteen decimals": body(observed=observed(diff="0.0000000000001")),
    "sharpe difference Infinity": body(observed=observed(diff="Infinity")),
    "sharpe difference with a plus sign": body(observed=observed(diff="+0.1")),
    "seed a number": body(seasonSeed=14),
    "seed too long": body(seasonSeed="1" * 20),
    "seed above int64": body(seasonSeed="9223372036854775808"),
    "seed below int64": body(seasonSeed="-9223372036854775809"),
    "n 99": body(n=99),
    "n 5001": body(n=5001),
    "n as a string": body(n="100"),
    "n as a float": body(n=100.0),
    "base date missing": {k: v for k, v in body().items() if k != "baseDate"},
    "base date as epoch seconds": body(baseDate=1767225600),
    "base date as a datetime": body(baseDate="2026-01-01T00:00:00"),
    "base date as a boolean": body(baseDate=True),
    "base date not a calendar day": body(baseDate="2026-02-30"),
}


@pytest.mark.parametrize("case", INVALID, ids=list(INVALID))
def test_e8_invalid_requests_are_400_before_the_history_is_read(post, reader, case):
    payload = problem(post(INVALID[case]), 400, "INVALID_ARGUMENT")
    assert reader.calls == []
    assert not YEAR_MONTH.search(str(payload))


def test_malformed_json_is_400(token, reader):
    client = client_for(token, reader)
    response = client.post(
        URL,
        content=b"{no",
        headers={"X-Antlab-Engine-Token": token, "content-type": "application/json"},
    )
    problem(response, 400, "INVALID_ARGUMENT")
    assert reader.calls == []


def test_the_boundary_values_are_accepted(post):
    edge = body(
        seasonSeed="-9223372036854775808",
        n=100,
        observed=observed("999999999999.999999999999", "-999999.999999999999"),
    )
    assert post(edge).status_code == 200
    edge = body(seasonSeed="9223372036854775807", n=5000, horizonTurns=12)
    assert ok(post(edge))["n"] == 5000
    empty = ok(post(body(observed=observed("0", "0"))))
    assert empty["endMultiple"]["below"] == 0


def test_e9_the_response_has_only_the_listed_keys_and_no_per_path_value(post):
    answer = ok(post(body()))
    assert set(answer) == KEYS
    assert set(answer["endMultiple"]) == {"below", "equal"}
    assert set(answer["sharpeDiff"]) == {"below", "equal"}
    assert re.fullmatch(r"[0-9a-f]{64}", answer["historyDigest"])
    assert (answer["projectionVersion"], answer["engineVersion"]) == ("m2-pj-v1", "m2-sb6-v1")
    assert (answer["n"], answer["horizonTurns"]) == (100, 24)
    for counts in (answer["endMultiple"], answer["sharpeDiff"]):
        assert all(isinstance(v, int) for v in counts.values())
        assert 0 <= counts["below"] + counts["equal"] <= answer["n"]


def test_the_defaults_are_2000_paths(post):
    payload = {k: v for k, v in body(horizonTurns=12).items() if k != "n"}
    answer = ok(post(payload))
    assert answer["n"] == 2000
    assert answer["endMultiple"]["below"] + answer["endMultiple"]["equal"] <= 2000


def test_the_request_and_result_models_have_no_money_or_month_field():
    schema = copy.deepcopy(create_app(Settings("t" * 40)).openapi())
    names = {n for n in schema["components"]["schemas"] if n.startswith(("Percentile", "Rank"))}
    assert {"PercentileRequest", "PercentileResult", "RankCounts"} <= names
    fields = {f for n in names for f in schema["components"]["schemas"][n].get("properties", {})}
    assert not [f for f in fields if re.search(r"(?i)won|amount|price|month|path|value", f)]


def test_the_digest_is_the_projection_digest_over_the_assets_of_team_and_benchmark(token):
    shot = snapshot(ASSETS)
    panel = panel_from_snapshot(shot)
    used = ["KR:A001", "KR:B002", "US:C003"]
    expected = history_digest(shot, panel, used, BASE_DATE)
    assert (
        ok(client_for(token, SnapshotReader(shot)).post(URL, json=body(), headers=auth(token)))[
            "historyDigest"
        ]
        == expected
    )


def auth(token) -> dict:
    return {"X-Antlab-Engine-Token": token}


def run_with(token, shot, **changes) -> dict:
    return ok(
        client_for(token, SnapshotReader(shot)).post(URL, json=body(**changes), headers=auth(token))
    )


def test_the_digest_follows_the_benchmark_assets_not_only_the_team(token):
    shot = snapshot(ASSETS)
    plain = run_with(token, shot)["historyDigest"]
    changed = snapshot(ASSETS, changes={("KR:B002", date(2015, 3, 1)): "0.01"})
    assert run_with(token, changed)["historyDigest"] != plain
    other = snapshot(ASSETS, changes={("KR:A001", date(2015, 3, 1)): "0.01"})
    assert run_with(token, other)["historyDigest"] != plain
    untouched = snapshot(ASSETS, changes={("US:D004", date(2015, 3, 1)): "0.01"})
    assert run_with(token, untouched) == run_with(token, shot)


def test_months_beyond_the_base_date_and_before_the_replay_start_change_nothing(token):
    outside = {
        (asset, month): "9.0" for asset in ASSETS for month in (date(2006, 12, 1), date(2026, 1, 1))
    }
    plain = client_for(token, SnapshotReader(snapshot(ASSETS))).post(
        URL, json=body(), headers=auth(token)
    )
    odd = client_for(token, SnapshotReader(snapshot(ASSETS, changes=outside))).post(
        URL, json=body(), headers=auth(token)
    )
    assert plain.status_code == odd.status_code == 200
    assert odd.content == plain.content
    inside = snapshot(ASSETS, changes={("KR:A001", date(2015, 5, 1)): "0.5"})
    assert run_with(token, inside) != plain.json()


def test_the_route_reads_the_history_for_the_requested_base_date_once(post, reader):
    assert post(body(baseDate="2026-03-05")).status_code == 200
    assert reader.calls == [date(2026, 3, 5)]


def test_reader_failures_map_to_http(token):
    cases = [
        (HistoryUnavailable("down"), 503, "HISTORY_UNAVAILABLE"),
        (PoolNotFound("none"), 404, "POOL_NOT_FOUND"),
        (RuntimeError("boom 2012-04"), 500, "INTERNAL_ERROR"),
    ]
    for error, status, code in cases:
        client = client_for(token, SnapshotReader(error=error))
        answer = problem(client.post(URL, json=body(), headers=auth(token)), status, code)
        assert not YEAR_MONTH.search(str(answer))


def test_a_history_that_cannot_build_the_paths_is_422(token):
    gone = frozenset(("KR:B002", m) for m in FULL_MONTHS)
    shot = snapshot(ASSETS, skip=gone)
    client = client_for(token, SnapshotReader(shot))
    answer = problem(client.post(URL, json=body(), headers=auth(token)), 422, "PATH_TOO_LONG")
    assert not YEAR_MONTH.search(str(answer))


def test_a_bad_request_is_400_even_without_a_configured_database(token):
    client = client_for(token, None)
    for bad in (body(team=team(cash="0")), {"seasonSeed": 1}, body(n=5)):
        problem(client.post(URL, json=bad, headers=auth(token)), 400, "INVALID_ARGUMENT")
    problem(client.post(URL, json=body(), headers=auth(token)), 503, "HISTORY_UNAVAILABLE")


def test_projections_in_flight_are_limited_and_a_busy_answer_says_when_to_retry(token):
    gate = GatedReader()
    client = client_for(token, gate, max_projections=1)
    answers = []
    worker = threading.Thread(
        target=lambda: answers.append(client.post(URL, json=body(), headers=auth(token)))
    )
    worker.start()
    assert gate.entered.wait(10)
    busy = client.post(URL, json=body(), headers=auth(token))
    problem(busy, 503, "ENGINE_BUSY")
    assert busy.headers["retry-after"] == "1"
    problem(
        client.post(URL, json=body(team=team(cash="0")), headers=auth(token)),
        400,
        "INVALID_ARGUMENT",
    )
    gate.release.set()
    worker.join(10)
    assert answers[0].status_code == 200
    assert client.post(URL, json=body(), headers=auth(token)).status_code == 200


def test_a_failing_request_gives_its_slot_back(token):
    failing = SnapshotReader(error=HistoryUnavailable("down"))
    client = client_for(token, failing, max_projections=1)
    for _ in range(3):
        problem(client.post(URL, json=body(), headers=auth(token)), 503, "HISTORY_UNAVAILABLE")
    failing.error = RuntimeError("boom")
    for _ in range(3):
        problem(client.post(URL, json=body(), headers=auth(token)), 500, "INTERNAL_ERROR")
    failing.error = None
    assert client.post(URL, json=body(), headers=auth(token)).status_code == 200


def test_a_percentile_and_a_projection_share_the_same_slots(token):
    gate = GatedReader()
    client = client_for(token, gate, max_projections=1)
    worker = threading.Thread(target=lambda: client.post(URL, json=body(), headers=auth(token)))
    worker.start()
    assert gate.entered.wait(10)
    projection = {
        "seasonSeed": "1",
        "baseDate": BASE_DATE.isoformat(),
        "horizonTurns": 60,
        "riskLimit": "-0.30",
        "portfolios": [{"key": "T", "cash": "1", "weights": []}],
    }
    busy = client.post("/v1/projections", json=projection, headers=auth(token))
    problem(busy, 503, "ENGINE_BUSY")
    gate.release.set()
    worker.join(10)


def test_c1_assets_that_never_move_rank_in_the_middle_through_the_route(token):
    assets = [f"KR:S{i:03d}" for i in range(10)]
    still = {(a, m): "0" for a in assets for m in FULL_MONTHS}
    reader = SnapshotReader(snapshot(assets, changes=still))
    client = client_for(token, reader)
    ten = {"cash": "0", "weights": [{"asset": a, "weight": "0.1"} for a in assets]}
    cash = {"cash": "1", "weights": []}
    for horizon in (12, 24):
        payload = body(horizonTurns=horizon, team=ten, benchmark=cash, observed=observed("1", "0"))
        answer = ok(client.post(URL, json=payload, headers=auth(token)))
        assert answer["endMultiple"] == {"below": 0, "equal": 100}
        assert answer["sharpeDiff"] == {"below": 0, "equal": 100}
