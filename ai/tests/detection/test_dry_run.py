import json
import re

import pytest
from sample_helpers import FIXTURES, write_sample

from antlab_ai.detection import __main__ as cli
from antlab_ai.detection.plan import build_units
from antlab_ai.detection.render import format_percent, krw_return
from antlab_ai.detection.sample import SAMPLE_DIR_ENV, SampleError, default_sample_dir, load_sample

MONTH_NAMES = (
    "january february march april may june july august september october november december"
).split()


def run(tmp_path, *extra, out="out"):
    return cli.main(
        ["dry-run", "--sample-dir", str(FIXTURES), "--out", str(tmp_path / out), *extra]
    )


def files(directory):
    return {p.name: p.read_bytes() for p in sorted(directory.iterdir())}


def test_dry_run_prints_counts_and_hash_only(tmp_path, capsys):
    assert run(tmp_path) == 0
    out = capsys.readouterr().out
    assert "units: 10" in out
    assert "units by window: 1=7 3=3 6=0" in out
    assert "calls per model: 10" in out
    assert re.search(r"plan hash: [0-9a-f]{64}", out)
    assert "files: 11" in out
    assert "%" not in out
    assert not re.search(r"[0-9]\.[0-9]{4,}", out)


def test_dry_run_files_are_deterministic(tmp_path):
    assert run(tmp_path, out="a") == 0
    assert run(tmp_path, out="b") == 0
    first, second = files(tmp_path / "a"), files(tmp_path / "b")
    assert first == second
    assert len(first) == 11
    assert json.loads(first["plan.json"])["plan_hash"]


def test_dry_run_touches_no_ledger_and_writes_only_under_out(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger" / "l.jsonl"
    monkeypatch.setenv("ANTLAB_OPENROUTER_LEDGER", str(ledger))
    monkeypatch.setenv("OPENROUTER_API_KEY", "sk-or-v1-TESTSECRETDRYRUN")
    before = {p for p in tmp_path.rglob("*")}
    assert run(tmp_path) == 0
    new = {p for p in tmp_path.rglob("*")} - before
    assert not ledger.parent.exists()
    assert all(p == tmp_path / "out" or (tmp_path / "out") in p.parents for p in new)
    assert not any("TESTSECRETDRYRUN" in p.read_text() for p in (tmp_path / "out").iterdir())


def test_plan_json_has_no_returns_or_dates(tmp_path):
    run(tmp_path, "--model", "vendor/a:free")
    text = (tmp_path / "out" / "plan.json").read_text()
    for token in (r"-?0\.[0-9]{4,}", r"[0-9]{4}-[0-9]{2}"):
        assert not re.search(token, text)
    data = json.loads(text)
    assert data["calls_per_model"] == 10
    assert data["models"] == ["vendor/a:free"]
    assert data["worst_usd"] == "0"


def test_rendered_prompts_do_not_leak_time_events_or_local_values(tmp_path, sample):
    assert run(tmp_path) == 0
    titles = (FIXTURES / "events" / "titles.txt").read_text().splitlines()
    year = re.compile(r"(?<![0-9.])(?:19|20)[0-9]{2}(?![0-9])")
    for index, unit in enumerate(build_units(sample), start=1):
        text = (tmp_path / "out" / f"unit-{index:04d}.txt").read_text()
        assert not year.search(text)
        assert not re.search(r"[0-9]{4}-[0-9]{2}|[0-9]+월", text)
        assert not any(re.search(rf"\b{name}\b", text, re.IGNORECASE) for name in MONTH_NAMES)
        assert not any(title in text for title in titles)
        assert not re.search(r"fx|exchange|usd|dollar|currency", text, re.IGNORECASE)
        start = sample.segments[unit.segment][0] + unit.target - unit.window + 1
        for asset in sample.assets:
            if asset.market != "US":
                continue
            local_row = ", ".join(
                format_percent(krw_return("KR", sample.returns[asset.key][i], sample.fx[i]))
                for i in range(start, start + unit.window)
            )
            assert local_row not in text


def test_stale_unit_files_from_a_larger_run_are_removed(tmp_path):
    out = tmp_path / "out"
    out.mkdir()
    (out / "unit-0011.txt").write_text("stale")
    (out / "unit-0012.txt").write_text("stale")
    (out / "notes.txt").write_text("mine")
    assert run(tmp_path) == 0
    assert not (out / "unit-0011.txt").exists()
    assert not (out / "unit-0012.txt").exists()
    assert (out / "notes.txt").read_text() == "mine"


@pytest.mark.parametrize(("limit", "code"), [(9, 2), (10, 0), (11, 0)])
def test_call_limit_boundary_with_one_model(tmp_path, limit, code):
    assert run(tmp_path, "--max-calls", str(limit)) == code
    assert (tmp_path / "out").exists() == (code == 0)


def test_call_limit_counts_every_model(tmp_path):
    models = ["--model", "vendor/a:free", "--model", "vendor/b:free"]
    assert run(tmp_path, *models, "--max-calls", "19", out="a") == 2
    assert run(tmp_path, *models, "--max-calls", "20", out="b") == 0
    assert not (tmp_path / "a").exists()


def test_refused_models_stop_before_any_output(tmp_path, capsys):
    for model in ("openai/gpt-oss-20b:free", "vendor/paid-model", "openrouter/free"):
        assert run(tmp_path, "--model", model) == 1
    assert not (tmp_path / "out").exists()
    assert "model is not allowed" in capsys.readouterr().err


def test_sample_dir_comes_from_the_environment(tmp_path, monkeypatch):
    monkeypatch.setenv(SAMPLE_DIR_ENV, str(FIXTURES))
    assert cli.main(["dry-run", "--out", str(tmp_path / "out")]) == 0


def test_set_env_with_missing_files_fails(tmp_path, monkeypatch, capsys):
    empty = tmp_path / "empty"
    empty.mkdir()
    monkeypatch.setenv(SAMPLE_DIR_ENV, str(empty))
    with pytest.raises(SampleError):
        load_sample(default_sample_dir())
    assert cli.main(["dry-run", "--out", str(tmp_path / "out")]) == 1
    assert "missing sample file" in capsys.readouterr().err
    assert not (tmp_path / "out").exists()


def test_missing_proxy_is_an_error(tmp_path, capsys):
    text = (FIXTURES / "asset.csv").read_text(encoding="utf-8").replace("SPY", "SPX")
    returns = (
        (FIXTURES / "asset_month_return.csv").read_text(encoding="utf-8").replace("SPY", "SPX")
    )
    directory = write_sample(
        tmp_path / "s", **{"asset.csv": text, "asset_month_return.csv": returns}
    )
    assert cli.main(["dry-run", "--sample-dir", str(directory), "--out", str(tmp_path / "o")]) == 1
    assert "market proxy" in capsys.readouterr().err
