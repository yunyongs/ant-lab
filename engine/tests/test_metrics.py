import asyncio
import re
import socket
import threading
import urllib.request
from pathlib import Path

import pytest
from conftest import TOKEN_HEADER
from fastapi.testclient import TestClient

from antlab_engine.app import create_app
from antlab_engine.metrics import SlotPool
from antlab_engine.settings import Settings, SettingsError


def _sample(app, name: str) -> str:
    from prometheus_client import generate_latest

    return generate_latest(app.state.metrics.registry).decode()


def test_route_template_label_not_raw_path(app, client, auth):
    client.get("/v1/info", headers=auth)
    client.get("/healthz")
    text = _sample(app, "")
    assert 'method="GET",route="/v1/info",status="200"' in text
    assert 'method="GET",route="/healthz",status="200"' in text


def test_unknown_path_and_rejected_token_use_unmatched_label(app, client, auth):
    client.get("/v1/secret-path-123", headers=auth)
    client.get("/v1/info", headers={TOKEN_HEADER: "wrong"})
    text = _sample(app, "")
    assert "secret-path-123" not in text
    assert 'method="GET",route="unmatched",status="404"' in text
    assert 'method="GET",route="unmatched",status="401"' in text


def test_labels_never_carry_the_token(app, client, auth):
    client.get("/v1/info", headers=auth)
    assert auth[TOKEN_HEADER] not in _sample(app, "")


def test_slot_pool_reports_held_slots_and_never_goes_negative():
    pool = SlotPool(3)
    assert pool.acquire(blocking=False) and pool.acquire(blocking=False)
    assert pool.held == 2
    pool.release()
    assert pool.held == 1
    pool.release()
    assert pool.held == 0
    with pytest.raises(ValueError):
        pool.release()
    assert pool.held == 0


def test_slot_pool_refuses_beyond_capacity_without_blocking():
    pool = SlotPool(1)
    assert pool.acquire(blocking=False)
    assert not pool.acquire(blocking=False)
    assert not pool.acquire(timeout=0.01)
    assert pool.held == 1


def test_slot_pool_context_manager_counts_inside_and_after():
    pool = SlotPool(2)
    with pool:
        assert pool.held == 1
        with pool:
            assert pool.held == 2
        assert pool.held == 1
    assert pool.held == 0


def test_slot_pool_is_restored_after_an_exception_and_a_cancellation():
    pool = SlotPool(2)
    with pytest.raises(RuntimeError), pool:
        raise RuntimeError("boom")
    assert pool.held == 0

    async def cancelled():
        with pool:
            raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(cancelled())
    assert pool.held == 0


def test_slot_pool_blocking_acquire_wakes_on_release():
    pool = SlotPool(1)
    pool.acquire()
    got = []
    thread = threading.Thread(target=lambda: got.append(pool.acquire(timeout=5)))
    thread.start()
    pool.release()
    thread.join(5)
    assert got == [True]
    assert pool.held == 1


def test_slot_gauge_follows_held_slots(token):
    app = create_app(Settings(token), max_projections=2)
    app.state.projection_slots.acquire(blocking=False)
    text = _sample(app, "")
    assert "antlab_engine_projection_slots_in_use 1.0" in text
    assert "antlab_engine_projection_slots_capacity 2.0" in text


@pytest.mark.skipif(not Path("/proc/self/stat").exists(), reason="process metrics need Linux /proc")
def test_process_collector_samples_are_exposed(app):
    text = _sample(app, "")
    assert "\nprocess_cpu_seconds_total " in text
    assert "\nprocess_resident_memory_bytes " in text


def test_python_info_is_exposed(app):
    assert "python_info" in _sample(app, "")


