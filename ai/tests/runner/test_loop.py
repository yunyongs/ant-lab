"""F4, F5, F7, F8: the no-response streak, a whole season, API refusals and the untouched reply."""

import ast
import json
import re
from pathlib import Path

import pytest
from loop_helpers import Harness, bad, fail, ok
from runner_helpers import reply

from antlab_ai.runner import loop as loop_module
from antlab_ai.runner.api_client import ApiProblem
from antlab_ai.runner.loop import FINISHED, PAUSED

CORPUS = (
    Path(__file__).resolve().parents[3] / "src" / "test" / "resources" / "agent" / "turn-decision"
)
NR = [fail("UPSTREAM", http_status=503)] * 3


def corpus_text(name: str) -> str:
    return (CORPUS / "good" / name).read_text(encoding="utf-8")


def outcomes(spring):
    return [spring.outcome(t) for t in sorted(spring.decided)]


# ---- F4: three turns without a response ---------------------------------------------------------------


def test_the_third_no_response_turn_is_submitted_and_then_the_season_pauses(tmp_path):
    h = Harness(tmp_path, *NR, *NR, *NR, path_length=5)
    result = h.run()
    assert (result.state, result.reason, result.decisions) == (PAUSED, "NO_RESPONSE_STREAK", 3)
    assert outcomes(h.spring) == ["NO_RESPONSE"] * 3
    assert h.spring.turn == 2  # the third decision is in, the turn was not advanced
    assert h.spring.requests["advanceAgentTurn"] == 2
    assert h.journal_state.no_response_streak == 3


def test_a_decision_in_between_resets_the_streak(tmp_path):
    h = Harness(tmp_path, *NR, ok(), *NR, *NR, path_length=4)
    result = h.run()
    assert result.state == FINISHED
    assert outcomes(h.spring) == ["NO_RESPONSE", "DECISION", "NO_RESPONSE", "NO_RESPONSE"]
    assert h.journal_state.no_response_streak == 2


def test_an_invalid_turn_does_not_count_as_no_response(tmp_path):
    h = Harness(tmp_path, *NR, *NR, bad(), bad(), *NR, path_length=4)
    assert h.run().state == FINISHED
    assert outcomes(h.spring)[2] == "INVALID"


def test_after_a_resume_the_streak_starts_again(tmp_path):
    h = Harness(tmp_path, *NR, *NR, *NR, *NR, *NR, path_length=5)
    assert h.run().reason == "NO_RESPONSE_STREAK"
    result = h.run(resume=True)
    assert result.state == FINISHED  # turns 3 and 4: two more, not three in a row since the resume
    assert outcomes(h.spring) == ["NO_RESPONSE"] * 5
    assert h.journal_state.no_response_streak == 2


def test_the_streak_the_journal_counts_equals_the_one_the_api_stores(tmp_path):
    h = Harness(tmp_path, *NR, ok(), *NR, *NR, path_length=4)
    h.run()
    tail = 0
    for turn in sorted(h.spring.decided, reverse=True):
        if h.spring.outcome(turn) != "NO_RESPONSE":
            break
        tail += 1
    assert tail == h.journal_state.no_response_streak == 2


# ---- F5: a whole season -------------------------------------------------------------------------------


def test_a_twelve_turn_season_runs_to_the_end_with_every_attempt_accounted_for(tmp_path):
    formation = reply(
        formation_change={
            "goalkeeper": 20,
            "defender": 30,
            "midfielder": 30,
            "forward": 20,
            "reason": "safer",
        }
    )
    script = [
        ok(formation),  # turn 0: the API answers TURN_ZERO_FORMATION
        fail("UPSTREAM", http_status=503),  # turn 1: one retry
        ok(),
        bad(),  # turn 2: one re-ask
        ok(),
        *[ok() for _ in range(9)],  # turns 3-11
    ]
    h = Harness(tmp_path, *script, path_length=12)
    result = h.run()
    assert (result.state, result.decisions) == (FINISHED, 12)
    assert h.spring.status == "FINISHED" and h.spring.turn == 12
    assert h.spring.decided[0]["formationChange"]["code"] == "TURN_ZERO_FORMATION"
    stored = sum(len(h.spring.stored(t)) for t in range(12))
    assert stored == h.journal_state.attempt_total == len(h.port.requests) == len(script)
    assert h.port.remaining == 0
    assert outcomes(h.spring) == ["DECISION"] * 12
    assert result.sends == len(script)
    assert [c["status"] for c in h.spring.stored(2)] == ["INVALID", "OK"]


