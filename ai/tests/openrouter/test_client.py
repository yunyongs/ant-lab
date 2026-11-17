import email.message
import io
import json
import logging
import socket
import threading
import time
import types
import urllib.response
from decimal import Decimal
from http.client import HTTPResponse
from urllib import error, request

import pytest

from antlab_ai.budget import BudgetExhausted, RunBudget
from antlab_ai.openrouter import client as client_module
from antlab_ai.openrouter.client import (
    MAX_BODY_BYTES,
    OpenRouterClient,
    ResponseTooLarge,
    SendCap,
    SendCapReached,
    map_status,
    urllib_get_transport,
    urllib_transport,
)
from antlab_ai.openrouter.ledger import Ledger
from antlab_ai.openrouter.limiter import Limiter, RefusedLocal
from antlab_ai.openrouter.policy import validate_model
from antlab_ai.openrouter.retry import Retry, Stop, decide

KEY = "sk-or-v1-TESTSECRET"
MODEL = "vendor/model:free"
OK_BODY = {"choices": [{"message": {"content": "OK"}}], "model": MODEL}


class Scripted:
    """Transport that replays queued responses (or raises queued exceptions)."""

    def __init__(self, *responses):
        self.responses, self.bodies, self.headers = list(responses), [], []

    def __call__(self, body, headers, timeout):
        self.bodies.append(json.loads(body))
        self.headers.append(headers)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def make(tmp_path, clock, *responses, sleeps=None):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    transport = Scripted(*responses)
    sleeps = [] if sleeps is None else sleeps
    client = OpenRouterClient(KEY, Limiter(ledger, 1000), transport, sleeps.append, lambda: 0.0)
    return client, ledger, transport


def test_request_body_and_headers(tmp_path, clock):
    client, _, transport = make(tmp_path, clock, (200, OK_BODY, {}))
    assert client.chat(MODEL, "sys", "usr", max_tokens=8).status == "OK"
    body = transport.bodies[0]
    assert body["provider"] == {"data_collection": "deny"}
    assert body["max_tokens"] == 8
    assert body["model"] == MODEL
    assert [m["role"] for m in body["messages"]] == ["system", "user"]
    assert not {"tools", "tool_choice", "plugins", "response_format"} & set(body)
    assert transport.headers[0]["X-Title"] == "ant-lab"
    assert set(transport.headers[0]) == {"Authorization", "Content-Type", "X-Title"}


KEY_BODY = {
    "data": {
        "label": "sk-or-v1-abc...xyz",
        "limit": None,
        "limit_remaining": None,
        "usage": Decimal("0.25"),
        "is_free_tier": True,
    }
}


class ScriptedGet:
    def __init__(self, *responses):
        self.responses, self.headers = list(responses), []

    def __call__(self, headers, timeout):
        self.headers.append(headers)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def make_key_client(tmp_path, clock, *responses):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    get = ScriptedGet(*responses)
    client = OpenRouterClient(
        KEY, Limiter(ledger, 1000), Scripted(), lambda s: None, lambda: 0.0, get
    )
    return client, ledger, get


def test_key_info_is_reserved_recorded_and_drops_the_label(tmp_path, clock):
    client, ledger, get = make_key_client(tmp_path, clock, (200, KEY_BODY, {}))
    info = client.key_info(caller="product:probe")
    assert info.status == "OK"
    assert info.usage == Decimal("0.25")
    assert info.limit is None
    assert info.is_free_tier is True
    assert "label" not in repr(info)
    assert get.headers[0]["Authorization"] == "Bearer " + KEY
    rows = ledger.rows()
    assert [r["phase"] for r in rows] == ["attempt", "outcome"]
    assert rows[0]["kind"] == "key_info"
    assert rows[1]["status"] == "OK"
    assert rows[1]["http"] == 200
    assert KEY not in ledger.path.read_text()


@pytest.mark.parametrize(
    ("response", "status"),
    [((401, {}, {}), "ERROR"), ((503, {}, {}), "UPSTREAM"), (TimeoutError(), "TIMEOUT")],
)
def test_key_info_failures_are_outcomes_and_not_retried(tmp_path, clock, response, status):
    client, ledger, get = make_key_client(tmp_path, clock, response)
    assert client.key_info().status == status
    assert len(ledger.rows()) == 2
    assert get.responses == []


