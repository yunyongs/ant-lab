import json

import pytest
from jsonschema import Draft202012Validator
from memory_helpers import CORPUS, REPO

SCHEMAS = {
    "season-reflection/1": REPO / "docs/api/season-reflection-1.schema.json",
    "operating-note/1": REPO / "docs/api/operating-note-1.schema.json",
}


def validator(kind):
    schema = json.loads(SCHEMAS[kind].read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    assert schema["title"] == kind
    return Draft202012Validator(schema)


def test_every_corpus_document_has_the_schema_shape(manifest):
    for entry in manifest["documents"]:
        doc = json.loads((CORPUS / entry["file"]).read_text(encoding="utf-8"))
        assert list(validator(entry["kind"]).iter_errors(doc)) == [], entry["file"]


GOOD = {
    "schema": "season-reflection/1",
    "good": ["a", "b", "c"],
    "mistakes": [{"type": "OTHER", "text": "x"}],
    "ruleCandidates": [],
}


@pytest.mark.parametrize(
    "change",
    [
        lambda d: d.update(schema="season-reflection/2"),
        lambda d: d.update(good=["a", "b"]),
        lambda d: d.update(extra=1),
        lambda d: d.update(good=["a" * 121, "b", "c"]),
        lambda d: d.update(mistakes=[{"type": "BOGUS", "text": "x"}]),
        lambda d: d.update(mistakes=[{"type": "OTHER", "text": "x"}] * 4),
        lambda d: d.update(ruleCandidates=["a"] * 4),
        lambda d: d.pop("ruleCandidates"),
    ],
)
def test_reflection_shape_violations(change):
    doc = json.loads(json.dumps(GOOD))
    change(doc)
    assert list(validator("season-reflection/1").iter_errors(doc))


def test_reflection_shape_accepts_the_minimal_document():
    assert list(validator("season-reflection/1").iter_errors(GOOD)) == []


@pytest.mark.parametrize(
    "doc",
    [
        {"schema": "operating-note/1", "rules": []},
        {"schema": "operating-note/1", "rules": ["a"] * 16},
        {"schema": "operating-note/1", "rules": ["a" * 151]},
        {"schema": "operating-note/2", "rules": ["a"]},
        {"schema": "operating-note/1", "rules": ["a"], "x": 1},
    ],
)
def test_note_shape_violations(doc):
    assert list(validator("operating-note/1").iter_errors(doc))


def test_note_shape_accepts_a_full_note():
    doc = {"schema": "operating-note/1", "rules": ["a" * 150] * 15}
    assert list(validator("operating-note/1").iter_errors(doc)) == []
