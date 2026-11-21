"""AG-05 E: the memory step of one finished season, branch by branch, with recorded replies only."""

import json

import pytest
from api_helpers import contract_errors
from fake_spring import Crash
from step_helpers import LEAK, YEAR, StepHarness, fail, note, reflection, say

from antlab_ai.memory import reflect
from antlab_ai.modelport.fake import FakeModelPort, scripted_attempt
from antlab_ai.openrouter.client import SendCap
from antlab_ai.runner.decision import parse_reply
from antlab_ai.runner.journal import Journal
from antlab_ai.runner.records import RecordBuilder


def kinds(body):
    return [(c["kind"], c["attemptNo"], c["status"]) for c in body["calls"]]


# ---- accept ----------------------------------------------------------------------------


def test_a_clean_reflection_and_note_make_the_first_version(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()))
    result = h.run()
    assert (result.state, result.version, result.sends) == ("ACCEPTED", 1, 2)
    (body,) = h.submits
    assert contract_errors("SubmitSeasonMemoryRequest", body) == []
    assert body["outcome"] == "ACCEPTED" and body["baseVersion"] is None
    assert body["playbookCarried"] is False
    assert kinds(body) == [("REFLECTION", 1, "OK"), ("PLAYBOOK", 1, "OK")]
    assert body["reflection"] == reflection() and body["playbook"] == note()
    assert len(h.spring.memory_versions) == 1
    assert h.memory_state.done == {"outcome": "ACCEPTED", "version": 1}


def test_the_second_version_is_written_on_the_first(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note(["New rule one."])), versions=1)
    assert h.run().version == 2
    assert h.submits[0]["baseVersion"] == 1
    playbook_request = h.requests[1].messages
    user = json.loads(playbook_request[1].content)
    assert user["currentRules"] == ["Rule 1 stays."]
    assert user["reflection"] == reflection()


def test_the_reflection_prompt_holds_the_review_and_not_the_base_note(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), versions=1)
    h.run()
    system, user = h.requests[0].messages
    review = json.loads(user.content)
    assert set(review) == {"pathLength", "report", "score", "turns"}
    assert "Rule 1 stays." not in system.content + user.content
    assert "1년차 2월" in user.content


def test_a_reply_in_a_code_fence_is_accepted_and_stored_in_nfc(tmp_path):
    decomposed = "가"  # NFD of the syllable that follows
    fenced = "```json\n" + json.dumps(reflection(ruleCandidates=[decomposed])) + "\n```"
    h = StepHarness(tmp_path, say(fenced), say(note()))
    assert h.run().state == "ACCEPTED"
    assert h.submits[0]["reflection"]["ruleCandidates"] == ["가"]


# ---- re-ask and discard --------------------------------------------------------------------


def test_a_leaking_reflection_is_asked_again_once_without_its_text(tmp_path):
    bad = reflection(good=[LEAK, "b line", "c line"])
    h = StepHarness(tmp_path, say(bad), say(reflection()), say(note()))
    result = h.run()
    assert result.state == "ACCEPTED" and result.sends == 3
    second = h.requests[1].messages
    assert second[:2] == h.requests[0].messages
    assert "/good/0#s1: EVENT_TERM" in second[2].content
    assert "리먼" not in "".join(m.content for m in second)
    (body,) = h.submits
    assert kinds(body) == [
        ("REFLECTION", 1, "INVALID"),
        ("REFLECTION", 2, "OK"),
        ("PLAYBOOK", 1, "OK"),
    ]
    assert body["calls"][0]["responseText"] is None


def test_a_rejected_reply_leaves_no_text_in_the_journal_or_the_submit(tmp_path):
    bad = reflection(good=[YEAR, "b line", "c line"], ruleCandidates=[LEAK])
    h = StepHarness(tmp_path, say(bad), say(reflection()), say(note()))
    h.run()
    assert "2008" not in h.journal_text() and "리먼" not in h.journal_text()
    assert "2008" not in json.dumps(h.submits, ensure_ascii=False)
    assert "Kept cash" in h.journal_text()  # the accepted reply is the memory text itself


