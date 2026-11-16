from typing import Any

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi

from . import __version__
from .errors import PROBLEM_MEDIA_TYPE

TOKEN_HEADER_NAME = "X-Antlab-Engine-Token"  # noqa: S105 - a header name, not a secret
PUBLIC_OPERATIONS = (("/healthz", "get"),)
VALIDATION_SCHEMAS = ("HTTPValidationError", "ValidationError")


def build_openapi(app: FastAPI) -> dict[str, Any]:
    """FastAPI's generated spec plus the engine token scheme and problem+json errors."""
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(title="my Fund Manager engine API", version=__version__, routes=app.routes)
    schema["openapi"] = "3.1.0"
    components = schema.setdefault("components", {})
    components.setdefault("securitySchemes", {})["EngineToken"] = {
        "type": "apiKey",
        "in": "header",
        "name": TOKEN_HEADER_NAME,
    }
    for name in VALIDATION_SCHEMAS:
        components.get("schemas", {}).pop(name, None)
    schema["security"] = [{"EngineToken": []}]
    for path, method in PUBLIC_OPERATIONS:
        schema["paths"][path][method]["security"] = []
    for operations in schema["paths"].values():
        for operation in operations.values():
            _normalize_responses(operation["responses"])
    app.openapi_schema = schema
    return schema


def _normalize_responses(responses: dict[str, Any]) -> None:
    validation = responses.pop("422", None)
    if validation is not None:
        responses["400"] = {
            "description": "Invalid argument",
            "content": {PROBLEM_MEDIA_TYPE: {"schema": {"$ref": "#/components/schemas/Problem"}}},
        }
    for status, response in responses.items():
        if status.startswith(("4", "5")) and "application/json" in response.get("content", {}):
            response["content"] = {PROBLEM_MEDIA_TYPE: response["content"]["application/json"]}
