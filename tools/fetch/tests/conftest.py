"""No test may reach a real network: sockets, yfinance and requests sessions fail loudly."""
import socket

import pytest


class NetworkBlocked(AssertionError):
    pass


@pytest.fixture(autouse=True)
def _no_network(monkeypatch):
    def blocked(*a, **k):
        raise NetworkBlocked("a test tried to use the network")

    monkeypatch.setattr(socket.socket, "connect", blocked)
    monkeypatch.setattr(socket, "getaddrinfo", blocked)
    try:
        import yfinance

        monkeypatch.setattr(yfinance, "Ticker", blocked)
    except ImportError:
        pass
    try:
        import curl_cffi.requests as cr

        monkeypatch.setattr(cr.Session, "request", blocked)
    except (ImportError, AttributeError):
        pass
