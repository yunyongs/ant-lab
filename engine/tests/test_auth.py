import logging

import httpx
import pytest
from conftest import TOKEN_HEADER

from antlab_engine import __version__


def _bodies_without_instance(client, headers) -> bytes:
    response = client.get("/v1/info", headers=headers)
    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"
    body = response.json()
    assert body["code"] == "UNAUTHENTICATED"
    assert body["instance"] == "/v1/info"
    body.pop("instance")
    return str(sorted(body.items())).encode()


def test_rejected_requests_get_identical_401(client, token):
    variants = [
        httpx.Headers(),
        httpx.Headers({TOKEN_HEADER: ""}),
        httpx.Headers({TOKEN_HEADER: "wrong"}),
        httpx.Headers({TOKEN_HEADER: token + " "}),
        httpx.Headers({TOKEN_HEADER: token[:-1]}),
        httpx.Headers({TOKEN_HEADER: token.upper() + "x"}),
        httpx.Headers([(TOKEN_HEADER, token), (TOKEN_HEADER, token)]),
        httpx.Headers([(TOKEN_HEADER, "wrong"), (TOKEN_HEADER, token)]),
        httpx.Headers([(TOKEN_HEADER, token), (TOKEN_HEADER.lower(), token)]),
    ]
    assert len({_bodies_without_instance(client, h) for h in variants}) == 1


def test_authorization_header_is_not_a_substitute(client, token):
    response = client.get("/v1/info", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 401


def test_right_token_returns_info(client, auth):
    response = client.get("/v1/info", headers=auth)
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.json() == {
        "service": "antlab-engine",
        "apiVersion": "v1",
        "version": __version__,
        "replayStart": "2007-01-01",
    }


def test_auth_happens_before_routing(client, auth):
    assert client.get("/v1/nope").status_code == 401
    response = client.get("/v1/nope", headers=auth)
    assert response.status_code == 404
    assert response.json()["code"] == "NOT_FOUND"
    post = client.post("/v1/info", headers=auth)
    assert post.status_code == 405
    assert post.json()["code"] == "METHOD_NOT_ALLOWED"


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_documentation_routes_are_disabled(client, auth, path):
    assert client.get(path).status_code == 401
    assert client.get(path, headers=auth).status_code == 404


def test_token_never_appears_in_logs_or_bodies(client, token, auth, caplog):
    caplog.set_level(logging.DEBUG)
    responses = [
        client.get("/v1/info", headers={TOKEN_HEADER: token + "x"}),
        client.get("/v1/info", headers=auth),
        client.get("/v1/nope", headers=auth),
    ]
    assert token not in "".join(r.text for r in responses)
    assert token not in caplog.text
    for response in responses:
        assert token not in str(dict(response.headers))
