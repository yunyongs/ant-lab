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

## Agent runner

`antlab_ai.runner` plays one agent season against the internal agent-runner API (`docs/api/agent-runner.yaml`): it reads the agent's turn view, asks a model through the route-blind `ModelPort`, applies the PLAN §4.16 failure table, submits the decision with every attempt recorded, and advances the turn. The runner never edits a model reply and never computes a price, fill, ledger row or score; the API's engine judges what the model wrote. Only the API writes the database; the runner keeps a local journal.

`antlab_ai.runner`는 내부 에이전트 러너 API(`docs/api/agent-runner.yaml`)로 에이전트 시즌 한 판을 진행한다. 에이전트의 턴 화면을 읽고, 경로를 모르는 `ModelPort`로 모델에 묻고, PLAN §4.16의 실패 표를 적용해, 모든 시도를 기록한 결정을 내고 턴을 넘긴다. 러너는 모델의 응답을 고치지 않고 가격·체결·원장·점수를 계산하지 않는다. 모델이 쓴 것은 API의 엔진이 판정한다. DB는 API만 쓰고, 러너는 로컬 저널을 둔다.

Settings come from the environment only (names in `.env.example`): `ANTLAB_API_URL` and `ANTLAB_AGENT_RUNNER_TOKEN` (32 or more printable characters, the same value the API holds) are required; `ANTLAB_RUNNER_STATE_DIR` (default `data/ai/runner`), `OLLAMA_BASE_URL`, `OLLAMA_API_KEY` and `ANTLAB_MODEL_STRUCTURED_OUTPUT` (default `false`) are optional. `OLLAMA_KEY` (the harness key) is never read.

설정은 환경 변수로만 읽는다(이름은 `.env.example`). `ANTLAB_API_URL`과 `ANTLAB_AGENT_RUNNER_TOKEN`(출력 가능한 문자 32자 이상, API가 가진 값과 같다)은 필수이고, `ANTLAB_RUNNER_STATE_DIR`(기본 `data/ai/runner`), `OLLAMA_BASE_URL`, `OLLAMA_API_KEY`, `ANTLAB_MODEL_STRUCTURED_OUTPUT`(기본 `false`)은 선택이다. 개발 하네스의 `OLLAMA_KEY`는 읽지 않는다.

```sh
uv run --frozen python -m antlab_ai.runner register --persona patient-value --model-id <id> [--route OPENROUTER|OLLAMA]
uv run --frozen python -m antlab_ai.runner start --agent-id <n> --persona patient-value --model-id <id> [--path-length 12|24|60]
uv run --frozen python -m antlab_ai.runner run --agent-id <n> --season-id <n> --persona patient-value --model-id <id> --model-script <file.json> [--max-calls <n>] [--resume]
uv run --frozen python -m antlab_ai.runner run ... --live --max-calls <n> [--env-file <path>]
uv run --frozen python -m antlab_ai.runner status --agent-id <n> --season-id <n>
```

Tests and `run` without `--live` make no network call to a model: `--model-script` is a JSON list of scripted attempts (`{"reply": {...}}` or `{"text": "..."}`, optional `status`, `http_status`, `retry_after_s`, `credit_refusal`, `repeat`). `--live` needs `OPENROUTER_API_KEY`, an absolute `ANTLAB_OPENROUTER_LEDGER` and `--max-calls`, a hard ceiling on model requests sent in the run. A live run is not part of any test or CI job, and none is allowed before the owner answers the data-policy and tier questions.

시험과 `--live` 없는 `run`은 모델로 네트워크를 부르지 않는다. `--model-script`는 가짜 시도의 JSON 목록이다(`{"reply": {...}}` 또는 `{"text": "..."}`, 선택 항목 `status`, `http_status`, `retry_after_s`, `credit_refusal`, `repeat`). `--live`에는 `OPENROUTER_API_KEY`, 절대 경로 `ANTLAB_OPENROUTER_LEDGER`, 그리고 그 실행에서 보내는 모델 요청 수의 상한 `--max-calls`가 필요하다. 실호출은 어떤 시험이나 CI에도 들어 있지 않고, 소유자가 데이터 정책과 등급 질문에 답하기 전에는 하지 않는다.

Failure table per turn: a transport error, 5xx or timeout is retried twice (2 s and 4 s plus up to 0.5 s of jitter), then the turn is `NO_RESPONSE`; a rate limit with a stated wait of up to 60 s is waited out and resent up to three times, and a limit without a usable wait is treated like the daily limit (pause `LIMITED_DAILY`); a credit refusal (HTTP 402) pauses with `CREDIT`, a policy refusal with `POLICY`, other 4xx with `CONFIG`; a reply that fails the shape check is asked again once with a fixed note (the bad reply is never echoed), then the turn is `INVALID`; at most 12 attempts per turn; the season budget is `3 × path length` calls and zero USD, and an unknown cost (a remote Ollama address) pauses with `BUDGET` before anything is sent; three `NO_RESPONSE` turns in a row submit the third and pause with `NO_RESPONSE_STREAK` before advancing. An integrity error (`LEDGER_MISMATCH`, `LINEUP_MISMATCH`, `INVALID_FORMATION`, `EVENT_TIME_CONFLICT`) stores the earlier attempts and pauses with `ENGINE_ERROR`. Waiting time is not counted in the per-attempt timeout (120 s OpenRouter, 300 s Ollama).

