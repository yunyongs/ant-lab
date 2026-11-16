import logging

import pytest
from fastapi.testclient import TestClient
from pydantic import BaseModel

from antlab_engine.app import create_app
from antlab_engine.settings import Settings

REQUIRED_KEYS = {"title", "status", "detail", "instance", "code"}


class Payload(BaseModel):
    count: int


def make_app(token: str):
    app = create_app(Settings(token))

    @app.post("/v1/echo", operation_id="echo")
    async def echo(payload: Payload) -> Payload:
        return payload

    @app.get("/v1/boom")
    async def boom():
        raise RuntimeError("secret internal detail")

    return app


@pytest.fixture
def test_client(token):
    return TestClient(make_app(token), raise_server_exceptions=False)


def assert_problem(response, status, code):
    assert response.status_code == status
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body.keys() >= REQUIRED_KEYS
    assert body["status"] == status
    assert body["code"] == code
    return body


def test_unauthenticated_shape(client):
    body = assert_problem(client.get("/v1/info"), 401, "UNAUTHENTICATED")
    assert body["title"] == "Unauthenticated"


def test_not_found_and_method_not_allowed_shape(client, auth):
    assert_problem(client.get("/missing", headers=auth), 404, "NOT_FOUND")
    assert_problem(client.delete("/v1/info", headers=auth), 405, "METHOD_NOT_ALLOWED")


def test_method_not_allowed_keeps_the_allow_header(client, auth):
    response = client.delete("/v1/info", headers=auth)
    assert response.status_code == 405
    assert {m.strip() for m in response.headers["allow"].split(",")} == {"GET"}
    assert "allow" not in client.get("/v1/info").headers
    assert "allow" not in client.get("/missing", headers=auth).headers


def test_malformed_json_is_400_with_field_errors(test_client, auth):
    response = test_client.post(
        "/v1/echo", content=b"{not json", headers={**auth, "content-type": "application/json"}
    )
    body = assert_problem(response, 400, "INVALID_ARGUMENT")
    assert body["errors"]


def test_type_error_is_400_not_422(test_client, auth):
    response = test_client.post("/v1/echo", json={"count": "many"}, headers=auth)
    body = assert_problem(response, 400, "INVALID_ARGUMENT")
    assert body["errors"][0]["field"] == "body.count"
    assert set(body["errors"][0]) == {"field", "reason"}


def test_valid_body_passes(test_client, auth):
    response = test_client.post("/v1/echo", json={"count": 3}, headers=auth)
    assert response.status_code == 200
    assert response.json() == {"count": 3}


def test_unhandled_error_is_500_without_leaking(test_client, auth, caplog):
    with caplog.at_level(logging.ERROR):
        response = test_client.get("/v1/boom", headers=auth)
    body = assert_problem(response, 500, "INTERNAL_ERROR")
    assert "secret internal detail" not in response.text
    assert "traceback" not in response.text.lower()
    assert "RuntimeError" not in body["detail"]
    assert "secret internal detail" in caplog.text
