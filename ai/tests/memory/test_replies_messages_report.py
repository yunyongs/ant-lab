"""AG-05 E: the verdict on a memory reply, the prompts, the re-ask note and the comparison report."""

import json

import pytest

from antlab_ai.memory import messages, replies
from antlab_ai.memory.filter import REFLECTION_TOTAL_MAX
from antlab_ai.memory.report import render_comparison
from antlab_ai.runner.persona import load_persona
from antlab_ai.runner.prompt import LookAheadGuard, PromptError

GOOD = {
    "schema": "season-reflection/1",
    "good": ["Kept cash.", "Cut early.", "Wrote reasons."],
    "mistakes": [{"type": "OVERTRADE", "text": "Too many orders."}],
    "ruleCandidates": ["Write the reason first."],
}
NOTE = {"schema": "operating-note/1", "rules": ["Write the reason first."]}


def text(doc):
    return json.dumps(doc, ensure_ascii=False)


def test_a_clean_reflection_and_note_are_accepted():
    assert replies.judge_reflection(text(GOOD)).doc == GOOD
    assert replies.judge_note(text(NOTE)).doc == NOTE


@pytest.mark.parametrize("wrapped", ["```json\n{}\n```", "  {}\n"])
def test_a_fence_or_blanks_around_the_object_are_peeled(wrapped):
    assert replies.judge_note(wrapped.format(text(NOTE)).replace("{}", text(NOTE))).ok


@pytest.mark.parametrize(
    ("reply", "expected"),
    [
        (None, (("/", "json"),)),
        ("", (("/", "empty"),)),
        ("[1]", (("/", "type"),)),
        ("{broken", (("/", "json"),)),
        ("NaN", (("/", "json"),)),
        ("a\x00b", (("/", "json"),)),
    ],
)
def test_a_reply_without_an_object_has_a_fixed_pointer_and_keyword(reply, expected):
    assert replies.judge_reflection(reply).violations == expected


def test_shape_failures_name_the_place_by_known_names_only():
    bad = {**GOOD, "good": ["a", "b"], "mistakes": [{"type": "BOGUS", "text": "x"}], "extra": 1}
    verdict = replies.judge_reflection(text(bad))
    assert ("/good", "minItems") in verdict.violations
    assert ("/mistakes/0/type", "enum") in verdict.violations
    assert ("/", "additionalProperties") in verdict.violations
    assert all("extra" not in pointer for pointer, _ in verdict.violations)


def test_an_unknown_key_deeper_down_is_named_by_its_parent():
    bad = {**GOOD, "mistakes": [{"type": "OTHER", "text": "x", "리먼": 1}]}
    assert ("/mistakes/0", "additionalProperties") in replies.judge_reflection(text(bad)).violations


@pytest.mark.parametrize("reply", ['"\\ud800"', '"a\\u0000b"'])
def test_a_lone_surrogate_or_nul_is_an_encoding_failure(reply):
    doc = text({**GOOD, "good": ["ok", "ok", "x"]}).replace('"x"', reply)
    verdict = replies.judge_reflection(doc)
    assert verdict.violations == (("/good/2", "encoding"),)


def test_a_surrogate_pair_is_fine():
    doc = {**GOOD, "good": ["ok", "ok", "fine \U0001f600"]}
    assert replies.judge_reflection(text(doc)).ok


def test_the_leak_filter_runs_after_the_shape_and_gives_fixed_pairs():
    leak = {**GOOD, "good": ["2008년 9월에 줄였다", "리먼 사태 때 팔았다", "Bought at 54,000원."]}
    verdict = replies.judge_reflection(text(leak))
    assert ("/good/0#s1", "YEAR") in verdict.violations
    assert ("/good/1#s1", "EVENT_TERM") in verdict.violations
    assert ("/good/2#s1", "PRICE") in verdict.violations
    assert verdict.doc is None


def test_a_shape_failure_hides_a_leak_the_way_the_api_does():
    both = {**GOOD, "good": ["2008년 9월", "b"]}
    assert {code for _, code in replies.judge_reflection(text(both)).violations} == {"minItems"}


def test_the_cap_is_counted_after_nfc_over_all_strings():
    line = "x" * 119
    rules = [line] * 6  # 6 lines of 119 characters is over the cap of 600
    doc = {**GOOD, "good": [line, line, line], "ruleCandidates": [line, line, line]}
    assert sum(len(s) for s in doc["good"] + doc["ruleCandidates"]) > REFLECTION_TOTAL_MAX
    assert ("/", "OVERSIZE") in replies.judge_reflection(text(doc)).violations
    assert rules


