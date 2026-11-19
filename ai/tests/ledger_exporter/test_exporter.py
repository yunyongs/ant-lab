import os
import re
from decimal import Decimal

import pytest

from antlab_ai.ledger_exporter import exporter
from antlab_ai.ledger_exporter.exporter import Config, collect, exposition, render
from antlab_ai.openrouter import limiter

from .fixtures import (
    FETCH_HEADER,
    NOW,
    SENTINELS,
    attempt,
    fetch_ledger,
    ollama_ledger,
    openrouter_ledger,
    write_jsonl,
)

LABEL_KEYS = {"ledger", "caller_class", "model", "status", "direction", "name", "window", "source"}


@pytest.fixture
def config(tmp_path):
    return Config(
        openrouter_path=str(openrouter_ledger(tmp_path / "or.jsonl")),
        ollama_path=str(ollama_ledger(tmp_path / "ol.jsonl")),
        fetch_path=str(fetch_ledger(tmp_path / "fetch.csv")),
    )


def values(series):
    return {(n, tuple(sorted(lbl.items()))): v for n, lbl, v in series}


def get(series, metric, **labels):
    return values(series)[(metric, tuple(sorted(labels.items())))]


def test_openrouter_totals_equal_the_fixture(config):
    s = collect(config, NOW)
    assert get(s, "antlab_ledger_readable", ledger="openrouter") == 1
    assert (
        get(
            s,
            "antlab_openrouter_attempts_today",
            caller_class="product",
            model="openai/gpt-oss-20b:free",
        )
        == 2
    )
    assert get(s, "antlab_openrouter_attempts_today", caller_class="harness", model="a/b") == 1
    assert get(s, "antlab_openrouter_attempts_today", caller_class="product", model="OTHER") == 1
    assert get(s, "antlab_openrouter_attempts_today", caller_class="OTHER", model="a/b") == 1
    assert get(s, "antlab_openrouter_outcomes_today", status="OK") == 1
    assert get(s, "antlab_openrouter_outcomes_today", status="TIMEOUT") == 1
    assert get(s, "antlab_openrouter_outcomes_today", status="LIMITED") == 1
    assert get(s, "antlab_openrouter_outcomes_today", status="ERROR") == 0
    assert get(s, "antlab_openrouter_tokens_today", direction="in") == 100
    assert get(s, "antlab_openrouter_tokens_today", direction="out") == 50
    assert get(s, "antlab_openrouter_cost_usd_today") == Decimal("0.25")
    assert get(s, "antlab_openrouter_attempts_last_minute") == 2


def test_openrouter_caps_come_from_the_limiter(config):
    assert exporter.MINUTE_CAP == limiter.MINUTE_CAP
    assert exporter.HARNESS_DAILY_CAP == limiter.HARNESS_DAILY_CAP
    assert exporter.PRODUCT_DAILY_CAP == limiter.PRODUCT_DAILY_CAP
    s = collect(config, NOW)
    assert get(s, "antlab_openrouter_cap", name="minute") == 18
    assert get(s, "antlab_openrouter_cap", name="harness_daily") == 40
    assert get(s, "antlab_openrouter_cap", name="product_daily") == 10
    assert get(s, "antlab_openrouter_cap", name="account_daily") == 50


def test_ollama_total_and_today_against_the_stop(config):
    s = collect(config, NOW)
    assert get(s, "antlab_ollama_spend_usd", window="total") == Decimal("2.0")
    assert get(s, "antlab_ollama_spend_usd", window="today") == Decimal("0.75")
    assert get(s, "antlab_ollama_stop_usd") == Decimal(18)


def test_fetch_requests_today_by_source_against_caps(config):
    s = collect(config, NOW)
    assert get(s, "antlab_fetch_requests_today", source="KIS") == 320
    assert get(s, "antlab_fetch_requests_today", source="YF") == 50
    assert get(s, "antlab_fetch_daily_cap", source="KIS") == 600
    assert get(s, "antlab_fetch_daily_cap", source="YF") == 50


def test_caps_and_stop_come_from_the_environment(tmp_path):
    cfg = Config.from_env(
        {
            "ANTLAB_OBS_OPENROUTER_TIER": "1000",
            "ANTLAB_OBS_FETCH_CAP_KIS": "900",
            "ANTLAB_OBS_OLLAMA_STOP_USD": "12.5",
        }
    )
    assert cfg.tier == 1000 and cfg.fetch_caps == {"KIS": 900, "YF": 50}
    assert cfg.ollama_stop_usd == Decimal("12.5")
    with pytest.raises(ValueError):
        Config.from_env({"ANTLAB_OBS_OPENROUTER_TIER": "7"})


