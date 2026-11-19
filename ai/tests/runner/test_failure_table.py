"""F1-F3: the failure table, row by row, first as a pure function and then through the loop."""

import json
from decimal import Decimal

import pytest
from loop_helpers import Harness, bad, fail, ok

from antlab_ai.modelport.fake import scripted_attempt
from antlab_ai.modelport.port import BudgetRefusal, LocalRefusal
from antlab_ai.runner.config import default_timeout_ms
from antlab_ai.runner.failure import (
    ERROR,
    INVALID,
    LIMITED,
    MAX_ATTEMPTS_PER_TURN,
    OK,
    POLICY_REFUSED,
    TIMEOUT,
    UPSTREAM,
    Finish,
    Pause,
    Result,
    Send,
    next_step,
)
from antlab_ai.runner.loop import PAUSED, StillPaused


def results(*statuses, retry_after=None):
    return [Result(s, retry_after) for s in statuses]


# ---- the table as a pure function -------------------------------------------------------------


def test_nothing_sent_yet_sends_the_first_request():
    assert next_step([]) == Send()


def test_an_ok_reply_finishes_as_a_decision():
    assert next_step(results(OK)) == Finish("DECISION")


@pytest.mark.parametrize("status", [UPSTREAM, TIMEOUT])
def test_transport_failures_back_off_twice_then_finish_without_a_response(status):
    assert next_step(results(status), jitter=0.25) == Send(2.25)
    assert next_step(results(status, status), jitter=0.25) == Send(4.25)
    assert next_step(results(status, status, status)) == Finish("NO_RESPONSE")


def test_a_mix_of_upstream_and_timeout_shares_one_retry_budget():
    assert next_step(results(UPSTREAM, TIMEOUT, UPSTREAM)) == Finish("NO_RESPONSE")


def test_limited_with_a_stated_wait_is_waited_out_three_times():
    for count in (1, 2, 3):
        assert next_step(results(*[LIMITED] * count, retry_after=30)) == Send(30.0)
    assert next_step(results(*[LIMITED] * 4, retry_after=30)) == Pause("LIMITED_DAILY")


@pytest.mark.parametrize("wait", [None, 61, 3600])
def test_limited_without_a_usable_wait_is_like_the_daily_limit(wait):
    assert next_step(results(LIMITED, retry_after=wait)) == Pause("LIMITED_DAILY")


def test_the_longest_usable_wait_is_sixty_seconds():
    assert next_step(results(LIMITED, retry_after=60)) == Send(60.0)


def test_a_credit_refusal_pauses_even_with_a_wait_stated():
    assert next_step([Result(LIMITED, 30, credit_refusal=True)]) == Pause("CREDIT")


def test_policy_and_config_refusals_pause_at_once():
    assert next_step(results(POLICY_REFUSED)) == Pause("POLICY")
    assert next_step(results(ERROR)) == Pause("CONFIG")


def test_an_invalid_reply_earns_one_reask_then_finishes_invalid():
    assert next_step(results(INVALID)) == Send(reask=True)
    assert next_step(results(INVALID, INVALID)) == Finish(INVALID)
    assert next_step(results(INVALID, OK)) == Finish("DECISION")


def test_the_reask_gets_its_own_retry_budget():
    assert next_step(results(UPSTREAM, UPSTREAM, INVALID)) == Send(reask=True)
    assert next_step(results(UPSTREAM, UPSTREAM, INVALID, UPSTREAM)) == Send(2.0, True)
    assert next_step(results(INVALID, UPSTREAM, UPSTREAM, UPSTREAM)) == Finish("NO_RESPONSE")


def test_limited_waits_are_counted_per_logical_request():
    history = results(LIMITED, LIMITED, LIMITED, INVALID, LIMITED, retry_after=30)
    assert next_step(history) == Send(30.0, True)


def test_after_a_pause_the_refused_request_is_tried_again():
    refused = results(LIMITED, retry_after=None)
    assert next_step(refused) == Pause("LIMITED_DAILY")
    assert next_step(refused, barrier=1) == Send()
    assert next_step(results(POLICY_REFUSED), barrier=1) == Send()


def test_after_a_pause_the_counters_start_again():
    history = results(*[LIMITED] * 4, retry_after=30)
    assert next_step(history, barrier=4) == Send()
    assert next_step([*history, Result(LIMITED, 30)], barrier=4) == Send(30.0)


def test_a_reask_survives_a_pause():
    history = results(INVALID, LIMITED)
    assert next_step(history, barrier=2) == Send(reask=True)