def test_two_failing_reflections_are_discarded_with_the_codes_of_the_last(tmp_path):
    first = reflection(good=[YEAR, "b line", "c line"])
    last = reflection(
        good=[LEAK, "b line", "c line"], ruleCandidates=["20% 하락, 8% 반등, 5% 상승 뒤에 팔았다."]
    )
    h = StepHarness(tmp_path, say(first), say(last), say(note()))
    result = h.run()
    assert (result.state, result.version, result.sends) == ("DISCARDED", None, 2)
    (body,) = h.submits
    assert contract_errors("SubmitSeasonMemoryRequest", body) == []
    assert body["outcome"] == "DISCARDED" and body["baseVersion"] is None
    assert body["discardCodes"] == ["EVENT_TERM", "FINGERPRINT"]
    assert "reflection" not in body and "playbook" not in body
    assert [c["kind"] for c in body["calls"]] == ["REFLECTION", "REFLECTION"]
    assert h.spring.memory_versions == []
    assert h.spring.memory_outcomes[7]["outcome"] == "DISCARDED"


def test_a_reflection_that_never_has_the_shape_is_discarded_with_schema(tmp_path):
    h = StepHarness(tmp_path, say("not json"), say({"schema": "season-reflection/1"}))
    assert h.run().state == "DISCARDED"
    assert h.submits[0]["discardCodes"] == ["SCHEMA"]
    note_text = h.requests[1].messages[2].content
    assert "Follow the schema exactly" in note_text


def test_a_lone_surrogate_in_a_reply_is_a_shape_failure_and_not_a_crash(tmp_path):
    text = json.dumps(reflection()).replace("Kept cash", "\\ud800 cash")
    h = StepHarness(tmp_path, say(text), say(reflection()), say(note()))
    assert h.run().state == "ACCEPTED"
    assert "/good/0: encoding" in h.requests[1].messages[2].content


def test_the_attempt_cap_is_two_per_reply_not_more(tmp_path):
    leaks = [say(reflection(good=[YEAR, "b line", "c line"]))] * 6
    h = StepHarness(tmp_path, *leaks)
    assert h.run().state == "DISCARDED"
    assert len(h.requests) == 2 and h.port.remaining == 4


# ---- the playbook rewrite -------------------------------------------------------------------


def test_a_failing_rewrite_twice_keeps_the_previous_rules(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note([YEAR])), say(note([LEAK])), versions=1)
    result = h.run()
    assert (result.state, result.version) == ("ACCEPTED", 2)
    (body,) = h.submits
    assert body["playbookCarried"] is True
    assert body["playbook"]["rules"] == ["Rule 1 stays."] and body["baseVersion"] == 1
    assert kinds(body) == [
        ("REFLECTION", 1, "OK"),
        ("PLAYBOOK", 1, "INVALID"),
        ("PLAYBOOK", 2, "INVALID"),
    ]
    assert h.spring.memory_versions[-1]["carried"] is True


def test_the_first_version_carries_the_rule_candidates(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say("nope"), say("nope"))
    assert h.run().state == "ACCEPTED"
    assert h.submits[0]["playbook"]["rules"] == reflection()["ruleCandidates"]
    assert h.submits[0]["playbookCarried"] is True


def test_the_first_version_without_rule_candidates_is_discarded_with_schema(tmp_path):
    h = StepHarness(tmp_path, say(reflection(ruleCandidates=[])), say("nope"), say("nope"))
    result = h.run()
    assert result.state == "DISCARDED"
    assert h.submits[0]["discardCodes"] == ["SCHEMA"]
    assert [c["kind"] for c in h.submits[0]["calls"]].count("PLAYBOOK") == 2


def test_an_oversize_note_gets_one_shorter_ask(tmp_path):
    long_rules = [f"Rule {n}: " + "keep the discipline of the plan " * 4 for n in range(15)]
    h = StepHarness(tmp_path, say(reflection()), say(note(long_rules)), say(note(["Short."])))
    assert h.run().state == "ACCEPTED"
    ask = h.requests[2].messages[-1].content
    assert "OVERSIZE" in ask and "Write less" in ask
    assert h.submits[0]["playbook"]["rules"] == ["Short."]


# ---- the API filter is authoritative ---------------------------------------------------------


def forced_leak(pointer, code="EVENT_TERM"):
    return (422, "MEMORY_LEAK", {"leaks": [{"pointer": pointer, "code": code}]})


