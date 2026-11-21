import csv
import re

import pytest

from antlab_ai.memory.filter import (
    REPO_ROOT,
    TAGS_PATH,
    TERMS_PATH,
    TITLE_MIN,
    compact,
    scan_text,
)


def test_terms_file_is_a_marked_owner_editable_draft():
    lines = TERMS_PATH.read_text(encoding="utf-8").splitlines()
    assert lines[0].startswith("#")
    assert "OWNER-EDITABLE" in "\n".join(lines[:5])
    entries = [line for line in lines if line.strip() and not line.startswith("#")]
    assert 50 <= len(entries) <= 130
    assert len({compact(e) for e in entries}) == len(entries)


def test_terms_hold_no_numbers():
    for line in TERMS_PATH.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            assert not re.search(r"\d", line), line


def test_every_tags_title_is_caught(shipped_terms):
    with TAGS_PATH.open(encoding="utf-8", newline="") as handle:
        titles = [
            row["title"]
            for row in csv.DictReader(handle)
            if len(compact(row["title"])) >= TITLE_MIN
        ]
    assert titles
    assert len(shipped_terms.titles) == len({compact(title) for title in titles})
    for title in titles:
        assert any(h.code == "EVENT_TERM" for h in scan_text(title, "/t", shipped_terms)), title


def _persona_files():
    return sorted((REPO_ROOT / "personas").glob("*.md"))


@pytest.mark.parametrize("path", _persona_files(), ids=lambda p: p.stem)
def test_shipped_persona_bodies_pass(path, shipped_terms):
    body = path.read_text(encoding="utf-8").split("\n---\n", 1)[1]
    for number, line in enumerate(body.splitlines()):
        assert scan_text(line, f"/{number}", shipped_terms) == [], (path.name, number)


def test_persona_files_exist():
    assert len(_persona_files()) >= 4
