"""POST /v1/paths with m2-sbc6-v1: reader labels, the digest, block flags and the 422 (TB1-TB7)."""

import csv
import hashlib
import json
import logging
import re
from collections import defaultdict
from datetime import date
from pathlib import Path

import pytest
from conftest import ENGINE_DIR
from synthetic_labels import label_fixture
from synthetic_months import BASE_DATE
from test_paths_route import FakeReader, make_client, problem, request

from antlab_engine.api.paths import history_digest
from antlab_engine.bootstrap import rules, stationary

DIGEST_VECTORS = ENGINE_DIR.parent / "src/test/resources/engine/parity/history-digest-labels-v1.csv"
VERSION = "m2-sbc6-v1"
YEAR_MONTH = re.compile(r"\d{4}-\d{2}")
POOL_NAME = re.compile(r"\bP[1-4]\b")
SEEDS = ("3", "17", "-42", "20261123")


def post(token, reader, **changes):
    client = make_client(token, reader, getattr(reader, "start", None))
    return client.post(
        "/v1/paths",
        json=request(engineVersion=VERSION, conditional=True, **changes),
        headers={"X-Antlab-Engine-Token": token},
    )


def fixture_reader(name="persist"):
    months, start, labels = label_fixture(name)
    reader = FakeReader(months, labels=labels)
    reader.start = start
    return reader, months, start, labels


def test_tb1_the_route_answers_what_the_generator_gives(token):
    reader, months, start, labels = fixture_reader()
    conditioned = 0
    for seed in SEEDS:
        for length in (12, 60, 120):
            response = post(token, reader, seed=seed, length=length)
            assert response.status_code == 200, response.text
            body = response.json()
            direct = stationary.generate(
                months, BASE_DATE, length, int(seed), labels=labels, replay_start=start
            )
            assert body["engineVersion"] == VERSION == direct.engine_version
            assert body["months"] == [f"{m:%Y-%m}" for m in direct.months]
            assert body["blocks"] == [
                {
                    "startTurn": b.start_turn,
                    "drawnLength": b.drawn_length,
                    "length": b.length,
                    "tier": b.tier.value,
                    "conditioned": b.target is not None,
                    "matched": b.matched,
                }
                for b in direct.blocks
            ]
            conditioned += sum(b["conditioned"] for b in body["blocks"])
    assert conditioned > 0  # the flags are not all false: the labels reached the generator


def test_tb1_the_block_flags_never_carry_the_regime(token):
    reader, *_ = fixture_reader()
    body = post(token, reader, length=120).json()
    for block in body["blocks"]:
        assert set(block) == {
            "startTurn",
            "drawnLength",
            "length",
            "tier",
            "conditioned",
            "matched",
        }
    assert not POOL_NAME.search(json.dumps(body))
    assert "target" not in json.dumps(body)


def test_the_paths_follow_the_rules_of_the_version(token):
    reader, months, start, labels = fixture_reader()
    for seed in SEEDS:
        body = post(token, reader, seed=seed, length=60).json()
        path = stationary.generate(
            months, BASE_DATE, 60, int(seed), labels=labels, replay_start=start
        )
        assert body["months"] == [f"{m:%Y-%m}" for m in path.months]
        assert (
            rules.violations(
                list(path.months),
                BASE_DATE,
                60,
                engine_version=VERSION,
                replay_start=start,
                blocks=path.blocks,
            )
            == []
        )


def test_the_rule_checker_knows_the_conditional_version():
    months, start, labels = label_fixture("persist")
    path = stationary.generate(months, BASE_DATE, 24, 5, labels=labels, replay_start=start)
    assert path.engine_version == VERSION
    assert (
        rules.violations(
            list(path.months),
            BASE_DATE,
            24,
            engine_version=VERSION,
            replay_start=start,
            blocks=path.blocks,
        )
        == []
    )
    with pytest.raises(ValueError):
        rules.violations([], BASE_DATE, 0, engine_version="m9-x-v1")


