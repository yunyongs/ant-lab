"""D3, D4: extraction rule and the strict parse layer."""

import json
import re

import pytest
from runner_helpers import reply

from antlab_ai.runner.decision import KEYWORDS, POINTER_PATTERN, Parsed, Rejected, parse_reply


def _violations(text):
    result = parse_reply(text)
    assert isinstance(result, Rejected), result
    return result.violations


def test_pure_json_is_accepted():
    assert isinstance(parse_reply(reply()), Parsed)


def test_surrounding_whitespace_is_accepted():
    assert isinstance(parse_reply("  \n\t" + reply() + "\r\n  "), Parsed)


@pytest.mark.parametrize("fence", ["```json\n{}\n```", "```\n{}\n```", "```json\r\n{}\r\n```"])
def test_one_whole_text_fence_is_accepted(fence):
    wrapped = fence.replace("{}", reply())
    assert isinstance(parse_reply(wrapped), Parsed)
    assert isinstance(parse_reply("\n" + wrapped + "\n"), Parsed)


@pytest.mark.parametrize(
    "text",
    [
        "```json\n" + reply() + "\n```\n```json\n" + reply() + "\n```",
        "Here is my answer:\n" + reply(),
        reply() + "\nThat is all.",
        "Here:\n```json\n" + reply() + "\n```",
        "```json\n" + reply() + "\n```\nThanks",
        "",
        "   \n ",
        None,
        "﻿" + reply(),
        "[" + reply() + "]",
        "```json\n```",
        "``` " + reply() + " ```",
    ],
    ids=[
        "two-fences", "prose-before", "prose-after", "prose-then-fence", "fence-then-prose",
        "empty", "blank", "none", "bom", "array", "empty-fence", "inline-fence",
    ],
)  # fmt: skip
def test_everything_else_is_rejected_at_the_reply_pointer(text):
    violations = _violations(text)
    assert violations
    assert violations[0][0] == "/reply"


@pytest.mark.parametrize(
    "text,keyword",
    [
        ("```json\n" + reply() + "\n```\n```json\n" + reply() + "\n```", "extract"),
        ("``` " + reply() + " ```", "extract"),
        ("```json\n```", "extract"),
        ("", "empty"),
        (None, "empty"),
        ("\ufeff" + reply(), "bom"),
        ("Here is my answer:\n" + reply(), "json"),
        ("[" + reply() + "]", "type"),
    ],
)
def test_each_extraction_failure_names_its_keyword(text, keyword):
    assert _violations(text) == (("/reply", keyword),)


def test_extraction_is_deterministic():
    text = "```json\n" + reply() + "\n```"
    assert parse_reply(text) == parse_reply(text)


@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_non_json_constants_are_refused(constant):
    head = '{"schema":"turn-decision/1","formation_change":null,"orders":[],"theses":[],"reason":'
    assert _violations(head + constant + "}") == (("/reply", "json"),)


def test_constant_inside_a_thesis_is_refused_too():
    text = reply().replace('"theses": []', '"theses": [{"x": NaN}]')
    assert _violations(text) == (("/reply", "json"),)


def test_duplicate_key_keeps_the_last_value(corpus):
    result = parse_reply((corpus / "good/duplicate_key_last_wins.json").read_text(encoding="utf-8"))
    assert isinstance(result, Parsed)
    assert result.decision["reason"] == "b"
    assert result.decision["orders"][0]["quantity"] == 1


def test_integer_beyond_int32_hits_the_schema_maximum():
    order = {
        "asset": "KR:005930",
        "side": "BUY",
        "quantity": 2147483648,
        "source": {"kind": "DIRECT"},
    }
    assert _violations(reply(orders=[order])) == (("/orders/0/quantity", "maximum"),)


def test_int32_bounds_are_accepted():
    order = {
        "asset": "KR:005930",
        "side": "BUY",
        "quantity": 2147483647,
        "source": {"kind": "DIRECT"},
    }
    low = dict(order, quantity=-2147483648)
    assert isinstance(parse_reply(reply(orders=[order, low])), Parsed)


def test_decimal_numbers_stay_exact():
    text = reply().replace('"theses": []', '"theses": [{"ratio": 0.10}]')
    result = parse_reply(text)
    assert isinstance(result, Parsed)
    assert str(result.decision["theses"][0]["ratio"]) == "0.10"


def test_absurd_integer_and_depth_are_refused_not_raised():
    huge = (
        '{"schema":"turn-decision/1","formation_change":null,"orders":[],"theses":[],"reason":"a","n":%s}'
        % ("9" * 6000)
    )
    assert _violations(huge) == (("/reply", "json"),)
    assert _violations("[" * 200_000) == (("/reply", "json"),)


def test_violations_use_the_fixed_vocabulary_and_never_carry_reply_text():
    marker = "ZZMARKER4711"
    bad = json.dumps(
        {
            "schema": "turn-decision/1",
            "formation_change": None,
            "orders": [{"asset": "kr:" + marker, "side": "buy", "quantity": "x"}],
            "theses": [],
            "reason": 5,
            marker: 1,
        }
    )
    violations = _violations(bad)
    assert 0 < len(violations) <= 20
    for pointer, keyword in violations:
        assert POINTER_PATTERN.fullmatch(pointer)
        assert keyword in KEYWORDS
        assert marker not in pointer and marker not in keyword
    assert ("/orders/0/asset", "pattern") in violations
    assert ("/reply", "additionalProperties") in violations


def test_violations_are_capped_at_twenty_and_sorted():
    orders = [{"asset": "x", "side": "y"} for _ in range(30)]
    violations = _violations(reply(orders=orders))
    assert len(violations) == 20
    assert list(violations) == sorted(set(violations))
    assert re.fullmatch(r"/orders/\d+(/\w+)?", violations[0][0])


@pytest.mark.parametrize("lang", ["python", "JSON", "jsonc", "yaml"])
def test_fence_with_another_language_is_rejected(lang):
    text = "```" + lang + "\n" + reply() + "\n```"
    assert _violations(text) == (("/reply", "extract"),)


def test_nbsp_around_json_is_not_json_whitespace():
    assert _violations(" " + reply()) == (("/reply", "json"),)
    assert _violations(reply() + " ") == (("/reply", "json"),)
