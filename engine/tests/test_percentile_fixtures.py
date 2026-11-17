"""The `/v1/percentiles` fixtures, read with the engine's own pydantic models.

The Spring client tests of MVP-18 PR B read the same files, so a field that only one side knows
breaks one of the two. The shared manifest of the other endpoints is not touched.
"""

import json

import pytest
from conftest import ENGINE_DIR
from pydantic import BaseModel, ValidationError

from antlab_engine.api.percentile_models import PercentileRequest, PercentileResult

FIXTURE_DIR = ENGINE_DIR.parent / "docs" / "api" / "fixtures" / "engine"
MANIFEST = json.loads((FIXTURE_DIR / "manifest.json").read_text(encoding="utf-8"))["files"]
MODELS: dict[str, type[BaseModel]] = {"request": PercentileRequest, "response": PercentileResult}
VALID = [e for e in MANIFEST if e["valid"]]
INVALID = [e for e in MANIFEST if not e["valid"]]


def load(entry: dict) -> object:
    return json.loads((FIXTURE_DIR / entry["file"]).read_text(encoding="utf-8"))


def test_the_manifest_lists_every_fixture_file_and_every_listed_file_exists():
    on_disk = {p.name for p in FIXTURE_DIR.glob("*.json")} - {"manifest.json"}
    assert on_disk == {e["file"] for e in MANIFEST}
    assert len(on_disk) == len(MANIFEST)


def test_both_verdicts_and_both_kinds_occur():
    assert {e["kind"] for e in VALID} == {"request", "response"}
    assert {e["kind"] for e in INVALID} == {"request", "response"}


@pytest.mark.parametrize("entry", VALID, ids=lambda e: e["file"])
def test_a_valid_fixture_parses_with_the_engine_model(entry):
    MODELS[entry["kind"]].model_validate(load(entry))


@pytest.mark.parametrize("entry", INVALID, ids=lambda e: e["file"])
def test_an_invalid_fixture_is_rejected_by_the_engine_model(entry):
    with pytest.raises(ValidationError):
        MODELS[entry["kind"]].model_validate(load(entry))


def test_a_valid_request_fixture_survives_a_round_trip_with_decimal_strings():
    entry = next(e for e in VALID if e["file"] == "percentile-request.json")
    parsed = PercentileRequest.model_validate(load(entry))
    assert parsed.observed.endMultiple == "1.234567890123"
    assert parsed.model_dump(mode="json", exclude_defaults=False) == load(entry)


def test_the_fixtures_carry_no_secret_or_path_value():
    text = "".join(p.read_text(encoding="utf-8") for p in FIXTURE_DIR.glob("*.json"))
    assert "token" not in text.lower()


RESPONSE_KEYS = {
    "projectionVersion",
    "engineVersion",
    "n",
    "horizonTurns",
    "historyDigest",
    "endMultiple",
    "sharpeDiff",
}


@pytest.mark.parametrize(
    "entry", [e for e in VALID if e["kind"] == "response"], ids=lambda e: e["file"]
)
def test_a_valid_response_fixture_has_exactly_the_documented_keys(entry):
    body = load(entry)
    assert set(body) == RESPONSE_KEYS
    assert set(body["endMultiple"]) == set(body["sharpeDiff"]) == {"below", "equal"}
