"""D7, D8: persona loader and the shipped persona."""

import hashlib
import re
from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from antlab_ai.runner.persona import PERSONAS_DIR, PersonaError, load_persona, parse_persona

SHIPPED = PERSONAS_DIR / "patient-value.md"
DENY_LIST = (
    "buffett", "lynch", "graham", "soros", "dalio", "munger", "templeton", "fisher", "marks", "greenblatt",
    "버핏", "린치", "그레이엄", "소로스", "달리오", "멍거", "템플턴",
)  # fmt: skip


def _text() -> str:
    return SHIPPED.read_text(encoding="utf-8")


def _swap(old: str, new: str) -> bytes:
    text = _text()
    assert old in text
    return text.replace(old, new, 1).encode("utf-8")


def test_shipped_persona_loads():
    persona = load_persona("patient-value")
    assert persona.key == "patient-value"
    assert persona.version == 1
    assert persona.display_name == "인내하는 가치 매니저"
    assert persona.style_master is None
    assert persona.default_formation == (10, 30, 30, 30)
    assert persona.body.startswith("## 철학")


def test_repo_personas_dir_is_the_top_level_folder(repo_root):
    assert PERSONAS_DIR == repo_root / "personas"


def test_hash_is_sha256_of_the_file_bytes_and_changes_with_one_byte():
    data = SHIPPED.read_bytes()
    persona = parse_persona(data)
    assert persona.sha256 == hashlib.sha256(data).hexdigest()
    changed = parse_persona(_swap("오래 기다린다", "오래 기다린다 "))
    assert changed.sha256 != persona.sha256


def test_risk_limit_is_an_exact_decimal_not_a_float():
    persona = load_persona("patient-value")
    assert persona.default_risk_limit == Decimal("-0.20")
    assert persona.default_risk_limit.as_tuple().exponent == -2
    assert str(persona.default_risk_limit) == "-0.20"


def test_safe_load_would_have_made_it_a_float():
    front = _text().split("---\n")[1]
    assert isinstance(yaml.safe_load(front)["default_risk_limit"], float)


@pytest.mark.parametrize("limit", ["-0.10", "-0.20", "-0.30", "-0.40", "-0.2", "-0.4"])
def test_allowed_risk_limits(limit):
    assert parse_persona(_swap("-0.20", limit)).default_risk_limit == Decimal(limit)


@pytest.mark.parametrize("limit", ["-0.25", "0.20", "-0.50", "-0.200", "NaN", "-1"])
def test_other_risk_limits_are_refused(limit):
    with pytest.raises(PersonaError):
        parse_persona(_swap("default_risk_limit: -0.20", f"default_risk_limit: {limit}"))


@pytest.mark.parametrize(
    "old,new",
    [
        ("key: patient-value\n", ""),
        ("style_master: null\n", ""),
        ("version: 1\n", "version: 1\nextra: 1\n"),
        ("version: 1\n", "version: 1\nversion: 2\n"),
        ("key: patient-value", "key: Patient_Value"),
        ("key: patient-value", "key: patient--value"),
        ("key: patient-value", "key: -patient"),
        ("key: patient-value", "key: " + "a" * 65),
        ("version: 1", "version: 0"),
        ("version: 1", "version: 1.0"),
        ("version: 1", "version: -1"),
        ("version: 1", "version: one"),
        ("display_name: 인내하는 가치 매니저", "display_name: "),
        ("display_name: 인내하는 가치 매니저", "display_name: " + "가" * 51),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [10, 30, 30, 29]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [0, 40, 30, 30]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [10, 30, 60]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [10, 30, 30, 30, 0]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [10.0, 30, 30, 30]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [10, 30, 30, -0]"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: 100"),
        ("default_formation: [10, 30, 30, 30]", "default_formation: [[10], 30, 30, 30]"),
        ("style_master: null", "style_master: Buffett"),
        ("style_master: null", "style_master: [a]"),
        ("style_master: null", "style_master: {a: b}"),
    ],
)
def test_front_matter_defects_are_refused(old, new):
    with pytest.raises(PersonaError):
        parse_persona(_swap(old, new))