def test_key_info_respects_the_local_limiter(tmp_path, clock):
    client, ledger, get = make_key_client(tmp_path, clock)
    for _ in range(18):
        client.limiter.reserve("harness:x", MODEL)
    with pytest.raises(RefusedLocal):
        client.key_info()
    assert get.headers == []


class FakeResponse(io.BytesIO):
    def __init__(self, raw=b"{}", status=200, headers=None):
        super().__init__(raw)
        self.status, self.headers = status, headers or {}


class FakeOpener:
    """Stands in for the module opener: records requests, returns or raises one outcome."""

    def __init__(self, outcome):
        self.outcome, self.seen = outcome, []

    def open(self, req, timeout=None):
        self.seen.append((req, timeout))
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        return self.outcome


def use_opener(monkeypatch, outcome):
    opener = FakeOpener(outcome)
    monkeypatch.setattr(client_module, "_OPENER", opener)
    return opener


def test_default_get_transport_targets_the_key_endpoint(monkeypatch):
    opener = use_opener(monkeypatch, FakeResponse(b'{"data": {"usage": 0.5}}'))
    code, payload, _ = urllib_get_transport({"Authorization": "x"}, 5)
    assert (code, payload["data"]["usage"]) == (200, Decimal("0.5"))
    req, _ = opener.seen[0]
    assert (req.full_url, req.get_method()) == ("https://openrouter.ai/api/v1/key", "GET")


