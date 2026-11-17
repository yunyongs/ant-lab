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
    "decimal",
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
        for p in [*PURE_DIR.rglob("*.py"), SRC / "engine_errors.py", SRC / "history" / "model.py"]
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
        "history/model.py",
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


PATH_MODULES = ("bootstrap/stationary.py", "bootstrap/rules.py", "api/paths.py")


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


HISTORY_DIR = SRC / "history"
SQL_START = re.compile(
    r"^\s*(SELECT|WITH|INSERT|UPDATE|DELETE|TRUNCATE|COPY|CREATE|ALTER|GRANT|DROP)\s+\S",
    re.IGNORECASE,
)
SQL_FORBIDDEN = re.compile(
    r"\b(INSERT|UPDATE|DELETE|TRUNCATE|COPY|CREATE|ALTER|GRANT|DROP)\b|SET\s+ROLE|\blo_|pg_read_file",
    re.IGNORECASE,
)


def string_constants(text: str) -> list[str]:
    return [
        node.value
        for node in ast.walk(ast.parse(text))
        if isinstance(node, ast.Constant) and isinstance(node.value, str)
    ]


def statement_problems(statements: list[str]) -> list[str]:
    problems = []
    for statement in statements:
        if not re.match(r"^\s*(SELECT|WITH)\b", statement, re.IGNORECASE):
            problems.append("not a SELECT/WITH statement")
        if SQL_FORBIDDEN.search(statement):
            problems.append("forbidden keyword")
    return problems


def test_x2_collection_sees_lowercase_sql():
    found = [s for s in string_constants('X = "delete from t"') if SQL_START.match(s)]
    assert found == ["delete from t"]
    assert statement_problems(found) != []


def test_x2_scanner_flags_bad_statements():
    assert statement_problems(["SELECT 1", "WITH x AS (SELECT 1) SELECT * FROM x"]) == []
    assert statement_problems(["DELETE FROM fx_month"]) != []
    assert statement_problems(["SELECT 1; DROP TABLE x"]) != []
    assert statement_problems(["SELECT lo_create(0)"]) != []
    assert statement_problems(["SELECT 1 /* grant */"]) != []
    assert statement_problems(["SELECT pg_read_file('x')"]) != []
    assert statement_problems(["SET ROLE x"]) != []


def test_x1_psycopg_is_imported_only_by_the_reader():
    importers = {
        name
        for name, text in sources().items()
        if any(m.split(".")[0] == "psycopg" for m in imported_modules(text))
    }
    assert importers == {"history/reader.py"}


def test_x2_sql_text_lives_only_in_queries_and_is_read_only():
    texts = sources()
    statements = [s for s in string_constants(texts["history/queries.py"]) if SQL_START.match(s)]
    assert len(statements) >= 6
    assert statement_problems(statements) == []
    outside = {
        name: [s for s in string_constants(text) if SQL_START.match(s)]
        for name, text in texts.items()
        if name != "history/queries.py"
    }
    assert {name: found for name, found in outside.items() if found} == {}


def test_x3_bootstrap_does_not_import_history():
    texts = sources()
    imports = {
        name: {m for m in imported_modules(text) if m.startswith("antlab_engine.history")}
        for name, text in texts.items()
        if name.startswith("bootstrap/")
    }
    assert {n: found for n, found in imports.items() if found} == {}


REGIME_DIR = SRC / "regime"
REGIME_ALLOWED_IMPORTS = {
    "__future__",
    "bisect",
    "collections",
    "dataclasses",
    "datetime",
    "decimal",
    "enum",
    "hashlib",
    "math",
    "numpy",
    "operator",
    "statistics",
    "types",
    "typing",
    "antlab_engine",
}
REGIME_ALLOWED_FIRST_PARTY = {"antlab_engine.regime", "antlab_engine.engine_errors"}
REGIME_BLOCKED = ("fastapi", "starlette", "pydantic", "psycopg", "pandas")


def regime_sources() -> dict[str, str]:
    return {
        str(p.relative_to(SRC)): p.read_text(encoding="utf-8") for p in REGIME_DIR.rglob("*.py")
    }


def regime_disallowed_imports(text: str) -> set[str]:
    bad = set()
    for module in imported_modules(text):
        first_party = module.startswith("antlab_engine")
        allowed_first_party = any(
            module == ok or module.startswith(ok + ".") for ok in REGIME_ALLOWED_FIRST_PARTY
        )
        if module.split(".")[0] not in REGIME_ALLOWED_IMPORTS or (
            first_party and not allowed_first_party
        ):
            bad.add(module)
    return bad


def test_regime_scanner_flags_outside_imports():
    assert regime_disallowed_imports("import numpy\nimport pandas\nimport os\n") == {"pandas", "os"}
    assert regime_disallowed_imports("from antlab_engine.history import model\n") == {
        "antlab_engine.history"
    }
    assert regime_disallowed_imports("from antlab_engine.regime import model\n") == set()


def test_regime_imports_only_numpy_and_the_standard_library():
    texts = regime_sources()
    assert texts.keys() >= {
        "regime/model.py",
        "regime/catalog.py",
        "regime/pit.py",
        "regime/transforms.py",
        "regime/zscore.py",
        "regime/stale.py",
        "regime/params.py",
        "regime/probability.py",
        "regime/hysteresis.py",
        "regime/scoring.py",
        "regime/evaluate.py",
        "regime/publication.py",
        "regime/adapter.py",
        "regime/monthly.py",
        "regime/coverage.py",
    }
    found = {n: regime_disallowed_imports(t) for n, t in texts.items()}
    assert {n: bad for n, bad in found.items() if bad} == {}


def test_regime_reads_no_clock_and_has_no_random_or_output():
    texts = regime_sources()
    assert {n: find(CLOCK_CALL, t) for n, t in texts.items() if find(CLOCK_CALL, t)} == {}
    assert {n: output_uses(t) for n, t in texts.items() if output_uses(t)} == {}
    assert [n for n, t in texts.items() if "numpy.random" in t or "np.random" in t] == []


def test_regime_imports_without_the_web_stack_database_or_pandas():
    blocked = ", ".join(repr(name) for name in REGIME_BLOCKED)
    code = (
        "import sys\n"
        f"for name in ({blocked}):\n"
        "    sys.modules[name] = None\n"
        "import antlab_engine.regime\n"
    )
    result = subprocess.run(  # noqa: S603
        [sys.executable, "-I", "-c", code], capture_output=True, text=True, check=False
    )
    assert result.returncode == 0, result.stderr
