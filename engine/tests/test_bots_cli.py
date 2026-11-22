"""CLI argument handling."""

import pytest

from antlab_engine.bots.__main__ import load_world, main


def test_a_bad_synthetic_spec_prints_the_usage_not_a_traceback():
    with pytest.raises(SystemExit):
        load_world("synthetic:x")
    with pytest.raises(SystemExit):
        load_world("synthetic:1:2:3:4")


def test_run_directory_key_includes_both_counts(tmp_path, capsys):
    base = ["--world", "synthetic:3:10:12"]
    main([*base, "--random", "3", "--static", "1", "--out", str(tmp_path / "a")])
    main([*base, "--random", "2", "--static", "2", "--out", str(tmp_path / "b")])
    first = (tmp_path / "a" / "runs.jsonl").read_text()
    assert first.count("\n") == 4
    assert capsys.readouterr().out.count("runs ->") == 2
