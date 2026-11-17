"""Shared helpers of the Spring client tests: a scripted transport, fixtures and an independent validator."""

import json
from functools import cache
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from antlab_ai.runner.settings import RunnerSettings

REPO_ROOT = Path(__file__).resolve().parents[3]
API_FIXTURES = Path(__file__).resolve().parent / "fixtures" / "api"
TOKEN = "T0ken-" + "x" * 40
BASE = "http://api.test:8080"


def fixture(name: str):
    return json.loads((API_FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def settings(tmp_path=None, **over) -> RunnerSettings:
    values = {"api_url": BASE, "runner_token": TOKEN, "state_dir": tmp_path or Path("state")}
    values.update(over)
    return RunnerSettings(**values)


@cache
def _yaml() -> dict:
    return yaml.safe_load((REPO_ROOT / "docs" / "api" / "agent-runner.yaml").read_text("utf-8"))


def contract_errors(schema_name: str, value) -> list:
    """Validate with the test's own reading of the yaml, independent of the client's."""
    registry = Registry().with_resource(
        "urn:test", Resource.from_contents(_yaml(), default_specification=DRAFT202012)
    )
    validator = Draft202012Validator(
        {"$ref": f"urn:test#/components/schemas/{schema_name}"}, registry=registry
    )
    return list(validator.iter_errors(value))


def yaml_operations() -> dict[str, tuple[str, str]]:
    found = {}
    for path, item in _yaml()["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                found[op["operationId"]] = (method.upper(), path)
    return found


class FakeTransport:
    """Answers from a script; an entry is (status, payload) or an exception to raise."""

    def __init__(self, *script):
        self.script = list(script)
        self.calls: list[dict] = []

    def __call__(self, method, url, headers, body, timeout):
        self.calls.append(
            {
                "method": method,
                "url": url,
                "headers": dict(headers),
                "body": None if body is None else json.loads(body),
                "raw": body,
                "timeout": timeout,
            }
        )
        if not self.script:
            raise AssertionError("fake transport script exhausted")
        item = self.script.pop(0)
        if isinstance(item, BaseException):
            raise item
        status, payload = item
        if isinstance(payload, bytes):
            raw = payload
        elif payload is None:
            raw = b""
        else:
            raw = json.dumps(payload, ensure_ascii=False).encode()
        return status, {"Content-Type": "application/json"}, raw


class Sleeps:
    def __init__(self):
        self.delays: list[float] = []

    def __call__(self, seconds):
        self.delays.append(seconds)
