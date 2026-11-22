"""CLI argument handling."""

import json

import pytest

from antlab_engine.bots.__main__ import load_world, load_world_with_panel, main
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


def test_counts_on_a_panel_world_fill_the_counts_and_the_manifest(tmp_path):
    main(
        [
            "--world",
            "panel:0:12:12",
            "--random",
            "2",
            "--static",
            "0",
            "--disciplined",
            "0",
            "--news",
            "0",
            "--counts",
            "100",
            "--out",
            str(tmp_path),
        ]
    )
    records = [json.loads(line) for line in (tmp_path / "runs.jsonl").read_text().splitlines()]
    assert all(r["counts"]["sharpeDiff"]["n"] == 100 for r in records)
    assert json.loads((tmp_path / "manifest.json").read_text())["counts"]["paths"] == 100


def test_counts_need_a_panel_world():
    with pytest.raises(SystemExit):
        main(["--world", "synthetic:3:10:12", "--counts"])


def test_a_bad_panel_spec_prints_the_usage():
    with pytest.raises(SystemExit):
        load_world_with_panel("panel:x")
    with pytest.raises(SystemExit):
        load_world_with_panel("panel:1:2:3:4")


def test_the_generator_is_part_of_the_panel_spec():
    simple_world, _ = load_world_with_panel("panel:0:12:12:simple")
    default_world, _ = load_world_with_panel("panel:0:12:12")
    stationary_world, _ = load_world_with_panel("panel:0:12:12:stationary")
    assert simple_world.world_id == default_world.world_id != stationary_world.world_id


@pytest.mark.parametrize("n", ["0", "50", "5001"])
def test_counts_outside_the_engine_range_are_refused_up_front(n):
    with pytest.raises(SystemExit):
        main(["--world", "panel:0:12:12", "--counts", n])
