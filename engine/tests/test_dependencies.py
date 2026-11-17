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
    """Names reachable from the root through runtime dependencies, including activated extras."""
    packages = {p["name"]: p for p in tomllib.loads(lock_text)["package"]}
    names: set[str] = set()
    visited: set[tuple[str, tuple[str, ...]]] = set()
    pending: list[tuple[str, tuple[str, ...]]] = [(ROOT, ())]
    while pending:
        name, extras = pending.pop()
        if (name, extras) in visited:
            continue
        visited.add((name, extras))
        names.add(name)
        package = packages[name]
        deps = list(package.get("dependencies", []))
        for extra in extras:
            deps += package.get("optional-dependencies", {}).get(extra, [])
        pending += [(d["name"], tuple(sorted(d.get("extra", [])))) for d in deps]
    return names


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


EXTRA_LOCK = """
[[package]]
name = "antlab-engine"
dependencies = [{ name = "driver", extra = ["fast"] }]

[[package]]
name = "driver"
dependencies = [{ name = "plain" }]
[package.optional-dependencies]
fast = [{ name = "driver-binary" }, { name = "wrapper", extra = ["net"] }]
unused = [{ name = "openai" }]

[[package]]
name = "plain"

[[package]]
name = "driver-binary"

[[package]]
name = "wrapper"
[package.optional-dependencies]
net = [{ name = "httpx" }]

[[package]]
name = "httpx"

[[package]]
name = "openai"
"""


def test_closure_follows_activated_extras_only():
    closure = runtime_closure(EXTRA_LOCK)
    assert {"driver", "plain", "driver-binary", "wrapper", "httpx"} <= closure
    assert "openai" not in closure
    assert denied(closure) == {"httpx"}


def test_denylist_catches_direct_and_transitive_packages():
    closure = runtime_closure(SYNTHETIC_LOCK)
    assert "pytest" not in closure
    assert denied(closure) == {"langchain-core", "httpx"}


def test_engine_runtime_closure_has_no_llm_or_network_client():
    closure = runtime_closure((ENGINE_DIR / "uv.lock").read_text(encoding="utf-8"))
    assert {"fastapi", "uvicorn", "psycopg", "psycopg-binary"} <= closure
    assert denied(closure) == set()
