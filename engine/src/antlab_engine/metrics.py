"""Prometheus metrics served on an internal port, apart from the API port and its token.

Labels are low-cardinality only: the matched route template (never the raw path), method and
status. Request bodies, path values and tokens never reach a label.
"""

import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from threading import Condition
from wsgiref.simple_server import WSGIServer

from prometheus_client import (
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    PlatformCollector,
    ProcessCollector,
    start_http_server,
)
from starlette.types import ASGIApp, Receive, Scope, Send

UNMATCHED = "unmatched"
METHODS = frozenset({"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
OTHER_METHOD = "OTHER"
DURATION_BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30)


class SlotPool:
    """A fixed number of slots with atomic accounting; `held` is what the gauge reports.

    Works like a bounded semaphore, including `with pool:` (blocking acquire, release on exit).
    """

    def __init__(self, capacity: int):
        if capacity < 1:
            raise ValueError("capacity must be at least 1")
        self.capacity = capacity
        self._held = 0
        self._cond = Condition()

    def acquire(self, blocking: bool = True, timeout: float | None = None) -> bool:
        with self._cond:
            if not blocking:
                if self._held >= self.capacity:
                    return False
            elif not self._cond.wait_for(lambda: self._held < self.capacity, timeout):
                return False
            self._held += 1
            return True

    def release(self) -> None:
        with self._cond:
            if self._held == 0:
                raise ValueError("released more often than acquired")
            self._held -= 1
            self._cond.notify()

    @property
    def held(self) -> int:
        with self._cond:
            return self._held

    def __enter__(self) -> "SlotPool":
        self.acquire()
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.release()


class EngineMetrics:
    def __init__(self, projection_slots: SlotPool):
        self.registry = CollectorRegistry()
        ProcessCollector(registry=self.registry)
        PlatformCollector(registry=self.registry)
        self.requests = Counter(
            "antlab_engine_http_requests_total",
            "Requests by route template, method and status.",
            ["route", "method", "status"],
            registry=self.registry,
        )
        self.duration = Histogram(
            "antlab_engine_http_request_duration_seconds",
            "Request duration by route template, method and status class.",
            ["route", "method", "status_class"],
            buckets=DURATION_BUCKETS,
            registry=self.registry,
        )
        self.regime_duration = Histogram(
            "antlab_engine_regime_evaluation_duration_seconds",
            "Duration of one regime evaluation.",
            buckets=DURATION_BUCKETS,
            registry=self.registry,
        )
        slots = Gauge(
            "antlab_engine_projection_slots_in_use",
            "Projection slots currently held.",
            registry=self.registry,
        )
        slots.set_function(lambda: projection_slots.held)
        capacity = Gauge(
            "antlab_engine_projection_slots_capacity",
            "Projection slots in total.",
            registry=self.registry,
        )
        capacity.set(projection_slots.capacity)


class MetricsMiddleware:
    """Counts every HTTP request, including those the token check rejects."""

    def __init__(self, app: ASGIApp, metrics: EngineMetrics):
        self.app = app
        self.metrics = metrics

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        status = 500
        started = time.perf_counter()

        async def capture(message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
            await send(message)

        try:
            await self.app(scope, receive, capture)
        finally:
            route = getattr(scope.get("route"), "path", None) or UNMATCHED
            method = scope["method"] if scope["method"] in METHODS else OTHER_METHOD
            self.metrics.requests.labels(route, method, str(status)).inc()
            self.metrics.duration.labels(route, method, f"{status // 100}xx").observe(
                time.perf_counter() - started
            )


@asynccontextmanager
async def serve_metrics(metrics: EngineMetrics, host: str, port: int | None) -> AsyncIterator[None]:
    server: WSGIServer | None = None
    if port is not None:
        try:
            server, _ = start_http_server(port, addr=host, registry=metrics.registry)
        except OSError as exc:
            raise RuntimeError(
                f"cannot bind the metrics port {host}:{port} ({exc.strerror}); "
                "the engine supports one worker process only"
            ) from None
    try:
        yield
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
