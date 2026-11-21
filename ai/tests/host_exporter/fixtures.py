from antlab_ai.host_exporter.processes import Roots

ROOTS = Roots(
    repo="/Users/dev/repos/ant-lab",
    worktrees="/Users/dev/orca/workspaces/ant-lab",
    job_tmp="/Users/dev/jobs",
)
WT = ROOTS.worktrees
SECRET = "sk-or-SECRET-TOKEN-123"

PS = f"""\
  101  2-03:11:22  97.3  812345 {WT}/old1/ai/.venv/bin/python {WT}/old1/ai/.venv/bin/pytest -q --token={SECRET} tests
  102  2-03:11:20  96.9  700000 {WT}/old1/ai/.venv/bin/python {WT}/old1/ai/.venv/bin/pytest -q tests
  103     12:30  88.0  400000 {WT}/new1/ai/.venv/bin/python -m pytest -q tests
  200  05:12:10   0.0   90000 /usr/lib/jvm/bin/java -Xmx512m -cp /g/gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14
  201     40:00   0.2   50000 /usr/lib/jvm/bin/java -cp x worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'
  300     10:00  12.0   30000 /opt/homebrew/bin/python3 /Users/dev/other/script.py --key={SECRET}
  301   3-00:00  99.0   30000 /Users/dev/repos/other/.venv/bin/pytest -q
  302   3-00:00  99.0   30000 /Users/dev/repos/ant-lab-old/.venv/bin/pytest -q
  400  03:00:00   0.0  200000 /usr/lib/jvm/bin/java -jar {WT}/mvp19/build/libs/app.jar
  401  03:00:00   0.5   60000 /opt/homebrew/bin/node {WT}/web1/web/node_modules/.bin/vite
  402  03:00:00   0.1   60000 {WT}/eng1/engine/.venv/bin/python -m uvicorn antlab_engine.app:app
  403  01:00:00   0.0   40000 /opt/homebrew/bin/python3.13 -m antlab_ai.runner --repo {WT}/ai1
  404  03:00:00   0.0   40000 /usr/local/bin/uv run --frozen python -m antlab_ai.runner {WT}/ai1/ai
  405  02:30:00   0.0   40000 /usr/local/pgsql/bin/postgres -D /Users/dev/jobs/abc/tmp/pgdata
  406  02:30:00   0.0   40000 /Applications/Code.app/helper --folder {ROOTS.repo}
  407     12:00   0.0    5000 /bin/zsh -c cd {WT}/old1/ai && nice -n 15 uv run --frozen pytest -q tests
  408     12:00   0.0    5000 tail -f {WT}/x/ai/pytest.log
  409   3-00:00   0.0   90000 /opt/homebrew/bin/node /opt/mcp/server.js --output-dir {ROOTS.repo}/.playwright-mcp
  410   3-00:00   0.0    5000 /bin/zsh -c cd {WT}/eng1/engine && uv run uvicorn antlab_engine.app:app
  500     00:01   0.0    1000 python3 -m antlab_ai.host_exporter
  garbage line
"""

DOCKER_PS = "\n".join(
    [
        '{"Names":"antlab-db-1","State":"running","Status":"Up 3 hours (healthy)",'
        '"Labels":"com.docker.compose.project=antlab,com.docker.compose.service=db"}',
        '{"Names":"antlab-grafana-1","State":"running","Status":"Up About an hour",'
        '"Labels":"com.docker.compose.project=antlab,com.docker.compose.service=grafana"}',
        '{"Names":"antlab-api-1","State":"exited","Status":"Exited (0) 2 days ago",'
        '"Labels":"com.docker.compose.project=antlab,com.docker.compose.service=api"}',
        '{"Names":"tc-pg","State":"running","Status":"Up 5 minutes",'
        '"Labels":"org.testcontainers=true,org.testcontainers.sessionId=x"}',
        '{"Names":"other-old","State":"exited","Status":"Exited (0) 1 day ago","Labels":""}',
        '{"Names":"other-up","State":"running","Status":"Up 2 days","Labels":""}',
    ]
)
DOCKER_STATS = "\n".join(
    [
        '{"Name":"antlab-db-1","CPUPerc":"1.50%","MemUsage":"120.5MiB / 7.653GiB"}',
        '{"Name":"antlab-grafana-1","CPUPerc":"0.20%","MemUsage":"243.6MiB / 512MiB"}',
        '{"Name":"tc-pg","CPUPerc":"0.00%","MemUsage":"30MiB / 7.653GiB"}',
        '{"Name":"other-up","CPUPerc":"2.00%","MemUsage":"1GiB / 7.653GiB"}',
    ]
)
WORKTREE_LIST = f"""\
worktree {ROOTS.repo}
HEAD aaaa000
branch refs/heads/main

worktree {WT}/old1
HEAD bbbb111
branch refs/heads/feat/1-old

worktree {WT}/fresh
HEAD aaaa000
branch refs/heads/feat/2-fresh

worktree {WT}/wip
HEAD cccc222
branch refs/heads/feat/3-wip

worktree {WT}/older
HEAD aaaa111
branch refs/heads/feat/4-older

worktree /elsewhere/detached
HEAD dddd333
detached
"""
