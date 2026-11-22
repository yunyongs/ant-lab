"""The simulator package is pure: no float, no database or network imports, and the parity
`expect` field is carried but never read."""

import ast

from conftest import ENGINE_DIR

SIM = ENGINE_DIR / "src" / "antlab_engine" / "sim"
ALLOWED_IMPORT_ROOTS = {
    "__future__",
    "collections",
    "dataclasses",
    "decimal",
    "enum",
    "re",
    "typing",
}


def trees() -> dict[str, ast.AST]:
    files = sorted(SIM.glob("*.py"))
    assert files, "no simulator sources found"
    return {p.name: ast.parse(p.read_text(encoding="utf-8")) for p in files}


def imported_roots(tree: ast.AST) -> list[str]:
    roots = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            roots += [a.name.split(".")[0] for a in node.names]
        elif isinstance(node, ast.ImportFrom):
            if node.module == "math" and all(a.name == "isqrt" for a in node.names):
                continue  # integer square root: the one math function the score kernel needs
            # relative imports stay inside the package; absolute ones must stay inside it too
            roots.append("." * node.level + (node.module or "").split(".")[0])
    return roots


def float_uses(tree: ast.AST) -> list[int]:
    return [
        node.lineno
        for node in ast.walk(tree)
        if (isinstance(node, ast.Name) and node.id == "float")
        or (isinstance(node, ast.Constant) and isinstance(node.value, float))
        or (isinstance(node, ast.Attribute) and node.attr in ("__float__", "to_float"))
        or (isinstance(node, ast.BinOp | ast.AugAssign) and isinstance(node.op, ast.Div))
        or (isinstance(node, ast.Name) and node.id in ("pow", "complex"))
    ]


def expect_reads(tree: ast.AST) -> list[int]:
    return [n.lineno for n in ast.walk(tree) if isinstance(n, ast.Attribute) and n.attr == "expect"]


def foreign_imports(tree: ast.AST) -> list[str]:
    return [
        root
        for root in imported_roots(tree)
        if not root.startswith(".") and root not in ALLOWED_IMPORT_ROOTS
    ]


def test_scanners_flag_what_they_should():
    bad = ast.parse("import psycopg\nfrom httpx import get\nx = float(1)\ny = 1.5\nz = u.expect\n")
    assert foreign_imports(bad) == ["psycopg", "httpx"]
    assert sorted(float_uses(bad)) == [3, 4]
    holes = ast.parse("a = 1 / 2\nb /= 3\nc = pow(2, -1)\nd = complex(1)\nimport math\n")
    assert sorted(float_uses(holes)) == [1, 2, 3, 4]
    assert foreign_imports(holes) == ["math"]
    assert foreign_imports(ast.parse("from math import isqrt\n")) == []
    assert foreign_imports(ast.parse("from math import sqrt\n")) == ["math"]
    assert expect_reads(bad) == [5]
    ok = ast.parse(
        "from decimal import Decimal\nfrom .money import fee\nfrom enum import StrEnum\n"
    )
    assert foreign_imports(ok) == [] and float_uses(ok) == [] and expect_reads(ok) == []


def test_sim_imports_no_database_network_or_other_package():
    assert {n: foreign_imports(t) for n, t in trees().items() if foreign_imports(t)} == {}


def test_sim_uses_no_float():
    assert {n: float_uses(t) for n, t in trees().items() if float_uses(t)} == {}


def test_sim_never_reads_the_parity_expectation():
    assert {n: expect_reads(t) for n, t in trees().items() if expect_reads(t)} == {}
