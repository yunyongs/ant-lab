# antlab-ai

OpenRouter client with a shared, append-only call ledger and local limits. Every attempt is reserved in the ledger before it is sent; failed attempts count too.

OpenRouter 호출 클라이언트와 공유 추가 전용 호출 장부·제한기입니다. 모든 시도는 보내기 전에 장부에 예약하며, 실패한 시도도 센다.

## Setup and checks

The package is installed into the project environment (`src/antlab_ai`, `uv_build` backend), and the `dev` group (pytest, ruff) is synced by default. Run from `ai/`:

패키지는 프로젝트 환경에 설치되고(`src/antlab_ai`, `uv_build`), `dev` 그룹(pytest, ruff)은 기본으로 맞춰진다. `ai/`에서 실행한다.

```sh
uv lock --check
uv run --frozen ruff check .
uv run --frozen ruff format --check .
uv run --frozen pytest -q
```

Tests are offline: connect, `connect_ex`, `create_connection` and name lookups all fail. Child processes started by a test are not covered; the multi-process ledger test starts workers that only touch the ledger file.

시험은 오프라인이다. 연결, `connect_ex`, `create_connection`, 이름 조회가 모두 실패하도록 막아 둔다. 시험이 띄우는 자식 프로세스는 막지 않는다. 다중 프로세스 장부 시험의 워커는 장부 파일만 만진다.

## Ledger commands

Live calls need an absolute `ANTLAB_OPENROUTER_LEDGER` path and `OPENROUTER_API_KEY` (names only in `.env.example`).

실호출에는 절대 경로 `ANTLAB_OPENROUTER_LEDGER`와 `OPENROUTER_API_KEY`가 필요하다(`.env.example`에는 이름만 있다).

```sh
uv run --frozen python -m antlab_ai.openrouter status
uv run --frozen python -m antlab_ai.openrouter probe --live --max-requests 3 --env-file <path>
```

`--max-requests` (at most 3) is a hard ceiling on requests sent in total: the key GET, both chats and every retry are charged before they are sent.

`--max-requests`(최대 3)는 보낸 요청 전체의 상한이다. 키 조회, 채팅 두 번, 모든 재시도는 보내기 전에 차감한다.

The ledger and its `.lock` file must be regular, unlinked files (mode 0600, owned by you); the directory must not be writable by group or others (created as 0700). Symlinks and other file types are refused.

장부와 `.lock` 파일은 링크 없는 일반 파일(0600, 본인 소유)이어야 하고, 디렉터리는 그룹·타인이 쓸 수 없어야 한다(새로 만들면 0700). 심볼릭 링크와 다른 파일 종류는 거부한다.

## Detection dry-run

Renders the detection prompts and prints the call plan without any network access or ledger write. Prompts go only to `--out` (default `data/ai/detection/dryrun`, git-ignored); the console shows counts and a plan hash, never returns. The sample folder is `--sample-dir`, then `ANTLAB_GOLDEN_SAMPLE_DIR`, then `db/sample`; the return and FX CSVs there are git-ignored, so tests use the synthetic fixture under `tests/detection/fixtures`.

탐지 dry-run은 네트워크와 장부 쓰기 없이 탐지 프롬프트를 만들고 호출 계획을 출력한다. 프롬프트는 `--out`(기본 `data/ai/detection/dryrun`, git 무시)에만 쓰고, 콘솔에는 건수와 계획 해시만 나오며 수익률은 나오지 않는다. 표본 폴더는 `--sample-dir`, `ANTLAB_GOLDEN_SAMPLE_DIR`, `db/sample` 순서로 정한다. 수익률·환율 CSV는 git 무시 파일이라 시험은 `tests/detection/fixtures`의 합성 픽스처를 쓴다.

```sh
uv run --frozen python -m antlab_ai.detection dry-run --sample-dir <dir> --out <dir> [--model <id>]... [--max-tokens 512] [--max-calls <n>]
```

`--max-calls` refuses the plan (exit code 2, nothing written) when units × models exceed it. Without `--model` the plan counts one model. The real-sample shape test is skipped, with a reason, when the sample CSVs are absent.

`--max-calls`보다 단위 수 × 모델 수가 크면 계획을 거부한다(종료 코드 2, 아무것도 쓰지 않는다). `--model`이 없으면 모델 한 개로 센다. 표본 CSV가 없으면 실표본 형태 시험은 사유와 함께 건너뛴다.
