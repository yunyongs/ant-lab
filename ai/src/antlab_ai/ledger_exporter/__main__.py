"""Serve /metrics for Prometheus; the files are read on every scrape."""

from __future__ import annotations

import os
import signal
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .exporter import Config, exposition


def make_handler(config: Config) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "ledger-exporter"
        sys_version = ""

        def do_GET(self) -> None:
            if self.path == "/metrics":
                body = exposition(config).encode()
                content_type = "text/plain; version=0.0.4; charset=utf-8"
            elif self.path == "/":
                body, content_type = b"antlab ledger exporter: /metrics\n", "text/plain"
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: object) -> None:  # noqa: A002
            return

    return Handler


def main() -> int:
    config = Config.from_env(os.environ)
    port = int(os.environ.get("LEDGER_EXPORTER_PORT", "9190"))
    server = ThreadingHTTPServer(("0.0.0.0", port), make_handler(config))
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
