"""Test-only entry for the Java PathRules mirror; the checker itself lives in bootstrap.rules."""

from datetime import date

from antlab_engine.bootstrap import rules, simple


def violations(
    months: list[date], base_date: date, length: int, block_length: int = 0
) -> list[str]:
    return rules.violations(
        months,
        base_date,
        length,
        engine_version=simple.ENGINE_VERSION,
        block_length=block_length,
    )
