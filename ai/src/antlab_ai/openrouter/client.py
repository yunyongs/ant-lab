"""OpenRouter chat client: policy check, reservation, request, outcome row, retries."""

from __future__ import annotations

import json
import random
import re
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol
from urllib import error, request

from ..budget import BudgetExhausted, RunBudget
from .limiter import Limiter
from .policy import validate_model
from .retry import Retry, decide

URL = "https://openrouter.ai/api/v1/chat/completions"
KEY_URL = "https://openrouter.ai/api/v1/key"

MAX_BODY_BYTES = 1 << 20
SOCKET_TIMEOUT_S = 15
CHAT_DEADLINE_S = 120
KEY_DEADLINE_S = 30
MAX_SEED = 2**31 - 1

_PRINTABLE = re.compile(r"[\x21-\x7e]{1,200}")


class Transport(Protocol):
    def __call__(
        self, body: bytes, headers: dict[str, str], timeout: int
    ) -> tuple[int, dict, dict]: ...


class GetTransport(Protocol):
    def __call__(self, headers: dict[str, str], timeout: int) -> tuple[int, dict, dict]: ...


@dataclass(frozen=True)
class KeyInfo:
    """Account figures from the key endpoint; the key label is deliberately not kept."""

    status: str
    limit: Decimal | None
    limit_remaining: Decimal | None
    usage: Decimal | None
    is_free_tier: bool | None


@dataclass(frozen=True)
class CallResult:
    text: str
    model_actual: str | None
    provider: str | None
    tokens_in: int | None
    tokens_out: int | None
    cost_usd: Decimal | None
    latency_ms: int
    status: str
    generation_id: str | None


@dataclass(frozen=True)
class AttemptOutcome:
    """One sent request: the parsed result plus what a caller needs to record it."""

    result: CallResult
    call_id: str
    http: int | None
    retry_after_s: int | None
    credit_refusal: bool
    body: bytes


def json_number(value: Decimal) -> int | float:
    """A finite Decimal as a JSON number: whole values stay integers, others become floats."""
    if type(value) is not Decimal or not value.is_finite():
        raise ValueError("number must be a finite Decimal")
    return int(value) if value == value.to_integral_value() else float(value)


def map_status(code: int | None, body: object, timed_out: bool = False) -> str:
    if timed_out:
        return "TIMEOUT"
    if code == 200:
        return "UPSTREAM" if not isinstance(body, dict) or not body or "error" in body else "OK"
    if code == 404 and body and "policy" in json.dumps(body, default=str).lower():
        return "POLICY_REFUSED"
    if code is None or code == 408 or 500 <= code <= 599:
        return "UPSTREAM"
    if code in (402, 429):
        return "LIMITED"
    return "ERROR"


class ResponseTooLarge(OSError):
    pass


