import pytest

from antlab_ai.host_exporter import sources
from antlab_ai.host_exporter.sources import SourceError

from .fixtures import DOCKER_PS, DOCKER_STATS, WORKTREE_LIST


def test_up_seconds_from_docker_status_words():
    f = sources.parse_up_seconds
    assert f("Up 3 hours (healthy)") == 3 * 3600
    assert f("Up About an hour") == 3600
    assert f("Up 45 seconds") == 45
    assert f("Up 2 days") == 2 * 86400
    assert f("Up Less than a second") == 0
    assert f("Exited (0) 2 days ago") == 0


def test_sizes_use_binary_and_decimal_units():
    assert sources.parse_size("1KiB") == 1024
    assert sources.parse_size("1kB") == 1000
    assert sources.parse_size("243.6MiB") == int(243.6 * 1024**2)
    with pytest.raises(SourceError):
        sources.parse_size("lots")


def test_containers_are_summarized_per_service_with_one_leftover_bucket():
    containers = sources.parse_docker_ps(DOCKER_PS)
    stats = sources.parse_docker_stats(DOCKER_STATS)
    services, testcontainers = sources.summarize_containers(containers, stats, "antlab")
    assert testcontainers == 1
    assert set(services) == {"db", "grafana", "api", "other_leftover_running"}
    assert services["db"].running == 1 and services["db"].up_seconds == 3 * 3600
    assert services["db"].cpu_percent == 1.5
    assert services["grafana"].mem_limit_bytes == 512 * 1024**2
    assert services["api"].running == 0 and services["api"].stopped == 1
    leftover = services["other_leftover_running"]
    assert (
        leftover.running == 2
    )  # the testcontainer and the stray container; exited strays are ignored
    assert leftover.stopped == 0


def test_without_stats_the_state_is_still_known():
    services, _ = sources.summarize_containers(sources.parse_docker_ps(DOCKER_PS), None, "antlab")
    assert services["db"].running == 1 and services["db"].mem_bytes == 0


def test_a_service_label_outside_the_safe_pattern_goes_to_the_bucket_only_if_running():
    line = (
        '{"Names":"x","State":"running","Status":"Up 1 hour",'
        '"Labels":"com.docker.compose.project=antlab,com.docker.compose.service=Bad Name"}'
    )
    services, _ = sources.summarize_containers(sources.parse_docker_ps(line), None, "antlab")
    assert services["other_leftover_running"].running == 1


def test_broken_docker_output_is_a_source_error():
    with pytest.raises(SourceError):
        sources.parse_docker_ps("not json")
    with pytest.raises(SourceError):
        sources.parse_docker_stats('{"Name":"a"}')


def test_worktree_list_porcelain():
    listed = sources.parse_worktree_list(WORKTREE_LIST)
    assert [w.branch for w in listed] == [
        "main",
        "feat/1-old",
        "feat/2-fresh",
        "feat/3-wip",
        "feat/4-older",
        "",
    ]
    assert listed[1].head == "bbbb111"


def test_git_helpers():
    assert sources.parse_branch_names("main\nfeat/1-old\n\n") == {"main", "feat/1-old"}
    assert sources.parse_status_count(" M a\n?? b\n") == 2
    assert sources.parse_status_count("") == 0
    assert sources.parse_commit_time("1700000000\n") == 1700000000
    with pytest.raises(SourceError):
        sources.parse_commit_time("")
    assert sources.parse_du("12\t/a\n34\t/b\nbad\n") == {"/a": 12 * 1024, "/b": 34 * 1024}


def test_slot_counts_tell_held_from_stale(tmp_path):
    for name, pid in (("1", "111"), ("2", "222"), ("3", "x")):
        (tmp_path / name).mkdir()
        (tmp_path / name / "pid").write_text(pid + "\n")
    (tmp_path / "4").mkdir()
    (tmp_path / "note.txt").write_text("not a slot")
    assert sources.slot_counts(str(tmp_path), lambda pid: pid == 111) == (1, 3)
    with pytest.raises(SourceError):
        sources.slot_counts(str(tmp_path / "missing"), lambda pid: True)
