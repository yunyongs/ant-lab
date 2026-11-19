"""Settings parsing, the usage contract and the owner-meter converter script."""

import importlib.util
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from gate_helpers import NOW

from antlab_ai.modelport import SubscriptionRefusal
from antlab_ai.modelport.subscription import (
    CommandUsageReader,
    SubscriptionSettings,
    SubscriptionSettingsError,
    UsageUnavailable,
)
from antlab_ai.modelport.subscription import settings as names
from antlab_ai.modelport.subscription.process import run_cli
from antlab_ai.modelport.subscription.usage import parse_usage

ON = {
    "ANTLAB_MODEL_SUBSCRIPTION_ENABLED": "true",
    "ANTLAB_MODEL_SUBSCRIPTION_ROUTES": "CLAUDE_CLI, CODEX_CLI",
    "ANTLAB_SUBSCRIPTION_OWNER_MEMBER_ID": "1",
    "ANTLAB_SUBSCRIPTION_USAGE_CMD": "python3 /meter/usage.py --json",
}


class LoggingEnv(dict):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.asked: list[str] = []

    def get(self, name, default=None):
        self.asked.append(name)
        return super().get(name, default)


# ---- settings ----------------------------------------------------------------------------


def test_everything_is_off_by_default():
    settings = SubscriptionSettings.from_env({})
    assert not settings.enabled and settings.routes == frozenset()
    assert not settings.is_on("CLAUDE_CLI") and not settings.is_on("CODEX_CLI")
    assert (settings.max_calls, settings.usage_max_age_s) == (15, 900)


def test_the_switch_and_a_listed_route_are_both_needed():
    on = SubscriptionSettings.from_env(ON)
    assert on.is_on("CLAUDE_CLI") and on.is_on("CODEX_CLI")
    only = SubscriptionSettings.from_env({**ON, "ANTLAB_MODEL_SUBSCRIPTION_ROUTES": "CODEX_CLI"})
    assert only.is_on("CODEX_CLI") and not only.is_on("CLAUDE_CLI")
    off = SubscriptionSettings.from_env({**ON, "ANTLAB_MODEL_SUBSCRIPTION_ENABLED": "false"})
    assert not off.is_on("CLAUDE_CLI")
    unlisted = SubscriptionSettings.from_env({**ON, "ANTLAB_MODEL_SUBSCRIPTION_ROUTES": ""})
    assert not unlisted.is_on("CLAUDE_CLI")


@pytest.mark.parametrize("value", ["TRUE", "True"])
def test_the_switch_is_case_insensitive_like_the_structured_output_switch(value):
    assert SubscriptionSettings.from_env({**ON, names.ENABLED: value}).enabled


@pytest.mark.parametrize("value", ["yes", "1", "on", "enabled", "truee"])
def test_a_bad_switch_value_is_a_startup_error(value):
    with pytest.raises(SubscriptionSettingsError, match="true or false"):
        SubscriptionSettings.from_env({names.ENABLED: value})


@pytest.mark.parametrize("value", ["GPT", "claude_cli", "CLAUDE_CLI,OPENROUTER"])
def test_an_unknown_route_name_is_a_startup_error(value):
    with pytest.raises(SubscriptionSettingsError, match="unknown route"):
        SubscriptionSettings.from_env({**ON, names.ROUTES: value})


@pytest.mark.parametrize(
    ("value", "ok"),
    [("1", True), ("15", True), ("60", True), ("0", False), ("61", False), ("-1", False),
     ("x", False), ("1.5", False), ("１５", False), (" 20 ", True)],
)  # fmt: skip
def test_the_cap_is_between_one_and_sixty(value, ok):
    if ok:
        assert SubscriptionSettings.from_env({**ON, names.MAX_CALLS: value}).max_calls == int(
            value.strip()
        )
    else:
        with pytest.raises(SubscriptionSettingsError, match=names.MAX_CALLS):
            SubscriptionSettings.from_env({**ON, names.MAX_CALLS: value})


@pytest.mark.parametrize("value", ["0", "86401", "x", "-5"])
def test_the_usage_age_is_bounded(value):
    with pytest.raises(SubscriptionSettingsError, match=names.USAGE_MAX_AGE):
        SubscriptionSettings.from_env({**ON, names.USAGE_MAX_AGE: value})


@pytest.mark.parametrize("missing", [names.OWNER_MEMBER_ID, names.USAGE_CMD])
def test_a_route_that_is_on_needs_the_owner_and_the_meter(missing):
    env = {k: v for k, v in ON.items() if k != missing}
    with pytest.raises(SubscriptionSettingsError, match=missing):
        SubscriptionSettings.from_env(env)


