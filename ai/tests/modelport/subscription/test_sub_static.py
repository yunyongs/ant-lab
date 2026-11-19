"""No image, compose file or workflow installs, mounts or runs either subscription CLI."""

import re
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[4]
WORD = re.compile(r"claude|codex", re.IGNORECASE)


def _files() -> list[Path]:
    found = list(REPO.glob("**/Dockerfile*"))
    found += list(REPO.glob("**/compose*.y*ml")) + list(REPO.glob("**/docker-compose*.y*ml"))
    found += list((REPO / ".github" / "workflows").glob("*.y*ml"))
    return [
        p
        for p in found
        if not any(part in {".venv", "node_modules", ".git", "build"} for part in p.parts)
    ]


def test_the_repo_layout_is_found():
    assert (REPO / "ai" / "pyproject.toml").exists()


@pytest.mark.parametrize("path", _files(), ids=lambda p: str(p.relative_to(REPO)))
def test_no_cli_in_images_compose_or_ci(path):
    hits = [n for n, line in enumerate(path.read_text().splitlines(), 1) if WORD.search(line)]
    assert hits == [], f"{path.name}: lines {hits}"


# ---- settings names, templates and documents (AG-03 PR C) ---------------------------------

ENV_EXAMPLE = (REPO / ".env.example").read_text(encoding="utf-8")


def test_the_env_template_carries_every_setting_name_and_the_switch_is_off():
    from antlab_ai.modelport.subscription.settings import SETTING_NAMES

    for name in SETTING_NAMES:
        assert re.search(rf"^(# )?{name}=", ENV_EXAMPLE, re.MULTILINE), name
    assert re.search(r"^ANTLAB_MODEL_SUBSCRIPTION_ENABLED=false$", ENV_EXAMPLE, re.MULTILINE)
    # No other subscription name is active, and none carries a value that could be a secret.
    for name in SETTING_NAMES:
        if name != "ANTLAB_MODEL_SUBSCRIPTION_ENABLED":
            assert not re.search(rf"^{name}=", ENV_EXAMPLE, re.MULTILINE), name


def test_the_env_template_never_assigns_an_api_billing_name():
    from antlab_ai.modelport.subscription.gate import FORBIDDEN_ENV

    for name in FORBIDDEN_ENV:
        assert not re.search(rf"^\s*{name}=\S", ENV_EXAMPLE, re.MULTILINE), name


def test_the_readme_documents_every_setting_and_every_refusal_code():
    from antlab_ai.modelport.subscription.settings import SETTING_NAMES

    text = (REPO / "ai" / "README.md").read_text(encoding="utf-8")
    for name in SETTING_NAMES:
        assert name in text, name
    for code in (
        "API_KEY_SET", "NOT_OWNER_DEVICE", "NOT_OWNER_STARTED", "BATCH_RUNNING", "CLI_MISSING",
        "NOT_LOGGED_IN", "NOT_OWNER_AGENT", "USAGE_UNKNOWN", "USAGE_START", "USAGE_STOP", "CALL_CAP",
    ):  # fmt: skip
        assert code in text, code


def test_deploy_lists_the_final_names_and_the_forbidden_environment():
    from antlab_ai.modelport.subscription.gate import FORBIDDEN_ENV

    text = (REPO / "docs" / "DEPLOY.md").read_text(encoding="utf-8")
    assert "ANTLAB_MODEL_SUBSCRIPTION_ENABLED" in text
    assert "ANTLAB_MODEL_SUBSCRIPTION_ROUTES" in text
    assert "이름 초안, AG-03" not in text
    for name in FORBIDDEN_ENV:
        assert name in text, name


def test_plan_records_the_flat_fee_rule_the_manual_resume_and_the_draft_mapping():
    text = (REPO / "docs" / "PLAN.md").read_text(encoding="utf-8")
    for phrase in ("Requested, not applied", "draft until the probe", "run --resume", "flat fee"):
        assert phrase.lower() in text.lower(), phrase