@pytest.mark.parametrize("ledger", ["openrouter", "ollama", "fetch"])
def test_unset_missing_directory_and_symlink_are_unreadable_not_a_crash(config, tmp_path, ledger):
    field = f"{ledger}_path"
    target = getattr(config, field)
    link = tmp_path / "link"
    link.symlink_to(target)
    for bad in ("", str(tmp_path / "missing"), str(tmp_path), str(link)):
        cfg = Config(**{**config.__dict__, field: bad})
        s = collect(cfg, NOW)
        assert get(s, "antlab_ledger_readable", ledger=ledger) == 0
        others = [x for x in LEDGER_NAMES if x != ledger]
        assert all(get(s, "antlab_ledger_readable", ledger=o) == 1 for o in others)
        assert not any(n.startswith(f"antlab_{PREFIX[ledger]}_") for n, _, _ in s)


LEDGER_NAMES = ["openrouter", "ollama", "fetch"]
PREFIX = {"openrouter": "openrouter", "ollama": "ollama", "fetch": "fetch"}


def test_unreadable_by_mode_is_not_a_crash(config):
    if os.geteuid() == 0:
        pytest.skip("root reads everything")
    os.chmod(config.fetch_path, 0)
    s = collect(config, NOW)
    assert get(s, "antlab_ledger_readable", ledger="fetch") == 0


