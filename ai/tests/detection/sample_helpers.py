from pathlib import Path

FIXTURES = Path(__file__).parent / "fixtures"


def write_sample(directory: Path, **overrides: str) -> Path:
    """Copy the synthetic fixture into `directory`, replacing whole files by name."""
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("asset.csv", "month_block.csv", "asset_month_return.csv", "fx_month.csv"):
        text = overrides.get(name, (FIXTURES / name).read_text(encoding="utf-8"))
        (directory / name).write_text(text, encoding="utf-8")
    return directory
