import copy

import pytest
import yaml
from conftest import CONTRACT_PATH
from openapi_normalizer import normalize

from antlab_engine.openapi import build_openapi


@pytest.fixture(scope="module")
def contract():
    return yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))


@pytest.fixture
def generated(app):
    return build_openapi(app)


def test_contract_equals_generated_spec(contract, generated):
    assert normalize(contract) == normalize(generated)


def _drop_info_security_responses(spec):
    del spec["paths"]["/v1/info"]["get"]["responses"]["401"]


def _drop_global_security(spec):
    del spec["security"]


def _make_healthz_secured(spec):
    del spec["paths"]["/healthz"]["get"]["security"]


def _version_becomes_integer(spec):
    spec["components"]["schemas"]["EngineInfo"]["properties"]["version"] = {"type": "integer"}


def _problem_loses_code(spec):
    spec["components"]["schemas"]["Problem"]["required"].remove("code")


def _extra_path(spec):
    spec["paths"]["/v1/extra"] = {"get": {"operationId": "extra", "responses": {}}}


def _token_header_renamed(spec):
    spec["components"]["securitySchemes"]["EngineToken"]["name"] = "X-Other"


def _error_media_type_changes(spec):
    response = spec["components"]["responses"]["Unauthenticated"]["content"]
    response["application/json"] = response.pop("application/problem+json")


def _error_code_dropped(spec):
    spec["components"]["schemas"]["ErrorCode"]["enum"].remove("NOT_FOUND")


def _paths_loses_a_status(spec):
    del spec["paths"]["/v1/paths"]["post"]["responses"]["503"]


def _paths_seed_becomes_integer(spec):
    spec["components"]["schemas"]["PathRequest"]["properties"]["seed"] = {"type": "integer"}


def _paths_length_gains_a_value(spec):
    spec["components"]["schemas"]["PathRequest"]["properties"]["length"]["enum"].append(36)


def _paths_request_allows_extra_fields(spec):
    del spec["components"]["schemas"]["PathRequest"]["additionalProperties"]


def _paths_digest_becomes_optional(spec):
    spec["components"]["schemas"]["PathResult"]["required"].remove("historyDigest")


def _paths_recent_months_may_repeat(spec):
    del spec["components"]["schemas"]["PathRequest"]["properties"]["recentMonths"]["uniqueItems"]


def _paths_overlap_pattern_widens(spec):
    spec["components"]["schemas"]["PathRequest"]["properties"]["maxOverlap"]["pattern"] = ".*"


def _error_code_domain_dropped(spec):
    spec["components"]["schemas"]["ErrorCode"]["enum"].remove("HISTORY_UNAVAILABLE")


def _paths_not_secured(spec):
    spec["paths"]["/v1/paths"]["post"]["security"] = []


MUTATIONS = [
    _paths_loses_a_status,
    _paths_seed_becomes_integer,
    _paths_length_gains_a_value,
    _paths_request_allows_extra_fields,
    _paths_digest_becomes_optional,
    _paths_recent_months_may_repeat,
    _paths_overlap_pattern_widens,
    _error_code_domain_dropped,
    _paths_not_secured,
    _drop_info_security_responses,
    _drop_global_security,
    _make_healthz_secured,
    _version_becomes_integer,
    _problem_loses_code,
    _extra_path,
    _token_header_renamed,
    _error_media_type_changes,
    _error_code_dropped,
]


@pytest.mark.parametrize("mutate", MUTATIONS, ids=lambda f: f.__name__.strip("_"))
def test_mutated_contract_is_detected(contract, generated, mutate):
    mutated = copy.deepcopy(contract)
    mutate(mutated)
    assert normalize(mutated) != normalize(generated)


def test_normalizer_ignores_documentation_only_changes(contract, generated):
    changed = copy.deepcopy(contract)
    changed["info"]["title"] = "other"
    changed["paths"]["/healthz"]["get"]["summary"] = "liveness"
    changed["components"]["schemas"]["Health"]["description"] = "text"
    assert normalize(changed) == normalize(generated)


@pytest.mark.parametrize("which", ["contract", "generated"])
def test_invariants(which, contract, generated):
    spec = contract if which == "contract" else generated
    assert spec["openapi"].startswith("3.1")
    assert spec["security"] == [{"EngineToken": []}]
    assert spec["components"]["securitySchemes"]["EngineToken"] == {
        "type": "apiKey",
        "in": "header",
        "name": "X-Antlab-Engine-Token",
    }
    assert "HTTPValidationError" not in spec["components"]["schemas"]
    normalized = normalize(spec)["paths"]
    assert set(normalized) == {"GET /healthz", "GET /v1/info", "POST /v1/paths"}
    for key, operation in normalized.items():
        # 422 is a domain status only (PATH_TOO_LONG), never FastAPI's validation response
        assert ("422" in operation["responses"]) == (key == "POST /v1/paths")
        assert operation["responses"]["500"]["content"] == {
            "application/problem+json": {"$ref": "Problem"}
        }
        if key == "GET /healthz":
            assert operation["security"] == []
        else:
            assert operation["security"] == [{"EngineToken": []}]
            assert operation["responses"]["401"]["content"] == {
                "application/problem+json": {"$ref": "Problem"}
            }
