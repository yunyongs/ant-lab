import http.client
import socket
import urllib.request

import pytest


def test_guard_blocks_connect():
    with pytest.raises(AssertionError, match="network disabled"):
        socket.socket().connect(("example.com", 80))


def test_guard_blocks_connect_ex():
    with pytest.raises(AssertionError, match="network disabled"):
        socket.socket().connect_ex(("example.com", 80))


def test_guard_blocks_create_connection():
    with pytest.raises(AssertionError, match="network disabled"):
        socket.create_connection(("example.com", 80))


@pytest.mark.parametrize(
    "call",
    [
        lambda: socket.getaddrinfo("example.com", 443),
        lambda: socket.gethostbyname("example.com"),
        lambda: socket.gethostbyname_ex("example.com"),
        lambda: socket.gethostbyaddr("93.184.216.34"),
    ],
)
def test_guard_blocks_name_lookups(call):
    with pytest.raises(AssertionError, match="network disabled"):
        call()


def test_guard_blocks_the_high_level_clients():
    with pytest.raises(AssertionError, match="network disabled"):
        urllib.request.urlopen("https://example.com", timeout=1)
    with pytest.raises(AssertionError, match="network disabled"):
        http.client.HTTPSConnection("example.com", timeout=1).request("GET", "/")


def test_guard_is_installed_outside_a_function_fixture():
    # Collection-time and module-level code run before any fixture; the patch is already there.
    assert socket.socket.connect.__name__ == "_refuse"
