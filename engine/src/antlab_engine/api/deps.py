from threading import BoundedSemaphore

from fastapi import Request

from antlab_engine.engine_errors import HistoryUnavailable
from antlab_engine.history.model import HistoryReader

NOT_CONFIGURED = "The history database is not configured."


def projection_slots(request: Request) -> BoundedSemaphore:
    return request.app.state.projection_slots


def history_reader(request: Request) -> HistoryReader:
    reader = request.app.state.history_reader
    if reader is None:
        raise HistoryUnavailable(NOT_CONFIGURED)
    return reader
