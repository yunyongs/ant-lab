import tomllib

from conftest import ENGINE_DIR
from fastapi.testclient import TestClient

from antlab_engine import __version__
from antlab_engine.app import create_app
from antlab_engine.settings import Settings


def test_apps_do_not_share_settings():
    first, second = "a" * 40, "b" * 40
    one = TestClient(create_app(Settings(first)))
    two = TestClient(create_app(Settings(second)))
    assert one.get("/v1/info", headers={"X-Antlab-Engine-Token": first}).status_code == 200
    assert one.get("/v1/info", headers={"X-Antlab-Engine-Token": second}).status_code == 401
    assert two.get("/v1/info", headers={"X-Antlab-Engine-Token": second}).status_code == 200
    assert two.get("/v1/info", headers={"X-Antlab-Engine-Token": first}).status_code == 401


def test_apps_do_not_share_openapi_cache():
    one, two = create_app(Settings("a" * 40)), create_app(Settings("b" * 40))
    assert one.openapi() is not two.openapi()


def test_version_matches_pyproject():
    project = tomllib.loads((ENGINE_DIR / "pyproject.toml").read_text())["project"]
    assert __version__ == project["version"]
