"""D5: what the API refuses to store (NUL, key shapes, size) is refused by the runner first."""

import json

import pytest
from runner_helpers import reply

from antlab_ai.runner.decision import (
    MAX_TEXT_BYTES,
    NO_KEYS,
    POINTER_PATTERN,
    Parsed,
    Rejected,
    is_storable,
    parse_reply,
    serialize,
)

# Samples of the A4 audit (C1): key shapes the API must catch, and ordinary words it must let through.
KEY_SHAPES = [
    "sk-or-v1-" + "x" * 24,
    "Bearer abcdefghij123456",
    "sk-ant-api03-" + "A1b2C3d4E5f6G7h8I9j0K1",
    "sk-proj-" + "A1b2C3d4E5f6G7h8I9j0K1",
    "sk-" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",
    "sk-or-v1-" + "0123456789abcdef" * 4,
]
ORDINARY = [
    "risk-adjusted-return-profile",
    "Bearer securities are rare",
    "task-management-dashboard-v2",
    "disk-space-utilization-alert",
    "risk-on-risk-off-rotation-regime",
    "bearer bonds-and-certificates-of-deposit",
    # No hyphen after the prefix: only the "no letter before sk-" rule lets these through.
    "risk-diversificationmanagementreview",
    "desk-allocationcommitteeminutes2",
]


@pytest.mark.parametrize("sample", KEY_SHAPES)
def test_key_shapes_are_caught(sample):
    assert NO_KEYS.search(sample)
    result = parse_reply(reply(reason=f"see {sample} here"))
    assert result == Rejected((("/reply", "secret"),))


@pytest.mark.parametrize("sample", ORDINARY)
def test_ordinary_words_pass(sample):
    assert NO_KEYS.search(sample) is None
    assert isinstance(parse_reply(reply(reason=sample)), Parsed)


def test_key_shape_in_a_free_form_thesis_key_is_caught():
    thesis = {KEY_SHAPES[0]: 1}
    assert parse_reply(reply(theses=[thesis])) == Rejected((("/reply", "secret"),))


def test_nul_in_reason_and_formation_reason():
    assert parse_reply(reply(reason="a\u0000b")) == Rejected((("/reason", "nul"),))
    formation = {
        "goalkeeper": 10,
        "defender": 30,
        "midfielder": 30,
        "forward": 30,
        "reason": "a\u0000",
    }
    assert parse_reply(reply(formation_change=formation)) == Rejected(
        (("/formation_change/reason", "nul"),)
    )


def test_nul_in_a_nested_value_and_in_a_key_of_a_free_form_thesis():
    assert parse_reply(reply(theses=[{"a": "x\u0000"}])) == Rejected((("/theses/0/_", "nul"),))
    assert parse_reply(reply(theses=[{"k\u0000": 1}])) == Rejected((("/theses/0", "nul"),))


def test_pointer_depth_stays_inside_the_pointer_pattern():
    deep = {"a": "x\u0000"}
    for _ in range(9):
        deep = {"k": deep}
    result = parse_reply(reply(theses=[deep]))
    assert isinstance(result, Rejected)
    assert all(POINTER_PATTERN.fullmatch(pointer) for pointer, _ in result.violations)


def test_pointer_never_carries_a_free_form_key():
    marker = "ZZMARKER99"
    result = parse_reply(reply(theses=[{marker: "x\u0000"}]))
    assert isinstance(result, Rejected)
    assert all(marker not in pointer for pointer, _ in result.violations)


def _padded(extra: int) -> str:
    base = reply(theses=[{"p": ""}])
    size = len(serialize(json.loads(base)).encode("utf-8"))
    return serialize(json.loads(reply(theses=[{"p": "x" * (MAX_TEXT_BYTES - size + extra)}])))


def test_size_limit_is_inclusive_at_262144_bytes():
    at_limit = _padded(0)
    assert len(serialize(json.loads(at_limit)).encode("utf-8")) == MAX_TEXT_BYTES
    assert isinstance(parse_reply(at_limit), Parsed)
    assert parse_reply(_padded(1)) == Rejected((("/reply", "size"),))


def test_size_counts_utf8_bytes_not_characters():
    base = reply(theses=[{"p": ""}])
    size = len(serialize(json.loads(base)).encode("utf-8"))
    characters = (MAX_TEXT_BYTES - size) // 3 + 1
    result = parse_reply(reply(theses=[{"p": "가" * characters}]))
    assert result == Rejected((("/reply", "size"),))


def test_is_storable_matches_the_call_record_rules():
    assert is_storable(None)
    assert is_storable("plain reply")
    assert not is_storable("a\u0000b")
    assert not is_storable(KEY_SHAPES[1])
    assert is_storable("x" * MAX_TEXT_BYTES)
    assert not is_storable("x" * (MAX_TEXT_BYTES + 1))
    assert not is_storable("가" * (MAX_TEXT_BYTES // 3 + 1))


# ---- audit round 1 (C2, C3) -------------------------------------------------------------------------------


def test_lone_surrogate_in_a_reply_is_rejected_not_raised():
    text = '{"schema":"turn-decision/1","formation_change":null,"orders":[],"theses":[],"reason":"\\ud800"}'
    assert parse_reply(text) == Rejected((("/reply", "json"),))
    assert parse_reply("\ud800") == Rejected((("/reply", "json"),))


def test_lone_surrogate_in_a_key_is_rejected_not_raised():
    text = reply().replace('"theses": []', '"theses": [{"\\udc00": 1}]')
    assert parse_reply(text) == Rejected((("/reply", "json"),))


def test_is_storable_is_false_for_a_lone_surrogate():
    assert is_storable("\ud800") is False


def test_duplicate_key_that_hides_a_key_shape_in_the_raw_text_is_rejected():
    raw = (
        '{"schema":"turn-decision/1","formation_change":null,"orders":[],"theses":[],'
        f'"reason":"{KEY_SHAPES[0]}","reason":"hold"}}'
    )
    assert not is_storable(raw)
    assert parse_reply(raw) == Rejected((("/reply", "secret"),))


def test_whitespace_padding_beyond_the_text_limit_is_rejected():
    raw = reply() + " " * (MAX_TEXT_BYTES + 1)
    assert parse_reply(raw) == Rejected((("/reply", "size"),))


def test_raw_text_at_the_limit_is_still_accepted():
    body = reply()
    raw = body + " " * (MAX_TEXT_BYTES - len(body.encode("utf-8")))
    assert len(raw.encode("utf-8")) == MAX_TEXT_BYTES
    assert isinstance(parse_reply(raw), Parsed)


def test_every_accepted_reply_is_storable(corpus, manifest):
    for entry in manifest["files"]:
        text = (corpus / entry["file"]).read_text(encoding="utf-8")
        if isinstance(parse_reply(text), Parsed):
            assert is_storable(text), entry["file"]
