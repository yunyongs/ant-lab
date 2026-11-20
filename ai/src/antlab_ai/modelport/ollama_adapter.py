"""Ollama route: one /api/chat request, no ledger, cost known only for a loopback host."""

from __future__ import annotations

import json
import math
import re
import time
from collections.abc import Callable
from decimal import Decimal
from typing import Protocol
from urllib import request

from ..openrouter.client import _build_opener, _exchange, json_number, map_status
from .port import ModelAttempt, ModelRequest
from .settings import is_loopback, validate_base_url

OLLAMA_DEADLINE_S = 300
_MODEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")
_PRINTABLE = re.compile(r"[\x21-\x7e]{1,200}")


class OllamaTransport(Protocol):
    def __call__(
        self, url: str, body: bytes, headers: dict[str, str], timeout: int
    ) -> tuple[int, dict, dict]: ...


# No proxy: HTTP_PROXY/HTTPS_PROXY must not carry a loopback call (or its key) off the machine.
def _ollama_opener() -> request.OpenerDirector:
    return _build_opener(request.ProxyHandler({}))


_OLLAMA_OPENER = _ollama_opener()


def urllib_ollama_transport(
    url: str, body: bytes, headers: dict[str, str], timeout: int
) -> tuple[int, dict, dict]:
    """Same no-redirect, size- and time-bounded exchange as the OpenRouter client, without proxies."""
    return _exchange(
        request.Request(url, data=body, headers=headers, method="POST"), timeout, _OLLAMA_OPENER
    )


def _count(value: object) -> int | None:
    return value if type(value) is int and value >= 0 else None


def _text(value: object) -> str | None:
    return value if isinstance(value, str) and _PRINTABLE.fullmatch(value) else None


class OllamaAdapter:
    def __init__(
        self,
        base_url: str,
        api_key: str | None = None,
        *,
        structured_output: bool = False,
        transport: OllamaTransport = urllib_ollama_transport,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._url = validate_base_url(base_url) + "/api/chat"
        self._local = is_loopback(base_url)
        if api_key and not self._local and not self._url.startswith("https://"):
            raise ValueError("OLLAMA_API_KEY must not be sent in clear text to a non-loopback host")
        self._key = api_key
        self._structured_output = structured_output
        self._transport = transport
        self._clock = clock

    def __repr__(self) -> str:
        key = "set" if self._key else "unset"
        return f"OllamaAdapter(local={self._local}, api_key={key})"

    def attempt(self, req: ModelRequest) -> ModelAttempt:
        if not _MODEL.fullmatch(req.model_id):
            raise ValueError("model is not allowed")
        timeout_s = min(max(1, math.ceil(req.timeout_ms / 1000)), OLLAMA_DEADLINE_S)
        options: dict[str, object] = {
            "temperature": json_number(req.temperature),
            "num_predict": req.max_tokens,
        }
        if req.seed is not None:
            options["seed"] = req.seed
        body_obj: dict[str, object] = {
            "model": req.model_id,
            "messages": [{"role": m.role, "content": m.content} for m in req.messages],
            "stream": False,
            "options": options,
        }
        if self._structured_output and req.output_schema is not None:
            body_obj["format"] = req.output_schema
        body = json.dumps(body_obj).encode()
        headers = {"Content-Type": "application/json"}
        if self._key:
            headers["Authorization"] = "Bearer " + self._key
        started = self._clock()
        code: int | None = None
        payload: dict = {}
        timed_out = False
        try:
            code, payload, _ = self._transport(self._url, body, headers, timeout_s)
        except TimeoutError:
            timed_out = True
        except Exception:
            code = None
        latency = int((self._clock() - started) * 1000)
        status = map_status(code, payload, timed_out)
        text = tokens_in = tokens_out = actual = None
        if status == "OK":
            message = payload.get("message")
            content = message.get("content") if isinstance(message, dict) else None
            if isinstance(content, str) and payload.get("done", True) is True:
                text = content
                tokens_in = _count(payload.get("prompt_eval_count"))
                tokens_out = _count(payload.get("eval_count"))
                actual = _text(payload.get("model"))
            else:
                status = "UPSTREAM"
        return ModelAttempt(
            status=status,
            http_status=code,
            text=text,
            model_actual=actual,
            provider="ollama-local" if self._local else "ollama-remote",
            tokens_in=tokens_in,
            tokens_out=tokens_out,
            cost_usd=Decimal(0) if self._local else None,
            latency_ms=max(latency, 0),
            ledger_call_id=None,
            retry_after_s=None,
            credit_refusal=False,
            request_text=body.decode("utf-8"),
        )
