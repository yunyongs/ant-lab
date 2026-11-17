"""The runner contract file docs/api/agent-runner.yaml, read once and used to check bodies.

The yaml is the single source: the client validates what it sends and what it receives against the
component schemas named here, so a drift between the API and the runner fails loudly.
"""

from __future__ import annotations

import re
from functools import cache
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

CONTRACT_PATH = Path(__file__).resolve().parents[4] / "docs" / "api" / "agent-runner.yaml"
BASE_PATH = "/internal/agent-runner/v1"
_URI = "urn:antlab:agent-runner"
_DECIMAL = re.compile(r"-?[0-9]+(\.[0-9]+)?")

# Only the contract's own `decimal` format is enforced (plain decimal text, no exponent, no sign plus).
_FORMATS = FormatChecker(formats=())


@_FORMATS.checks("decimal")
def _is_decimal(value: object) -> bool:
    return not isinstance(value, str) or _DECIMAL.fullmatch(value) is not None


class ContractError(Exception):
    """A body does not match the contract. The text names the schema, never a value."""


@cache
def load_contract() -> dict[str, Any]:
    loaded = yaml.safe_load(CONTRACT_PATH.read_text(encoding="utf-8"))
    if not isinstance(loaded, dict) or "components" not in loaded:
        raise ContractError("agent-runner.yaml has no components")
    return loaded


@cache
def _registry() -> Registry:
    resource = Resource.from_contents(load_contract(), default_specification=DRAFT202012)
    return Registry().with_resource(_URI, resource)


@cache
def _validator(schema_name: str) -> Draft202012Validator:
    if schema_name not in load_contract()["components"]["schemas"]:
        raise ContractError(f"unknown schema {schema_name}")
    ref = {"$ref": f"{_URI}#/components/schemas/{schema_name}"}
    return Draft202012Validator(ref, registry=_registry(), format_checker=_FORMATS)


def check(schema_name: str, value: Any) -> None:
    """Raise ContractError when ``value`` does not match the named component schema."""
    if next(_validator(schema_name).iter_errors(value), None) is not None:
        raise ContractError(f"body does not match {schema_name}")


def operations() -> dict[str, tuple[str, str]]:
    """operationId to (METHOD, path), as the yaml states them."""
    found: dict[str, tuple[str, str]] = {}
    for path, item in load_contract()["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                found[op["operationId"]] = (method.upper(), path)
    return found
