"""D7, D8: persona loader and the shipped persona."""

import hashlib
import json
import re
from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from antlab_ai.runner.persona import (
    LOCK_NAME,
    MASTER_DENY_LIST,
    PERSONAS_DIR,
    PersonaError,
    load_persona,
    parse_persona,
    read_lock,
    verify_lock,
)

SHIPPED = PERSONAS_DIR / "patient-value.md"
DENY_LIST = MASTER_DENY_LIST
KEYS = sorted(path.stem for path in PERSONAS_DIR.glob("*.md"))


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


def test_a_style_master_must_exist_in_the_masters_folder(tmp_path):
    data = _swap("style_master: null", "style_master: sage")
    with pytest.raises(PersonaError):
        parse_persona(data, masters_dir=tmp_path / "missing")
    with pytest.raises(PersonaError):
        parse_persona(data, masters_dir=tmp_path)
    (tmp_path / "sage.md").write_text("x", encoding="utf-8")
    assert parse_persona(data, masters_dir=tmp_path).style_master == "sage"


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


@pytest.mark.parametrize("key", KEYS)
def test_shipped_personas_load_and_match_the_policy(key, repo_root):
    persona = load_persona(key)
    assert persona.key == key
    assert persona.style_master is None
    assert persona.version >= 1
    text = (PERSONAS_DIR / f"{key}.md").read_text(encoding="utf-8")
    assert not re.search(r"(?<!\d)(19|20)\d\d(?!\d)", text)
    assert [name for name in DENY_LIST if name in text.lower()] == []
    assert not (repo_root / "masters").exists()


def test_the_expected_personas_ship():
    assert KEYS == ["headline-chaser", "patient-value", "steady-allocator", "trend-rider"]


def test_loader_reads_only_inside_the_given_directory(tmp_path):
    other = Path(tmp_path) / "patient-value.md"
    other.write_bytes(SHIPPED.read_bytes())
    assert load_persona("patient-value", tmp_path).sha256 == load_persona("patient-value").sha256


@pytest.mark.parametrize("shares", ["[40, 30, 30, 10]", "[10, 30, 30, 31]", "[100, 1, 0, 0]"])
def test_formation_sum_above_100_is_refused(shares):
    with pytest.raises(PersonaError):
        parse_persona(_swap("default_formation: [10, 30, 30, 30]", f"default_formation: {shares}"))


# ---- audit round 1 (C9) -----------------------------------------------------------------------------------


def test_unhashable_front_matter_key_is_a_persona_error():
    with pytest.raises(PersonaError):
        parse_persona(_swap("version: 1\n", "version: 1\n[a, b]: 1\n"))


@pytest.mark.parametrize("name", ["a\nb", "a\tb", "a\x07b", " 가", "가 "])
def test_display_name_with_control_or_edge_space_is_refused(name):
    with pytest.raises(PersonaError):
        parse_persona(
            _swap("display_name: 인내하는 가치 매니저", f"display_name: {json.dumps(name)}")
        )


@pytest.mark.parametrize("text", ["2020-03", "2020/03", "202003", "YYYY-MM", "1999.12"])
def test_body_with_a_year_month_is_refused_by_the_loader(text):
    with pytest.raises(PersonaError):
        parse_persona(_swap("## 철학\n", f"## 철학\n{text} 에는 사지 않는다. "))


def test_body_numbers_that_are_not_dates_pass():
    assert parse_persona(_swap("## 철학\n", "## 철학\n여섯 턴, 20% 한도, 12345 종목, 1234567 원. "))


def test_carriage_return_in_the_body_is_refused():
    with pytest.raises(PersonaError):
        parse_persona(_swap("## 철학\n", "## 철학\r\n"))


# ---- D8 content policy ------------------------------------------------------------------------------------

GOOD_RULES = "\n".join(f"- 규칙 {i}" for i in "가나다라마")


def _persona(philosophy="철학 문장.", rules=GOOD_RULES, avoid="- 하지 않는다.", head="") -> bytes:
    front = (
        "---\nkey: sample\nversion: 1\ndisplay_name: 시험\nstyle_master: null\n"
        "default_risk_limit: -0.20\ndefault_formation: [10, 30, 30, 30]\n---\n"
    )
    body = f"{head}## 철학\n{philosophy}\n\n## 행동 규칙\n{rules}\n\n## 하지 않는 것\n{avoid}\n"
    return (front + body).encode("utf-8")