def test_the_owner_and_the_meter_are_not_needed_while_nothing_is_on():
    SubscriptionSettings.from_env({names.ENABLED: "true"})
    SubscriptionSettings.from_env({names.ROUTES: "CLAUDE_CLI"})


@pytest.mark.parametrize("value", ["0", "-1", "abc", "9223372036854775808"])
def test_the_owner_member_id_is_a_positive_int64(value):
    with pytest.raises(SubscriptionSettingsError, match=names.OWNER_MEMBER_ID):
        SubscriptionSettings.from_env({**ON, names.OWNER_MEMBER_ID: value})


def test_the_usage_command_is_split_into_an_argv_without_a_shell():
    settings = SubscriptionSettings.from_env({**ON, names.USAGE_CMD: "python3 '/a b/usage.py' -x"})
    assert settings.usage_cmd == ("python3", "/a b/usage.py", "-x")
    with pytest.raises(SubscriptionSettingsError):
        SubscriptionSettings.from_env({**ON, names.USAGE_CMD: "python3 'unterminated"})


def test_the_cli_work_root_is_absolute_outside_the_repository_and_outside_the_state_dir(tmp_path):
    ok = SubscriptionSettings.from_env({**ON, names.WORK_ROOT: str(tmp_path / "work")})
    assert ok.work_root == tmp_path / "work"
    assert SubscriptionSettings.from_env(ON).work_root.is_relative_to(Path.home())
    with pytest.raises(SubscriptionSettingsError, match="absolute"):
        SubscriptionSettings.from_env({**ON, names.WORK_ROOT: "relative/work"})
    repo = Path(__file__).resolve().parents[4]
    with pytest.raises(SubscriptionSettingsError, match="outside the repository"):
        SubscriptionSettings.from_env({**ON, names.WORK_ROOT: str(repo / "data" / "ai" / "work")})


def test_config_directories_are_passed_to_their_own_cli_only_and_must_be_absolute():
    settings = SubscriptionSettings.from_env(
        {**ON, names.CODEX_HOME: "/h/codex", names.CLAUDE_CONFIG_DIR: "/h/claude"}
    )
    assert settings.extra_env("CODEX_CLI") == {"CODEX_HOME": "/h/codex"}
    assert settings.extra_env("CLAUDE_CLI") == {"CLAUDE_CONFIG_DIR": "/h/claude"}
    assert SubscriptionSettings.from_env(ON).extra_env("CODEX_CLI") == {}
    with pytest.raises(SubscriptionSettingsError, match=names.CODEX_HOME):
        SubscriptionSettings.from_env({**ON, names.CODEX_HOME: "relative"})


def test_only_the_documented_names_are_ever_read_and_the_repr_shows_no_value():
    env = LoggingEnv({**ON, "ANTHROPIC_API_KEY": "sk-ant-SECRET", "OPENROUTER_API_KEY": "k"})
    settings = SubscriptionSettings.from_env(env)
    assert set(env.asked) <= set(names.SETTING_NAMES)
    text = repr(settings)
    assert "/meter" not in text and "SECRET" not in text and "usage.py" not in text


def test_the_error_texts_never_hold_a_value():
    with pytest.raises(SubscriptionSettingsError) as caught:
        SubscriptionSettings.from_env({names.ENABLED: "sk-ant-SECRET"})
    assert "SECRET" not in str(caught.value)


# ---- usage contract ----------------------------------------------------------------------


def document(**over):
    entry = {
        "weekly_used_percent": "41.0",
        "session_used_percent": "12.5",
        "as_of": "2026-11-19T11:59:00Z",
        "weekly_resets_at": "2026-11-23T00:00:00Z",
        "session_resets_at": "2026-11-19T15:00:00+09:00",
    }
    entry.update(over)
    return {"schema": "subscription-usage/1", "claude": entry, "codex": dict(entry)}


def parse(doc, route="CLAUDE_CLI", now=NOW, max_age=900):
    raw = doc if isinstance(doc, bytes) else json.dumps(doc).encode()
    return parse_usage(raw, route, now=now, max_age_s=max_age)


def test_a_clean_reading_parses_to_decimals_and_utc_times():
    snap = parse(document())
    assert (str(snap.weekly_used_pct), str(snap.session_used_pct)) == ("41.0", "12.5")
    assert snap.as_of == datetime(2026, 11, 19, 11, 59, tzinfo=UTC)
    assert snap.session_resets_at == datetime(2026, 11, 19, 6, 0, tzinfo=UTC)
    assert parse(document(), "CODEX_CLI").route == "CODEX_CLI"


def test_the_reset_times_are_optional():
    doc = document()
    del doc["claude"]["weekly_resets_at"]
    doc["claude"]["session_resets_at"] = None
    snap = parse(doc)
    assert snap.weekly_resets_at is None and snap.session_resets_at is None


