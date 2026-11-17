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
    "enum",
    "fractions",
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
        "bootstrap/stationary.py",
        "bootstrap/rules.py",
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
        "import antlab_engine.bootstrap.stationary\n"
        "import antlab_engine.bootstrap.rules\n"
    )
    result = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", code], capture_output=True, text=True, check=False
    )
    assert result.returncode == 0, result.stderr


PATH_MODULES = ("bootstrap/stationary.py", "bootstrap/rules.py")


def float_uses(text: str) -> list[str]:
    found = []
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Constant) and isinstance(node.value, float):
            found.append("float literal")
        elif isinstance(node, ast.Call) and getattr(node.func, "id", "") == "float":
            found.append("float()")
        elif isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div):
            found.append("true division")
    return found


def output_uses(text: str) -> list[str]:
    found = []
    for node in ast.walk(ast.parse(text)):
        if isinstance(node, ast.Call) and getattr(node.func, "id", "") in {"print", "breakpoint"}:
            found.append("print")
    found += [m for m in imported_modules(text) if m.split(".")[0] in {"logging", "warnings"}]
    return found


def test_float_scanner_flags_float_arithmetic():
    assert float_uses("x = 0.5\ny = float(3)\nz = 1 / 2\n") == [
        "float literal",
        "float()",
        "true division",
    ]
    assert float_uses("x = 7 // 2\ny = Fraction(1, 2)\n") == []


def test_output_scanner_flags_print_and_logging():
    assert output_uses("import logging\nprint('x')\n") == ["print", "logging"]
    assert output_uses("from datetime import date\n") == []


def test_path_modules_use_no_float_arithmetic():
    texts = sources()
    assert {name: float_uses(texts[name]) for name in PATH_MODULES if float_uses(texts[name])} == {}


def test_bootstrap_modules_never_print_or_log():
    texts = sources()
    bad = {n: output_uses(t) for n, t in texts.items() if n.startswith("bootstrap/")}
    assert {n: hits for n, hits in bad.items() if hits} == {}


PROJECTION_DIR = SRC / "projection"
PROJECTION_ALLOWED_IMPORTS = PURE_ALLOWED_IMPORTS | {"decimal", "numpy", "re", "types"}
BLAS_CALL = re.compile(r"\.dot\(|einsum|matmul|tensordot|\.inner\(|numpy\.linalg|\blinalg\b")


def projection_sources() -> dict[str, str]:
    return {
        str(p.relative_to(SRC)): p.read_text(encoding="utf-8") for p in PROJECTION_DIR.rglob("*.py")
    }


def matmul_operators(text: str) -> int:
    return sum(
        isinstance(node, ast.BinOp) and isinstance(node.op, ast.MatMult)
        for node in ast.walk(ast.parse(text))
    )


def projection_disallowed(text: str) -> set[str]:
    bad = set()
    for module in imported_modules(text):
        first = module.split(".")[0]
        if first == "antlab_engine":
            ok = any(module == a or module.startswith(a + ".") for a in PURE_ALLOWED_FIRST_PARTY)
            if not (ok or module.startswith("antlab_engine.projection")):
                bad.add(module)
        elif first not in PROJECTION_ALLOWED_IMPORTS:
            bad.add(module)
    return bad


def test_projection_scanners_flag_what_they_should():
    assert projection_disallowed(
        "import numpy\nimport pandas\nfrom antlab_engine.api import x\n"
    ) == {
        "pandas",
        "antlab_engine.api",
    }
    assert matmul_operators("c = a @ b\n") == 1 and matmul_operators("c = a * b\n") == 0
    assert find(BLAS_CALL, "x = a.dot(b)\ny = np.einsum('i', a)\nz = np.matmul(a, b)") != []
    assert find(BLAS_CALL, "np.cumprod(a, axis=1)") == []


def test_projection_modules_import_only_numpy_and_the_pure_core():
    sources = projection_sources()
    assert sources.keys() >= {"projection/api.py", "projection/evaluate.py", "projection/stats.py"}
    assert {
        n: projection_disallowed(t) for n, t in sources.items() if projection_disallowed(t)
    } == {}


def test_projection_modules_read_no_clock_random_source_or_output():
    sources = projection_sources()
    assert {n: find(CLOCK_CALL, t) for n, t in sources.items() if find(CLOCK_CALL, t)} == {}
    assert {n: output_uses(t) for n, t in sources.items() if output_uses(t)} == {}
    assert [n for n, t in sources.items() if "default_rng" in t or "RandomState" in t] == []


def test_projection_modules_use_no_blas_routine():
    sources = projection_sources()
    assert {n: find(BLAS_CALL, t) for n, t in sources.items() if find(BLAS_CALL, t)} == {}
    assert {n: matmul_operators(t) for n, t in sources.items() if matmul_operators(t)} == {}
