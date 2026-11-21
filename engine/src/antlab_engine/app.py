from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from threading import BoundedSemaphore

from fastapi import FastAPI

from .api import health, info, paths, percentiles, projections, regime
from .errors import register_handlers
from .history.model import HistoryReader
from .history.reader import PostgresHistoryReader
from .metrics import EngineMetrics, MetricsMiddleware, SlotPool, serve_metrics
from .openapi import build_openapi
from .regime.pg_source import PostgresRegimeSource
from .regime.source import RegimeSource
from .security import EngineTokenMiddleware
from .settings import Settings

MAX_PROJECTIONS = 3  # projections in flight at once; each worst case holds about 110 MB


def create_app(
    settings: Settings,
    *,
    history_reader: HistoryReader | None = None,
    regime_source: RegimeSource | None = None,
    max_projections: int = MAX_PROJECTIONS,
) -> FastAPI:
    projection_slots = SlotPool(max_projections)
    metrics = EngineMetrics(projection_slots)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        async with serve_metrics(metrics, settings.metrics_host, settings.metrics_port):
            yield

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    if history_reader is None and settings.database is not None:
        history_reader = PostgresHistoryReader(settings.database)
    app.state.history_reader = history_reader
    app.state.replay_start = settings.replay_start
    if regime_source is None and settings.database is not None:
        regime_source = PostgresRegimeSource(settings.database)
    app.state.regime_source = regime_source
    app.state.regime_slot = BoundedSemaphore(1)  # one evaluation at a time
    app.state.projection_slots = projection_slots
    app.state.metrics = metrics
    app.add_middleware(EngineTokenMiddleware, settings=settings)
    app.add_middleware(MetricsMiddleware, metrics=metrics)
    app.include_router(health.router)
    app.include_router(info.router)
    app.include_router(paths.router)
    app.include_router(projections.router)
    app.include_router(percentiles.router)
    app.include_router(regime.router)
    register_handlers(app)
    app.openapi = lambda: build_openapi(app)  # type: ignore[method-assign]
    return app


def create_app_from_env() -> FastAPI:
    return create_app(Settings.from_env())