def test_the_minimal_valid_persona_passes():
    assert parse_persona(_persona()).key == "sample"


@pytest.mark.parametrize(
    "data",
    [
        _persona(philosophy="가" * 301),
        _persona(rules="\n".join(f"- 규칙 {i}" for i in "가나다라")),
        _persona(rules="\n".join(f"- 규칙 {i}" for i in "가나다라마바사아자차카")),
        _persona(rules=GOOD_RULES + "\n일반 문장"),
        _persona(avoid=""),
        _persona(avoid="\n".join(f"- 금지 {i}" for i in "가나다라마바사아자차카")),
        _persona(avoid="그냥 문장"),
        _persona(head="앞에 붙은 문장\n"),
        _persona(philosophy="2030년에는 좋았다."),
        _persona(philosophy="1999 이후."),
        _persona(rules="\n".join("- " + "가" * 900 for _ in range(5))),
    ],
    ids=[
        "philosophy-long", "rules-4", "rules-11", "rules-prose", "avoid-empty", "avoid-11",
        "avoid-prose", "text-before-section", "year", "year-2", "body-too-big",
    ],
)  # fmt: skip
def test_content_defects_are_refused(data):
    with pytest.raises(PersonaError):
        parse_persona(data)


def test_a_repeated_or_missing_or_reordered_section_is_refused():
    good = _persona().decode("utf-8")
    for bad in (
        good + "\n## 철학\n또\n",
        good.replace("## 하지 않는 것\n- 하지 않는다.\n", ""),
        good.replace("## 철학", "## 행동 규칙 ", 1),
        good + "\n## 덤\n- 하나\n",
    ):
        with pytest.raises(PersonaError):
            parse_persona(bad.encode("utf-8"))


def test_empty_philosophy_is_refused():
    with pytest.raises(PersonaError):
        parse_persona(_persona(philosophy=""))


@pytest.mark.parametrize("name", ["Buffett", "워런 버핏", "SOROS"])
def test_master_names_in_body_or_display_name_are_refused(name):
    with pytest.raises(PersonaError):
        parse_persona(_persona(philosophy=f"{name} 처럼."))
    data = _persona().decode("utf-8").replace("display_name: 시험", f"display_name: {name}")
    with pytest.raises(PersonaError):
        parse_persona(data.encode("utf-8"))


def test_an_event_title_in_the_body_is_refused(tmp_path):
    (tmp_path / "tags.csv").write_text(
        "event_key,title\nev1,시험용 사건 제목\nev2,짧\n", encoding="utf-8"
    )
    with pytest.raises(PersonaError):
        parse_persona(_persona(philosophy="시험용 사건 제목 때문에."), events_dir=tmp_path)
    assert parse_persona(_persona(philosophy="짧 은 글."), events_dir=tmp_path)
    assert parse_persona(_persona(), events_dir=tmp_path / "none")


def test_shipped_bodies_hold_no_real_event_title():
    for key in KEYS:
        assert load_persona(key)  # uses the repo events folder


# ---- personas.lock ----------------------------------------------------------------------------------------


def _lock_dir(tmp_path, rows=None, data=None):
    data = data or _persona()
    (tmp_path / "sample.md").write_bytes(data)
    sha = hashlib.sha256(data).hexdigest()
    rows = [f"sample 1 {sha}"] if rows is None else rows
    (tmp_path / LOCK_NAME).write_text("".join(f"{r}\n" for r in rows), encoding="utf-8")
    return tmp_path, sha


def test_the_shipped_lock_matches_every_file_and_is_sorted():
    verify_lock()
    rows = read_lock()
    assert [r.key for r in rows if r.version == 1] == KEYS


def test_a_valid_lock_passes(tmp_path):
    directory, _ = _lock_dir(tmp_path)
    verify_lock(directory)


def test_a_changed_file_without_a_version_bump_fails(tmp_path):
    directory, _ = _lock_dir(tmp_path)
    (directory / "sample.md").write_bytes(_persona(philosophy="다른 철학."))
    with pytest.raises(PersonaError):
        verify_lock(directory)


