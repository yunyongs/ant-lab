"""F6: a runner killed at any point resumes the same turn without a second application."""

import pytest
from fake_spring import Crash
from loop_helpers import Harness, bad, fail, ok

from antlab_ai.modelport.fake import FakeModelPort
from antlab_ai.runner.api_client import ApiProblem
from antlab_ai.runner.config import ScriptedPort
from antlab_ai.runner.loop import FINISHED


def second_process(h: Harness, *attempts, **kwargs):
    """A fresh process: a new model port with its own script, the same journal directory and API."""
    h.port = ScriptedPort(attempts, h.route)
    return h.run(**kwargs)


def test_a_crash_before_the_submit_resumes_without_a_new_model_call(tmp_path):
    h = Harness(tmp_path, ok(), path_length=2)
    h.spring.crash_before["submitAgentDecision"] = 1
    with pytest.raises(Crash):
        h.run()
    assert len(h.port.requests) == 1
    result = second_process(
        h, ok()
    )  # a script for turn 1 only: a second call for turn 0 would fail
    assert result.state == FINISHED
    assert len(h.port.requests) == 1
    assert h.spring.requests["submitAgentDecision"] == 3  # one that crashed, one resent, turn 1
    assert [c["status"] for c in h.spring.stored(0)] == ["OK"]
    assert sorted(h.spring.decided) == [0, 1]


def test_a_crash_after_the_submit_reads_the_stored_decision_instead_of_sending_again(tmp_path):
    h = Harness(tmp_path, ok(), path_length=2)
    h.spring.crash_after["submitAgentDecision"] = 1
    with pytest.raises(Crash):
        h.run()
    assert 0 in h.spring.decided
    assert "decided" not in [r["kind"] for r in h.journal_rows()]
    result = second_process(h, ok())
    assert result.state == FINISHED
    assert h.spring.requests["getAgentDecision"] == 1
    assert h.spring.requests["submitAgentDecision"] == 2  # turn 0 once, turn 1 once
    assert h.spring.turn == 2


def test_a_lost_advance_answer_is_a_turn_mismatch_and_the_season_goes_on(tmp_path):
    h = Harness(tmp_path, ok(), ok(), path_length=2)
    h.spring.lose_answer["advanceAgentTurn"] = 1
    result = h.run()
    assert result.state == FINISHED
    assert h.spring.turn == 2
    assert sorted(h.spring.decided) == [0, 1]
    advanced = [r["turnNo"] for r in h.journal_rows() if r["kind"] == "advanced"]
    assert advanced == [0, 1]


def test_a_crash_after_the_advance_resumes_at_the_new_turn(tmp_path):
    h = Harness(tmp_path, ok(), path_length=2)
    h.spring.crash_after["advanceAgentTurn"] = 1
    with pytest.raises(Crash):
        h.run()
    assert h.spring.turn == 1
    result = second_process(h, ok())
    assert result.state == FINISHED
    assert sorted(h.spring.decided) == [0, 1]
    assert h.spring.requests["advanceAgentTurn"] == 2  # never advanced twice from the same turn


def test_a_crash_in_the_second_request_rebuilds_the_same_reask(tmp_path):
    h = Harness(tmp_path, bad(), Crash("model call"), path_length=1)
    with pytest.raises(Crash):
        h.run()
    first_reask = h.port.requests[1].messages
    result = second_process(h, ok())
    assert result.state == FINISHED
    assert h.port.requests[0].messages == first_reask
    assert [c["status"] for c in h.spring.stored(0)] == ["INVALID", "OK"]


def test_a_crash_in_a_backoff_does_not_repeat_the_attempts_already_made(tmp_path):
    h = Harness(tmp_path, fail("UPSTREAM", http_status=503), Crash("model call"), path_length=1)
    with pytest.raises(Crash):
        h.run()
    result = second_process(h, ok())
    assert result.state == FINISHED
    assert [c["attemptNo"] for c in h.spring.stored(0)] == [1, 2]
    assert [c["status"] for c in h.spring.stored(0)] == ["UPSTREAM", "OK"]


def test_attempt_numbers_continue_after_a_pause_and_a_restart(tmp_path):
    limited = fail("LIMITED", http_status=429)
    h = Harness(tmp_path, limited, path_length=1)
    assert h.run().state == "PAUSED"
    result = second_process(h, ok(), resume=True)
    assert result.state == FINISHED
    assert [c["attemptNo"] for c in h.spring.stored(0)] == [1, 2]


def test_a_journaled_ok_reply_is_never_asked_for_twice(tmp_path):
    h = Harness(tmp_path, ok(), path_length=1)
    h.spring.crash_before["submitAgentDecision"] = 1
    with pytest.raises(Crash):
        h.run()
    silent = FakeModelPort([])  # any call fails the test loudly
    h.port = silent
    assert h.run().state == FINISHED
    assert silent.requests == []


@pytest.mark.parametrize("status", [400, 401, 500])
def test_an_error_reading_the_stored_decision_stops_the_run_before_any_model_call(tmp_path, status):
    h = Harness(tmp_path, ok(), path_length=2)
    h.spring.crash_after["submitAgentDecision"] = 1
    with pytest.raises(Crash):
        h.run()
    h.spring.fail["getAgentDecision"] = (status, "INTERNAL_ERROR" if status == 500 else "NOT_FOUND")
    silent = FakeModelPort([])
    h.port = silent
    with pytest.raises(ApiProblem):
        h.run()
    assert silent.requests == []