턴마다의 실패 표: 전송 오류·5xx·시간 초과는 두 번 다시 시도하고(2초, 4초 + 최대 0.5초 지터) 그다음은 `NO_RESPONSE`다. 대기 시간이 60초 이하로 적힌 한도 거절은 기다렸다가 최대 세 번 다시 보내고, 쓸 수 있는 대기 시간이 없으면 일 한도처럼 다뤄 `LIMITED_DAILY`로 멈춘다. 크레딧 거절(HTTP 402)은 `CREDIT`, 정책 거절은 `POLICY`, 그 밖의 4xx는 `CONFIG`로 멈춘다. 형식 검사를 못 넘은 응답은 고정 문장을 붙여 한 번만 다시 묻고(잘못된 응답은 되풀이하지 않는다), 또 틀리면 그 턴은 `INVALID`다. 턴당 시도는 12번까지다. 시즌 예산은 `3 × 경로 길이`번의 호출과 0달러이며, 비용을 모르는 경우(원격 Ollama 주소)는 보내기 전에 `BUDGET`으로 멈춘다. 세 턴 연속 `NO_RESPONSE`이면 세 번째를 제출한 뒤 턴을 넘기기 전에 `NO_RESPONSE_STREAK`으로 멈춘다. 무결성 오류(`LEDGER_MISMATCH`, `LINEUP_MISMATCH`, `INVALID_FORMATION`, `EVENT_TIME_CONFLICT`)는 앞선 시도를 저장하고 `ENGINE_ERROR`로 멈춘다. 기다린 시간은 시도별 시간 제한(OpenRouter 120초, Ollama 300초)에 넣지 않는다.

A pause is the runner's own state until the API has a paused status: the season still shows as in progress and counts toward the in-progress and weekly limits. A paused season is run again with `run --resume`; without it the command stops with exit code 2. The Ollama adapter runs only for a loopback address in owner-started runs: a remote address has an unknown cost, so the runner pauses with `BUDGET` before sending (the adapter alone does not refuse a remote address).

API가 일시 정지 상태를 갖기 전까지 정지는 러너 자신의 상태다. 시즌은 API에 진행 중으로 보이고 진행 중·주간 상한에 계속 들어간다. 멈춘 시즌은 `run --resume`으로 다시 돌리고, 그 옵션이 없으면 종료 코드 2로 멈춘다. Ollama 어댑터는 소유자가 시작한 실행에서 루프백 주소일 때만 돈다. 원격 주소는 비용을 알 수 없어 러너가 보내기 전에 `BUDGET`으로 멈춘다(어댑터 혼자서는 원격 주소를 거부하지 않는다).

Journal: `${ANTLAB_RUNNER_STATE_DIR:-data/ai/runner}/agent-<agentId>-season-<seasonId>.jsonl` (git-ignored), directory mode 0700 and file mode 0600, owner only, no symlinks, one exclusive lock per run so a second runner on the same season is refused, append-only rows. A restarted runner reads it to resume the same turn without a second model call for an attempt already made and without a second application. It holds full request and reply texts and has no rotation; delete it only together with the season. If it is lost in the middle of a turn whose decision was already stored, the next submit meets `DECISION_CONFLICT` and the run stops with exit code 1. Parent directories above the state directory are not checked.

저널: `${ANTLAB_RUNNER_STATE_DIR:-data/ai/runner}/agent-<agentId>-season-<seasonId>.jsonl`(git 무시), 폴더 0700·파일 0600, 소유자만, 심볼릭 링크 거부, 실행마다 배타 잠금이라 같은 시즌의 두 번째 러너는 거부되며, 추가만 한다. 재시작한 러너는 이것을 읽어 이미 한 시도에 모델을 다시 부르지 않고 같은 턴을 이어 가며 두 번 적용하지 않는다. 요청·응답 원문 전체가 들어 있고 순환 삭제는 없으니 시즌과 함께만 지운다. 결정이 이미 저장된 턴의 한가운데에서 저널을 잃으면 다음 제출이 `DECISION_CONFLICT`를 만나 종료 코드 1로 멈춘다. 상태 폴더 위쪽 폴더는 검사하지 않는다.

Exit codes: `0` season finished, `3` paused or stopped (the reason code is printed: a pause reason, a season status other than `IN_PROGRESS`, or `CALL_CAP`), `2` usage, refused configuration, a paused season run without `--resume`, or a second runner on the same season, `1` any other error. The console prints counts, turn numbers, outcomes and reason codes only, never prompts, replies, keys or tokens.

종료 코드: `0` 시즌 끝, `3` 멈춤(이유 코드를 출력한다: 정지 사유, `IN_PROGRESS`가 아닌 시즌 상태, `CALL_CAP`), `2` 사용법·거부된 설정·`--resume` 없이 멈춘 시즌 실행·같은 시즌의 두 번째 러너, `1` 그 밖의 오류. 콘솔에는 건수·턴 번호·결과·이유 코드만 나오고 프롬프트·응답·키·토큰은 나오지 않는다.

CI: `.github/workflows/ai.yml` runs the four commands above (`uv lock --check`, `ruff check`, `ruff format --check`, `pytest`) on PRs and `main` pushes that touch `ai/**`, `personas/**`, the agent-runner contract, the decision schema or its corpus.

CI: `.github/workflows/ai.yml`가 위의 네 명령(`uv lock --check`, `ruff check`, `ruff format --check`, `pytest`)을 `ai/**`, `personas/**`, 에이전트 러너 계약, 결정 스키마와 그 말뭉치를 바꾼 PR과 `main` 푸시에서 돌린다.
