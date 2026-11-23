"""The fixtures the Spring client tests use, read with the engine's own pydantic models.

The Java side (`EngineContractTest`) reads the same manifest and checks the same files against
`docs/api/engine.yaml` and its records, so a field that only one side knows breaks one of the two.
"""

import json

import pytest
from conftest import ENGINE_DIR
from pydantic import BaseModel, ValidationError

from antlab_engine.api.info import EngineInfo
from antlab_engine.api.paths import PathRequest, PathResult
from antlab_engine.api.projection_models import ProjectionRequest, ProjectionResult
from antlab_engine.api.regime_models import RegimeRequest, RegimeResult
from antlab_engine.errors import ErrorCode, Problem

# The status per code as docs/api/engine.yaml documents it. The engine's own mapping is checked
# in test_contract.py and test_error_codes.py, so this test needs no private engine names.
STATUS_OF_CODE = {
    ErrorCode.INVALID_ARGUMENT: 400,
    ErrorCode.UNAUTHENTICATED: 401,
    ErrorCode.NOT_FOUND: 404,
    ErrorCode.POOL_NOT_FOUND: 404,
    ErrorCode.METHOD_NOT_ALLOWED: 405,
    ErrorCode.PATH_TOO_LONG: 422,
    ErrorCode.INTERNAL_ERROR: 500,
    ErrorCode.HISTORY_UNAVAILABLE: 503,
    ErrorCode.ENGINE_BUSY: 503,
    ErrorCode.REGIME_DATA_INVALID: 422,
    ErrorCode.REGIME_LABELS_MISSING: 422,
    ErrorCode.REFERENCE_MISMATCH: 409,
    ErrorCode.FEED_INPUT: 422,
    ErrorCode.NO_REFERENCE: 422,
}

CONTRACT_DIR = ENGINE_DIR.parent / "src" / "test" / "resources" / "engine" / "contract"
MANIFEST = json.loads((CONTRACT_DIR / "manifest.json").read_text(encoding="utf-8"))["files"]

REQUEST_MODELS: dict[str, type[BaseModel]] = {
    "createPath": PathRequest,
    "createProjection": ProjectionRequest,
    "evaluateRegime": RegimeRequest,
}
RESULT_MODELS: dict[str, type[BaseModel]] = {
    "createPath": PathResult,
    "createProjection": ProjectionResult,
    "evaluateRegime": RegimeResult,
    "getEngineInfo": EngineInfo,
}


def model_of(entry: dict) -> type[BaseModel]:
    if entry["kind"] == "request":
        return REQUEST_MODELS[entry["operation"]]
    if entry["status"] == 200:
        return RESULT_MODELS[entry["operation"]]
    return Problem


def load(entry: dict) -> object:
    return json.loads((CONTRACT_DIR / entry["file"]).read_text(encoding="utf-8"))


def label(entry: dict) -> str:
    return f"{entry['file']}:{entry.get('operation')}:{entry.get('status', '-')}"


VALID = [e for e in MANIFEST if e["valid"]]
INVALID = [e for e in MANIFEST if not e["valid"]]


def test_the_manifest_lists_every_fixture_file_and_every_listed_file_exists():
    on_disk = {p.name for p in CONTRACT_DIR.glob("*.json")} - {"manifest.json"}
    listed = {e["file"] for e in MANIFEST}
    assert on_disk == listed


@pytest.mark.parametrize("entry", VALID, ids=label)
def test_a_valid_fixture_parses_with_the_engine_model(entry):
    model_of(entry).model_validate(load(entry))


@pytest.mark.parametrize("entry", INVALID, ids=label)
def test_an_invalid_fixture_is_rejected_by_the_engine_model(entry):
    with pytest.raises(ValidationError):
        model_of(entry).model_validate(load(entry))


def test_the_engine_still_rejects_an_unknown_request_field_that_the_client_never_sends():
    entry = next(e for e in MANIFEST if e["file"] == "paths-request-p0.json")
    request = {**load(entry), "extra": 1}
    with pytest.raises(ValidationError):
        PathRequest.model_validate(request)


def test_problem_fixtures_carry_the_status_the_engine_gives_that_code():
    problems = [e for e in VALID if e["kind"] == "response" and e["status"] != 200]
    assert problems
    for entry in problems:
        body = load(entry)
        assert body["status"] == entry["status"], label(entry)
        assert STATUS_OF_CODE[ErrorCode(body["code"])] == entry["status"], label(entry)


def test_the_status_table_names_every_engine_error_code():
    assert set(STATUS_OF_CODE) == set(ErrorCode)


def test_every_engine_error_code_has_a_problem_fixture():
    seen = {load(e)["code"] for e in VALID if e["kind"] == "response" and e["status"] != 200}
    assert seen == {code.value for code in ErrorCode}
