import json
from decimal import Decimal

from antlab_ai.modelport import Message, ModelRequest

KEY = "sk-or-v1-TESTSECRET"
OLLAMA_KEY = "ollama-TESTSECRET"
MODEL = "vendor/model:free"
OK_BODY = {
    "id": "gen-1",
    "model": MODEL,
    "provider": "Prov",
    "choices": [{"message": {"content": "hello"}}],
    "usage": {"prompt_tokens": 5, "completion_tokens": 2, "cost": Decimal("0")},
}
OLLAMA_OK = {
    "model": "llama3",
    "message": {"role": "assistant", "content": "hello"},
    "done": True,
    "prompt_eval_count": 7,
    "eval_count": 3,
}


class Scripted:
    """OpenRouter-style transport: replays queued responses or raises queued exceptions."""

    def __init__(self, *responses):
        self.responses, self.bodies, self.headers, self.timeouts = list(responses), [], [], []

    def __call__(self, body, headers, timeout):
        self.bodies.append(body)
        self.headers.append(headers)
        self.timeouts.append(timeout)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


class ScriptedOllama:
    """Ollama-style transport (url first)."""

    def __init__(self, *responses):
        self.responses, self.urls, self.bodies, self.headers, self.timeouts = (
            list(responses),
            [],
            [],
            [],
            [],
        )

    def __call__(self, url, body, headers, timeout):
        self.urls.append(url)
        self.bodies.append(body)
        self.headers.append(headers)
        self.timeouts.append(timeout)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def make_request(**overrides) -> ModelRequest:
    values = {
        "model_id": MODEL,
        "messages": (Message("system", "be brief"), Message("user", "hi")),
        "max_tokens": 64,
        "temperature": Decimal("0"),
        "seed": 1,
        "timeout_ms": 120000,
        "output_schema": None,
    }
    values.update(overrides)
    return ModelRequest(**values)


def body_of(raw: bytes) -> dict:
    return json.loads(raw)
