"""CliConfig rejects anything but an absolute binary, short printable text and config-dir names."""

import pytest

from antlab_ai.modelport.subscription import CliConfig


def test_a_valid_config(tmp_path):
    config = CliConfig("/bin/x", "1.2.3", tmp_path, extra_env={"CODEX_HOME": "/h"})
    assert "/bin/x" in repr(config) and "/h" not in repr(config)


@pytest.mark.parametrize(
    "extra", [{"OPENROUTER_API_KEY": "x"}, {"PATH": "/x"}, {"CODEX_HOME": "/h", "HOME": "/x"}]
)
def test_extra_env_may_only_hold_the_config_directory_names(tmp_path, extra):
    with pytest.raises(ValueError):
        CliConfig("/bin/x", "1", tmp_path, extra_env=extra)


@pytest.mark.parametrize("binary", ["claude", "./claude", ""])
def test_the_binary_must_be_absolute(tmp_path, binary):
    with pytest.raises(ValueError):
        CliConfig(binary, "1", tmp_path)


@pytest.mark.parametrize("version", ["", "has space", "x" * 81, "a\nb"])
def test_the_version_must_be_short_printable_text(tmp_path, version):
    with pytest.raises(ValueError):
        CliConfig("/bin/x", version, tmp_path)
