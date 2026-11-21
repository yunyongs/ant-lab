"""A model-chosen key in a refused pointer never comes back through the server's answer."""

import pytest
from api_helpers import FakeTransport, fixture, settings
from step_helpers import note, reflection

from antlab_ai.runner.api_client import ApiClient, ApiProblem


def test_unknown_pointer_segments_become_a_placeholder():
    body = fixture("problem")
    body.update(
        status=422,
        code="MEMORY_LEAK",
        leaks=[
            {"pointer": "/reflection/Lehman2008", "code": "YEAR"},
            {"pointer": "/reflection/good/1#s2", "code": "YEAR"},
        ],
    )
    api = ApiClient(settings(), transport=FakeTransport((422, body)), sleep=lambda s: None)
    request = {
        "baseVersion": None,
        "outcome": "ACCEPTED",
        "reflection": reflection(),
        "playbook": note(),
        "playbookCarried": False,
        "calls": [],
    }
    with pytest.raises(ApiProblem) as stop:
        api.submit_season_memory(11, 7, request)
    assert stop.value.details == (
        ("/reflection/_", "YEAR"),
        ("/reflection/good/1#s2", "YEAR"),
    )