@pytest.mark.parametrize(
    "text",
    [
        "not json\n",
        "[1]\n",
        '{"v":1,"phase":"bogus","ts":"2026-11-20T01:00:00.000Z"}\n',
        b"\xff\xfe\n",
    ],
)
def test_malformed_openrouter_ledger_is_unreadable(tmp_path, text):
    path = tmp_path / "or.jsonl"
    path.write_bytes(text if isinstance(text, bytes) else text.encode())
    s = collect(Config(openrouter_path=str(path)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="openrouter") == 0


def test_malformed_fetch_and_ollama_are_unreadable(tmp_path):
    fetch = tmp_path / "f.csv"
    fetch.write_text(FETCH_HEADER + "2026-11-20,KIS,1,abc,DONE,a,b\n")
    ollama = tmp_path / "o.jsonl"
    ollama.write_text('{"ts":"2026-11-20T01:00:00","usd":"-1"}\n')
    s = collect(Config(fetch_path=str(fetch), ollama_path=str(ollama)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="fetch") == 0
    assert get(s, "antlab_ledger_readable", ledger="ollama") == 0
    wrong_header = tmp_path / "h.csv"
    wrong_header.write_text("a,b\n")
    s = collect(Config(fetch_path=str(wrong_header)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="fetch") == 0


def test_half_written_last_line_is_ignored(tmp_path):
    path = tmp_path / "or.jsonl"
    write_jsonl(path, [attempt("a1", "2026-11-20T01:00:00.000Z")])
    with path.open("a") as handle:
        handle.write('{"v":1,"phase":"att')
    s = collect(Config(openrouter_path=str(path)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="openrouter") == 1
    assert (
        get(
            s,
            "antlab_openrouter_attempts_today",
            caller_class="product",
            model="openai/gpt-oss-20b:free",
        )
        == 1
    )


def test_empty_ledgers_are_readable_with_zero_totals(tmp_path):
    path = tmp_path / "empty"
    path.write_text("")
    s = collect(Config(openrouter_path=str(path), ollama_path=str(path)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="openrouter") == 1
    assert get(s, "antlab_ollama_spend_usd", window="total") == 0


def test_model_labels_are_capped_and_unknown_ids_map_to_other(tmp_path):
    rows = [
        attempt(f"c{i}", "2026-11-20T01:00:00.000Z", model=f"v/model-{i:02d}") for i in range(30)
    ]
    path = write_jsonl(tmp_path / "or.jsonl", rows)
    s = collect(Config(openrouter_path=str(path)), NOW)
    models = {lbl["model"] for n, lbl, _ in s if n == "antlab_openrouter_attempts_today"}
    assert len(models) <= exporter.MAX_MODELS + 1
    assert "OTHER" in models
    assert sum(v for n, _, v in s if n == "antlab_openrouter_attempts_today") == 30


def test_full_output_has_only_fixed_label_keys_and_no_row_text(config):
    text = exposition(config, NOW)
    for sentinel in SENTINELS:
        assert sentinel not in text
    for match in re.finditer(r"(\w+)=\"", text):
        assert match.group(1) in LABEL_KEYS
    for forbidden in ("purpose", "call_id", "run_id", "ticker", "generation", "provider"):
        assert forbidden not in text.lower()
    assert re.fullmatch(r"(?:[^\n]*\n)+", text)


def test_render_escapes_label_values():
    text = render([("antlab_x", {"model": 'a"b\\c\nd'}, 1)])
    assert text == '# TYPE antlab_x gauge\nantlab_x{model="a\\"b\\\\c\\nd"} 1\n'


def test_every_metric_family_is_contiguous_with_one_type_line(config):
    names = [
        line.split("{")[0].split(" ")[0]
        for line in exposition(config, NOW).splitlines()
        if not line.startswith("#")
    ]
    seen: list[str] = []
    for name in names:
        if not seen or seen[-1] != name:
            assert name not in seen, f"{name} is split"
            seen.append(name)
    assert exposition(config, NOW).count("# TYPE antlab_ledger_readable") == 1


def test_fetch_caps_equal_the_tool_defaults_and_hard_maxima():
    import ast
    from pathlib import Path

    tree = ast.parse((Path(__file__).parents[3] / "tools/fetch/batch.py").read_text())
    found = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name):
            if node.targets[0].id in ("DEFAULT_CAP", "HARD_MAX_CAP", "LEDGER_HEADER"):
                found[node.targets[0].id] = ast.literal_eval(node.value)
    assert exporter.DEFAULT_FETCH_CAP == found["DEFAULT_CAP"]
    assert exporter.HARD_MAX_FETCH_CAP == found["HARD_MAX_CAP"]
    assert exporter.LEDGER_HEADER == found["LEDGER_HEADER"]


def test_blank_cap_variables_use_the_exporter_defaults():
    cfg = Config.from_env({"ANTLAB_OBS_FETCH_CAP_KIS": "", "ANTLAB_OBS_FETCH_CAP_YF": ""})
    assert cfg.fetch_caps == exporter.DEFAULT_FETCH_CAP


@pytest.mark.parametrize(
    "env",
    [
        {"ANTLAB_OBS_FETCH_CAP_KIS": "0"},
        {"ANTLAB_OBS_FETCH_CAP_KIS": "1501"},
        {"ANTLAB_OBS_FETCH_CAP_YF": "51"},
        {"ANTLAB_OBS_FETCH_CAP_YF": "x"},
        {"ANTLAB_OBS_OLLAMA_STOP_USD": "x"},
        {"ANTLAB_OBS_OLLAMA_STOP_USD": "0"},
        {"ANTLAB_OBS_OLLAMA_STOP_USD": "NaN"},
    ],
)
def test_invalid_settings_fail_startup_with_value_error(env):
    with pytest.raises(ValueError):
        Config.from_env(env)


def test_oversize_ledger_is_unreadable_and_streaming_keeps_totals(tmp_path, monkeypatch):
    path = write_jsonl(tmp_path / "or.jsonl", [attempt("a1", "2026-11-20T01:00:00.000Z")])
    monkeypatch.setattr(exporter, "MAX_BYTES", 10)
    s = collect(Config(openrouter_path=str(path)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="openrouter") == 0
    assert exporter.MAX_BYTES == 10  # patched; the shipped limit is checked below
    monkeypatch.undo()
    assert exporter.MAX_BYTES <= 16 * 1024 * 1024


def test_quoted_csv_cells_are_parsed_like_the_writer(tmp_path):
    path = tmp_path / "f.csv"
    path.write_text(FETCH_HEADER + '2026-11-20,KIS,1,5,DONE,"KR:a,b",KR:c\n')
    s = collect(Config(fetch_path=str(path)), NOW)
    assert get(s, "antlab_ledger_readable", ledger="fetch") == 1
    assert get(s, "antlab_fetch_requests_today", source="KIS") == 5


def test_dashboard_allowlist_names_equal_the_exported_families(config):
    from pathlib import Path

    listed = {
        line.split("#")[0].strip()
        for line in (
            Path(__file__).parents[3] / "src/test/resources/monitoring/metric-names-ledger.txt"
        )
        .read_text()
        .splitlines()
        if line.strip() and not line.startswith("#")
    }
    families = {n for n, _, _ in collect(config, NOW)}
    assert families == listed