def test_a_miss_of_the_pre_check_is_one_failure_and_the_reflection_is_asked_again(tmp_path):
    h = StepHarness(
        tmp_path,
        say(reflection()),
        say(note()),
        say(reflection(ruleCandidates=["Other rule."])),
        say(note()),
    )
    h.spring.memory_forced.append(forced_leak("/reflection/good/1#s1"))
    result = h.run()
    assert (result.state, result.version) == ("ACCEPTED", 1)
    assert len(h.submits) == 2
    assert kinds(h.submits[0]) == [("REFLECTION", 1, "OK"), ("PLAYBOOK", 1, "OK")]
    # The rewrite was made from the refused reflection, so it is made again; numbering restarts.
    assert kinds(h.submits[1]) == [
        ("REFLECTION", 1, "INVALID"),
        ("REFLECTION", 2, "OK"),
        ("PLAYBOOK", 1, "OK"),
    ]
    assert "/good/1#s1: EVENT_TERM" in h.requests[2].messages[-1].content
    assert h.submits[1]["calls"][0]["responseText"] is None


def test_two_refusals_of_the_reflection_by_the_api_discard_it(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), say(reflection()), say(note()))
    h.spring.memory_forced += [forced_leak("/reflection/good/1#s1")] * 2
    h.spring.memory_forced[1] = forced_leak("/reflection/ruleCandidates/0#s1", "YEAR")
    result = h.run()
    assert result.state == "DISCARDED"
    assert h.submits[-1]["discardCodes"] == ["YEAR"]


def test_a_refused_note_is_rewritten_once_and_the_reflection_is_kept(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), say(note(["Better rule."])))
    h.spring.memory_forced.append(forced_leak("/playbook/rules/0#s1", "PRICE"))
    assert h.run().state == "ACCEPTED"
    assert kinds(h.submits[1]) == [
        ("REFLECTION", 1, "OK"),
        ("PLAYBOOK", 1, "INVALID"),
        ("PLAYBOOK", 2, "OK"),
    ]
    assert h.submits[1]["playbook"]["rules"] == ["Better rule."]
    assert "/rules/0#s1: PRICE" in h.requests[2].messages[-1].content


def test_a_carried_note_the_api_refuses_discards_the_step(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say("x"), say("x"), versions=1)
    h.spring.memory_forced.append(forced_leak("/playbook/rules/0#s1", "EVENT_TERM"))
    result = h.run()
    assert result.state == "DISCARDED"
    assert h.submits[-1]["discardCodes"] == ["EVENT_TERM"]


def test_a_shape_refusal_by_the_api_is_handled_like_a_hit(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), say(reflection()), say(note()))
    h.spring.memory_forced.append(
        (
            422,
            "MEMORY_SCHEMA_INVALID",
            {"violations": [{"pointer": "/reflection/good", "keyword": "minItems"}]},
        )
    )
    assert h.run().state == "ACCEPTED"
    assert "/good: minItems" in h.requests[2].messages[-1].content


def test_a_refusal_with_no_pointer_leaves_the_season_pending(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()))
    h.spring.memory_forced.append((422, "MEMORY_LEAK", {}))
    result = h.run()
    assert (result.state, result.reason) == ("PENDING", "MEMORY_LEAK")


# ---- stale base, restart, lost answer --------------------------------------------------------


def test_a_stale_base_stops_and_the_next_run_rewrites_only_the_note(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note(["Old base rule."])))
    h.spring.memory_forced.append((409, "MEMORY_BASE_STALE", {}))
    first = h.run()
    assert (first.state, first.reason) == ("PENDING", "MEMORY_BASE_STALE")
    h.spring.memory_versions.append(
        {
            "rules": ["Rule from elsewhere."],
            "reflection": reflection(),
            "carried": False,
            "source": 91,
        }
    )
    h.port = h.port.__class__([say(note(["Rewritten on the new base."]))], "OLLAMA")
    second = h.run()
    assert (second.state, second.version, second.sends) == ("ACCEPTED", 2, 1)
    assert h.submits[-1]["baseVersion"] == 1
    user = json.loads(h.requests[0].messages[1].content)
    assert user["currentRules"] == ["Rule from elsewhere."]
    assert kinds(h.submits[-1]) == [("REFLECTION", 1, "OK"), ("PLAYBOOK", 1, "OK")]


def test_a_crash_before_the_submit_resumes_without_a_model_call(tmp_path):
    h = StepHarness(
        tmp_path, say(reflection(good=[YEAR, "b line", "c line"])), say(reflection()), say(note())
    )
    h.spring.crash_before["submitSeasonMemory"] = 1
    with pytest.raises(Crash):
        h.run()
    assert h.spring.memory_submits == []
    silent = FakeModelPort([])
    result = h.run(port=silent)
    assert (result.state, result.version, result.sends) == ("ACCEPTED", 1, 0)
    assert kinds(h.submits[0]) == [
        ("REFLECTION", 1, "INVALID"),
        ("REFLECTION", 2, "OK"),
        ("PLAYBOOK", 1, "OK"),
    ]