def test_attempt_row_is_written_before_the_request(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    seen = []

    def transport(body, headers, timeout):
        seen.append([r["phase"] for r in ledger.rows()])
        return 200, OK_BODY, {}

    OpenRouterClient(KEY, Limiter(ledger, 1000), transport).chat(MODEL, "s", "u")
    assert seen == [["attempt"]]
    assert [r["phase"] for r in ledger.rows()] == ["attempt", "outcome"]


@pytest.mark.parametrize(
    "model",
    [
        "openai/gpt-oss-20b:free",
        "anthropic/claude-x",
        "anthropic/claude-x:free",
        "openrouter/free",
        "openrouter/auto:free",
        "OpenAI/gpt-oss-20b:free",
        "OpenRouter/auto:free",
        "ANTHROPIC/claude-x:free",
        "Anthropic/claude-x",
        "meta/x:online",
        "meta/x:free:online",
        "meta/x:extended",
        "some/paid-model",
        "no-vendor:free",
        "a/b/c:free",
        "",
    ],
)
def test_policy_refuses(model):
    with pytest.raises(ValueError):
        validate_model(model)


@pytest.mark.parametrize("model", ["meta/llama-3.3-70b:free", "nvidia/nemotron-3.5-lightning:free"])
def test_policy_allows_free_named_models(model):
    validate_model(model)


def test_policy_paid_needs_explicit_enable_and_keeps_vendor_ban():
    validate_model("some/paid-model", paid_enabled=True)
    with pytest.raises(ValueError):
        validate_model("openai/gpt-x", paid_enabled=True)


@pytest.mark.parametrize("model", ["meta/x:online", "meta/x:free:online", "meta/x:nitro"])
def test_policy_paid_enabled_still_refuses_suffixes(model):
    with pytest.raises(ValueError):
        validate_model(model, paid_enabled=True)


def test_refused_model_never_reserves(tmp_path, clock):
    client, ledger, transport = make(tmp_path, clock)
    with pytest.raises(ValueError):
        client.chat("openrouter/free", "s", "u")
    assert not ledger.path.exists()
    assert transport.bodies == []


def test_local_refusal_sends_nothing_and_writes_no_row(tmp_path, clock):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    limiter = Limiter(ledger, 50)
    transport = Scripted(*[(200, OK_BODY, {})] * 11)
    client = OpenRouterClient(KEY, limiter, transport, lambda s: None, lambda: 0.0)
    for _ in range(10):
        client.chat(MODEL, "s", "u")
    with pytest.raises(RefusedLocal):
        client.chat(MODEL, "s", "u")
    assert len(transport.bodies) == 10
    assert len(ledger.rows()) == 20  # 10 attempts + 10 outcomes, nothing for the refusal


@pytest.mark.parametrize(
    ("code", "body", "expected"),
    [
        (200, OK_BODY, "OK"),
        (200, [1], "UPSTREAM"),
        (302, {}, "ERROR"),
        (504, {}, "UPSTREAM"),
        (507, {}, "UPSTREAM"),
        (529, {}, "UPSTREAM"),
        (599, {}, "UPSTREAM"),
        (200, {"error": {"message": "boom"}}, "UPSTREAM"),
        (200, {}, "UPSTREAM"),
        (200, None, "UPSTREAM"),
        (400, {}, "ERROR"),
        (401, {}, "ERROR"),
        (402, {}, "LIMITED"),
        (403, {}, "ERROR"),
        (404, {"error": {"message": "No endpoints match your data policy"}}, "POLICY_REFUSED"),
        (404, {"error": {"message": "model not found"}}, "ERROR"),
        (408, {}, "UPSTREAM"),
        (429, {}, "LIMITED"),
        (500, {}, "UPSTREAM"),
        (502, {}, "UPSTREAM"),
        (503, {}, "UPSTREAM"),
        (None, None, "UPSTREAM"),
    ],
)
def test_status_mapping(code, body, expected):
    assert map_status(code, body) == expected


def test_timeout_flag_wins():
    assert map_status(200, OK_BODY, timed_out=True) == "TIMEOUT"


def test_transport_exceptions_become_outcomes(tmp_path, clock):
    client, ledger, _ = make(tmp_path, clock, TimeoutError(), ConnectionResetError(), OSError())
    assert client.chat(MODEL, "s", "u").status == "UPSTREAM"  # timeout retried, reset retried
    statuses = [r["status"] for r in ledger.rows() if r["phase"] == "outcome"]
    assert statuses == ["TIMEOUT", "UPSTREAM", "UPSTREAM"]


def test_each_retry_is_a_new_attempt_row(tmp_path, clock):
    sleeps = []
    client, ledger, transport = make(
        tmp_path, clock, (503, {}, {}), (502, {}, {}), (200, OK_BODY, {}), sleeps=sleeps
    )
    assert client.chat(MODEL, "s", "u").status == "OK"
    assert len(transport.bodies) == 3
    assert [r["phase"] for r in ledger.rows()] == ["attempt", "outcome"] * 3
    assert sleeps == [2.0, 4.0]


def test_upstream_gives_up_after_two_retries(tmp_path, clock):
    client, ledger, transport = make(tmp_path, clock, *[(500, {}, {})] * 3)
    assert client.chat(MODEL, "s", "u").status == "UPSTREAM"
    assert len(transport.bodies) == 3


def test_daily_like_limit_is_not_retried(tmp_path, clock):
    client, ledger, transport = make(tmp_path, clock, (429, {}, {}))
    assert client.chat(MODEL, "s", "u").status == "LIMITED"
    assert len(transport.bodies) == 1


def test_per_minute_limit_waits_retry_after(tmp_path, clock):
    sleeps = []
    client, ledger, _ = make(
        tmp_path, clock, (429, {}, {"Retry-After": "7"}), (200, OK_BODY, {}), sleeps=sleeps
    )
    assert client.chat(MODEL, "s", "u").status == "OK"
    assert sleeps == [7.0]
    assert [r["retry_after_s"] for r in ledger.rows() if r["phase"] == "outcome"][0] == 7


@pytest.mark.parametrize("status", ["ERROR", "POLICY_REFUSED"])
def test_hard_failures_stop_at_once(status):
    assert decide(status, 1) == Stop(status.lower())


def test_decide_table():
    assert decide("UPSTREAM", 1, jitter=0.25) == Retry(2.25)
    assert decide("TIMEOUT", 2) == Retry(4.0)
    assert decide("UPSTREAM", 3) == Stop("upstream")
    assert decide("LIMITED", 1, 5) == Retry(5.0)
    assert decide("LIMITED", 3, 5) == Retry(5.0)
    assert decide("LIMITED", 4, 5) == Stop("daily-like")
    assert decide("LIMITED", 1, None) == Stop("daily-like")


def test_cost_is_exact_decimal_and_stored_as_string(tmp_path, clock):
    body = {**OK_BODY, "usage": {"cost": Decimal("0.000123")}}
    client, ledger, _ = make(tmp_path, clock, (200, body, {}))
    result = client.chat(MODEL, "s", "u")
    assert result.cost_usd == Decimal("0.000123")
    assert ledger.rows()[1]["cost_usd"] == "0.000123"


def test_json_parse_float_path_keeps_exactness(monkeypatch):
    raw = b'{"choices":[{"message":{"content":"x"}}],"usage":{"cost":0.1000000000000000055511}}'
    use_opener(monkeypatch, FakeResponse(raw))
    _, payload, _ = urllib_transport(b"{}", {}, 1)
    assert payload["usage"]["cost"] == Decimal("0.1000000000000000055511")


def test_absent_cost_is_null_not_zero(tmp_path, clock):
    client, ledger, _ = make(tmp_path, clock, (200, OK_BODY, {}))
    assert client.chat(MODEL, "s", "u").cost_usd is None
    assert ledger.rows()[1]["cost_usd"] is None


def http_error(code, raw=b"{}", headers=None):
    return error.HTTPError("https://openrouter.ai/x", code, "x", headers or {}, io.BytesIO(raw))


def test_default_transport_maps_http_errors(monkeypatch):
    use_opener(monkeypatch, http_error(429, b'{"error":{}}', {"Retry-After": "3"}))
    code, payload, headers = urllib_transport(b"{}", {}, 1)
    assert (code, payload, headers["Retry-After"]) == (429, {"error": {}}, "3")


def test_default_transport_tolerates_non_json_error_body(monkeypatch):
    use_opener(monkeypatch, http_error(502, b"<html>"))
    assert urllib_transport(b"{}", {}, 1)[:2] == (502, {})


def test_default_transport_drops_non_object_error_body(monkeypatch):
    use_opener(monkeypatch, http_error(500, b"[1]"))
    assert urllib_transport(b"{}", {}, 1)[:2] == (500, {})


def test_default_transport_targets_openrouter(monkeypatch):
    opener = use_opener(monkeypatch, http_error(500))
    urllib_transport(b"{}", {}, 120)
    req, timeout = opener.seen[0]
    assert (req.full_url, req.get_method()) == (client_module.URL, "POST")
    assert timeout == client_module.SOCKET_TIMEOUT_S  # per-read limit; the deadline is separate
    assert client_module.URL == "https://openrouter.ai/api/v1/chat/completions"


def test_budget_max_calls_stops_before_reservation(tmp_path, clock):
    client, ledger, transport = make(tmp_path, clock, (200, OK_BODY, {}), (200, OK_BODY, {}))
    budget = RunBudget(1, 100, Decimal("0"))
    client.chat(MODEL, "s", "u", budget=budget)
    with pytest.raises(BudgetExhausted):
        client.chat(MODEL, "s", "u", budget=budget)
    assert len(transport.bodies) == 1
    assert len(ledger.rows()) == 2


def test_budget_counts_failed_attempts(tmp_path, clock):
    client, _, transport = make(tmp_path, clock, (500, {}, {}), (500, {}, {}))
    budget = RunBudget(2, 100, Decimal("0"))
    with pytest.raises(BudgetExhausted):
        client.chat(MODEL, "s", "u", budget=budget)
    assert len(transport.bodies) == 2


def test_budget_boundaries():
    budget = RunBudget(2, 10, Decimal("0.01"))
    assert budget.check(10, Decimal("0.01"))  # exactly at the limits is allowed
    assert not budget.check(11, Decimal("0"))
    assert not budget.check(1, Decimal("0.0100001"))
    budget.consume(5, Decimal("0.004"))
    assert budget.check(5, Decimal("0.006"))
    assert not budget.check(6, Decimal("0"))
    assert not budget.check(1, Decimal("0.0061"))
    budget.consume(1, Decimal("0"))
    assert not budget.check(0, Decimal("0"))  # calls exhausted


def test_budget_rejects_floats():
    with pytest.raises(TypeError):
        RunBudget(1, 1, 0.1)
    with pytest.raises(TypeError):
        RunBudget(1, 1, Decimal("1")).check(1, 0.1)


def test_secret_hygiene(tmp_path, clock, caplog):
    leaky = RuntimeError("failed with Bearer " + KEY)
    client, ledger, _ = make(tmp_path, clock, leaky, (500, {"error": KEY}, {}), (200, OK_BODY, {}))
    with caplog.at_level(logging.DEBUG):
        client.chat(MODEL, "s", "u")
        with pytest.raises(ValueError) as refused:
            client.chat("openrouter/free", "s", "u")
    assert "TESTSECRET" not in ledger.path.read_text()
    assert "TESTSECRET" not in repr(client) + str(client)
    assert "TESTSECRET" not in caplog.text
    assert "TESTSECRET" not in str(refused.value)


# --- C1: one send cap covers the key GET, every chat and every retry ---------------------------


def capped(tmp_path, clock, limit, chats=(), keys=()):
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    cap = SendCap(limit)
    seen = []

    def post(body, headers, timeout):
        seen.append(("chat", cap.remaining))
        return chats[len([s for s in seen if s[0] == "chat"]) - 1]

    def get(headers, timeout):
        seen.append(("key", cap.remaining))
        return keys[len([s for s in seen if s[0] == "key"]) - 1]

    client = OpenRouterClient(
        KEY, Limiter(ledger, 1000), post, lambda s: None, lambda: 0.0, get, cap
    )
    return client, ledger, seen


def test_cap_is_spent_before_each_send_and_shared_by_retries(tmp_path, clock):
    fail = (503, {}, {})
    client, ledger, seen = capped(tmp_path, clock, 3, chats=[fail] * 9, keys=[fail])
    assert client.key_info().status == "UPSTREAM"
    assert client.chat(MODEL, "s", "u").status == "UPSTREAM"  # retry stops when the cap is empty
    with pytest.raises(SendCapReached):
        client.chat(MODEL, "s", "u")
    assert seen == [("key", 2), ("chat", 1), ("chat", 0)]  # already charged when the send runs
    attempts = [r for r in ledger.rows() if r["phase"] == "attempt"]
    assert len(attempts) == 3


def test_cap_of_zero_sends_and_reserves_nothing(tmp_path, clock):
    client, ledger, seen = capped(tmp_path, clock, 0)
    with pytest.raises(SendCapReached):
        client.key_info()
    with pytest.raises(SendCapReached):
        client.chat(MODEL, "s", "u")
    assert seen == [] and not ledger.path.exists()


def test_no_cap_means_no_limit_object_needed(tmp_path, clock):
    client, _, transport = make(tmp_path, clock, (200, OK_BODY, {}))
    assert client.send_cap is None
    assert client.chat(MODEL, "s", "u").status == "OK"


# --- C2: redirects are refused ----------------------------------------------------------------


class RedirectingHandler(request.HTTPSHandler):
    def __init__(self):
        super().__init__()
        self.urls, self.auth = [], []

    def https_open(self, req):
        self.urls.append(req.full_url)
        self.auth.append(req.get_header("Authorization"))
        headers = email.message.Message()
        headers["Location"] = "http://elsewhere.example/steal"
        response = urllib.response.addinfourl(io.BytesIO(b""), headers, req.full_url, 302)
        response.msg = "Found"
        return response


@pytest.mark.parametrize("which", ["post", "get"])
def test_redirects_are_refused_and_followed_by_nothing(monkeypatch, which):
    handler = RedirectingHandler()
    monkeypatch.setattr(client_module, "_OPENER", client_module._build_opener(handler))
    headers = {"Authorization": "Bearer " + KEY}
    if which == "post":
        code, payload, _ = urllib_transport(b"{}", headers, 5)
    else:
        code, payload, _ = urllib_get_transport(headers, 5)
    assert (code, payload) == (302, {})
    assert len(handler.urls) == 1  # no second request to the Location target
    assert map_status(code, payload) == "ERROR"


def test_module_opener_has_the_redirect_refusal():
    handler_types = [type(h) for h in client_module._OPENER.handlers]
    assert client_module._NoRedirect in handler_types
    assert request.HTTPRedirectHandler not in handler_types


# --- C7: response body size and total deadline ------------------------------------------------


def test_body_exactly_at_the_cap_is_accepted(monkeypatch):
    pad = MAX_BODY_BYTES - len(b'{"a":""}')
    use_opener(monkeypatch, FakeResponse(b'{"a":"' + b"x" * pad + b'"}'))
    assert urllib_transport(b"{}", {}, 5)[0] == 200


class Endless:
    status, headers = 200, {}

    def __init__(self):
        self.total = 0

    def read1(self, n=-1):
        self.total += n
        return b"x" * n

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_oversized_success_body_is_not_read_beyond_the_cap(monkeypatch):
    endless = Endless()
    use_opener(monkeypatch, endless)
    with pytest.raises(ResponseTooLarge):
        urllib_transport(b"{}", {}, 5)
    assert endless.total <= MAX_BODY_BYTES + 1


def test_oversized_error_body_is_bounded_too(monkeypatch):
    raw = b" " * (MAX_BODY_BYTES + 1)
    use_opener(monkeypatch, http_error(500, raw))
    with pytest.raises(ResponseTooLarge):
        urllib_get_transport({}, 5)


def test_oversized_body_is_a_recorded_upstream_failure(tmp_path, clock, monkeypatch):
    use_opener(monkeypatch, FakeResponse(b" " * (MAX_BODY_BYTES + 1)))
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    client = OpenRouterClient(KEY, Limiter(ledger, 1000), sleep=lambda s: None, jitter=lambda: 0.0)
    assert client.chat(MODEL, "s", "u").status == "UPSTREAM"
    outcomes = [r["status"] for r in ledger.rows() if r["phase"] == "outcome"]
    assert outcomes == ["UPSTREAM"] * 3


class Drip:
    """One byte per read; every read costs ten seconds on the fake clock."""

    status, headers = 200, {}

    def __init__(self, clock):
        self.clock = clock

    def read1(self, n=-1):
        self.clock[0] += 10
        return b" "

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_slow_drip_response_hits_the_total_deadline(monkeypatch):
    now = [0.0]
    monkeypatch.setattr(
        client_module, "time", types.SimpleNamespace(monotonic=lambda: now[0], sleep=lambda s: None)
    )
    use_opener(monkeypatch, Drip(now))
    with pytest.raises(TimeoutError):
        urllib_transport(b"{}", {}, 30)
    assert now[0] <= 40  # stopped within one read of the 30 s deadline


class RawDrip:
    """A real HTTPResponse over a socket pair; a thread sends one byte per `gap` seconds."""

    def __init__(self, total: int, gap: float, chunked: bool = False):
        self.client, self.server = socket.socketpair()
        self.client.settimeout(client_module.SOCKET_TIMEOUT_S)
        head = b"HTTP/1.1 200 OK\r\n"
        head += (
            b"Transfer-Encoding: chunked\r\n\r\n"
            if chunked
            else (b"Content-Length: %d\r\n\r\n" % total)
        )
        self.server.sendall(head)
        self.total, self.gap, self.chunked = total, gap, chunked
        self.thread = threading.Thread(target=self._send, daemon=True)
        self.response = HTTPResponse(self.client)
        self.response.begin()
        self.thread.start()

    def _send(self):
        try:
            for _ in range(self.total):
                time.sleep(self.gap)
                self.server.sendall(b"1\r\n \r\n" if self.chunked else b" ")
            if self.chunked:
                self.server.sendall(b"0\r\n\r\n")
        except OSError:
            pass

    def close(self):
        self.client.close()
        self.server.close()
        self.thread.join(5)


@pytest.mark.parametrize("chunked", [False, True])
def test_real_socket_drip_is_cut_at_the_total_deadline(chunked):
    drip = RawDrip(total=40, gap=0.1, chunked=chunked)  # would take about 4 s to finish
    try:
        started = time.monotonic()
        with pytest.raises(TimeoutError):
            client_module._read_body(drip.response, started + 0.5)
        assert time.monotonic() - started < 1.5
    finally:
        drip.close()


def test_real_socket_stall_is_cut_by_the_time_left_not_the_socket_timeout():
    drip = RawDrip(total=1, gap=3.0)  # silent for 3 s, far under the 15 s socket timeout
    try:
        started = time.monotonic()
        with pytest.raises(TimeoutError):
            client_module._read_body(drip.response, started + 0.5)
        assert time.monotonic() - started < 1.5
    finally:
        drip.close()


def test_real_socket_error_body_is_cut_at_the_total_deadline():
    drip = RawDrip(total=40, gap=0.1)
    try:
        exc = error.HTTPError("https://openrouter.ai/x", 500, "x", {}, drip.response)
        started = time.monotonic()
        with pytest.raises(TimeoutError):
            client_module._read_body(exc, started + 0.5)
        assert time.monotonic() - started < 1.5
    finally:
        drip.close()


def test_real_socket_body_within_the_deadline_is_read_whole():
    drip = RawDrip(total=5, gap=0.01)
    try:
        assert client_module._read_body(drip.response, time.monotonic() + 5) == b" " * 5
    finally:
        drip.close()


def test_slow_drip_is_a_timeout_outcome(tmp_path, clock, monkeypatch):
    now = [0.0]
    monkeypatch.setattr(
        client_module, "time", types.SimpleNamespace(monotonic=lambda: now[0], sleep=lambda s: None)
    )
    use_opener(monkeypatch, Drip(now))
    ledger = Ledger(tmp_path / "x.jsonl", clock)
    client = OpenRouterClient(KEY, Limiter(ledger, 1000), sleep=lambda s: None, jitter=lambda: 0.0)
    assert client.chat(MODEL, "s", "u").status == "TIMEOUT"
    assert ledger.rows()[1]["status"] == "TIMEOUT"


# --- C8: status mapping, retry decisions and response structure --------------------------------


def test_402_is_never_retried_even_with_retry_after(tmp_path, clock):
    sleeps = []
    client, ledger, transport = make(
        tmp_path, clock, (402, {}, {"Retry-After": "5"}), (200, OK_BODY, {}), sleeps=sleeps
    )
    assert client.chat(MODEL, "s", "u").status == "LIMITED"
    assert len(transport.bodies) == 1 and sleeps == []
    assert ledger.rows()[1]["retry_after_s"] == 5  # still recorded


def test_long_retry_after_is_not_waited_for(tmp_path, clock):
    client, _, transport = make(tmp_path, clock, (429, {}, {"Retry-After": "3600"}))
    assert client.chat(MODEL, "s", "u").status == "LIMITED"
    assert len(transport.bodies) == 1


def test_non_ascii_retry_after_is_ignored(tmp_path, clock):
    client, ledger, _ = make(tmp_path, clock, (429, {}, {"Retry-After": "\u00b2"}))
    assert client.chat(MODEL, "s", "u").status == "LIMITED"
    assert ledger.rows()[1]["retry_after_s"] is None


BAD_CHAT_BODIES = [
    {"usage": {}},
    {"choices": []},
    {"choices": [{}]},
    {"choices": ["x"]},
    {"choices": [{"message": "x"}]},
    {"choices": [{"message": {"content": 5}}]},
    {**OK_BODY, "usage": []},
    {**OK_BODY, "usage": {"completion_tokens": -5}},
    {**OK_BODY, "usage": {"completion_tokens": 1.5}},
    {**OK_BODY, "usage": {"completion_tokens": True}},
    {**OK_BODY, "usage": {"prompt_tokens": "3"}},
    {**OK_BODY, "usage": {"cost": Decimal("-0.1")}},
    {**OK_BODY, "usage": {"cost": Decimal("NaN")}},
    {**OK_BODY, "usage": {"cost": Decimal("Infinity")}},
    {**OK_BODY, "usage": {"cost": 0.5}},
    {**OK_BODY, "usage": {"cost": "0.5"}},
]


@pytest.mark.parametrize("body", BAD_CHAT_BODIES)
def test_malformed_success_body_is_upstream_and_trusted_for_nothing(tmp_path, clock, body):
    client, ledger, _ = make(tmp_path, clock, *[(200, body, {})] * 3)
    budget = RunBudget(10, 100, Decimal("1"))
    result = client.chat(MODEL, "s", "u", max_tokens=8, budget=budget)
    assert result.status == "UPSTREAM"
    assert (result.tokens_in, result.tokens_out, result.cost_usd) == (None, None, None)
    outcomes = [r for r in ledger.rows() if r["phase"] == "outcome"]
    assert [r["status"] for r in outcomes] == ["UPSTREAM"] * 3
    assert {r["tokens_out"] for r in outcomes} == {None}
    assert budget.tokens_out == 24 and budget.spent_usd == 0  # charged the reserved maximum


def test_valid_usage_is_recorded(tmp_path, clock):
    body = {**OK_BODY, "usage": {"prompt_tokens": 3, "completion_tokens": 2, "cost": 0}}
    client, ledger, _ = make(tmp_path, clock, (200, body, {}))
    result = client.chat(MODEL, "s", "u")
    assert (result.tokens_in, result.tokens_out, result.cost_usd) == (3, 2, Decimal("0"))
    assert ledger.rows()[1]["cost_usd"] == "0"


def test_unsafe_server_text_is_dropped_not_written_to_the_ledger(tmp_path, clock):
    body = {**OK_BODY, "provider": "bad provider\n", "id": "g" * 500}
    client, ledger, _ = make(tmp_path, clock, (200, body, {}))
    result = client.chat(MODEL, "s", "u")
    assert (result.provider, result.generation_id) == (None, None)
    assert result.model_actual == MODEL
    assert ledger.rows()[1]["provider"] is None


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"data": []},
        {"data": {"usage": -1}},
        {"data": {"usage": "1"}},
        {"data": {"usage": True}},
        {"data": {"usage": 0.5}},
        {"data": {"limit": Decimal("NaN")}},
        {"data": {"is_free_tier": "yes"}},
    ],
)
def test_malformed_key_body_is_upstream(tmp_path, clock, body):
    client, ledger, _ = make_key_client(tmp_path, clock, (200, body, {}))
    assert client.key_info().status == "UPSTREAM"
    assert [r["status"] for r in ledger.rows() if r["phase"] == "outcome"] == ["UPSTREAM"]


