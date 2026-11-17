"""P9, P10 (Ollama part): the local route on a fake transport."""

import io
import json
from decimal import Decimal
from urllib import error

import pytest
from modelport_helpers import OLLAMA_KEY, OLLAMA_OK, ScriptedOllama, body_of, make_request

from antlab_ai.modelport import OllamaAdapter
from antlab_ai.modelport.ollama_adapter import OLLAMA_DEADLINE_S, urllib_ollama_transport
from antlab_ai.openrouter import client as client_module

LOCAL = "http://127.0.0.1:11434"
SCHEMA = {"type": "object"}


def make(*responses, base=LOCAL, key=None, structured=False):
    transport = ScriptedOllama(*responses)
    return OllamaAdapter(base, key, structured_output=structured, transport=transport), transport


def test_body_shape_and_url():
    adapter, transport = make((200, OLLAMA_OK, {}))
    adapter.attempt(make_request(temperature=Decimal("0.5"), seed=3, max_tokens=50))
    assert transport.urls == [LOCAL + "/api/chat"]
    body = body_of(transport.bodies[0])
    assert set(body) == {"model", "messages", "stream", "options"}
    assert body["stream"] is False
    assert body["options"] == {"temperature": 0.5, "seed": 3, "num_predict": 50}
    assert [m["role"] for m in body["messages"]] == ["system", "user"]


def test_seed_omitted_and_format_only_with_flag_and_schema():
    adapter, transport = make((200, OLLAMA_OK, {}))
    adapter.attempt(make_request(seed=None, output_schema=SCHEMA))
    body = body_of(transport.bodies[0])
    assert "seed" not in body["options"]
    assert "format" not in body
    adapter, transport = make((200, OLLAMA_OK, {}), structured=True)
    adapter.attempt(make_request(output_schema=SCHEMA))
    assert body_of(transport.bodies[0])["format"] == SCHEMA
    adapter, transport = make((200, OLLAMA_OK, {}), structured=True)
    adapter.attempt(make_request(output_schema=None))
    assert "format" not in body_of(transport.bodies[0])


def test_ok_reply_tokens_model_and_no_ledger_id():
    adapter, _ = make((200, OLLAMA_OK, {}))
    attempt = adapter.attempt(make_request())
    assert (attempt.status, attempt.text) == ("OK", "hello")
    assert (attempt.tokens_in, attempt.tokens_out) == (7, 3)
    assert attempt.model_actual == "llama3"
    assert attempt.ledger_call_id is None
    assert attempt.retry_after_s is None
    assert attempt.http_status == 200


@pytest.mark.parametrize(
    ("response", "status"),
    [
        ((200, {"error": "boom"}, {}), "UPSTREAM"),
        ((200, {}, {}), "UPSTREAM"),
        ((200, {"message": {"content": 5}, "done": True}, {}), "UPSTREAM"),
        ((200, {"message": {"content": "x"}, "done": False}, {}), "UPSTREAM"),
        ((200, {"message": "x", "done": True}, {}), "UPSTREAM"),
        ((500, {}, {}), "UPSTREAM"),
        ((404, {"error": "model 'x' not found"}, {}), "ERROR"),
        ((401, {}, {}), "ERROR"),
        ((429, {}, {"Retry-After": "5"}), "LIMITED"),
        (TimeoutError(), "TIMEOUT"),
        (ConnectionRefusedError(), "UPSTREAM"),
    ],
)
def test_status_table(response, status):
    adapter, _ = make(response)
    attempt = adapter.attempt(make_request())
    assert attempt.status == status
    assert attempt.text is None
    assert attempt.credit_refusal is False


def test_negative_token_counts_are_dropped():
    adapter, _ = make((200, {**OLLAMA_OK, "prompt_eval_count": -1, "eval_count": True}, {}))
    attempt = adapter.attempt(make_request())
    assert (attempt.tokens_in, attempt.tokens_out) == (None, None)


@pytest.mark.parametrize(
    "base",
    [
        "http://127.0.0.1:11434",
        "http://[::1]:11434",
        "http://localhost:11434",
        "http://LOCALHOST:11434",
        "http://host.docker.internal:11434",
    ],
)
def test_loopback_hosts_cost_zero(base):
    adapter, _ = make((200, OLLAMA_OK, {}), base=base)
    attempt = adapter.attempt(make_request())
    assert attempt.cost_usd == Decimal(0)
    assert attempt.provider == "ollama-local"


