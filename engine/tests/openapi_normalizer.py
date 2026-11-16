"""Reduces an OpenAPI document to the facts a caller depends on.

Compared: operations (id, parameters, request body, responses), effective security, security
schemes, and the schemas reachable from the operations. Ignored: descriptions, summaries, tags,
examples, defaults, schema titles, info, servers and x- extensions.
"""

import copy
from typing import Any

_IGNORED_KEYS = {"title", "description", "summary", "example", "examples", "default", "tags"}
_SCHEMA_PREFIX = "#/components/schemas/"
_RESPONSE_PREFIX = "#/components/responses/"


def normalize(spec: dict[str, Any]) -> dict[str, Any]:
    spec = copy.deepcopy(spec)
    components = spec.get("components", {})
    schemas = components.get("schemas", {})
    responses = components.get("responses", {})
    global_security = spec.get("security", [])
    used: set[str] = set()
    paths: dict[str, Any] = {}
    for path, item in spec.get("paths", {}).items():
        for method, operation in item.items():
            if method.startswith("x-") or not isinstance(operation, dict):
                continue
            paths[f"{method.upper()} {path}"] = {
                "operationId": operation.get("operationId"),
                "parameters": _schema(operation.get("parameters", []), used),
                "requestBody": _content_holder(operation.get("requestBody"), used),
                "responses": {
                    str(status): _content_holder(_resolve_response(r, responses), used)
                    for status, r in operation.get("responses", {}).items()
                },
                "security": operation.get("security", global_security),
            }
    reachable = _close(used, schemas)
    return {
        "openapi": ".".join(str(spec["openapi"]).split(".")[:2]),
        "paths": paths,
        "securitySchemes": components.get("securitySchemes", {}),
        "schemas": {name: _schema(schemas[name], set()) for name in sorted(reachable)},
    }


def _resolve_response(response: dict[str, Any], responses: dict[str, Any]) -> dict[str, Any]:
    ref = response.get("$ref")
    if ref is None:
        return response
    assert ref.startswith(_RESPONSE_PREFIX), ref
    return responses[ref.removeprefix(_RESPONSE_PREFIX)]


def _content_holder(holder: dict[str, Any] | None, used: set[str]) -> dict[str, Any] | None:
    if holder is None:
        return None
    content = {
        media.split(";")[0]: _schema(body.get("schema"), used)
        for media, body in holder.get("content", {}).items()
    }
    return {"content": content, "required": holder.get("required", False)}


def _schema(node: Any, used: set[str]) -> Any:
    if isinstance(node, list):
        return [_schema(item, used) for item in node]
    if not isinstance(node, dict):
        return node
    result: dict[str, Any] = {}
    for key, value in node.items():
        if key in _IGNORED_KEYS or key.startswith("x-"):
            continue
        if key == "$ref":
            name = value.removeprefix(_SCHEMA_PREFIX)
            used.add(name)
            result["$ref"] = name
        elif key == "const":
            result["enum"] = [value]
        elif key == "type" and isinstance(value, list):
            result["type"] = sorted(value)
        elif key == "properties":
            result["properties"] = {k: _schema(v, used) for k, v in value.items()}
        else:
            result[key] = _schema(value, used)
    if "required" in result:
        result["required"] = sorted(result["required"])
    return result


def _close(names: set[str], schemas: dict[str, Any]) -> set[str]:
    reachable: set[str] = set()
    pending = set(names)
    while pending:
        name = pending.pop()
        if name in reachable:
            continue
        reachable.add(name)
        found: set[str] = set()
        _schema(schemas[name], found)
        pending |= found - reachable
    return reachable
