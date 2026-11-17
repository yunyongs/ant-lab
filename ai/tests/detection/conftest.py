from pathlib import Path

import pytest
from sample_helpers import FIXTURES

from antlab_ai.detection.sample import load_sample


@pytest.fixture
def fixture_dir() -> Path:
    return FIXTURES


@pytest.fixture
def sample():
    return load_sample(FIXTURES)