class _NoRedirect(request.HTTPRedirectHandler):
    """Refuse every redirect: no request, and no Authorization header, ever leaves for another URL."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _build_opener(*extra: request.BaseHandler) -> request.OpenerDirector:
    return request.build_opener(_NoRedirect(), *extra)


_OPENER = _build_opener()


def _arm_socket(response, remaining: float) -> None:
    """Cap the next blocking socket read at the time left; fakes without a socket are skipped."""
    holder = response
    for _ in range(3):
        raw = getattr(holder, "raw", None)
        sock = getattr(raw, "_sock", None)
        if sock is not None:
            sock.settimeout(min(SOCKET_TIMEOUT_S, remaining))
            return
        holder = getattr(holder, "fp", None)
        if holder is None:
            return


def _read_body(response, deadline: float) -> bytes:
    """Read at most MAX_BODY_BYTES; every read is bounded by the time left to the overall deadline.

    read1 does at most one socket read, so a server that drips bytes cannot keep one call alive
    past the deadline the way a buffered read(n) can.
    """
    chunks: list[bytes] = []
    size = 0
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("response deadline exceeded")
        _arm_socket(response, remaining)
        chunk = response.read1(min(8192, MAX_BODY_BYTES + 1 - size))
        if not chunk:
            return b"".join(chunks)
        size += len(chunk)
        if size > MAX_BODY_BYTES:
            raise ResponseTooLarge("response body too large")
        chunks.append(chunk)


def _exchange(req: request.Request, timeout: int) -> tuple[int, dict, dict]:
    """Send one request; the status, a JSON object body (or {}) and the headers come back."""
    deadline = time.monotonic() + timeout
    try:
        with _OPENER.open(req, timeout=min(timeout, SOCKET_TIMEOUT_S)) as response:
            payload = json.loads(_read_body(response, deadline), parse_float=Decimal)
            return response.status, payload, dict(response.headers)
    except error.HTTPError as exc:
        try:
            payload = json.loads(_read_body(exc, deadline), parse_float=Decimal)
        except ValueError:
            payload = {}
        return exc.code, payload if isinstance(payload, dict) else {}, dict(exc.headers)


def urllib_transport(body: bytes, headers: dict[str, str], timeout: int) -> tuple[int, dict, dict]:
    return _exchange(request.Request(URL, data=body, headers=headers, method="POST"), timeout)


def urllib_get_transport(headers: dict[str, str], timeout: int) -> tuple[int, dict, dict]:
    return _exchange(request.Request(KEY_URL, headers=headers, method="GET"), timeout)


def _failed(status: str, started: float) -> CallResult:
    latency = int((time.monotonic() - started) * 1000)
    return CallResult("", None, None, None, None, None, latency, status, None)


class SendCapReached(RuntimeError):
    pass


class SendCap:
    """A hard ceiling on requests actually sent, shared by GETs, chats and every retry."""

    def __init__(self, limit: int):
        self.remaining = max(limit, 0)

    def take(self) -> bool:
        """Spend one send before it happens; False means nothing may be sent."""
        if self.remaining <= 0:
            return False
        self.remaining -= 1
        return True


class _Invalid(ValueError):
    pass


def _text(value: object) -> str | None:
    if value is None:
        return None
    if isinstance(value, str) and _PRINTABLE.fullmatch(value):
        return value
    return None


def _count(value: object) -> int | None:
    if value is None:
        return None
    if type(value) is not int or value < 0:
        raise _Invalid
    return value


def _amount(value: object) -> Decimal | None:
    if value is None:
        return None
    if type(value) is bool or not isinstance(value, int | Decimal):
        raise _Invalid
    amount = Decimal(value)
    if not amount.is_finite():
        raise _Invalid
    return amount


def _parse_chat(payload: dict) -> CallResult | None:
    """Fields of a successful chat body, or None when the structure is not a chat completion."""
    try:
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise _Invalid
        message = choices[0].get("message")
        if not isinstance(message, dict):
            raise _Invalid
        content = message.get("content")
        if content is not None and not isinstance(content, str):
            raise _Invalid
        usage = payload.get("usage")
        if usage is None:
            usage = {}
        if not isinstance(usage, dict):
            raise _Invalid
        cost = _amount(usage.get("cost"))
        if cost is not None and cost < 0:
            raise _Invalid
        return CallResult(
            content or "",
            _text(payload.get("model")),
            _text(payload.get("provider")),
            _count(usage.get("prompt_tokens")),
            _count(usage.get("completion_tokens")),
            cost,
            0,
            "OK",
            _text(payload.get("id")),
        )
    except _Invalid:
        return None


def _parse_key(payload: dict) -> KeyInfo | None:
    try:
        data = payload.get("data")
        if not isinstance(data, dict):
            raise _Invalid
        limit, remaining, usage = (
            _amount(data.get(name)) for name in ("limit", "limit_remaining", "usage")
        )
        if usage is not None and usage < 0:
            raise _Invalid
        free = data.get("is_free_tier")
        if free is not None and not isinstance(free, bool):
            raise _Invalid
        return KeyInfo("OK", limit, remaining, usage, free)
    except _Invalid:
        return None


def _retry_after(headers: dict) -> int | None:
    raw = {str(k).lower(): v for k, v in headers.items()}.get("retry-after")
    text = None if raw is None else str(raw)
    if text and text.isascii() and text.isdigit() and len(text) <= 6:
        return int(text)
    return None


class OpenRouterClient:
    def __init__(
        self,
        api_key: str,
        limiter: Limiter,
        transport: Transport = urllib_transport,
        sleep: Callable[[float], None] = time.sleep,
        jitter: Callable[[], float] = lambda: random.uniform(0, 0.5),
        get_transport: GetTransport = urllib_get_transport,
        send_cap: SendCap | None = None,
    ):
        self.api_key, self.limiter, self.transport = api_key, limiter, transport
        self.sleep, self.jitter, self.get_transport = sleep, jitter, get_transport
        self.send_cap = send_cap

    def __repr__(self) -> str:
        return "OpenRouterClient(api_key='***')"

    def _take_send(self) -> bool:
        return self.send_cap is None or self.send_cap.take()

    def chat(
        self,
        model: str,
        system: str,
        user: str,
        max_tokens: int = 8,
        caller: str = "product:ag01",
        budget: RunBudget | None = None,
    ) -> CallResult:
        validate_model(model)
        if type(max_tokens) is not int or max_tokens < 1:
            raise ValueError("max_tokens must be a positive integer")
        messages = (("system", system), ("user", user))
        attempt_no = 0
        last: CallResult | None = None
        while True:
            attempt_no += 1
            if budget is not None and not budget.check(max_tokens, Decimal("0")):
                raise BudgetExhausted("run budget exhausted")
            try:
                outcome = self.attempt_once(
                    model,
                    messages,
                    max_tokens=max_tokens,
                    temperature=Decimal(0),
                    seed=None,
                    timeout_s=CHAT_DEADLINE_S,
                    caller=caller,
                )
            except SendCapReached:
                if last is None:
                    raise
                return last
            result = outcome.result
            if budget is not None:
                budget.consume(
                    max_tokens if result.tokens_out is None else result.tokens_out,
                    result.cost_usd or Decimal("0"),
                )
            if result.status == "OK":
                return result
            last = result
            step = decide(result.status, attempt_no, outcome.retry_after_s, self.jitter())
            if not isinstance(step, Retry):
                return result
            self.sleep(step.delay_s)

    def attempt_once(
        self,
        model: str,
        messages: Sequence[tuple[str, str]],
        *,
        max_tokens: int,
        temperature: Decimal,
        seed: int | None,
        timeout_s: int,
        caller: str,
        response_format: Mapping[str, object] | None = None,
    ) -> AttemptOutcome:
        """Exactly one reservation, one request and one outcome row; no retry, no wait.

        Raises ValueError before anything is sent for a model outside the policy or a bad
        parameter, RefusedLocal when the limiter refuses, and SendCapReached when the cap is spent.
        """
        validate_model(model)
        if type(max_tokens) is not int or max_tokens < 1:
            raise ValueError("max_tokens must be a positive integer")
        if type(timeout_s) is not int or not 1 <= timeout_s <= CHAT_DEADLINE_S:
            raise ValueError("timeout_s must be between 1 and the chat deadline")
        if seed is not None and (type(seed) is not int or not 0 <= seed <= MAX_SEED):
            raise ValueError("seed must be a non-negative integer or None")
        body_obj: dict[str, object] = {
            "model": model,
            "messages": [{"role": role, "content": content} for role, content in messages],
            "temperature": json_number(temperature),
            "max_tokens": max_tokens,
            "provider": {"data_collection": "deny"},
            "usage": {"include": True},
        }
        if seed is not None:
            body_obj["seed"] = seed
        if response_format is not None:
            body_obj["response_format"] = response_format
        body = json.dumps(body_obj).encode()
        if not self._take_send():
            raise SendCapReached("send cap reached")
        return self._attempt(model, body, max_tokens, caller, timeout_s)

    def key_info(self, caller: str = "product:ag01") -> KeyInfo:
        """One GET of the key endpoint: reserved and recorded like any other call, never retried."""
        if not self._take_send():
            raise SendCapReached("send cap reached")
        call_id = self.limiter.reserve(caller, "key", kind="key_info")
        started = time.monotonic()
        code: int | None = None
        info = KeyInfo("UPSTREAM", None, None, None, None)
        try:
            headers = {"Authorization": "Bearer " + self.api_key, "X-Title": "ant-lab"}
            code, payload, _ = self.get_transport(headers, KEY_DEADLINE_S)
            status = map_status(code, payload)
            if status == "OK":
                info = _parse_key(payload) or KeyInfo("UPSTREAM", None, None, None, None)
            else:
                info = KeyInfo(status, None, None, None, None)
        except TimeoutError:
            info = KeyInfo("TIMEOUT", None, None, None, None)
        except Exception:
            info = KeyInfo("UPSTREAM", None, None, None, None)
        self.limiter.ledger.outcome(
            call_id,
            info.status,
            http=code,
            latency_ms=int((time.monotonic() - started) * 1000),
        )
        return info

    def _attempt(
        self, model: str, body: bytes, max_tokens: int, caller: str, timeout_s: int
    ) -> AttemptOutcome:
        call_id = self.limiter.reserve(caller, model, max_tokens=max_tokens)
        started = time.monotonic()
        headers = {
            "Authorization": "Bearer " + self.api_key,
            "Content-Type": "application/json",
            "X-Title": "ant-lab",
        }
        code: int | None = None
        header_wait: int | None = None
        try:
            code, payload, response_headers = self.transport(body, headers, timeout_s)
            status = map_status(code, payload)
            header_wait = _retry_after(response_headers)
            parsed = _parse_chat(payload) if status == "OK" else None
            if status == "OK" and parsed is None:
                status = "UPSTREAM"
            latency = int((time.monotonic() - started) * 1000)
            result = (
                _failed(status, started)
                if parsed is None
                else CallResult(**{**parsed.__dict__, "latency_ms": latency})
            )
        except TimeoutError:
            result = _failed("TIMEOUT", started)
        except Exception:
            result = _failed("UPSTREAM", started)
        self.limiter.ledger.outcome(
            call_id,
            result.status,
            http=code,
            model_actual=result.model_actual,
            provider=result.provider,
            generation_id=result.generation_id,
            tokens_in=result.tokens_in,
            tokens_out=result.tokens_out,
            cost_usd=None if result.cost_usd is None else str(result.cost_usd),
            latency_ms=result.latency_ms,
            retry_after_s=header_wait,
        )
        # A 402 is a credit refusal: a Retry-After header must not turn it into a retry.
        credit = code == 402
        return AttemptOutcome(result, call_id, code, None if credit else header_wait, credit, body)
