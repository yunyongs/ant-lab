import hmac

from starlette.types import ASGIApp, Receive, Scope, Send

from .errors import ErrorCode, problem_body
from .settings import Settings

TOKEN_HEADER = b"x-antlab-engine-token"


class EngineTokenMiddleware:
    """Requires the engine token on every request except GET /healthz."""

    def __init__(self, app: ASGIApp, settings: Settings):
        self.app = app
        self.settings = settings

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "lifespan" or (
            scope["type"] == "http" and scope["method"] == "GET" and scope["path"] == "/healthz"
        ):
            await self.app(scope, receive, send)
            return
        if scope["type"] != "http":
            await send({"type": "websocket.close", "code": 1008})
            return
        values = [v for k, v in scope.get("headers", []) if k.lower() == TOKEN_HEADER]
        if len(values) == 1 and hmac.compare_digest(values[0], self.settings.engine_token.encode()):
            await self.app(scope, receive, send)
            return
        body = problem_body(
            401, ErrorCode.UNAUTHENTICATED, "A valid engine token is required.", scope["path"]
        )
        await send(
            {
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    (b"content-type", b"application/problem+json"),
                    (b"content-length", str(len(body)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})