def test_many_custom_methods_share_one_label_value(app, client, auth):
    for i in range(100):
        client.request(f"CUSTOM{i}", "/v1/info", headers=auth)
    client.get("/v1/info", headers=auth)
    text = _sample(app, "")
    assert "CUSTOM" not in text
    assert 'method="OTHER"' in text
    methods = set(re.findall(r'method="([^"]*)"', text))
    assert methods <= {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER"}
    assert len([line for line in text.splitlines() if line.startswith("antlab_engine_http")]) < 60


def test_duration_histogram_separates_status_classes(app, client, auth):
    client.get("/v1/info", headers=auth)
    client.get("/v1/info", headers={TOKEN_HEADER: "wrong"})
    text = _sample(app, "")
    assert 'route="/v1/info",status_class="2xx"' in text
    assert 'route="unmatched",status_class="4xx"' in text


def test_path_parameters_and_query_never_reach_labels(app, client, auth):
    @app.get("/v1/items/{item_id}")
    def item(item_id: str):
        return {"id": item_id}

    client.get("/v1/items/SECRET-PATH-VALUE?q=SECRET-QUERY-VALUE", headers=auth)
    text = _sample(app, "")
    assert 'route="/v1/items/{item_id}"' in text
    assert "SECRET" not in text


def test_the_api_port_does_not_serve_metrics_even_with_the_token(client, auth):
    assert client.get("/metrics", headers=auth).status_code == 404
    assert client.get("/metrics").status_code == 401


def test_apps_do_not_share_registries(token):
    one, two = create_app(Settings(token)), create_app(Settings(token))
    assert one.state.metrics.registry is not two.state.metrics.registry


def test_metrics_port_serves_without_token_and_stops(token):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    app = create_app(Settings(token, metrics_port=port))
    with TestClient(app) as client:
        client.get("/healthz")
        body = urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=5).read().decode()  # noqa: S310
        assert 'route="/healthz"' in body
        assert client.get("/metrics").status_code == 401  # the API port does not serve metrics
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", port)) != 0


def test_regime_duration_histogram_exists(app):
    assert "antlab_engine_regime_evaluation_duration_seconds_count 0.0" in _sample(app, "")


def test_metrics_bind_to_loopback_by_default_and_report_a_taken_port(token):
    assert Settings(token).metrics_host == "127.0.0.1"
    with socket.socket() as taken:
        taken.bind(("127.0.0.1", 0))
        taken.listen()
        app = create_app(Settings(token, metrics_port=taken.getsockname()[1]))
        with pytest.raises(RuntimeError, match="one worker"), TestClient(app):
            pass


def test_more_than_one_worker_is_refused(token):
    base = {
        "ANTLAB_ENGINE_TOKEN": token,
        "ANTLAB_ENGINE_DB_HOST": "h",
        "ANTLAB_ENGINE_DB_NAME": "n",
        "ANTLAB_ENGINE_DB_USER": "u",
    }
    assert Settings.from_env({**base, "WEB_CONCURRENCY": "1"}).metrics_host == "127.0.0.1"
    for bad in ("2", "0", "x"):
        with pytest.raises(SettingsError, match="WEB_CONCURRENCY"):
            Settings.from_env({**base, "WEB_CONCURRENCY": bad})


def test_metrics_host_env(token):
    base = {
        "ANTLAB_ENGINE_TOKEN": token,
        "ANTLAB_ENGINE_DB_HOST": "h",
        "ANTLAB_ENGINE_DB_NAME": "n",
        "ANTLAB_ENGINE_DB_USER": "u",
    }
    wide = "0.0.0.0"  # noqa: S104
    assert Settings.from_env({**base, "ANTLAB_ENGINE_METRICS_HOST": wide}).metrics_host == wide


def test_metrics_port_env(token):
    base = {
        "ANTLAB_ENGINE_TOKEN": token,
        "ANTLAB_ENGINE_DB_HOST": "h",
        "ANTLAB_ENGINE_DB_NAME": "n",
        "ANTLAB_ENGINE_DB_USER": "u",
    }
    assert Settings.from_env(base).metrics_port == 8001
    assert Settings.from_env({**base, "ANTLAB_ENGINE_METRICS_PORT": "9100"}).metrics_port == 9100
    assert re.fullmatch(r"\d+", "8001")
