"""공통 옵션(--env-file, --asset-csv, --rps)은 하위 명령 앞·뒤 어디에 써도 같은 결과여야 한다."""
import argparse

import pytest

import fetch_p0_08 as f

SUBS = ["check-env", "probe", "fetch", "norm", "compare"]


def _parse(argv, monkeypatch):
    seen = {}

    def grab(args):
        seen["a"] = args
        return 0

    for name in ("cmd_check_env", "cmd_probe", "cmd_fetch", "cmd_norm", "cmd_compare"):
        monkeypatch.setattr(f, name, grab)
    rc = f.main(argv)
    return rc, seen["a"]


@pytest.mark.parametrize("sub", SUBS)
def test_options_after_subcommand_same_as_before(sub, monkeypatch):
    opts = ["--env-file", "E.env", "--asset-csv", "A.csv", "--rps", "2"]
    rc1, before = _parse(opts + [sub], monkeypatch)
    rc2, after = _parse([sub] + opts, monkeypatch)
    assert rc1 == rc2 == 0
    assert vars(before) == vars(after)
    assert (after.env_file, after.asset_csv, after.rps) == ("E.env", "A.csv", 2)


@pytest.mark.parametrize("sub", SUBS)
def test_defaults_survive_when_not_given(sub, monkeypatch):
    _, a = _parse([sub], monkeypatch)
    assert a.env_file.endswith(".env") and a.asset_csv.endswith("asset.csv") and a.rps is None


def test_top_level_value_not_overwritten_by_subparser_default(monkeypatch):
    _, a = _parse(["--rps", "3", "probe"], monkeypatch)
    assert a.rps == 3


def test_check_env_env_file_after_subcommand_rc0(tmp_path, capsys):
    env = tmp_path / ".env"
    env.write_text("KIS_APP_KEY=fakekey\n", encoding="utf-8")
    assert f.main(["check-env", "--env-file", str(env)]) == 0
    out = capsys.readouterr().out
    assert "있음" in out and "fakekey" not in out


def test_help_describes_options(capsys):
    with pytest.raises(SystemExit):
        f.main(["probe", "--help"])
    out = capsys.readouterr().out
    assert "--env-file ENV_FILE" in out and "경로" in out
