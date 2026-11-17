"""D6, D9, D10: prompt building."""

import copy
import json
from decimal import Decimal

import pytest
from runner_helpers import reply

from antlab_ai.runner import decision, prompt
from antlab_ai.runner.decision import MAX_TEXT_BYTES, Rejected, parse_reply
from antlab_ai.runner.persona import load_persona
from antlab_ai.runner.prompt import (
    PROMPT_TEMPLATE_VERSION,
    LookAheadGuard,
    PromptError,
    build_messages,
    canonical_json,
    reask_messages,
    system_message,
)


@pytest.fixture(scope="module")
def persona():
    return load_persona("patient-value")


def test_template_version_id():
    assert PROMPT_TEMPLATE_VERSION == "td1-p1"


def test_system_message_is_template_persona_and_schema_text(persona):
    system = system_message(persona)
    assert persona.body in system
    assert decision.SCHEMA_PATH.read_text(encoding="utf-8") in system
    assert "{{" not in system
    template = prompt.TEMPLATE_PATH.read_text(encoding="utf-8")
    assert system.startswith(template.split("{{PERSONA}}")[0])


def test_system_message_depends_only_on_persona(persona, small_view, pool180_view):
    first = build_messages(persona, small_view)
    second = build_messages(persona, pool180_view)
    assert first[0] == second[0]
    assert first[1] != second[1]
    assert [m.role for m in first] == ["system", "user"]


def test_placeholder_text_inside_the_persona_is_not_substituted_again(persona):
    from dataclasses import replace

    tricky = replace(persona, body="## 철학\n{{SCHEMA}} stays as written")
    system = system_message(tricky)
    assert "{{SCHEMA}} stays as written" in system
    assert system.count('"title": "turn-decision/1"') == 1


