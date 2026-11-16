import socket
from datetime import UTC, datetime, timedelta

import pytest

BLOCKED = (
    (socket.socket, "connect"),
    (socket.socket, "connect_ex"),
    (socket, "create_connection"),
    (socket, "getaddrinfo"),
    (socket, "gethostbyname"),
    (socket, "gethostbyname_ex"),
    (socket, "gethostbyaddr"),
)


def _refuse(*args, **kwargs):
    raise AssertionError("network disabled in tests")


# Installed while conftest is imported, so test-module collection is covered as well.
_patch = pytest.MonkeyPatch()
for _owner, _name in BLOCKED:
    _patch.setattr(_owner, _name, _refuse)


def pytest_unconfigure(config):
    _patch.undo()


class FakeClock:
    def __init__(self, start: datetime):
        self.now = start

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **kwargs) -> None:
        self.now += timedelta(**kwargs)


@pytest.fixture
def clock():
    return FakeClock(datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC))
