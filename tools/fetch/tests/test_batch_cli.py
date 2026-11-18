import pytest

import fetch_batch as cli
from tests.batch_helpers import write_targets


def targets(tmp_path):
    return write_targets(tmp_path / "t.csv", [["KR", "005930", "", "ADJUSTED", "2019-01", "005930.KS", "", "1"]])


def test_status_runs_without_network_or_env(tmp_path, capsys):
    raw = tmp_path / "raw"
    assert cli.main(["status", "--targets", str(targets(tmp_path)), "--raw-dir", str(raw)]) == 0
    out = capsys.readouterr().out
    assert "drip: PENDING 1" in out and "requests today: KIS 0, YF 0" in out


@pytest.fixture
def repo(tmp_path, monkeypatch):
    r = tmp_path / "repo"
    (r / "data").mkdir(parents=True)
    monkeypatch.setattr(cli, "REPO_ROOT", r)
    env = tmp_path / ".env"
    env.write_text("KIS_APP_KEY=FAKEKEY1234\nKIS_APP_SECRET=FAKESECRET1234\n", encoding="utf-8")
    return r, env


@pytest.mark.parametrize("cmd,cap", [("drip", "1501"), ("sweep", "0"), ("yf", "51")])
def test_cap_flag_over_the_maximum_is_refused_before_anything_runs(tmp_path, capsys, repo, cmd, cap):
    r, env = repo
    rc = cli.main([cmd, "--targets", str(targets(tmp_path)), "--raw-dir", str(r / "data" / "raw"), "--daily-cap", cap,
                   "--outside-window"] + (["--env-file", str(env)] if cmd != "yf" else []))
    assert rc == 3 and "CAP_FLAG" in capsys.readouterr().err
    assert not (r / "data" / "raw" / "ledger.csv").exists()


def test_raw_dir_outside_data_is_refused(tmp_path, capsys, repo):
    r, env = repo
    rc = cli.main(["drip", "--targets", str(targets(tmp_path)), "--raw-dir", str(tmp_path / "raw"), "--env-file", str(env),
                   "--outside-window"])
    assert rc == 3 and "RAW_DIR" in capsys.readouterr().err


def test_a_worktree_is_refused_by_the_cli_before_any_request(tmp_path, capsys, repo):
    r, env = repo
    (r / ".git").write_text("gitdir: /elsewhere\n", encoding="utf-8")
    for cmd in ("yf", "drip", "sweep"):
        rc = cli.main([cmd, "--targets", str(targets(tmp_path)), "--raw-dir", str(r / "data" / "raw" / "mvp-04"), "--outside-window"]
                      + (["--env-file", str(env)] if cmd != "yf" else []))
        assert rc == 3 and "WORKTREE" in capsys.readouterr().err
    assert not (r / "data" / "raw" / "mvp-04").exists()


def test_the_test_suite_cannot_reach_the_network(tmp_path):
    import socket

    with pytest.raises(AssertionError, match="network"):
        socket.create_connection(("example.invalid", 80))


def test_the_cli_runs_the_kis_batch_at_one_request_per_second(tmp_path, monkeypatch, repo):
    r, env = repo
    env.write_text("KIS_APP_KEY=FAKEKEY1234\nKIS_APP_SECRET=FAKESECRET1234\nKIS_RPS=4\n", encoding="utf-8")
    seen = {}

    def fake_run(kind, targets_, **kw):
        seen["rps"] = kw["cfg"].rps
        raise cli.KisError("stop here")

    monkeypatch.setattr(cli, "run_kis_batch", fake_run)
    assert cli.main(["drip", "--targets", str(targets(tmp_path)), "--raw-dir", str(r / "data" / "raw" / "mvp-04"), "--env-file", str(env)]) == 2
    assert seen["rps"] == 1 and cli.KIS_BATCH_RPS == 1


def test_status_lists_failed_assets(tmp_path, capsys, repo):
    r, _ = repo
    import batch
    raw = r / "data" / "raw" / "mvp-04"
    ft = batch.load_fetch_targets(targets(tmp_path))[0]
    batch.mark_failed(raw, "drip", ft, "2026-11-19", "DATA_CHECK", "first row late")
    assert cli.main(["status", "--targets", str(targets(tmp_path)), "--raw-dir", str(raw)]) == 0
    out = capsys.readouterr().out
    assert "FAILED drip KR:005930 DATA_CHECK" in out and "drip: FAILED 1" in out


def test_bad_targets_file_is_an_error_not_a_crash(tmp_path, capsys):
    p = tmp_path / "t.csv"
    p.write_text("a,b\n", encoding="utf-8")
    assert cli.main(["status", "--targets", str(p), "--raw-dir", str(tmp_path)]) == 2


def test_options_exist_on_each_batch_command():
    for cmd in ("sweep", "drip", "yf"):
        with pytest.raises(SystemExit):
            cli.main([cmd, "--help"])
