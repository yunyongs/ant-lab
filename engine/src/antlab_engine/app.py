from threading import BoundedSemaphore

from fastapi import FastAPI

from .api import health, info, paths, percentiles, projections, regime
from .errors import register_handlers
from .history.model import HistoryReader
from .history.reader import PostgresHistoryReader
from .openapi import build_openapi
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
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    if history_reader is None and settings.database is not None:
        history_reader = PostgresHistoryReader(settings.database)
    app.state.history_reader = history_reader
    app.state.regime_source = regime_source
    app.state.regime_slot = BoundedSemaphore(1)  # one evaluation at a time
    app.state.projection_slots = BoundedSemaphore(max_projections)
    app.add_middleware(EngineTokenMiddleware, settings=settings)
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
