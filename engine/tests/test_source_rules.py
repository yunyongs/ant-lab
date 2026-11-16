import re

from conftest import ENGINE_DIR

SRC = ENGINE_DIR / "src" / "antlab_engine"
LLM_AND_NETWORK = (
    "openai|anthropic|langchain\\w*|langgraph\\w*|langsmith|llama_index|litellm|ollama|"
    "google\\.generativeai|google\\.genai|mistralai|cohere|transformers|tiktoken|"
    "requests|httpx|aiohttp|urllib3|urllib\\.request|socket|subprocess|random"
)
FORBIDDEN_IMPORT = re.compile(rf"^\s*(?:import|from)\s+(?:{LLM_AND_NETWORK})\b", re.MULTILINE)
ENVIRONMENT_READ = re.compile(r"os\.environ|getenv\(")
SECRET_FILE_READ = re.compile(r"dotenv|\.env\b")


def find(pattern: re.Pattern[str], text: str) -> list[str]:
    return [m.group(0).strip() for m in pattern.finditer(text)]


def sources() -> dict[str, str]:
    return {str(p.relative_to(SRC)): p.read_text(encoding="utf-8") for p in SRC.rglob("*.py")}


def test_scanner_flags_forbidden_imports():
    bad = "import openai\nfrom langchain_core import x\n  import random\nfrom httpx import get\n"
    assert len(find(FORBIDDEN_IMPORT, bad)) == 4
    assert find(FORBIDDEN_IMPORT, "import hmac\nfrom fastapi import FastAPI\nimport json\n") == []


def test_scanner_flags_environment_reads():
    assert find(ENVIRONMENT_READ, "os.environ['x']") and find(ENVIRONMENT_READ, "os.getenv('x')")
    assert find(ENVIRONMENT_READ, "import os") == []


def test_engine_source_has_no_llm_or_network_clients():
    found = {name: find(FORBIDDEN_IMPORT, text) for name, text in sources().items()}
    assert {name: hits for name, hits in found.items() if hits} == {}


def test_only_settings_reads_the_environment():
    readers = {name for name, text in sources().items() if find(ENVIRONMENT_READ, text)}
    assert readers == {"settings.py"}


def test_engine_never_reads_env_files():
    assert [n for n, text in sources().items() if find(SECRET_FILE_READ, text)] == []


def test_token_comparison_is_constant_time():
    text = sources()["security.py"]
    assert "hmac.compare_digest" in text
    assert not re.search(r"engine_token\s*[!=]=", text)
