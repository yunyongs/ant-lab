import os

import pytest

from antlab_ai.detection.plan import build_units
from antlab_ai.detection.sample import SAMPLE_DIR_ENV, default_sample_dir, load_sample


def test_real_sample_shape_only():
    directory = default_sample_dir()
    if not os.environ.get(SAMPLE_DIR_ENV) and not (directory / "asset_month_return.csv").exists():
        pytest.skip(f"{SAMPLE_DIR_ENV} is not set and the git-ignored sample CSVs are absent")
    sample = load_sample(directory)
    assert [end - start for start, end in sample.segments] == [64, 36]
    assert len(build_units(sample)) == 280