@pytest.mark.parametrize("pct", ["-0.1", "100.1", "NaN", "Infinity", "", "abc", "1e400"])
def test_a_percentage_outside_zero_to_hundred_is_unknown(pct):
    with pytest.raises(UsageUnavailable):
        parse(document(weekly_used_percent=pct))
    with pytest.raises(UsageUnavailable):
        parse(document(session_used_percent=pct))


@pytest.mark.parametrize("pct", [41.0, 41, True, None, ["41"]])
def test_a_percentage_must_be_a_decimal_string_not_a_json_number(pct):
    with pytest.raises(UsageUnavailable):
        parse(document(weekly_used_percent=pct))


@pytest.mark.parametrize("edge", ["0", "100", "0.0", "99.99"])
def test_the_range_ends_are_valid(edge):
    assert str(parse(document(weekly_used_percent=edge)).weekly_used_pct) == edge


@pytest.mark.parametrize(
    "as_of", ["2026-11-19T11:44:59Z", "2026-11-19T12:00:06Z", "2026-11-19T11:59:00", "x", 5, None]
)
def test_a_stale_future_or_naive_time_is_unknown(as_of):
    with pytest.raises(UsageUnavailable):
        parse(document(as_of=as_of))


def test_the_age_boundary():
    assert parse(document(as_of="2026-11-19T11:45:00Z"))
    assert parse(document(as_of="2026-11-19T12:00:05Z"))  # tolerated clock skew
    with pytest.raises(UsageUnavailable):
        parse(document(as_of="2026-11-19T11:44:59Z"))
    assert parse(document(as_of="2026-11-19T11:44:59Z"), max_age=901)


@pytest.mark.parametrize(
    "raw",
    [b"", b"[]", b"null", b"not json", b"\xff\xfe", b'{"schema":"other/1","claude":{}}'],
)
def test_malformed_output_is_unknown(raw):
    with pytest.raises(UsageUnavailable):
        parse(raw)


def test_a_missing_route_or_unknown_route_is_unknown():
    doc = document()
    del doc["codex"]
    with pytest.raises(UsageUnavailable):
        parse(doc, "CODEX_CLI")
    with pytest.raises(UsageUnavailable):
        parse(doc, "OPENROUTER")


# ---- command reader ----------------------------------------------------------------------


def script(tmp_path, body: str) -> list[str]:
    path = tmp_path / "meter.py"
    path.write_text(body)
    return [sys.executable, str(path)]


def fresh_doc(now: datetime) -> str:
    return json.dumps(document(as_of=(now - timedelta(seconds=30)).isoformat()))


def test_the_command_reader_runs_the_argv_and_parses_stdout(tmp_path):
    argv = script(tmp_path, f"print({fresh_doc(NOW)!r})")
    snap = CommandUsageReader(argv, max_age_s=900, clock=lambda: NOW).read("CLAUDE_CLI")
    assert str(snap.weekly_used_pct) == "41.0"


@pytest.mark.parametrize(
    "body",
    [
        "import sys; sys.exit(1)",
        "print('{}')",
        "print('x' * 200000)",
        "import time; time.sleep(60)",
        "pass",
    ],
)
def test_the_command_reader_fails_closed(tmp_path, body):
    def quick(*args, **kwargs):
        return run_cli(*args, **{**kwargs, "deadline_s": 1, "kill_grace_s": 0.2})

    reader = CommandUsageReader(
        script(tmp_path, body), max_age_s=900, clock=lambda: NOW, runner=quick
    )
    with pytest.raises(UsageUnavailable):
        reader.read("CLAUDE_CLI")


def test_a_missing_command_is_unknown_not_a_crash(tmp_path):
    reader = CommandUsageReader([str(tmp_path / "nothing")], max_age_s=900, clock=lambda: NOW)
    with pytest.raises(UsageUnavailable):
        reader.read("CLAUDE_CLI")


def test_the_meter_gets_only_the_allowlisted_environment(tmp_path):
    marker = tmp_path / "env.json"
    argv = script(
        tmp_path,
        f"import json, os; json.dump(sorted(os.environ), open({str(marker)!r}, 'w'));"
        f"print({fresh_doc(NOW)!r})",
    )
    env = {
        "PATH": "/usr/bin",
        "HOME": "/h",
        "OPENROUTER_API_KEY": "k",
        "ANTLAB_AGENT_RUNNER_TOKEN": "t",
    }
    CommandUsageReader(argv, max_age_s=900, clock=lambda: NOW, source_env=env).read("CLAUDE_CLI")
    names_seen = set(json.loads(marker.read_text()))
    assert "OPENROUTER_API_KEY" not in names_seen and "ANTLAB_AGENT_RUNNER_TOKEN" not in names_seen
    assert {"PATH", "HOME"} <= names_seen