def test_tracked_product_code_never_reaches_into_the_harness_folder():
    for folder in (REPO / "ai" / "src", REPO / "ai" / "scripts"):
        for path in folder.rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            assert ".claude/" not in text and ".claude'" not in text, path.name
            assert "antlab-orchestrator" not in text, path.name


LEDGER_CODES = ("LEDGER_UNSAFE", "LEDGER_UNREADABLE", "LEDGER_SCHEMA", "LEDGER_ROW", "LEDGER_WRITE")
PAUSE_CODES = ("USAGE_GUARD", "SUBSCRIPTION_REFUSED")


def test_the_ledger_codes_and_pause_reasons_are_in_the_documents():
    readme = (REPO / "ai" / "README.md").read_text(encoding="utf-8")
    plan = (REPO / "docs" / "PLAN.md").read_text(encoding="utf-8")
    deploy = (REPO / "docs" / "DEPLOY.md").read_text(encoding="utf-8")
    for code in (*LEDGER_CODES, *PAUSE_CODES):
        assert code in readme and code in plan, code
    for code in (*LEDGER_CODES, *PAUSE_CODES):
        assert code in deploy, code
    for code in PAUSE_CODES:
        assert code in ENV_EXAMPLE, code


def test_c9_the_readme_states_which_processes_a_refusal_can_launch():
    text = (REPO / "ai" / "README.md").read_text(encoding="utf-8")
    assert "A refusal can launch at most two kinds of process" in text
    assert "--reconcile" in text
    plan = (REPO / "docs" / "PLAN.md").read_text(encoding="utf-8")
    assert "5 seconds in the future" in plan and "RECONCILE_REQUIRED" in plan


def test_c9_the_readme_names_the_meter_command_among_the_launchable_processes():
    text = (REPO / "ai" / "README.md").read_text(encoding="utf-8")
    assert "at most two kinds of process" in text and "usage meter command" in text


def _readme_blocks():
    text = (REPO / "ai" / "README.md").read_text(encoding="utf-8")
    start = text.index("Refusals (exit code 2")
    middle = text.index("거부(시작에서")
    end = text.index("CI: `.github/workflows/ai.yml` runs")
    return text[start:middle], text[middle:end]


def test_every_refusal_code_is_in_both_language_sections_and_in_exactly_one_launch_row():
    from antlab_ai.modelport.subscription.gate import FORBIDDEN_ENV  # noqa: F401

    codes = (
        "API_KEY_SET", "NOT_OWNER_DEVICE", "NOT_OWNER_STARTED", "BATCH_RUNNING", "CLI_MISSING",
        "NOT_LOGGED_IN", "NOT_OWNER_AGENT", "USAGE_UNKNOWN", "USAGE_START", "USAGE_STOP",
        "CALL_CAP", "BATCH_NOT_ACTIVE", "RECONCILE_REQUIRED", "LEDGER_UNSAFE",
        "LEDGER_UNREADABLE", "LEDGER_SCHEMA", "LEDGER_ROW", "LEDGER_WRITE",
    )  # fmt: skip
    english, korean = _readme_blocks()
    for code in codes:
        assert code in english, code
        assert code in korean, code
    for block in (english, korean):
        rows = [line for line in block.splitlines() if line.startswith("| ") and "---" not in line]
        assert len(rows) == 4  # header plus the three launch rows
        for code in ("CLI_MISSING", "NOT_LOGGED_IN"):
            assert sum(code in row for row in rows[1:]) == 1, code
        assert "`LEDGER_SCHEMA`" in rows[1]


def test_the_deploy_cell_and_the_plan_record_the_decisions():
    deploy = (REPO / "docs" / "DEPLOY.md").read_text(encoding="utf-8")
    assert "API-billing or proxy names" in deploy
    plan = (REPO / "docs" / "PLAN.md").read_text(encoding="utf-8")
    assert "Subscription gate decisions (AG-03" in plan
    erd = (REPO / "docs" / "erd.md").read_text(encoding="utf-8")
    assert "CLAUDE_CLI" in erd and "AG-03" in erd