def test_key_body_with_integers_is_accepted(tmp_path, clock):
    body = {"data": {"limit": 10, "limit_remaining": 4, "usage": 6, "is_free_tier": False}}
    client, _, _ = make_key_client(tmp_path, clock, (200, body, {}))
    info = client.key_info()
    assert (info.status, info.limit, info.limit_remaining, info.usage) == (
        "OK",
        Decimal(10),
        Decimal(4),
        Decimal(6),
    )


# --- C11: budget input validation --------------------------------------------------------------


@pytest.mark.parametrize("tokens", [-1, True, 1.5, "1"])
def test_budget_consume_rejects_bad_tokens(tokens):
    with pytest.raises(ValueError):
        RunBudget(1, 10, Decimal("1")).consume(tokens, Decimal("0"))


@pytest.mark.parametrize("cost", [Decimal("-0.01"), Decimal("NaN"), Decimal("Infinity")])
def test_budget_consume_rejects_bad_cost(cost):
    budget = RunBudget(1, 10, Decimal("1"))
    with pytest.raises(ValueError):
        budget.consume(1, cost)
    with pytest.raises(ValueError):
        budget.check(1, cost)
    assert (budget.calls, budget.tokens_out, budget.spent_usd) == (0, 0, 0)


def test_budget_consume_rejects_float_cost():
    with pytest.raises(TypeError):
        RunBudget(1, 10, Decimal("1")).consume(1, 0.1)


@pytest.mark.parametrize("max_tokens", [0, -3, True, 1.5])
def test_chat_rejects_bad_max_tokens_before_reserving(tmp_path, clock, max_tokens):
    client, ledger, transport = make(tmp_path, clock)
    with pytest.raises(ValueError):
        client.chat(MODEL, "s", "u", max_tokens=max_tokens)
    assert not ledger.path.exists() and transport.bodies == []
