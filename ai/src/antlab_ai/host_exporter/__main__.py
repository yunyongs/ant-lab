"""Serve /metrics on the loopback address only; Prometheus reaches it as host.docker.internal."""

from __future__ import annotations

import os
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .exporter import Config, HostCollector

BIND_ADDRESS = "127.0.0.1"
DEFAULT_PORT = 9191


def respond(path: str, collector: HostCollector) -> tuple[int, str, bytes]:
    if path == "/metrics":
        return 200, "text/plain; version=0.0.4; charset=utf-8", collector.exposition().encode()
    if path == "/":
        return 200, "text/plain", b"antlab host exporter: /metrics\n"
    return 404, "text/plain", b"not found\n"


def make_handler(collector: HostCollector) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "host-exporter"
        sys_version = ""

        def do_GET(self) -> None:
            status, content_type, body = respond(self.path, collector)
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            return

    return Handler


def main() -> int:
    config = Config.from_env(os.environ)
    port = int(os.environ.get("ANTLAB_HOST_EXPORTER_PORT", str(DEFAULT_PORT)))
    server = ThreadingHTTPServer((BIND_ADDRESS, port), make_handler(HostCollector(config)))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
