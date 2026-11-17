# Engine / 엔진

The engine is an internal, deterministic FastAPI service (package `antlab_engine`). Its HTTP contract is `docs/api/engine.yaml`; every route except `GET /healthz` needs the `X-Antlab-Engine-Token` header.
엔진은 내부 전용 결정적 FastAPI 서비스(패키지 `antlab_engine`)다. HTTP 계약은 `docs/api/engine.yaml`이며, `GET /healthz`를 뺀 모든 경로는 `X-Antlab-Engine-Token` 헤더가 필요하다.

## Develop / 개발

```sh
cd engine
uv lock --check
uv sync --frozen
uv run --frozen ruff check .
uv run --frozen ruff format --check .
uv run --frozen pytest
```

Run locally (the token is read from the environment only, at least 32 printable ASCII characters, no default). / 로컬 실행(토큰은 환경변수에서만 읽고, 32자 이상 ASCII 출력 문자이며 기본값이 없다).

```sh
export ANTLAB_ENGINE_TOKEN="$(openssl rand -hex 32)"
uv run --frozen uvicorn --factory antlab_engine.app:create_app_from_env --reload
```

VS Code: `Cmd+Shift+P` → `Python: Select Interpreter` → `engine/.venv`. / VS Code: `Cmd+Shift+P` → `Python: Select Interpreter` → `engine/.venv`.

## Compose / 컴포즈

Put `ANTLAB_ENGINE_TOKEN` in the untracked `.env`, then run from the repository root. / 추적하지 않는 `.env`에 `ANTLAB_ENGINE_TOKEN`을 넣고 저장소 루트에서 실행한다.

```sh
docker compose --profile engine up -d --build --wait engine
docker compose stop engine
```

`docker compose down` would also remove the owner database container of the same project, so only the engine service is started and stopped here. / 같은 프로젝트의 소유자 DB 컨테이너까지 내려가므로 `docker compose down`은 쓰지 않고, 엔진 서비스만 켜고 멈춘다.

Isolated check that leaves the default project alone. / 기본 프로젝트를 건드리지 않는 격리 확인.

```sh
ANTLAB_ENGINE_TOKEN="$(openssl rand -hex 32)" ANTLAB_ENGINE_PORT=18201 docker compose --profile engine -p antlab-m201 up -d --build --wait engine
docker compose --profile engine -p antlab-m201 down -v --rmi local
```

The local `backend` network is a plain bridge, so engine egress isolation is not proven locally. / 로컬 `backend` 네트워크는 일반 브리지라서 엔진의 외부 통신 차단은 로컬에서 증명되지 않는다.

## Bootstrap parity / 부트스트랩 대사

`antlab_engine.bootstrap` is a bit-exact Python port of `p0-boot6-v1` (`SplitMix64` and the month selection of the Java `SimpleBootstrap`). It is a library only; no endpoint uses it yet. `tests/test_vectors.py` reads the tracked Java vector file `src/test/resources/engine/p0-boot6-v1_vectors.csv` in place and compares every row character for character; a missing file fails the test. Changing any constant or formula changes the paths, so the engine version must change with the vectors. / `antlab_engine.bootstrap`은 `p0-boot6-v1`(`SplitMix64`와 Java `SimpleBootstrap`의 달 선택)을 비트 단위로 같게 옮긴 Python 라이브러리이며 아직 엔드포인트는 없다. `tests/test_vectors.py`는 추적되는 Java 벡터 파일 `src/test/resources/engine/p0-boot6-v1_vectors.csv`를 제자리에서 읽어 모든 행을 글자 단위로 비교하고, 파일이 없으면 시험이 실패한다. 상수나 식이 바뀌면 경로가 달라지므로 벡터와 함께 엔진 판을 올려야 한다.

## Stationary bootstrap / 정상 블록 부트스트랩

`antlab_engine.bootstrap.stationary` is the M2 path generator `m2-sb6-v1`: geometric block lengths (mean 6, cap 24), each block starts on the virtual calendar month (fewer than 3 candidates → ±1 month → any month), no month twice in one season, months of recent seasons avoided up to a caller threshold (a path that cannot meet it is still returned, best attempt first, with `overlap_met` false). The block length mean is 6 before the cap of 24 and about 5.92 after it. It is deterministic for a seed and pinned by `tests/vectors/m2-sb6-v1_vectors.csv`; `tests/gen_stationary_vectors.py` regenerates the file (review the diff as data). It is a library only; no endpoint uses it yet, and `p0-boot6-v1` stays unchanged. `uv run --frozen pytest -m slow -s tests/test_stationary_perf.py` prints the timing and overlap measurements. Supported history is up to about 1,200 months; one path costs under 0.15 s at the default 32 attempts even on the worst measured inputs, and the `attempts` budget is not capped in the library (256 attempts is about 1 s), so only the default is exposed over HTTP. / `antlab_engine.bootstrap.stationary`는 M2 경로 생성기 `m2-sb6-v1`이다. 블록 길이는 기하 분포(평균 6, 상한 24)이고, 블록마다 첫 달을 가상 달력의 월에 맞추며(후보 3개 미만이면 ±1개월 → 아무 달), 한 시즌에 같은 달을 두 번 쓰지 않고, 최근 시즌의 달은 호출자가 준 문턱까지 피하되, 문턱을 못 맞추면 가장 나은 시도의 경로를 `overlap_met` 거짓으로 돌려준다. 블록 길이 평균은 상한 24를 적용하기 전 6, 적용한 뒤 약 5.92이다. 같은 시드는 같은 경로를 내며 `tests/vectors/m2-sb6-v1_vectors.csv`가 고정하고, `tests/gen_stationary_vectors.py`가 이 파일을 다시 만든다(차이는 데이터로 검토한다). 라이브러리만 있고 아직 엔드포인트는 없으며 `p0-boot6-v1`은 그대로다. `uv run --frozen pytest -m slow -s tests/test_stationary_perf.py`가 시간과 겹침 측정값을 출력한다. 지원하는 이력은 약 1,200개월까지이고, 측정한 최악 입력에서도 기본 32회 시도에서 경로 하나가 0.15초 미만이다. 라이브러리는 `attempts`에 상한을 두지 않으므로(256회는 약 1초) HTTP에는 기본값만 노출한다.
