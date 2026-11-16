import fnmatch
import tomllib

from conftest import ENGINE_DIR

DENY = [
    "openai",
    "anthropic",
    "langchain*",
    "langgraph*",
    "langsmith",
    "llama-index*",
    "litellm",
    "ollama",
    "openrouter*",
    "google-generativeai",
    "google-genai",
    "mistralai",
    "cohere",
    "transformers",
    "tiktoken",
    "requests",
    "httpx",
    "aiohttp",
    "urllib3",
]
ROOT = "antlab-engine"


def runtime_closure(lock_text: str) -> set[str]:
    packages = {p["name"]: p for p in tomllib.loads(lock_text)["package"]}
    seen: set[str] = set()
    pending = [ROOT]
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        pending += [d["name"] for d in packages[name].get("dependencies", [])]
    return seen


def denied(names: set[str]) -> set[str]:
    return {n for n in names if any(fnmatch.fnmatch(n, pattern) for pattern in DENY)}


SYNTHETIC_LOCK = """
[[package]]
name = "antlab-engine"
dependencies = [{ name = "langchain-core" }, { name = "uvicorn" }]
[package.dev-dependencies]
dev = [{ name = "pytest" }]

[[package]]
name = "langchain-core"
dependencies = [{ name = "httpx" }]

[[package]]
name = "httpx"

[[package]]
name = "uvicorn"

[[package]]
name = "pytest"
"""


def test_denylist_catches_direct_and_transitive_packages():
    closure = runtime_closure(SYNTHETIC_LOCK)
    assert "pytest" not in closure
    assert denied(closure) == {"langchain-core", "httpx"}


def test_engine_runtime_closure_has_no_llm_or_network_client():
    closure = runtime_closure((ENGINE_DIR / "uv.lock").read_text(encoding="utf-8"))
    assert {"fastapi", "uvicorn"} <= closure
    assert denied(closure) == set()


def test_numpy_is_a_runtime_dependency_pinned_to_one_major():
    closure = runtime_closure((ENGINE_DIR / "uv.lock").read_text(encoding="utf-8"))
    assert "numpy" in closure
    project = tomllib.loads((ENGINE_DIR / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    (spec,) = [d for d in project["dependencies"] if d.startswith("numpy")]
    assert spec.replace(" ", "").endswith(",<3")
