import pytest
from sub_fakes import FakeClis, RecordingGate


@pytest.fixture
def fakes(tmp_path):
    return FakeClis(tmp_path)


@pytest.fixture
def gate():
    return RecordingGate()
