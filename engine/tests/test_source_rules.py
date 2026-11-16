import ast
import re
import subprocess
import sys

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


PURE_DIR = SRC / "bootstrap"
PURE_ALLOWED_IMPORTS = {
    "__future__",
    "collections",
    "dataclasses",
    "datetime",
    "typing",
    "antlab_engine",
}
PURE_ALLOWED_FIRST_PARTY = {"antlab_engine.bootstrap", "antlab_engine.engine_errors"}
CLOCK_CALL = re.compile(
    r"time\.time\(|datetime\.now\(|date\.today\(|time\.monotonic\(|numpy\.random"
)


def imported_modules(text: str) -> set[str]:
    modules: set[str] = set()
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module is not None:
            modules.add(node.module)
    return modules


def disallowed_imports(text: str) -> set[str]:
    bad = set()
    for module in imported_modules(text):
        outside = module.split(".")[0] not in PURE_ALLOWED_IMPORTS
        first_party = module.startswith("antlab_engine")
        allowed_first_party = any(
            module == ok or module.startswith(ok + ".") for ok in PURE_ALLOWED_FIRST_PARTY
        )
        if outside or (first_party and not allowed_first_party):
            bad.add(module)
    return bad


def pure_sources() -> dict[str, str]:
    return {
        str(p.relative_to(SRC)): p.read_text(encoding="utf-8")
        for p in [*PURE_DIR.rglob("*.py"), SRC / "engine_errors.py"]
    }


def test_pure_import_scanner_flags_outside_imports():
    assert disallowed_imports("import numpy\nfrom fastapi import FastAPI\nimport datetime\n") == {
        "numpy",
        "fastapi",
    }
    assert disallowed_imports("from antlab_engine.errors import ErrorCode\n") == {
        "antlab_engine.errors"
    }
    assert (
        disallowed_imports("from datetime import date\nfrom collections.abc import Sequence\n")
        == set()
    )


def test_pure_clock_scanner_flags_wall_clock_and_numpy_random():
    assert find(CLOCK_CALL, "x = time.time()\ny = datetime.now()\nz = date.today()") != []
    assert find(CLOCK_CALL, "numpy.random.seed(1)") != []
    assert find(CLOCK_CALL, "from datetime import date\nd = date(2007, 1, 1)") == []


def test_pure_modules_import_only_the_standard_library():
    assert pure_sources().keys() >= {
        "bootstrap/simple.py",
        "bootstrap/splitmix64.py",
        "engine_errors.py",
    }
    assert {
        n: disallowed_imports(t) for n, t in pure_sources().items() if disallowed_imports(t)
    } == {}


def test_pure_modules_read_no_clock():
    assert {n: find(CLOCK_CALL, t) for n, t in pure_sources().items() if find(CLOCK_CALL, t)} == {}


def test_bootstrap_imports_without_the_web_stack():
    code = (
        "import sys\n"
        "for name in ('fastapi', 'starlette', 'pydantic', 'numpy', 'pandas'):\n"
        "    sys.modules[name] = None\n"
        "import antlab_engine.bootstrap.simple\n"
    )
    result = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", code], capture_output=True, text=True, check=False
    )
    assert result.returncode == 0, result.stderr
