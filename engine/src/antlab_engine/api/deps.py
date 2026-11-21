from datetime import date
from threading import BoundedSemaphore

from fastapi import Request

from antlab_engine.engine_errors import HistoryUnavailable
from antlab_engine.history.model import HistoryReader
from antlab_engine.metrics import SlotPool
from antlab_engine.regime.source import RegimeSource

NOT_CONFIGURED = "The history database is not configured."
REGIME_NOT_CONFIGURED = "The regime data source is not configured."


def projection_slots(request: Request) -> SlotPool:
    return request.app.state.projection_slots


def history_reader(request: Request) -> HistoryReader:
    reader = request.app.state.history_reader
    if reader is None:
        raise HistoryUnavailable(NOT_CONFIGURED)
    return reader


def replay_start(request: Request) -> date:
    return request.app.state.replay_start


def regime_slot(request: Request) -> BoundedSemaphore:
    return request.app.state.regime_slot


def regime_source(request: Request) -> RegimeSource:
    source = request.app.state.regime_source
    if source is None:
        raise HistoryUnavailable(REGIME_NOT_CONFIGURED)
    return source
