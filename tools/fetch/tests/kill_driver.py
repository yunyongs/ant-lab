"""Subprocess driver for the kill tests: runs a drip batch and dies (os._exit) or is terminated at the 5th page request."""
import os
import signal
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import batch  # noqa: E402
from tests.batch_helpers import kr  # noqa: E402
from tests.fake_kis import FakeKis, clock_at  # noqa: E402
from tests.fakes import FakeSession, cfg  # noqa: E402

repo, mode = Path(sys.argv[1]), sys.argv[2]
fake = FakeKis()
n = {"gets": 0}


def handler(method, url, kw):
    if method == "get":
        n["gets"] += 1
        if n["gets"] == 5:
            if mode == "exit":
                os._exit(9)
            os.kill(os.getpid(), signal.SIGTERM)
    return fake.handler(method, url, kw)


batch.run_kis_batch("drip", [kr("000001", 1, "2019-01")], cfg=cfg(repo.parent, rps=1), session=FakeSession(handler),
                    raw_dir=repo / "data" / "raw" / "mvp-04", repo_root=repo, clock=clock_at())