@pytest.mark.parametrize(
    "mutate",
    [
        lambda d: b"\xef\xbb\xbf" + d,
        lambda d: d.replace(b"\n", b"\r\n"),
        lambda d: d.replace(b"---\n", b"", 1),
        lambda d: d.replace(b"\n---\n", b"\n", 1),
        lambda d: d + b"\x00",
        lambda d: b"\xff\xfe" + d,
        lambda d: b"\n" + d,
    ],
    ids=["bom", "crlf", "no-open", "no-close", "nul", "not-utf8", "leading-blank"],
)
def test_file_level_defects_are_refused(mutate):
    with pytest.raises(PersonaError):
        parse_persona(mutate(SHIPPED.read_bytes()))


def test_a_style_master_key_is_accepted_as_text():
    persona = parse_persona(_swap("style_master: null", "style_master: buffett"))
    assert persona.style_master == "buffett"


def test_file_name_must_match_key(tmp_path):
    (tmp_path / "other-name.md").write_bytes(SHIPPED.read_bytes())
    with pytest.raises(PersonaError):
        load_persona("other-name", tmp_path)


@pytest.mark.parametrize("key", ["../patient-value", "Patient", "a/b", "", "patient value"])
def test_unsafe_keys_never_touch_the_disk(key):
    with pytest.raises(PersonaError):
        load_persona(key)


def test_symlinked_persona_is_refused(tmp_path):
    (tmp_path / "patient-value.md").symlink_to(SHIPPED)
    with pytest.raises(PersonaError):
        load_persona("patient-value", tmp_path)


def test_missing_persona(tmp_path):
    with pytest.raises(PersonaError):
        load_persona("patient-value", tmp_path)


# ---- the shipped content (D8) ---------------------------------------------------------------------------------


def _sections() -> dict[str, list[str]]:
    persona = load_persona("patient-value")
    sections: dict[str, list[str]] = {}
    current = None
    for line in persona.body.splitlines():
        if line.startswith("## "):
            current = line
            sections[current] = []
        elif current and line.strip():
            sections[current].append(line)
    return sections


def test_sections_are_present_in_order():
    assert list(_sections()) == ["## 철학", "## 행동 규칙", "## 하지 않는 것"]


def test_philosophy_is_at_most_300_characters():
    text = " ".join(_sections()["## 철학"])
    assert 0 < len(text) <= 300


def test_rules_are_five_to_ten_lines_and_not_empty_lists():
    rules = _sections()["## 행동 규칙"]
    assert 5 <= len(rules) <= 10
    assert all(line.startswith("- ") for line in rules)
    assert _sections()["## 하지 않는 것"]


def test_no_date_no_event_title_no_real_master_name(repo_root):
    text = _text()
    assert not re.search(r"(19|20)\d\d", text)
    assert "YYYY-MM" not in text
    assert not re.search(r"\d{4}-\d{2}", text)
    lowered = text.lower()
    assert [name for name in DENY_LIST if name in lowered] == []
    events = repo_root / "events"
    if events.is_dir():
        for path in events.iterdir():
            assert path.stem not in text


def test_style_master_is_null_while_no_masters_folder_exists(repo_root):
    assert load_persona("patient-value").style_master is None
    assert not (repo_root / "masters").exists()


def test_persona_name_is_not_a_real_name_and_is_short():
    name = load_persona("patient-value").display_name
    assert 1 <= len(name) <= 50


def test_loader_reads_only_inside_the_given_directory(tmp_path):
    other = Path(tmp_path) / "patient-value.md"
    other.write_bytes(SHIPPED.read_bytes())
    assert load_persona("patient-value", tmp_path).sha256 == load_persona("patient-value").sha256


@pytest.mark.parametrize("shares", ["[40, 30, 30, 10]", "[10, 30, 30, 31]", "[100, 1, 0, 0]"])
def test_formation_sum_above_100_is_refused(shares):
    with pytest.raises(PersonaError):
        parse_persona(_swap("default_formation: [10, 30, 30, 30]", f"default_formation: {shares}"))