def test_the_text_is_stored_in_nfc():
    doc = {**GOOD, "ruleCandidates": ["가"]}
    assert replies.judge_reflection(text(doc)).doc["ruleCandidates"] == ["가"]


def test_discard_codes_keep_the_contract_order_and_map_shape_failures_to_schema():
    pairs = (("/good/0#s1", "PRICE"), ("/good/1", "minItems"), ("/good/0#s1", "YEAR"))
    assert replies.discard_codes(pairs) == ["YEAR", "PRICE", "SCHEMA"]
    assert replies.discard_codes(()) == ["SCHEMA"]
    assert replies.discard_codes((("/", "OVERSIZE"),)) == ["OVERSIZE"]


# ---- prompts ------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def persona():
    return load_persona("patient-value")


def view():
    return {
        "baseVersion": 1,
        "basePlaybook": ["Base rule that must stay out."],
        "pathLength": 3,
        "report": {"totalReturn": "0.04"},
        "score": {"score": "61.00"},
        "turns": [{"turnNo": 0, "label": "1년차 1월", "reason": "hold"}],
    }


def test_the_reflection_prompt_has_persona_schema_and_the_review_only(persona):
    system, user = messages.reflection_messages(persona, view())
    assert persona.body in system.content and '"title": "season-reflection/1"' in system.content
    assert "{{" not in system.content
    assert set(json.loads(user.content)) == {"pathLength", "report", "score", "turns"}
    assert "Base rule" not in system.content + user.content


def test_the_playbook_prompt_holds_the_base_rules_and_the_new_reflection(persona):
    system, user = messages.playbook_messages(persona, ["Old."], GOOD)
    assert '"title": "operating-note/1"' in system.content
    assert json.loads(user.content) == {"currentRules": ["Old."], "reflection": GOOD}


def test_a_year_month_in_the_review_trips_the_guard(persona):
    bad = view()
    bad["turns"][0]["reason"] = "as of 2020-03"
    with pytest.raises(LookAheadGuard):
        messages.reflection_messages(persona, bad)


def test_the_reask_adds_one_note_with_pointers_rules_and_fixed_hints(persona):
    base = messages.playbook_messages(persona, [], GOOD)
    asked = messages.reask_messages(base, [("/rules/0#s1", "YEAR"), ("/rules/1", "minItems")])
    assert asked[:2] == base and len(asked) == 3
    note = asked[2].content
    assert "- /rules/0#s1: YEAR" in note and "- /rules/1: minItems" in note
    assert messages.HINTS["YEAR"] in note and "Follow the schema exactly" in note


@pytest.mark.parametrize(
    "pair", [("/rules/리먼", "YEAR"), ("/rules/0", "2008년"), ("rules", "YEAR")]
)
def test_the_reask_refuses_anything_outside_the_fixed_vocabulary(persona, pair):
    base = messages.playbook_messages(persona, [], GOOD)
    with pytest.raises(PromptError):
        messages.reask_messages(base, [pair])


def test_every_filter_code_has_a_hint():
    assert set(messages.HINTS) == {
        "YEAR",
        "YEAR_MONTH",
        "EVENT_TERM",
        "FINGERPRINT",
        "TURN_RETURN",
        "PRICE",
        "OVERSIZE",
    }


# ---- report -------------------------------------------------------------------------------


def side(version, multiple, persona=1, score=None):
    return {
        "memoryVersion": version,
        "personaVersion": persona,
        "valuationMultiple": multiple,
        "score": score,
        "failedTurns": 1,
        "experiencedSeed": False,
    }


def test_the_report_shows_the_rows_of_the_answer():
    data = {
        "versionA": 1,
        "versionB": 2,
        "groups": [{"groupLabel": "G1", "a": side(1, "1.01000000"), "b": side(2, "0.99000000")}],
        "summary": {"groups": 1, "bWins": 0, "anecdotal": True, "meanDiff": None},
    }
    out = render_comparison(data)
    assert "anecdotal" in out and "| G1 | A | v1 | p1 | 1.01000000 | - |" in out
    assert "| G1 | B | v2 | p1 | 0.99000000 | - |" in out
    assert "persona version differs" not in out


def test_the_report_of_no_groups_says_so():
    data = {
        "versionA": 0,
        "versionB": 1,
        "groups": [],
        "summary": {"groups": 0, "bWins": 0, "anecdotal": True, "meanDiff": None},
    }
    assert "No closed group" in render_comparison(data)
