"""The CI job, the README commands, the env names and the version must agree."""

import re
import tomllib
from pathlib import Path

import antlab_ai

ROOT = Path(__file__).resolve().parents[3]
CHECKS = [
    "uv lock --check",
    "uv run --frozen ruff check .",
    "uv run --frozen ruff format --check .",
]


def workflow_commands() -> list[str]:
    text = (ROOT / ".github" / "workflows" / "ai.yml").read_text(encoding="utf-8")
    return [m.group(1) for m in re.finditer(r"^\s+- run: (.+)$", text, re.MULTILINE)]


def test_the_ci_job_runs_the_checks_of_the_readme_in_the_ai_folder():
    text = (ROOT / "ai" / "README.md").read_text(encoding="utf-8")
    workflow = (ROOT / ".github" / "workflows" / "ai.yml").read_text(encoding="utf-8")
    commands = workflow_commands()
    assert commands[0] == CHECKS[0]
    assert commands[2:4] == CHECKS[1:]
    assert commands[4].startswith("uv run --frozen pytest")
    assert "working-directory: ai" in workflow
    for command in CHECKS:
        assert command in text
    assert "uv run --frozen pytest -q" in text


def test_the_ci_job_has_no_secret_and_no_write_permission():
    workflow = (ROOT / ".github" / "workflows" / "ai.yml").read_text(encoding="utf-8")
    assert "secrets." not in workflow
    assert "contents: read" in workflow
    assert "persist-credentials: false" in workflow
    for line in workflow.splitlines():
        if "uses:" in line:
            assert re.search(r"@[0-9a-f]{40}\b", line), line


def test_the_ci_job_watches_the_files_the_tests_read():
    workflow = (ROOT / ".github" / "workflows" / "ai.yml").read_text(encoding="utf-8")
    for path in (
        "ai/**",
        "personas/**",
        ".env.example",
        "docs/api/agent-runner.yaml",
        "docs/api/turn-decision-1.schema.json",
        "src/test/resources/agent/turn-decision/**",
    ):
        assert workflow.count(f"'{path}'") == 2, path  # pull_request and push


def test_the_env_example_names_the_runner_settings():
    text = (ROOT / ".env.example").read_text(encoding="utf-8")
    for name in (
        "ANTLAB_API_URL",
        "ANTLAB_RUNNER_STATE_DIR",
        "ANTLAB_MODEL_STRUCTURED_OUTPUT",
        "OLLAMA_BASE_URL",
        "OLLAMA_API_KEY",
        "ANTLAB_AGENT_RUNNER_TOKEN",
    ):
        assert re.search(rf"^(# )?{name}=", text, re.MULTILINE), name
    # A secret name carries no value in the template.
    assert re.search(r"^ANTLAB_AGENT_RUNNER_TOKEN=$", text, re.MULTILINE)
    assert re.search(r"^# OLLAMA_API_KEY=$", text, re.MULTILINE)


def test_the_runner_version_is_the_package_version():
    project = tomllib.loads((ROOT / "ai" / "pyproject.toml").read_text(encoding="utf-8"))
    assert antlab_ai.__version__ == project["project"]["version"] == "0.2.0"
