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