def test_a_bumped_version_needs_a_new_row_and_keeps_the_old_one(tmp_path):
    directory, sha = _lock_dir(tmp_path)
    data = _persona(philosophy="다른 철학.").decode("utf-8").replace("version: 1", "version: 2")
    (directory / "sample.md").write_bytes(data.encode("utf-8"))
    with pytest.raises(PersonaError):
        verify_lock(directory)
    new_sha = hashlib.sha256(data.encode("utf-8")).hexdigest()
    (directory / LOCK_NAME).write_text(f"sample 1 {sha}\nsample 2 {new_sha}\n", encoding="utf-8")
    verify_lock(directory)
    (directory / LOCK_NAME).write_text(f"sample 2 {new_sha}\n", encoding="utf-8")
    with pytest.raises(PersonaError):
        verify_lock(directory)


def test_lock_defects_are_refused(tmp_path):
    directory, sha = _lock_dir(tmp_path)
    other = "b" * 64
    for rows in (
        [f"sample 1 {sha}", f"sample 1 {sha}"],
        [f"sample 1 {sha}", f"aaa 1 {other}"],
        [f"sample 1 {sha}", f"zzz 1 {other}"],
        [f"zzz 1 {other}", f"sample 1 {sha}"],
        [f"sample 1 {sha.upper()}"],
        [f"sample 1 {sha[:-1]}"],
        [f"sample 1  {sha}"],
        [f"sample 3 {sha}"],
        [],
    ):
        (directory / LOCK_NAME).write_text("".join(f"{r}\n" for r in rows), encoding="utf-8")
        with pytest.raises(PersonaError):
            verify_lock(directory)


def test_lock_without_final_newline_or_with_crlf_is_refused(tmp_path):
    directory, sha = _lock_dir(tmp_path)
    (directory / LOCK_NAME).write_text(f"sample 1 {sha}", encoding="utf-8")
    with pytest.raises(PersonaError):
        verify_lock(directory)
    (directory / LOCK_NAME).write_bytes(f"sample 1 {sha}\r\n".encode())
    with pytest.raises(PersonaError):
        verify_lock(directory)


def test_a_file_missing_from_the_lock_is_refused(tmp_path):
    directory, _ = _lock_dir(tmp_path)
    (directory / "extra.md").write_bytes(_persona().replace(b"key: sample", b"key: extra"))
    with pytest.raises(PersonaError):
        verify_lock(directory)


def test_a_missing_lock_file_is_refused(tmp_path):
    (tmp_path / "sample.md").write_bytes(_persona())
    with pytest.raises(PersonaError):
        verify_lock(tmp_path)


def test_unsorted_lock_rows_are_refused_even_when_every_row_is_right(tmp_path):
    directory, sha = _lock_dir(tmp_path)
    data = _persona().replace(b"key: sample", b"key: other")
    (directory / "other.md").write_bytes(data)
    other = hashlib.sha256(data).hexdigest()
    (directory / LOCK_NAME).write_text(f"sample 1 {sha}\nother 1 {other}\n", encoding="utf-8")
    with pytest.raises(PersonaError):
        verify_lock(directory)
    (directory / LOCK_NAME).write_text(f"other 1 {other}\nsample 1 {sha}\n", encoding="utf-8")
    verify_lock(directory)


# ---- review round 1 ---------------------------------------------------------------------------------------


@pytest.mark.parametrize("name", ["피셔", "마크스", "그린블랫", "Fisher", "marks"])
def test_more_master_names_are_refused(name):
    with pytest.raises(PersonaError):
        parse_persona(
            _persona(philosophy=f"{name}처럼 한다." if name.isascii() is False else f"like {name}.")
        )


@pytest.mark.parametrize("word", ["benchmarks", "remarks", "fishery"])
def test_latin_deny_names_match_whole_words_only(word):
    assert parse_persona(_persona(philosophy=f"{word} 를 본다."))


def test_display_name_with_a_year_is_refused():
    data = _persona().decode("utf-8").replace("display_name: 시험", "display_name: 2008 매니저")
    with pytest.raises(PersonaError):
        parse_persona(data.encode("utf-8"))


def test_a_short_tags_row_is_not_a_crash(tmp_path):
    (tmp_path / "tags.csv").write_text(
        "event_key,month_start,title\nev1,2007-08-01\n", encoding="utf-8"
    )
    assert parse_persona(_persona(), events_dir=tmp_path)


@pytest.mark.parametrize("key", KEYS)
def test_persona_bodies_carry_no_meta_note_about_themselves(key):
    body = load_persona(key).body
    assert "초안" not in body
    assert "비교용" not in body