def test_the_attempt_cap_ends_the_turn_with_the_last_status():
    assert (
        MAX_ATTEMPTS_PER_TURN == 12
    )  # two logical requests, each up to 3 transport tries and 3 waits
    history = results(*[UPSTREAM] * MAX_ATTEMPTS_PER_TURN)
    assert next_step(history, barrier=MAX_ATTEMPTS_PER_TURN - 2) == Finish("NO_RESPONSE")
    invalid = results(*[LIMITED] * (MAX_ATTEMPTS_PER_TURN - 1), INVALID)
    assert next_step(invalid, barrier=MAX_ATTEMPTS_PER_TURN - 1) == Finish(INVALID)


# ---- the table through the loop ---------------------------------------------------------------


def statuses(spring, turn=0):
    return [c["status"] for c in spring.stored(turn)]


@pytest.mark.parametrize("status", ["UPSTREAM", "TIMEOUT"])
def test_transport_failures_are_retried_with_backoff_and_end_without_a_response(tmp_path, status):
    h = Harness(tmp_path, *[fail(status, http_status=503)] * 3)
    result = h.run()
    assert h.sleeps.delays == [2.0, 4.0]
    assert statuses(h.spring) == [status] * 3
    assert h.spring.outcome(0) == "NO_RESPONSE"
    assert len(h.port.requests) == 3
    assert result.state == "FINISHED"


def test_a_limited_turn_waits_for_the_stated_time_and_then_succeeds(tmp_path):
    limited = fail("LIMITED", http_status=429, retry_after_s=30)
    h = Harness(tmp_path, limited, limited, limited, ok())
    h.run()
    assert h.sleeps.delays == [30.0, 30.0, 30.0]
    assert statuses(h.spring) == ["LIMITED"] * 3 + ["OK"]
    assert h.spring.outcome(0) == "DECISION"


def test_four_limited_answers_flush_all_four_and_pause_for_the_day(tmp_path):
    limited = fail("LIMITED", http_status=429, retry_after_s=30)
    h = Harness(tmp_path, *[limited] * 4)
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "LIMITED_DAILY")
    assert h.sleeps.delays == [30.0, 30.0, 30.0]
    assert statuses(h.spring) == ["LIMITED"] * 4
    assert h.spring.requests["flushAgentCalls"] == 1
    assert 0 not in h.spring.decided
    assert h.journal_rows()[-1]["kind"] == "pause"


def test_limited_without_a_wait_pauses_after_one_attempt(tmp_path):
    h = Harness(tmp_path, fail("LIMITED", http_status=429))
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "LIMITED_DAILY")
    assert h.sleeps.delays == []
    assert statuses(h.spring) == ["LIMITED"]


def test_a_credit_refusal_pauses_for_credit(tmp_path):
    h = Harness(tmp_path, fail("LIMITED", http_status=402, credit_refusal=True))
    result = h.run()
    assert result.reason == "CREDIT"
    assert h.sleeps.delays == []


@pytest.mark.parametrize(
    ("status", "http", "reason"), [("POLICY_REFUSED", 404, "POLICY"), ("ERROR", 400, "CONFIG")]
)
def test_policy_and_config_refusals_pause_after_one_attempt(tmp_path, status, http, reason):
    h = Harness(tmp_path, fail(status, http_status=http))
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, reason)
    assert statuses(h.spring) == [status]
    assert len(h.port.requests) == 1


def test_an_invalid_reply_is_asked_again_once_and_the_second_answer_decides(tmp_path):
    h = Harness(tmp_path, bad(), ok())
    h.run()
    assert statuses(h.spring) == ["INVALID", "OK"]
    assert h.spring.outcome(0) == "DECISION"
    first, second = h.port.requests
    assert len(first.messages) == 2
    assert len(second.messages) == 3
    assert second.messages[:2] == first.messages


def test_two_invalid_replies_end_the_turn_as_invalid(tmp_path):
    h = Harness(tmp_path, bad(), bad())
    h.run()
    assert statuses(h.spring) == ["INVALID", "INVALID"]
    assert h.spring.outcome(0) == "INVALID"


def test_invalid_then_three_failures_is_no_response_after_four_attempts(tmp_path):
    h = Harness(tmp_path, bad(), *[fail("UPSTREAM", http_status=500)] * 3)
    h.run()
    assert statuses(h.spring) == ["INVALID", "UPSTREAM", "UPSTREAM", "UPSTREAM"]
    assert h.spring.outcome(0) == "NO_RESPONSE"
    assert h.sleeps.delays == [2.0, 4.0]


