import pytest
from pydantic import BaseModel

from antlab_engine.app import create_app
from antlab_engine.openapi import build_openapi
from antlab_engine.settings import Settings


class Payload(BaseModel):
    count: int


@pytest.fixture
def spec(token):
    app = create_app(Settings(token))

    @app.post("/v1/echo", operation_id="echo")
    async def echo(payload: Payload) -> Payload:
        return payload

    return build_openapi(app)


def test_validation_response_becomes_400_problem(spec):
    responses = spec["paths"]["/v1/echo"]["post"]["responses"]
    assert "422" not in responses
    assert responses["400"]["content"] == {
        "application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}
    }


def test_validation_schemas_are_removed(spec):
    assert "HTTPValidationError" not in spec["components"]["schemas"]
    assert "ValidationError" not in spec["components"]["schemas"]
    assert "Problem" in spec["components"]["schemas"]


def test_spec_is_cached_on_the_app(app):
    assert build_openapi(app) is build_openapi(app)
    assert app.openapi() is app.openapi_schema
