from antlab_ai.host_exporter.processes import KINDS, classify, group_processes, parse_ps

from .fixtures import PS, ROOTS, SECRET


def kinds_by_pid():
    return {p.pid: classify(p.command, ROOTS) for p in parse_ps(PS)}


def test_parse_ps_reads_age_cpu_rss_and_skips_noise():
    procs = {p.pid: p for p in parse_ps(PS)}
    assert procs[101].age_seconds == 2 * 86400 + 3 * 3600 + 11 * 60 + 22
    assert procs[103].age_seconds == 12 * 60 + 30
    assert procs[101].cpu_percent == 97.3
    assert procs[101].rss_bytes == 812345 * 1024
    assert 0 not in procs and len(procs) == 20


def test_classification_covers_each_kind():
    got = kinds_by_pid()
    assert got[101] == ("pytest", "old1")
    assert got[103] == ("pytest", "new1")
    assert got[200] == ("gradle_daemon", "shared")
    assert got[201] == ("gradle_worker", "shared")
    assert got[400] == ("java_api_local", "mvp19")
    assert got[401] == ("node_web", "web1")
    assert got[402] == ("engine_uvicorn", "eng1")
    assert got[403] == ("uv_or_python_ai", "ai1")
    assert got[404] == ("uv_or_python_ai", "ai1")
    assert got[405] == ("postgres_temp", "jobtmp")
    assert got[406] == ("other_antlab", "main")
    assert got[407] == ("other_antlab", "old1")  # shell wrapper around a real pytest
    assert got[408] == ("other_antlab", "x")  # a log tail, not a test run
    assert got[409] == ("other_antlab", "main")  # a node CLI that only mentions the repo
    assert got[410] == ("other_antlab", "eng1")  # shell wrapper around uvicorn
    assert {k for k, _ in (v for v in got.values() if v)} <= set(KINDS)


def test_other_python_and_lookalike_paths_do_not_match():
    got = kinds_by_pid()
    assert got[300] is None
    assert got[301] is None
    assert got[302] is None  # ant-lab-old is not the repo root ant-lab


def test_the_exporter_itself_is_never_counted():
    assert kinds_by_pid()[500] is None
    own = [p for p in parse_ps(PS) if p.pid == 406]
    groups = group_processes(own, ROOTS, own_pid=406)
    assert all(g.count == 0 for g in groups.values())


def test_stale_and_idle_follow_the_thresholds():
    groups = group_processes(parse_ps(PS), ROOTS, own_pid=1)
    assert groups[("pytest", "old1")].count == 2  # the wrapper does not count a third time
    assert groups[("pytest", "old1")].stale == 2
    assert groups[("pytest", "old1")].idle == 0
    assert groups[("pytest", "new1")].stale == 0  # busy but young
    assert groups[("gradle_daemon", "shared")].idle == 1
    assert groups[("gradle_worker", "shared")].idle == 0  # 40 min is under the hour
    assert groups[("other_antlab", "main")].idle == 0  # the catch-all is never idle or stale


def test_kinds_without_a_process_get_a_zero_row():
    groups = group_processes([], ROOTS, own_pid=1)
    assert set(groups) == {(k, "none") for k in KINDS}
    assert all(g.count == 0 for g in groups.values())


def test_secret_in_a_command_line_is_never_kept():
    groups = group_processes(parse_ps(PS), ROOTS, own_pid=1)
    assert SECRET not in repr(groups)
