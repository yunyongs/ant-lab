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
