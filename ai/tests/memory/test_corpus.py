"""The shared corpus is the contract with the Java filter: exact codes, exact ordered hits."""

import json

import pytest
from memory_helpers import CORPUS

from antlab_ai.memory.filter import (
    Hit,
    check_note,
    check_reflection,
    scan_text,
)


def _cases():
    return json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))["cases"]


def _documents():
    return json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))["documents"]


@pytest.mark.parametrize("case", _cases(), ids=[c["id"] for c in _cases()])
def test_case_codes_are_exact(case, terms):
    found = {hit.code for hit in scan_text(case["text"], "/c", terms)}
    assert found == set(case["codes"])


@pytest.mark.parametrize("entry", _documents(), ids=[d["file"] for d in _documents()])
def test_document_hits_are_exact(entry, terms):
    doc = json.loads((CORPUS / entry["file"]).read_text(encoding="utf-8"))
    check = check_reflection if entry["kind"] == "season-reflection/1" else check_note
    verdict = check(doc, terms)
    assert verdict.hits == tuple(Hit(h["pointer"], h["code"]) for h in entry["hits"])
    assert verdict.oversize is entry["oversize"]


def test_case_ids_are_unique_and_documents_are_listed(manifest):
    ids = [c["id"] for c in manifest["cases"]]
    assert len(ids) == len(set(ids))
    on_disk = {p.relative_to(CORPUS).as_posix() for p in (CORPUS / "documents").glob("*.json")}
    assert on_disk == {d["file"] for d in manifest["documents"]}


def test_every_code_has_a_positive_case_and_there_are_negatives(manifest):
    seen = {code for case in manifest["cases"] for code in case["codes"]}
    assert seen == {"YEAR", "YEAR_MONTH", "EVENT_TERM", "FINGERPRINT", "TURN_RETURN", "PRICE"}
    assert sum(1 for case in manifest["cases"] if not case["codes"]) >= 8
