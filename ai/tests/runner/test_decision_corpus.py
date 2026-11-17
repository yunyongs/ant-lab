"""D1, D2: the runner verdict equals the manifest verdict of the Java corpus for every file."""

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from antlab_ai.runner import decision
from antlab_ai.runner.decision import Parsed, Rejected, parse_reply


def _entries():
    manifest = json.loads(
        (
            Path(__file__).resolve().parents[3]
            / "src/test/resources/agent/turn-decision/manifest.json"
        ).read_text(encoding="utf-8")
    )
    return manifest["files"]


ENTRIES = _entries()


@pytest.mark.parametrize("entry", ENTRIES, ids=[e["file"] for e in ENTRIES])
def test_runner_verdict_equals_manifest(entry, corpus):
    result = parse_reply((corpus / entry["file"]).read_text(encoding="utf-8"))
    if entry["file"].startswith("envelope/"):
        # Shape-valid, but the API answers 400 (apiStatus): the runner must refuse to send it.
        assert entry["valid"] is True and entry["apiStatus"] == 400
        assert isinstance(result, Rejected)
        assert any(keyword == "nul" for _, keyword in result.violations)
    elif entry["valid"]:
        assert isinstance(result, Parsed), result
    else:
        assert isinstance(result, Rejected)


def test_every_corpus_file_is_in_the_manifest_and_back(corpus, manifest):
    on_disk = {
        p.relative_to(corpus).as_posix()
        for p in corpus.rglob("*.json")
        if p.name != "manifest.json"
    }
    listed = {e["file"] for e in manifest["files"]}
    assert on_disk - listed == set(), "corpus file missing from the manifest"
    assert listed - on_disk == set(), "manifest entry without a file"
    assert len(listed) == len(manifest["files"]), "manifest lists a file twice"


def test_corpus_has_the_cases_the_plan_names(manifest):
    flagged = {e["file"] for e in manifest["files"] if e.get("schemaFileMayDiffer")}
    assert flagged == {"bad/quantity_integral_float.json", "bad/quantity_exponent.json"}


@pytest.mark.parametrize("name", ["bad/quantity_integral_float.json", "bad/quantity_exponent.json"])
def test_plain_json_loads_would_have_accepted_these(name, corpus):
    """The strict parse layer is what refuses 3.0 and 1e2: a validator over float-parsed JSON accepts them."""
    validator = Draft202012Validator(json.loads(decision.SCHEMA_PATH.read_text(encoding="utf-8")))
    plain = json.loads((corpus / name).read_text(encoding="utf-8"))
    assert list(validator.iter_errors(plain)) == []
    assert isinstance(parse_reply((corpus / name).read_text(encoding="utf-8")), Rejected)


def test_trailing_newline_in_asset_is_refused_by_the_shared_pattern(corpus):
    result = parse_reply((corpus / "bad/asset_trailing_newline.json").read_text(encoding="utf-8"))
    assert result == Rejected((("/orders/0/asset", "pattern"),))


def test_validator_reads_the_one_shared_schema_file(repo_root):
    assert decision.SCHEMA_PATH == repo_root / "docs" / "api" / "turn-decision-1.schema.json"
    assert decision.SCHEMA_PATH.is_file()


def test_no_second_turn_decision_schema_under_ai(repo_root):
    ai = repo_root / "ai"
    found = [
        p.relative_to(ai).as_posix()
        for p in ai.rglob("*turn-decision*")
        if ".venv" not in p.parts and p.suffix in {".json", ".yaml", ".yml"}
    ]
    assert found == []