def test_a_crash_between_the_two_calls_does_not_ask_the_reflection_again(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()))
    crash = FakeModelPort([say(reflection()), Crash("dies in the playbook call")])
    with pytest.raises(Crash):
        h.run(port=crash)
    result = h.run(port=FakeModelPort([say(note())]))
    assert result.state == "ACCEPTED" and result.sends == 1
    assert len(crash.requests) == 2


def test_the_same_replies_give_the_same_submit(tmp_path):
    def script():
        return [say(reflection(good=[YEAR, "b line", "c line"])), say(reflection()), say(note())]

    first = StepHarness(tmp_path / "one", *script())
    second = StepHarness(tmp_path / "two", *script())
    first.run()
    second.run()
    assert first.submits == second.submits
    assert first.memory_state.attempts == second.memory_state.attempts


def test_a_lost_answer_is_read_back_as_done_and_nothing_is_sent_twice(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()))
    h.spring.lose_answer["submitSeasonMemory"] = 1
    first = h.run()
    assert (first.state, first.reason) == ("PENDING", "API_UNAVAILABLE")
    assert len(h.spring.memory_versions) == 1  # the API recorded it
    second = h.run(port=FakeModelPort([]))
    assert second.state == "DONE" and len(h.submits) == 1
    assert h.memory_state.done == {"outcome": "UNKNOWN", "version": None}
    assert h.run(port=FakeModelPort([])).state == "DONE"
    assert h.spring.requests["getReflectionView"] == 2  # a journaled outcome asks nothing


# ---- seasons that are not offered ------------------------------------------------------------


@pytest.mark.parametrize(
    ("state", "code"),
    [
        ("unfinished", "SEASON_NOT_FINISHED"),
        ("void", "MEMORY_NOT_ELIGIBLE"),
        ("abandoned", "MEMORY_NOT_ELIGIBLE"),
        ("held", "LEAGUE_OPEN"),
    ],
)
def test_a_season_the_api_does_not_offer_is_never_sent_to_the_model(tmp_path, state, code):
    h = StepHarness(tmp_path)
    h.spring.memory_seasons[7] = state
    result = h.run()
    assert (result.state, result.reason, result.sends) == ("NOT_OFFERED", code, 0)
    assert h.submits == [] and h.requests == []


def test_an_earlier_waiting_season_comes_first(tmp_path):
    h = StepHarness(tmp_path)
    h.spring.memory_seasons = {6: "pending", 7: "pending"}
    assert h.run().reason == "MEMORY_ORDER"


def test_a_season_with_an_outcome_is_done(tmp_path):
    h = StepHarness(tmp_path)
    h.spring.memory_seasons[7] = "done"
    assert h.run().state == "DONE"


def test_a_score_that_is_not_stored_yet_is_pending_and_nothing_is_sent(tmp_path):
    h = StepHarness(tmp_path)
    h.spring.score_pending = True
    result = h.run()
    assert (result.state, result.reason, result.sends) == ("PENDING", "SCORE_PENDING", 0)


def test_a_review_with_a_year_month_marker_is_discarded_without_a_call(tmp_path):
    import copy

    from api_helpers import fixture

    view = copy.deepcopy(fixture("reflection_view"))
    view["turns"][0]["reason"] = "as of 2020-03 the team held still"
    h = StepHarness(tmp_path)
    h.spring.reflection_view = view
    result = h.run()
    assert (result.state, result.sends) == ("DISCARDED", 0)
    assert h.submits[0]["discardCodes"] == ["YEAR_MONTH"] and h.submits[0]["calls"] == []


# ---- transport, limits, budget ---------------------------------------------------------------


def test_a_transport_failure_after_the_retries_leaves_the_season_pending_and_a_rerun_goes_on(
    tmp_path,
):
    h = StepHarness(tmp_path, *[fail("UPSTREAM", http_status=503)] * 3)
    first = h.run()
    assert (first.state, first.reason) == ("PENDING", "NO_RESPONSE")
    assert h.submits == [] and h.sleeps.delays
    h.port = h.port.__class__([say(reflection()), say(note())], "OLLAMA")
    second = h.run()
    assert second.state == "ACCEPTED"
    assert kinds(h.submits[0])[-2:] == [("REFLECTION", 4, "OK"), ("PLAYBOOK", 1, "OK")]