@pytest.mark.parametrize(
    ("version", "conditional"),
    [("m2-sb6-v1", True), (VERSION, False), ("p0-boot6-v1", True)],
)
def test_tb2_a_mismatched_switch_is_400_before_any_read(token, version, conditional):
    reader = FakeReader()
    client = make_client(token, reader)
    response = client.post(
        "/v1/paths",
        json=request(engineVersion=version, conditional=conditional),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(response, 400, "INVALID_ARGUMENT")
    assert reader.calls == []
    unconfigured = make_client(token, None).post(
        "/v1/paths",
        json=request(engineVersion=version, conditional=conditional),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(unconfigured, 400, "INVALID_ARGUMENT")


def test_the_default_switch_value_is_not_the_conditional_one(token):
    reader = FakeReader()
    response = make_client(token, reader).post(
        "/v1/paths",
        json=request(engineVersion=VERSION),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(response, 400, "INVALID_ARGUMENT")


@pytest.mark.parametrize("labelled", [0, 23])
def test_tb3_too_few_labels_is_422_without_months(token, labelled):
    months, start, labels = label_fixture("persist")
    window = [m for m in months if start <= m < date(2026, 1, 1)][:labelled]
    reader = FakeReader(months, labels={m: labels[m] for m in window})
    # base date 2026: the fixture history is the 1997-based one, so use its own replay start
    response = make_client(token, reader, replay_start=start).post(
        "/v1/paths",
        json=request(engineVersion=VERSION, conditional=True),
        headers={"X-Antlab-Engine-Token": token},
    )
    body = problem(response, 422, "REGIME_LABELS_MISSING")
    assert not YEAR_MONTH.search(response.text)
    assert not POOL_NAME.search(body["detail"])
    assert f"{labelled} labelled months" in body["detail"]


def test_tb3_labels_in_the_base_year_do_not_make_up_the_minimum(token):
    months, start, labels = label_fixture("persist")
    base_year_only = {m: p for m, p in labels.items() if m >= date(2026, 1, 1)}
    reader = FakeReader(months, labels=base_year_only)
    response = make_client(token, reader, replay_start=start).post(
        "/v1/paths",
        json=request(engineVersion=VERSION, conditional=True),
        headers={"X-Antlab-Engine-Token": token},
    )
    problem(response, 422, "REGIME_LABELS_MISSING")


def test_a_history_without_any_label_never_falls_back_to_the_unconditional_rule(token):
    response = post(token, FakeReader())
    problem(response, 422, "REGIME_LABELS_MISSING")


def test_a_null_label_is_an_absent_key_not_a_fifth_state(token):
    months, start, labels = label_fixture("persist")
    patchy = {m: p for i, (m, p) in enumerate(sorted(labels.items())) if i % 5}
    reader = FakeReader(months, labels=patchy)
    client = make_client(token, reader, replay_start=start)
    response = client.post(
        "/v1/paths",
        json=request(engineVersion=VERSION, conditional=True, length=60),
        headers={"X-Antlab-Engine-Token": token},
    )
    assert response.status_code == 200, response.text
    direct = stationary.generate(months, BASE_DATE, 60, 14, labels=patchy, replay_start=start)
    assert response.json()["months"] == [f"{m:%Y-%m}" for m in direct.months]


def read_digest_vectors():
    cases = defaultdict(list)
    with Path(DIGEST_VECTORS).open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            cases[row["case"]].append(row)
    return cases


def reference_digest(version, rows, labelled):
    """The layout written in docs/api/engine.yaml, built without the engine's own function."""
    first, last = "2007-01", "2025-12"
    kept = sorted((r["month"], r["label"]) for r in rows if first <= r["month"] <= last)
    lines = [f"{m}:{p or '-'}" if labelled else m for m, p in kept]
    return hashlib.sha256("\n".join([version, *lines]).encode("ascii")).hexdigest()


@pytest.mark.parametrize("case", sorted(read_digest_vectors()))
def test_tb4_the_digest_equals_the_shared_vector(case):
    rows = read_digest_vectors()[case]
    version = rows[0]["version"]
    assert {r["digest"] for r in rows} == {rows[0]["digest"]}
    months = [date.fromisoformat(r["month"] + "-01") for r in rows]
    labels = {date.fromisoformat(r["month"] + "-01"): r["label"] for r in rows if r["label"] != ""}
    got = history_digest(
        version,
        months,
        date.fromisoformat(rows[0]["base_date"]),
        date.fromisoformat(rows[0]["replay_start"]),
        labels if version == VERSION else None,
    )
    assert got == rows[0]["digest"]
    assert got == reference_digest(version, rows, version == VERSION)


def test_tb4_the_vector_file_covers_the_edge_cases():
    cases = read_digest_vectors()
    mixed = cases["mixed"]
    assert any(r["label"] == "" for r in mixed)
    assert any(r["month"] < "2007-01" and r["label"] for r in mixed)
    assert any(r["month"] >= "2026-01" and r["label"] for r in mixed)
    assert cases["mixed"][0]["digest"] != cases["mixed-without-labels"][0]["digest"]
    assert cases["unlabelled"][0]["digest"] != cases["mixed"][0]["digest"]


def digest_over_http(token, labels, months=None, **changes):
    months = months if months is not None else label_fixture("sample")[0]
    response = post(token, FakeReader(months, labels=labels), length=12, **changes)
    assert response.status_code == 200, response.text
    return response.json()["historyDigest"]


def test_tb4_a_replayable_label_moves_the_digest_and_a_base_year_label_does_not(token):
    months, _, labels = label_fixture("sample")
    reference = digest_over_http(token, labels)
    other = {"P1": "P2", "P2": "P3", "P3": "P4", "P4": "P1"}
    target = date(2010, 5, 1)
    assert digest_over_http(token, {**labels, target: other[labels[target]]}) != reference
    dropped = {m: p for m, p in labels.items() if m != target}
    assert digest_over_http(token, dropped) != reference
    base_year = date(2026, 1, 1)
    assert base_year in months
    flipped = {**labels, base_year: other.get(labels.get(base_year, "P1"), "P1")}
    assert digest_over_http(token, flipped) == reference
    below = date(2006, 12, 1)
    assert digest_over_http(token, {**labels, below: "P4"}) == reference
    assert digest_over_http(token, {k: v for k, v in labels.items() if k != base_year}) == reference


def test_tb4_the_unconditional_digest_ignores_labels(token):
    months, _, labels = label_fixture("sample")
    client_a = make_client(token, FakeReader(months, labels=labels))
    client_b = make_client(token, FakeReader(months))
    headers = {"X-Antlab-Engine-Token": token}
    a = client_a.post("/v1/paths", json=request(length=12), headers=headers).json()
    b = client_b.post("/v1/paths", json=request(length=12), headers=headers).json()
    assert a["historyDigest"] == b["historyDigest"]
    assert a["months"] == b["months"]
    assert a["engineVersion"] == "m2-sb6-v1"


def test_the_conditional_digest_is_not_the_months_only_text(token):
    months, _, labels = label_fixture("sample")
    got = digest_over_http(token, labels)
    replayable = sorted(m for m in months if date(2007, 1, 1) <= m < date(2026, 1, 1))
    plain = "\n".join([VERSION, *(f"{m:%Y-%m}" for m in replayable)])
    assert got != hashlib.sha256(plain.encode()).hexdigest()


def test_tb7_no_month_label_or_seed_in_logs_or_failures(token, caplog):
    caplog.set_level(logging.DEBUG)
    reader, *_ = fixture_reader()
    outputs = [post(token, FakeReader(), seed="987654321").text]
    ok = post(token, reader, seed="987654321", length=60)
    assert ok.status_code == 200
    for text in outputs:
        assert not YEAR_MONTH.search(text)
        assert not POOL_NAME.search(text)
        assert "987654321" not in text
    assert not YEAR_MONTH.search(caplog.text)
    assert not POOL_NAME.search(caplog.text)
    assert "987654321" not in caplog.text
    assert not any(month in caplog.text for month in ok.json()["months"])


def test_the_route_refuses_an_answer_of_another_version(token, monkeypatch):
    real = stationary.generate

    def wrong(*args, **kwargs):
        kwargs["labels"] = None
        return real(*args, **kwargs)

    monkeypatch.setattr(stationary, "generate", wrong)
    reader, *_ = fixture_reader()
    response = post(token, reader)
    assert response.status_code == 500
    assert response.json()["code"] == "INTERNAL_ERROR"


def test_the_422_answer_has_the_shape_of_the_shared_fixture(token):
    fixture = json.loads(
        (
            ENGINE_DIR.parent
            / "src/test/resources/engine/contract/problem-regime-labels-missing.json"
        ).read_text(encoding="utf-8")
    )
    body = post(token, FakeReader()).json()
    assert body.keys() == fixture.keys()
    for key in ("title", "status", "instance", "code"):
        assert body[key] == fixture[key]
    assert re.sub(r"\d+", "N", body["detail"]) == re.sub(r"\d+", "N", fixture["detail"])