def test_user_message_is_the_turn_view_and_nothing_else(persona, small_view):
    before = copy.deepcopy(small_view)
    user = build_messages(persona, small_view)[1].content
    assert json.loads(user) == small_view
    assert small_view == before
    assert user == json.dumps(small_view, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def test_canonical_form_ignores_key_order_and_keeps_korean():
    assert canonical_json({"b": 1, "a": "가"}) == '{"a":"가","b":1}'
    assert canonical_json({"a": {"y": 1, "x": 2}}) == canonical_json({"a": {"x": 2, "y": 1}})


def test_agent_part_arrives_whole(persona, small_view):
    parsed = json.loads(build_messages(persona, small_view)[1].content)
    assert set(parsed) == set(small_view)
    assert parsed["agent"] == small_view["agent"]


@pytest.mark.parametrize(
    "key", ["seed", "monthBlockId", "yearMonth", "engineVersion", "seasonPath", "returnKind"]
)
def test_forbidden_keys_are_refused_at_any_depth(persona, small_view, key):
    view = copy.deepcopy(small_view)
    view["season"][key] = 1
    with pytest.raises(LookAheadGuard):
        build_messages(persona, view)
    nested = copy.deepcopy(small_view)
    nested["pool"][0][key] = "x"
    with pytest.raises(LookAheadGuard):
        build_messages(persona, nested)


@pytest.mark.parametrize("key", ["month_block_id", "YEAR_MONTH", "Return_Kind", "SEED"])
def test_forbidden_keys_in_other_spellings(persona, small_view, key):
    view = copy.deepcopy(small_view)
    view[key] = 1
    with pytest.raises(LookAheadGuard):
        build_messages(persona, view)


@pytest.mark.parametrize("value", ["2026-01", "1999-12-31", "2008-09 crash"])
def test_year_month_values_are_refused(persona, small_view, value):
    view = copy.deepcopy(small_view)
    view["pool"][1]["name"] = value
    with pytest.raises(LookAheadGuard):
        build_messages(persona, view)


@pytest.mark.parametrize("value", ["20-01", "2026", "-0.20", "KR:005930", "1999 fund", "0.0500"])
def test_ordinary_strings_pass_the_guard(persona, small_view, value):
    view = copy.deepcopy(small_view)
    view["pool"][1]["name"] = value
    assert build_messages(persona, view)


@pytest.mark.parametrize("number", [1.5, Decimal("1.5")])
def test_fractional_numbers_are_refused(persona, small_view, number):
    view = copy.deepcopy(small_view)
    view["season"]["extra"] = number
    with pytest.raises(PromptError):
        build_messages(persona, view)


def test_both_fixtures_pass_the_guard(persona, small_view, pool180_view):
    assert build_messages(persona, small_view)
    assert build_messages(persona, pool180_view)


def test_prompt_sizes_stay_under_the_text_limit(persona, small_view, pool180_view, capsys):
    sizes = {}
    for name, view in (("small(4)", small_view), ("pool180", pool180_view)):
        messages = build_messages(persona, view)
        sizes[name] = [len(m.content.encode("utf-8")) for m in messages]
        assert sum(sizes[name]) < MAX_TEXT_BYTES
    with capsys.disabled():
        print(f"\nprompt bytes (system, user): {sizes}")


def test_oversized_turn_view_is_refused_before_any_send(persona, small_view):
    view = copy.deepcopy(small_view)
    view["pool"] = view["pool"] * 400
    with pytest.raises(PromptError):
        build_messages(persona, view)


# ---- re-ask (D6) ---------------------------------------------------------------------------------------------


def _bad_reply_with_marker(marker):
    body = json.loads(reply())
    body["reason"] = 5
    body["orders"] = [{"asset": marker, "side": marker, "quantity": marker, marker: marker}]
    body["theses"] = [{marker: marker}]
    return json.dumps(body)


def test_reask_does_not_echo_the_bad_reply(persona, small_view):
    marker = "UNIQUEMARKER7731"
    bad = _bad_reply_with_marker(marker)
    result = parse_reply(bad)
    assert isinstance(result, Rejected)
    original = build_messages(persona, small_view)
    again = reask_messages(original, result.violations)
    assert again[:2] == original
    assert len(again) == 3 and again[2].role == "user"
    assert all(marker not in m.content for m in again)
    assert "/orders/0/asset: pattern" in again[2].content


def test_reask_lists_at_most_twenty_pairs(persona, small_view):
    result = parse_reply(reply(orders=[{"asset": "x"} for _ in range(30)]))
    assert isinstance(result, Rejected)
    note = reask_messages(build_messages(persona, small_view), result.violations)[2].content
    assert len([line for line in note.splitlines() if line.startswith("- ")]) == 20
    many = [(f"/orders/{i}", "type") for i in range(35)]
    note = reask_messages(build_messages(persona, small_view), many)[2].content
    assert len([line for line in note.splitlines() if line.startswith("- ")]) == 20


@pytest.mark.parametrize(
    "pair",
    [
        ("/orders/0", "because the model said so"),
        ("/orders/ZZ-1", "type"),
        ("orders/0", "type"),
        ("/" + "a/" * 7 + "a", "type"),
        ("", "type"),
        ("/reply", "ZZMARKER"),
    ],
)
def test_reask_refuses_pairs_outside_the_vocabulary(persona, small_view, pair):
    with pytest.raises(PromptError):
        reask_messages(build_messages(persona, small_view), [pair])


def test_every_keyword_the_corpus_produces_is_in_the_vocabulary(corpus, manifest):
    seen = set()
    for entry in manifest["files"]:
        result = parse_reply((corpus / entry["file"]).read_text(encoding="utf-8"))
        if isinstance(result, Rejected):
            seen |= {keyword for _, keyword in result.violations}
    assert seen and seen <= decision.KEYWORDS


def test_prompt_size_guard_is_exact_at_limit_minus_headroom(persona, small_view):
    limit = MAX_TEXT_BYTES - 4096
    view = copy.deepcopy(small_view)
    view["pad"] = ""
    base = prompt._size(
        (
            prompt.PromptMessage("system", system_message(persona)),
            prompt.PromptMessage("user", canonical_json(view)),
        )
    )
    view["pad"] = "x" * (limit - base)
    assert prompt._size(build_messages(persona, view)) == limit
    view["pad"] += "x"
    with pytest.raises(PromptError):
        build_messages(persona, view)
