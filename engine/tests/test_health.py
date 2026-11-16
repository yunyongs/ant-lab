def test_health_is_public_and_exact(client):
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.content == b'{"status":"ok"}'


def test_health_ignores_a_wrong_token(client):
    response = client.get("/healthz", headers={"X-Antlab-Engine-Token": "wrong"})
    assert response.status_code == 200


def test_only_get_healthz_is_public(client):
    assert client.post("/healthz").status_code == 401
    assert client.head("/healthz").status_code == 401
    assert client.get("/healthz/").status_code == 401
    assert client.options("/healthz").status_code == 401
    assert client.get("/HEALTHZ").status_code == 401
    assert client.get("/healthz/extra").status_code == 401
