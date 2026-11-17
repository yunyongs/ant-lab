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


def _proj_loses_a_status(spec):
    del spec["paths"]["/v1/projections"]["post"]["responses"]["422"]


def _proj_seed_becomes_integer(spec):
    spec["components"]["schemas"]["ProjectionRequest"]["properties"]["seasonSeed"] = {
        "type": "integer"
    }


def _proj_horizon_gains_a_value(spec):
    spec["components"]["schemas"]["ProjectionRequest"]["properties"]["horizonTurns"]["enum"].append(
        24
    )


def _proj_n_bound_widens(spec):
    spec["components"]["schemas"]["ProjectionRequest"]["properties"]["n"]["maximum"] = 50000


def _proj_request_allows_extra_fields(spec):
    del spec["components"]["schemas"]["ProjectionRequest"]["additionalProperties"]


def _proj_weight_becomes_number(spec):
    spec["components"]["schemas"]["Holding"]["properties"]["weight"] = {"type": "number"}


def _proj_risk_limit_pattern_widens(spec):
    spec["components"]["schemas"]["ProjectionRequest"]["properties"]["riskLimit"]["pattern"] = ".*"


def _proj_portfolio_limit_grows(spec):
    spec["components"]["schemas"]["ProjectionRequest"]["properties"]["portfolios"]["maxItems"] = 60


def _proj_digest_becomes_optional(spec):
    spec["components"]["schemas"]["ProjectionResult"]["required"].remove("historyDigest")


def _proj_band_gains_won(spec):
    spec["components"]["schemas"]["ProjectionBand"]["properties"]["wonValue"] = {"type": "string"}


def _proj_ratio_becomes_number(spec):
    spec["components"]["schemas"]["EndQuantiles"]["properties"]["p50"] = {"type": "number"}


def _proj_not_secured(spec):
    spec["paths"]["/v1/projections"]["post"]["security"] = []


def _pct_loses_a_status(spec):
    del spec["paths"]["/v1/percentiles"]["post"]["responses"]["422"]


def _pct_horizon_loses_a_value(spec):
    spec["components"]["schemas"]["PercentileRequest"]["properties"]["horizonTurns"]["enum"].remove(
        12
    )


def _pct_seed_becomes_integer(spec):
    spec["components"]["schemas"]["PercentileRequest"]["properties"]["seasonSeed"] = {
        "type": "integer"
    }


def _pct_request_allows_extra_fields(spec):
    del spec["components"]["schemas"]["PercentileRequest"]["additionalProperties"]


def _pct_observed_becomes_number(spec):
    spec["components"]["schemas"]["PercentileObserved"]["properties"]["endMultiple"] = {
        "type": "number"
    }


def _pct_observed_pattern_widens(spec):
    spec["components"]["schemas"]["PercentileObserved"]["properties"]["sharpeDiff"]["pattern"] = (
        ".*"
    )


def _pct_count_becomes_number(spec):
    spec["components"]["schemas"]["RankCounts"]["properties"]["below"] = {"type": "number"}


def _pct_count_may_be_negative(spec):
    del spec["components"]["schemas"]["RankCounts"]["properties"]["equal"]["minimum"]


def _pct_result_gains_a_path_value(spec):
    spec["components"]["schemas"]["PercentileResult"]["properties"]["values"] = {"type": "array"}


def _pct_digest_becomes_optional(spec):
    spec["components"]["schemas"]["PercentileResult"]["required"].remove("historyDigest")


def _pct_not_secured(spec):
    spec["paths"]["/v1/percentiles"]["post"]["security"] = []


def _pct_n_bound_widens(spec):
    spec["components"]["schemas"]["PercentileRequest"]["properties"]["n"]["maximum"] = 50000


def _busy_code_dropped(spec):
    spec["components"]["schemas"]["ErrorCode"]["enum"].remove("ENGINE_BUSY")


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
    _proj_loses_a_status,
    _proj_seed_becomes_integer,
    _proj_horizon_gains_a_value,
    _proj_n_bound_widens,
    _proj_request_allows_extra_fields,
    _proj_weight_becomes_number,
    _proj_risk_limit_pattern_widens,
    _proj_portfolio_limit_grows,
    _proj_digest_becomes_optional,
    _proj_band_gains_won,
    _proj_ratio_becomes_number,
    _proj_not_secured,
    _pct_loses_a_status,
    _pct_horizon_loses_a_value,
    _pct_seed_becomes_integer,
    _pct_request_allows_extra_fields,
    _pct_observed_becomes_number,
    _pct_observed_pattern_widens,
    _pct_count_becomes_number,
    _pct_count_may_be_negative,
    _pct_result_gains_a_path_value,
    _pct_digest_becomes_optional,
    _pct_not_secured,
    _pct_n_bound_widens,
    _busy_code_dropped,
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


DOMAIN_POSTS = {"POST /v1/paths", "POST /v1/projections", "POST /v1/percentiles"}


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
    assert set(normalized) == {
        "GET /healthz",
        "GET /v1/info",
        "POST /v1/paths",
        "POST /v1/projections",
        "POST /v1/percentiles",
    }
    for key, operation in normalized.items():
        # 422 is a domain status only (PATH_TOO_LONG), never FastAPI's validation response
        assert ("422" in operation["responses"]) == (key in DOMAIN_POSTS)
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