@pytest.mark.parametrize(
    "base",
    [
        "https://ollama.com",
        "http://192.168.1.5:11434",
        "http://127.0.0.1.evil.example",
        "http://localhost.evil.example",
        "http://10.0.0.1",
    ],
)
def test_other_hosts_have_unknown_cost(base):
    adapter, _ = make((200, OLLAMA_OK, {}), base=base)
    attempt = adapter.attempt(make_request())
    assert attempt.cost_usd is None
    assert attempt.provider == "ollama-remote"


@pytest.mark.parametrize(
    "base",
    [
        "ftp://127.0.0.1",
        "127.0.0.1:11434",
        "file:///etc/passwd",
        "http://",
        "http://user:pw@127.0.0.1:11434",
        "http://127.0.0.1:11434?x=1",
        "http://127.0.0.1:notaport",
    ],
)
def test_bad_base_urls_are_refused_without_echoing_them(base):
    with pytest.raises(ValueError) as caught:
        OllamaAdapter(base)
    assert "pw" not in str(caught.value)


def test_trailing_slash_is_normalised():
    adapter, transport = make((200, OLLAMA_OK, {}), base=LOCAL + "/")
    adapter.attempt(make_request())
    assert transport.urls == [LOCAL + "/api/chat"]


def test_authorization_only_when_a_key_is_set():
    adapter, transport = make((200, OLLAMA_OK, {}))
    adapter.attempt(make_request())
    assert "Authorization" not in transport.headers[0]
    adapter, transport = make((200, OLLAMA_OK, {}), key=OLLAMA_KEY)
    attempt = adapter.attempt(make_request())
    assert transport.headers[0]["Authorization"] == "Bearer " + OLLAMA_KEY
    assert OLLAMA_KEY not in attempt.request_text


def test_timeout_is_rounded_up_and_capped():
    for timeout_ms, expected in [(1, 1), (2500, 3), (300000, 300), (900000, OLLAMA_DEADLINE_S)]:
        adapter, transport = make((200, OLLAMA_OK, {}))
        adapter.attempt(make_request(timeout_ms=timeout_ms))
        assert transport.timeouts == [expected]


@pytest.mark.parametrize("model", ["", "a b", "x\n", "../etc", "m" * 101, "-lead"])
def test_unusable_model_ids_are_refused_before_sending(model):
    adapter, transport = make((200, OLLAMA_OK, {}))
    with pytest.raises(ValueError):
        adapter.attempt(make_request(model_id=model))
    assert transport.bodies == []


def test_ollama_model_names_with_tags_are_accepted():
    adapter, _ = make((200, OLLAMA_OK, {}))
    assert adapter.attempt(make_request(model_id="gemma3:27b")).status == "OK"


def test_one_attempt_is_one_transport_call():
    adapter, transport = make((500, {}, {}), (200, OLLAMA_OK, {}))
    assert adapter.attempt(make_request()).status == "UPSTREAM"
    assert len(transport.bodies) == 1
    assert len(transport.responses) == 1


# The default transport reuses AG-01's no-redirect, bounded exchange.
class FakeResponse(io.BytesIO):
    def __init__(self, raw=b"{}", status=200, headers=None):
        super().__init__(raw)
        self.status, self.headers = status, headers or {}


class FakeOpener:
    def __init__(self, outcome):
        self.outcome, self.seen = outcome, []

    def open(self, req, timeout=None):
        self.seen.append((req, timeout))
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        return self.outcome


def test_default_transport_posts_json_and_parses_decimals(monkeypatch):
    opener = FakeOpener(FakeResponse(json.dumps({"x": 1.5}).encode()))
    monkeypatch.setattr(client_module, "_OPENER", opener)
    code, payload, _ = urllib_ollama_transport(LOCAL + "/api/chat", b"{}", {"A": "b"}, 9)
    request_seen, timeout = opener.seen[0]
    assert (request_seen.full_url, request_seen.get_method()) == (LOCAL + "/api/chat", "POST")
    assert code == 200
    assert payload == {"x": Decimal("1.5")}
    assert timeout <= 9


def test_default_transport_treats_a_redirect_as_an_error_status(monkeypatch):
    exc = error.HTTPError(LOCAL, 302, "Found", {"Location": "http://evil.example"}, io.BytesIO(b""))  # type: ignore[arg-type]
    opener = FakeOpener(exc)
    monkeypatch.setattr(client_module, "_OPENER", opener)
    adapter = OllamaAdapter(LOCAL)
    attempt = adapter.attempt(make_request())
    assert attempt.status == "ERROR"
    assert attempt.http_status == 302
    assert len(opener.seen) == 1


def test_real_opener_has_no_redirect_handler_that_follows():
    handler = client_module._NoRedirect()
    assert handler.redirect_request(None, None, 302, "Found", {}, "http://evil.example") is None