def test_a_finished_season_is_left_alone_on_a_second_run(tmp_path):
    h = Harness(tmp_path, ok(), path_length=1)
    h.run()
    again = h.run(port=Harness(tmp_path, ok()).port)
    assert again.state == FINISHED and again.sends == 0


def test_the_season_status_other_than_in_progress_stops_the_run(tmp_path):
    h = Harness(tmp_path, ok(), path_length=2)
    h.spring.status = "HALTED"
    result = h.run()
    assert (result.state, result.reason) == ("STOPPED", "HALTED")
    assert h.port.requests == []


# ---- F7: what the API refuses ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "code", ["LEDGER_MISMATCH", "LINEUP_MISMATCH", "INVALID_FORMATION", "EVENT_TIME_CONFLICT"]
)
def test_an_integrity_failure_flushes_the_earlier_attempts_and_pauses(tmp_path, code):
    h = Harness(tmp_path, fail("UPSTREAM", http_status=503), ok(), path_length=1)
    h.spring.engine_error = code
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "ENGINE_ERROR")
    assert [c["status"] for c in h.spring.stored(0)] == [
        "UPSTREAM"
    ]  # the last attempt must stay final
    assert 0 not in h.spring.decided
    h.spring.engine_error = None
    assert h.run(resume=True).state == FINISHED
    assert [c["status"] for c in h.spring.stored(0)] == ["UPSTREAM", "OK"]


@pytest.mark.parametrize(
    ("status", "code"),
    [
        (409, "DECISION_CONFLICT"),
        (400, "INVALID_ARGUMENT"),
        (401, "UNAUTHENTICATED"),
        (404, "NOT_FOUND"),
    ],
)
def test_other_api_refusals_stop_the_run_and_are_not_retried(tmp_path, status, code):
    h = Harness(tmp_path, ok())
    h.spring.fail["submitAgentDecision"] = (status, code)
    with pytest.raises(ApiProblem) as caught:
        h.run()
    assert (caught.value.status, caught.value.code) == (status, code)
    assert h.spring.requests["submitAgentDecision"] == 1
    rows = h.journal_rows()
    assert [r["kind"] for r in rows] == ["attempt"]  # nothing written after the failing call


def test_a_5xx_from_the_api_is_retried_by_the_client_not_the_loop(tmp_path):
    h = Harness(tmp_path, ok())
    h.spring.fail["getAgentTurnView"] = (500, "INTERNAL_ERROR")
    with pytest.raises(ApiProblem):
        h.run()
    assert h.port.requests == []


def test_advancing_without_a_decision_is_an_abort_not_a_retry(tmp_path):
    h = Harness(tmp_path, ok())
    h.spring.fail["advanceAgentTurn"] = (409, "DECISION_MISSING")
    with pytest.raises(ApiProblem):
        h.run()
    assert h.spring.requests["advanceAgentTurn"] == 1


# ---- F8: the runner does not edit the reply --------------------------------------------------------------


@pytest.mark.parametrize(
    "name", ["quantity_zero.json", "unknown_asset.json", "source_bench.json", "theses_present.json"]
)
def test_a_reply_the_engine_will_reject_is_sent_unchanged(tmp_path, name):
    text = corpus_text(name)
    h = Harness(tmp_path, ok(text))
    h.run()
    sent = h.spring.decision_hash[0]
    body = json.loads(sent)
    assert body["decision"] == json.loads(text)
    assert body["outcome"] == "DECISION"
    assert body["calls"][-1]["responseText"] == text


def test_a_fractional_number_in_a_thesis_stays_a_number(tmp_path):
    text = reply(theses=[{"weight": 1.5}])
    h = Harness(tmp_path, ok(text))
    h.run()
    assert json.loads(h.spring.decision_hash[0])["decision"]["theses"] == [{"weight": 1.5}]


RUNNER_SOURCES = sorted((Path(loop_module.__file__).parent).glob("*.py"))
MONEY_KEYS = {"price", "fee", "fee_rate", "fill", "fills", "avgPrice", "avg_price", "cashBalance"}


def test_runner_modules_import_nothing_from_the_engine():
    for path in RUNNER_SOURCES:
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            assert not [n for n in names if re.match(r"(antlab_engine|engine)(\.|$)", n)], path.name


def test_runner_modules_build_no_price_fee_or_fill_field():
    for path in RUNNER_SOURCES:
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Dict):
                keys = {k.value for k in node.keys if isinstance(k, ast.Constant)}
            elif isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Constant):
                keys = {node.slice.value}
            else:
                continue
            assert not (keys & MONEY_KEYS), f"{path.name} touches {keys & MONEY_KEYS}"
