"""AG-05 E: the memory operations of the client, their query, their errors and the fixed details of a 422."""

import pytest
from api_helpers import BASE, FakeTransport, Sleeps, fixture, settings
from step_helpers import note, reflection

from antlab_ai.runner.api_client import ApiClient, ApiProblem, ApiUnavailable
from antlab_ai.runner.contract import ContractError

PREFIX = f"{BASE}/internal/agent-runner/v1/agents"


def client(*script):
    transport = FakeTransport(*script)
    sleeps = Sleeps()
    return ApiClient(settings(), transport=transport, sleep=sleeps), transport, sleeps


def memory_dto():
    return {"version": 1, "playbook": ["Rule."], "reflections": [reflection()]}


def test_the_read_operations_use_their_paths_and_check_the_answers():
    api, transport, _ = client(
        (200, {"latestVersion": 2, "pendingSeasonIds": [7], "heldSeasonCount": 0}),
        (
            200,
            {"memory": memory_dto(), "playbookCarried": False, "createdAt": "2026-11-20T00:00:00Z"},
        ),
        (200, fixture("reflection_view")),
    )
    assert api.get_memory(11)["latestVersion"] == 2
    assert api.get_memory_version(11, 1)["playbookCarried"] is False
    assert api.get_reflection_view(11, 7)["pathLength"] == 3
    assert [c["url"] for c in transport.calls] == [
        f"{PREFIX}/11/memory",
        f"{PREFIX}/11/memory/1",
        f"{PREFIX}/11/seasons/7/reflection-view",
    ]
    assert all(c["method"] == "GET" and c["body"] is None for c in transport.calls)


def test_an_answer_that_breaks_the_contract_is_refused():
    api, _, _ = client((200, {"latestVersion": 0, "pendingSeasonIds": [], "heldSeasonCount": 0}))
    with pytest.raises(ContractError):
        api.get_memory(11)


def test_the_comparison_carries_both_versions_in_the_query():
    api, transport, _ = client(
        (
            200,
            {
                "versionA": 0,
                "versionB": 3,
                "groups": [],
                "summary": {"groups": 0, "bWins": 0, "anecdotal": True, "meanDiff": None},
            },
        )
    )
    api.get_memory_comparison(11, 0, 3)
    assert transport.calls[0]["url"] == f"{PREFIX}/11/memory/comparison?versionA=0&versionB=3"


@pytest.mark.parametrize("a_b", [(-1, 1), (1, -2), (True, 1), ("1", 1)])
def test_the_comparison_refuses_a_bad_version_before_sending(a_b):
    api, transport, _ = client()
    with pytest.raises(ValueError):
        api.get_memory_comparison(11, *a_b)
    assert transport.calls == []


def test_a_version_below_one_is_refused_before_sending():
    api, transport, _ = client()
    with pytest.raises(ValueError):
        api.get_memory_version(11, 0)
    assert transport.calls == []


def accepted_body():
    return {
        "baseVersion": None,
        "outcome": "ACCEPTED",
        "reflection": reflection(),
        "playbook": note(),
        "playbookCarried": False,
        "calls": [],
    }


def test_submit_is_posted_once_and_never_retried_after_a_server_error():
    api, transport, sleeps = client(
        (503, fixture("problem")), (201, {"outcome": "ACCEPTED", "version": 1})
    )
    with pytest.raises(ApiProblem) as stop:
        api.submit_season_memory(11, 7, accepted_body())
    assert stop.value.status == 503 and len(transport.calls) == 1 and sleeps.delays == []
    assert transport.calls[0]["url"] == f"{PREFIX}/11/seasons/7/memory"
    assert transport.calls[0]["method"] == "POST"


def test_submit_does_not_retry_a_lost_connection():
    api, transport, _ = client(OSError("lost"))
    with pytest.raises(ApiUnavailable):
        api.submit_season_memory(11, 7, accepted_body())
    assert len(transport.calls) == 1


def test_submit_checks_the_body_against_the_contract_before_sending():
    api, transport, _ = client()
    with pytest.raises(ContractError):
        api.submit_season_memory(11, 7, {**accepted_body(), "outcome": "MAYBE"})
    assert transport.calls == []


def test_the_reflection_view_is_retried_on_a_server_error():
    api, transport, sleeps = client((503, fixture("problem")), (200, fixture("reflection_view")))
    api.get_reflection_view(11, 7)
    assert len(transport.calls) == 2 and sleeps.delays == [1]


def problem(status, code, **extra):
    body = fixture("problem")
    body.update(status=status, code=code, **extra)
    return status, body


def test_a_leak_refusal_keeps_pointers_and_codes_only():
    api, _, _ = client(
        problem(
            422,
            "MEMORY_LEAK",
            leaks=[
                {"pointer": "/reflection/good/1#s2", "code": "YEAR"},
                {"pointer": "/playbook", "code": "OVERSIZE", "text": "2008년 9월"},
            ],
        )
    )
    with pytest.raises(ApiProblem) as stop:
        api.submit_season_memory(11, 7, accepted_body())
    assert (stop.value.status, stop.value.code) == (422, "MEMORY_LEAK")
    assert stop.value.details == (("/reflection/good/1#s2", "YEAR"), ("/playbook", "OVERSIZE"))
    assert "2008" not in repr(stop.value.details) + str(stop.value)


def test_a_shape_refusal_keeps_pointer_and_keyword():
    api, _, _ = client(
        problem(
            422,
            "MEMORY_SCHEMA_INVALID",
            violations=[{"pointer": "/reflection/good", "keyword": "minItems"}],
        )
    )
    with pytest.raises(ApiProblem) as stop:
        api.submit_season_memory(11, 7, accepted_body())
    assert stop.value.details == (("/reflection/good", "minItems"),)


@pytest.mark.parametrize(
    "leak",
    [
        {"pointer": "/reflection/리먼", "code": "YEAR"},
        {"pointer": "/a b", "code": "YEAR"},
        {"pointer": "/reflection/good/1", "code": "2008년"},
        {"pointer": "/reflection/good/1", "code": "NOT A CODE"},
        {"pointer": 5, "code": "YEAR"},
        "text",
        {"pointer": "/reflection/good/1"},
    ],
)
def test_a_detail_that_is_not_a_fixed_pointer_and_code_is_dropped(leak):
    api, _, _ = client(problem(422, "MEMORY_LEAK", leaks=[leak]))
    with pytest.raises(ApiProblem) as stop:
        api.submit_season_memory(11, 7, accepted_body())
    assert stop.value.details == ()


def test_a_refusal_without_details_has_none():
    api, _, _ = client((409, fixture("problem")))
    with pytest.raises(ApiProblem) as stop:
        api.get_reflection_view(11, 7)
    assert stop.value.details == ()


def test_create_league_sends_the_same_seed_root_only_when_given():
    league = {
        "id": 6,
        "name": "Twin",
        "status": "OPEN",
        "pathLength": 12,
        "maxEntries": 8,
        "entries": [],
    }
    api, transport, _ = client((201, league), (201, league))
    api.create_league("Twin", same_seed_as=5)
    api.create_league("Twin")
    assert transport.calls[0]["body"] == {"name": "Twin", "sameSeedAs": 5}
    assert transport.calls[1]["body"] == {"name": "Twin"}
    with pytest.raises(ValueError):
        api.create_league("Twin", same_seed_as=0)
