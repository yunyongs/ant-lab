import logging
from pathlib import Path

import pytest

from common import SecretFilter, env_status, load_config, mask, plain, dec, read_env
from tests.fakes import FAKE_KEY, FAKE_SECRET, FAKE_TOKEN


def test_mask_known_and_shaped():
    t = f"appkey={FAKE_KEY}&x=1 appsecret: {FAKE_SECRET} Authorization: Bearer {FAKE_TOKEN} \"access_token\":\"{FAKE_TOKEN}\""
    out = mask(t, [FAKE_KEY, FAKE_SECRET])
    for s in (FAKE_KEY, FAKE_SECRET, FAKE_TOKEN):
        assert s not in out
    assert "****" in out


def test_mask_unknown_value_by_shape():
    out = mask("header appsecret=ZZZZunknownSecretValue1234 failed")
    assert "ZZZZunknownSecretValue1234" not in out


def test_log_masking(caplog):
    log = logging.getLogger("t_mask")
    log.addFilter(SecretFilter([FAKE_KEY]))
    caplog.set_level(logging.INFO)
    with caplog.at_level(logging.INFO, logger="t_mask"):
        log.info("call with %s and appsecret=%s", FAKE_KEY, FAKE_SECRET)
    text = caplog.text
    assert FAKE_KEY not in text and FAKE_SECRET not in text


def test_config_repr_has_no_secret(tmp_path):
    c = load_config({"KIS_APP_KEY": FAKE_KEY, "KIS_APP_SECRET": FAKE_SECRET})
    assert FAKE_KEY not in repr(c) and FAKE_SECRET not in repr(c)
    assert c.rps == 4


def test_config_missing_and_bad_env():
    with pytest.raises(SystemExit) as e:
        load_config({})
    assert "KIS_APP_KEY" in str(e.value)
    with pytest.raises(SystemExit):
        load_config({"KIS_APP_KEY": "a", "KIS_APP_SECRET": "b", "KIS_ENV": "prod"})


def test_env_status_shows_names_only(tmp_path):
    f = tmp_path / ".env"
    f.write_text(f"KIS_APP_KEY={FAKE_KEY}\n")
    st = env_status(read_env(f))
    assert st["KIS_APP_KEY"] == "있음" and st["KIS_APP_SECRET"] == "없음"
    assert FAKE_KEY not in str(st)
    assert read_env(tmp_path / "none") == {}


def test_decimal_helpers_no_float():
    assert plain(dec("123.4500")) == "123.4500"
    assert plain(dec("1E+2")) == "100"


def test_env_var_only_real_allowed():
    ok = {"KIS_APP_KEY": "a", "KIS_APP_SECRET": "b"}
    assert load_config({**ok, "KIS_ENV": "real"}).rps == 4
    for bad in ("vt" + "s", "prod"):
        with pytest.raises(SystemExit):
            load_config({**ok, "KIS_ENV": bad})


def test_cache_dir_resolved_under_repo_root(tmp_path, monkeypatch):
    from common import REPO_ROOT, DEFAULT_TOKEN_CACHE
    monkeypatch.chdir(tmp_path)
    ok = {"KIS_APP_KEY": "a", "KIS_APP_SECRET": "b"}
    assert load_config(ok).token_cache_dir == DEFAULT_TOKEN_CACHE
    assert load_config({**ok, "KIS_TOKEN_CACHE_DIR": "data/cache/other"}).token_cache_dir == REPO_ROOT / "data/cache/other"
    for outside in ("../elsewhere", str(tmp_path / "x"), "/etc"):
        with pytest.raises(SystemExit):
            load_config({**ok, "KIS_TOKEN_CACHE_DIR": outside})


def test_defaults_are_absolute_under_repo_root(tmp_path, monkeypatch):
    import fetch_p0_08 as f
    from common import REPO_ROOT
    monkeypatch.chdir(tmp_path)
    assert f.RAW.is_absolute() and REPO_ROOT in f.RAW.parents
    assert REPO_ROOT / "tools" / "fetch" == Path(f.__file__).resolve().parent


def test_log_exception_traceback_masked(caplog):
    log = logging.getLogger("t_exc")
    log.addFilter(SecretFilter([FAKE_SECRET]))
    with caplog.at_level(logging.INFO, logger="t_exc"):
        try:
            raise ValueError(f"boom {FAKE_SECRET} token=ZZZZshapedvalue99")
        except ValueError:
            log.exception("failed")
    assert FAKE_SECRET not in caplog.text and "ZZZZshapedvalue99" not in caplog.text and "boom" in caplog.text


def test_mask_new_shapes():
    for t in ("app-key: SHAPEDabc123", "token=SHAPEDabc123", "appkey%3DSHAPEDabc123", "secret=SHAPEDabc123"):
        assert "SHAPEDabc123" not in mask(t), t