def test_the_reask_note_never_echoes_the_bad_reply(tmp_path):
    marker = "MARKER-7f3a-do-not-echo"
    h = Harness(tmp_path, scripted_attempt(json.dumps({"reason": marker, "x": 1})), ok())
    h.run()
    sent = [m.content for m in h.port.requests[1].messages]
    assert not any(marker in text for text in sent)
    assert "Where it failed" in sent[-1]


def test_a_paused_season_must_be_resumed_explicitly(tmp_path):
    h = Harness(tmp_path, fail("LIMITED", http_status=429), ok())
    h.run()
    with pytest.raises(StillPaused):
        h.run()
    h.run(resume=True)
    assert h.spring.outcome(0) == "DECISION"
    assert statuses(h.spring) == ["LIMITED", "OK"]
    assert [r["kind"] for r in h.journal_rows()].count("resume") == 1


def test_after_a_resume_a_refusal_is_tried_again_not_paused_on_at_once(tmp_path):
    h = Harness(
        tmp_path, fail("POLICY_REFUSED", http_status=404), fail("POLICY_REFUSED", http_status=404)
    )
    assert h.run().reason == "POLICY"
    assert h.run(resume=True).reason == "POLICY"
    assert len(h.port.requests) == 2


# ---- F2: waits and timeouts ---------------------------------------------------------------------


@pytest.mark.parametrize(
    ("route", "turn_timeout", "expected"),
    [
        ("OPENROUTER", 120_000, 120_000),
        ("OPENROUTER", 30_000, 30_000),
        ("OLLAMA", 900_000, 300_000),
    ],
)
def test_each_attempt_timeout_is_the_smaller_of_the_turn_and_the_route_cap(
    tmp_path, route, turn_timeout, expected
):
    limited = fail("LIMITED", http_status=429, retry_after_s=60)
    h = Harness(
        tmp_path, limited, limited, limited, ok(), route=route, turn_timeout_ms=turn_timeout
    )
    h.run()
    assert [r.timeout_ms for r in h.port.requests] == [expected] * 4
    assert sum(h.sleeps.delays) == 180
    assert default_timeout_ms(route) >= expected


# ---- F3: budget -----------------------------------------------------------------------------------


def test_the_call_budget_pauses_before_the_attempt_that_would_exceed_it(tmp_path):
    h = Harness(tmp_path, *[fail("UPSTREAM", http_status=503)] * 3, budget_calls=2)
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "BUDGET")
    assert len(h.port.requests) == 2
    assert statuses(h.spring) == ["UPSTREAM", "UPSTREAM"]
    assert h.port.remaining == 1


def test_a_remote_model_whose_cost_is_unknown_never_gets_a_first_send(tmp_path):
    h = Harness(tmp_path, ok(), route="OLLAMA", cost_mode="UNKNOWN")
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "BUDGET")
    assert h.port.requests == []
    assert h.spring.requests["flushAgentCalls"] == 0


def test_spending_over_the_usd_budget_pauses_before_the_next_send(tmp_path):
    costly = fail("UPSTREAM", http_status=503, cost_usd=Decimal("0.5"))
    h = Harness(tmp_path, costly, ok())
    result = h.run()
    assert result.reason == "BUDGET"
    assert len(h.port.requests) == 1


def test_zero_cost_attempts_never_trip_the_zero_budget(tmp_path):
    h = Harness(tmp_path, ok(cost_usd=Decimal(0)))
    assert h.run().state == "FINISHED"


def test_a_local_limiter_refusal_pauses_with_nothing_recorded(tmp_path):
    h = Harness(tmp_path, LocalRefusal("local"))
    result = h.run()
    assert (result.state, result.reason) == (PAUSED, "LOCAL_CAP")
    assert h.spring.requests["flushAgentCalls"] == 0
    assert [r["kind"] for r in h.journal_rows()] == ["pause"]


def test_a_port_budget_refusal_pauses(tmp_path):
    h = Harness(tmp_path, BudgetRefusal("budget"))
    assert h.run().reason == "BUDGET"


def test_the_cap_does_not_trap_a_turn_whose_attempts_are_all_stored():
    history = results(*[POLICY_REFUSED] * MAX_ATTEMPTS_PER_TURN)
    assert next_step(history, barrier=MAX_ATTEMPTS_PER_TURN) == Send()


def test_twelve_pauses_in_one_turn_can_still_be_resumed(tmp_path):
    refused = fail("POLICY_REFUSED", http_status=404)
    h = Harness(tmp_path, *[refused] * 12, ok(), budget_calls=500)
    assert h.run().reason == "POLICY"
    for _ in range(11):
        assert h.run(resume=True).reason == "POLICY"
    assert h.run(resume=True).state == "FINISHED"
    assert h.spring.outcome(0) == "DECISION"
