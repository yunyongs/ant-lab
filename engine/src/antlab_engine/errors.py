import json
import logging
from collections.abc import Mapping
from enum import StrEnum

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import Response
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

PROBLEM_MEDIA_TYPE = "application/problem+json"

logger = logging.getLogger(__name__)


class ErrorCode(StrEnum):
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    NOT_FOUND = "NOT_FOUND"
    METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    UNAUTHENTICATED = "UNAUTHENTICATED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class FieldViolation(BaseModel):
    field: str
    reason: str


class Problem(BaseModel):
    title: str
    status: int = Field(json_schema_extra={"format": "int32"})
    detail: str
    instance: str
    code: ErrorCode
    errors: list[FieldViolation] = Field(default_factory=list)


def problem_body(
    status: int,
    code: ErrorCode,
    detail: str,
    instance: str,
    errors: list[dict[str, str]] | None = None,
) -> bytes:
    body: dict[str, object] = {
        "title": code.value.replace("_", " ").title(),
        "status": status,
        "detail": detail,
        "instance": instance,
        "code": code.value,
    }
    if errors:
        body["errors"] = errors
    return json.dumps(body, separators=(",", ":")).encode()


def problem_response(
    status: int,
    code: ErrorCode,
    detail: str,
    instance: str,
    errors: list[dict[str, str]] | None = None,
    headers: Mapping[str, str] | None = None,
) -> Response:
    return Response(
        problem_body(status, code, detail, instance, errors),
        status_code=status,
        media_type=PROBLEM_MEDIA_TYPE,
        headers=headers,
    )


_HTTP_CODES = {
    401: ErrorCode.UNAUTHENTICATED,
    404: ErrorCode.NOT_FOUND,
    405: ErrorCode.METHOD_NOT_ALLOWED,
}


def register_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> Response:
        errors = [
            {"field": ".".join(str(part) for part in e["loc"]), "reason": e["msg"]}
            for e in exc.errors()
        ]
        return problem_response(
            400, ErrorCode.INVALID_ARGUMENT, "The request is invalid.", request.url.path, errors
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_handler(request: Request, exc: StarletteHTTPException) -> Response:
        code = _HTTP_CODES.get(exc.status_code, ErrorCode.INVALID_ARGUMENT)
        return problem_response(
            exc.status_code,
            code,
            "The requested resource is not available.",
            request.url.path,
            headers=exc.headers,
        )

    @app.exception_handler(Exception)
    async def internal_handler(request: Request, exc: Exception) -> Response:
        logger.exception("Unhandled engine exception")
        return problem_response(
            500,
            ErrorCode.INTERNAL_ERROR,
            "The engine could not process the request.",
            request.url.path,
        )