def test_a_policy_refusal_pauses_the_step(tmp_path):
    h = StepHarness(tmp_path, fail("POLICY_REFUSED", http_status=400))
    assert h.run().reason == "POLICY"


def test_a_budget_that_cannot_cover_a_send_stops_before_sending(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), budget_calls=1)
    result = h.run()
    assert (result.state, result.reason, result.sends) == ("PENDING", "BUDGET", 1)
    again = h.run()
    assert (again.state, again.reason, again.sends) == ("PENDING", "BUDGET", 0)
    assert len(h.requests) == 1 and h.submits == []


def test_the_turn_attempts_of_the_season_count_against_the_same_budget(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), budget_calls=3)
    builder = RecordBuilder("OLLAMA", "vendor/model:free")
    text = json.dumps({"schema": "turn-decision/1"})
    with Journal.open(h.state_dir, 1, 7) as journal:
        for number in (1, 2):
            record = builder.build(scripted_attempt(text), number, parse_reply(text))
            journal.append_attempt(0, record)
    assert h.run().reason == "BUDGET"
    assert len(h.requests) == 1


def test_a_budget_stop_becomes_a_discard_only_on_the_owners_flag(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), budget_calls=1)
    h.run()
    result = h.run(discard_on_budget=True)
    assert result.state == "DISCARDED"
    body = h.submits[0]
    assert body["discardCodes"] == ["BUDGET"] and body["baseVersion"] is None
    assert [c["kind"] for c in body["calls"]] == ["REFLECTION"]


def test_the_send_cap_of_the_command_stops_the_step_without_a_stop_row(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()))
    result = h.run(send_cap=SendCap(1))
    assert (result.state, result.reason) == ("STOPPED", "CALL_CAP")
    assert h.memory_state.last_stop is None
    assert h.run().state == "ACCEPTED" and h.spring.memory_versions


def test_an_unknown_cost_mode_never_sends(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), cost_mode="UNKNOWN")
    assert h.run().reason == "BUDGET" and h.requests == []


def test_stop_reasons_are_fixed_codes(tmp_path):
    h = StepHarness(tmp_path, fail("LIMITED", http_status=402, credit_refusal=True))
    result = h.run()
    assert result.reason == "CREDIT"
    rows = [json.loads(line) for line in h.journal_text().splitlines()]
    assert rows[-1] == {"v": 1, "kind": "mstop", "reason": "CREDIT"}
    assert reflect.PENDING == "PENDING"


def test_a_repeated_key_cannot_carry_unfiltered_text_into_the_submit(tmp_path):
    leak = "2008년 9월 리먼 사태 때 -20%, +8%, +6%"
    clean = json.dumps(reflection(), ensure_ascii=False)
    twice = clean.replace('"good": [', f'"good": ["{leak}", "b", "c"], "good": [', 1)
    assert twice.count('"good"') == 2
    h = StepHarness(tmp_path, say(twice), say(reflection()), say(note()))
    result = h.run()
    assert result.state == "ACCEPTED" and result.sends == 3
    assert kinds(h.submits[0])[0] == ("REFLECTION", 1, "INVALID")
    assert "/: duplicate" in h.requests[1].messages[2].content
    for text in (h.journal_text(), json.dumps(h.submits, ensure_ascii=False)):
        assert "리먼" not in text and "2008" not in text


def test_a_forbidden_key_in_the_review_stops_the_run_and_discards_nothing(tmp_path):
    import copy

    from api_helpers import fixture

    from antlab_ai.runner.prompt import LookAheadGuard

    view = copy.deepcopy(fixture("reflection_view"))
    view["turns"][0]["seed"] = "1"
    h = StepHarness(tmp_path)
    h.spring.reflection_view = view
    with pytest.raises(LookAheadGuard):
        h.run()
    assert h.submits == [] and h.requests == []
    assert h.spring.memory_seasons[7] == "pending"


def test_dropped_playbook_sends_are_counted(tmp_path):
    h = StepHarness(tmp_path, say(reflection()), say(note()), say(reflection()), say(note()))
    h.spring.memory_forced.append(forced_leak("/reflection/good/1#s1"))
    assert h.run().dropped == 1