def test_an_empty_argv_is_refused():
    with pytest.raises(ValueError):
        CommandUsageReader([], max_age_s=900)


def test_the_unknown_reading_error_text_is_a_fixed_code():
    assert str(UsageUnavailable()) == "USAGE_UNKNOWN"
    assert SubscriptionRefusal("USAGE_UNKNOWN").code == "USAGE_UNKNOWN"


# ---- owner-meter converter ---------------------------------------------------------------

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "usage_from_orca.py"


@pytest.fixture(scope="module")
def converter():
    spec = importlib.util.spec_from_file_location("usage_from_orca", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def orca_sample(**claude_over):
    claude = {
        "status": "ok",
        "session": {"usedPercent": 12.5, "windowMinutes": 300, "resetsAt": 1795075200000},
        "weekly": {"usedPercent": 41, "windowMinutes": 10080, "resetsAt": None},
        "updatedAt": 1795071540000,
    }
    claude.update(claude_over)
    codex = {**claude, "status": "unavailable"}
    return {"result": {"rateLimits": {"claude": claude, "codex": codex}}}


def test_the_converter_output_is_accepted_by_the_reader_contract(converter):
    out = converter.convert(orca_sample())
    assert out["schema"] == "subscription-usage/1" and "codex" not in out
    now = datetime.fromtimestamp(1795071600, UTC)
    snap = parse_usage(json.dumps(out).encode(), "CLAUDE_CLI", now=now, max_age_s=900)
    assert (str(snap.weekly_used_pct), str(snap.session_used_pct)) == ("41", "12.5")
    assert snap.weekly_resets_at is None and snap.session_resets_at is not None


@pytest.mark.parametrize(
    "bad",
    [
        {"status": "error"},
        {"status": "ok", "weekly": None},
        {"status": "ok", "weekly": {"usedPercent": 140}},
        {"status": "ok", "session": {"usedPercent": "12"}},
        {"status": "ok", "session": {"usedPercent": True}},
        {"status": "ok", "updatedAt": "yesterday"},
    ],
)
def test_the_converter_omits_a_route_it_cannot_read(converter, bad):
    assert "claude" not in converter.convert(orca_sample(**bad))


@pytest.mark.parametrize("raw", [None, [], {}, {"result": None}, {"result": {"rateLimits": []}}])
def test_the_converter_survives_garbage_input(converter, raw):
    assert converter.convert(raw) == {"schema": "subscription-usage/1"}


def test_a_meter_entry_without_a_timestamp_is_unknown_never_stamped_fresh(converter):
    sample = orca_sample()
    del sample["result"]["rateLimits"]["claude"]["updatedAt"]
    assert "claude" not in converter.convert(sample)


def test_the_forbidden_environment_names_are_exactly_these():
    from antlab_ai.modelport.subscription.gate import FORBIDDEN_ENV

    assert set(FORBIDDEN_ENV) == {
        "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_OAUTH_TOKEN",
        "OPENAI_API_KEY", "OPENAI_BASE_URL", "CODEX_API_KEY", "CODEX_ACCESS_TOKEN",
        "OPENROUTER_API_KEY", "OLLAMA_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY",
        "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_ENDPOINT", "AWS_BEARER_TOKEN_BEDROCK",
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy",
    }  # fmt: skip
    assert len(FORBIDDEN_ENV) == 23


def test_the_container_and_ci_indicators_are_exactly_these():
    from antlab_ai.modelport.subscription.gate import CONTAINER_ENV, CONTAINER_MARKERS

    assert set(CONTAINER_ENV) == {
        "CI", "GITHUB_ACTIONS", "ANTLAB_IN_CONTAINER", "TF_BUILD", "GITLAB_CI", "JENKINS_URL",
        "BUILDKITE", "CIRCLECI", "TRAVIS", "TEAMCITY_VERSION", "CODEBUILD_BUILD_ID",
        "KUBERNETES_SERVICE_HOST", "container",
    }  # fmt: skip
    assert {str(m) for m in CONTAINER_MARKERS} == {"/.dockerenv", "/run/.containerenv"}


def test_a_meter_window_of_the_wrong_or_missing_length_is_unknown(converter):
    now = 1795071600000
    for field, minutes in (("session", 10080), ("weekly", 300), ("weekly", None), ("session", 299)):
        sample = orca_sample()
        window = sample["result"]["rateLimits"]["claude"][field]
        if minutes is None:
            del window["windowMinutes"]
        else:
            window["windowMinutes"] = minutes
        assert "claude" not in converter.convert(sample), (field, minutes)
    swapped = orca_sample(
        session={"usedPercent": 75, "windowMinutes": 10080},
        weekly={"usedPercent": 5, "windowMinutes": 300},
    )
    assert "claude" not in converter.convert(swapped)
    assert now
