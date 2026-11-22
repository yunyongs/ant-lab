"""CLI argument handling."""

import json

import pytest

from antlab_engine.bots.__main__ import load_world, main
from antlab_engine.sim.feed.catalog import FILES


def test_a_bad_synthetic_spec_prints_the_usage_not_a_traceback():
    with pytest.raises(SystemExit):
        load_world("synthetic:x")
    with pytest.raises(SystemExit):
        load_world("synthetic:1:2:3:4")


def test_run_directory_key_includes_both_counts(tmp_path, capsys):
    base = ["--world", "synthetic:3:10:12", "--disciplined", "0", "--news", "0"]
    main(
        [
            *base,
            "--random",
            "3",
            "--static",
            "1",
            "--disciplined",
            "0",
            "--news",
            "0",
            "--out",
            str(tmp_path / "a"),
        ]
    )
    main(
        [
            *base,
            "--random",
            "2",
            "--static",
            "2",
            "--disciplined",
            "0",
            "--news",
            "0",
            "--out",
            str(tmp_path / "b"),
        ]
    )
    first = (tmp_path / "a" / "runs.jsonl").read_text()
    assert first.count("\n") == 4
    assert capsys.readouterr().out.count("runs ->") == 2


def test_one_disciplined_and_one_news_bot_run_by_default_with_the_catalog_in_the_manifest(
    tmp_path,
):
    main(["--world", "synthetic:3:10:12", "--random", "2", "--static", "1", "--out", str(tmp_path)])
    kinds = [
        json.loads(line)["bot"]["kind"]
        for line in (tmp_path / "runs.jsonl").read_text().splitlines()
    ]
    assert kinds == ["random", "random", "static", "disciplined", "news"]
    manifest = json.loads((tmp_path / "manifest.json").read_text())
    assert set(manifest["catalogSha256"]) == set(FILES)
    assert manifest["botVersions"]["news"] == 1 and manifest["runs"] == 5
