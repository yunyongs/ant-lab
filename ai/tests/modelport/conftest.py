import pytest
from modelport_helpers import KEY, Scripted

from antlab_ai.openrouter.client import OpenRouterClient
from antlab_ai.openrouter.ledger import Ledger
from antlab_ai.openrouter.limiter import Limiter


@pytest.fixture
def ledger(tmp_path, clock):
    return Ledger(tmp_path / "ledger.jsonl", clock)


@pytest.fixture
def make_client(ledger):
    def build(*responses, tier=1000):
        transport = Scripted(*responses)
        client = OpenRouterClient(
            KEY, Limiter(ledger, tier), transport, lambda s: None, lambda: 0.0
        )
        return client, transport

    return build
