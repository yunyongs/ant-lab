# 배포 구조와 규칙 (Docker Compose 기반, 초안 2026-11-08)

서버 배포를 Docker로 할 수 있도록, 지금 만드는 로컬 실행 환경과 앞으로 만들 Spring·Python·웹 코드가 **고치지 않고** 같은 Docker Compose 구조로 서버에 올라가게 하는 구조와 규칙이다(소유자 지시 2026-11-08). 이 문서가 배포의 정본이다. PLAN §10(기술 스택)·§11(로드맵)·§15(저장소 구조), SPRING_JPA §3·§9·§10은 여기를 가리킨다. MACRO_ETF §6.4는 방법론 문서의 참고 설명이며 배포는 이 문서가 우선한다.

> 이 문서는 문서와 공개 웹 문서만 읽고 썼다. Docker·DB·CI를 **실행해 확인하지 않은 부분**(CI 절차, 메모리 예산 등)은 손으로 추적·계산한 것이고, 확인하지 못한 사실은 "확인 필요"로 적었다. 한도·가격은 바뀌므로 확인 날짜를 함께 적는다(MACRO_ETF 부록 A 머리말과 같은 원칙).

**무엇을 지금 하고 무엇을 미루는가:** 지금(Phase 0)은 로컬 `compose.yaml`(서비스 `db` 하나)과 **이름·포트·비밀값·설정 규칙**만 정한다. 이미지(Dockerfile)는 각 서비스가 생기는 단계(api는 Phase 0의 P0-24, engine은 M2, proxy는 APP)에서 만들고, 운영 배포 구성(`compose.prod.yaml`·Caddy·백업·배포 워크플로)은 OPS까지 만들지 않는다. 단계별 준비 일은 §11.

## 1. 원칙

1. **같은 이미지, 다른 설정.** 이미지는 한 번 빌드해 로컬·CI·운영에서 같은 것을 쓰고, 달라지는 것은 환경 변수와 비밀값뿐이다(12-factor).
2. **외부(0.0.0.0)에 열리는 포트는 `proxy`의 80·443뿐이다.** 다른 서비스의 `ports:`는 언제나 `127.0.0.1:` 접두사를 쓴다(§5).
3. **DB에 쓰는 것은 Java뿐이다**(PLAN §10)를 구조로 강제한다: 엔진(`engine`)은 내부 네트워크에만 붙고 DB는 읽기 전용 역할로 접속한다(§5, §7).
4. **이미지에 비밀값을 굽지 않는다**(§9).
5. **로그는 stdout/stderr로만** 낸다(§8).
6. 구조는 호스팅 결정과 무관하게 같다 — OCI든 다른 VM이든 Compose 구조는 그대로다(§10).

## 2. 서비스 표 (OPS 완성형, 단일 VM)

서비스 이름은 compose 서비스 키와 글자 단위로 같아야 한다. 지금 만들어지는 것은 `db`뿐이다.

| 서비스 | 이미지(초안) | 만드는 단계 | 네트워크 | 호스트 포트 | 볼륨 | 헬스체크 | 기본 상태 |
|---|---|---|---|---|---|---|---|
| `db` | 로컬 `postgres:17`, 운영은 `postgres:17.x`로 고정. AI 단계에 pgvector 이미지(`pgvector/pgvector:pg17` 후보 — arm64 태그 **확인 필요**) | Phase 0(지금) | `backend` | 로컬 `127.0.0.1:${ANTLAB_DB_PORT:-5432}`, 운영 없음(`backend`가 internal이라 게시되지 않는다 — §5) | `db-data` | `pg_isready` | 항상 |
| `api` | `ghcr.io/yunyongs/antlab-api` (Spring Boot 4.1.x, Java 21) | Phase 0 (P0-24) | `backend`, `edge`, `egress` | 로컬 `127.0.0.1:8080`, 운영 없음 | 없음(무상태) | `GET /actuator/health/readiness` | 항상 |
| `engine` | `ghcr.io/yunyongs/antlab-engine` (FastAPI, `python:3.13-slim` 기반) | M2 | `backend`만 | 로컬 `127.0.0.1:8000`(개발만), 운영 없음 | 없음 | `GET /healthz` | 항상(M2부터) |
| `proxy` | `ghcr.io/yunyongs/antlab-proxy` (`caddy:2` + React 정적 빌드 `/srv`) | APP(로컬은 선택), OPS | `edge` | 운영 `80`, `443`(유일한 외부 포트) | `caddy-data`, `caddy-config` | Caddy 관리 API 또는 `GET /`(방법 **확인 필요**) | 항상(운영) |
| `agent-runner` | AG-02에서 정함(언어 미정) | AG-02 | `egress`(api 호출 + OpenRouter) | 없음 | 없음(기록은 api가 DB에) | 프로세스 생존 | profile `agents` |
| `n8n` | `n8nio/n8n`(arm64 **확인 필요**) | 선택(EXT 이후) | `edge`(+`egress`) | 없음(Caddy 경로 뒤, 인증 필수) | `n8n-data` | `GET /healthz`(**확인 필요**) | profile `n8n` |
| `ollama` | `ollama/ollama` | 선택, **로컬 전용** | `backend` | 없음 | `ollama-models` | — | profile `ollama`(VM에 올리지 않음 — §10.2) |

- 웹(React)은 **별도 컨테이너를 두지 않는다.** CI에서 `vite build` → 결과를 Caddy 이미지 `/srv`에 굽고 Caddy가 `file_server`로 준다. 데스크탑(Tauri)·모바일(Expo)은 서버 배포 대상이 아니다(같은 REST API를 `https://{도메인}/api`로 부른다, PLAN §10). 대안 — 웹을 `nginx` 컨테이너로 따로: 컨테이너와 메모리가 늘고 TLS와 정적 파일을 Caddy 하나로 하는 이점이 없어 택하지 않았다.
- Phase 0–M1의 Java 적재·수집기 잡은 `api` 프로세스 안의 `@Scheduled`로 둔다(MACRO_ETF §6.2·부록 A.3 "Airflow 과함" 유지). 별도 워커 컨테이너는 만들지 않는다.
- `tools/fetch`(P0-08)는 **소유자 기기 전용 CLI**로 남는다 — 컨테이너로 만들지 않는다(KIS 실전 키를 서버에 두지 않음, M1 수집기는 Java, PLAN §11).

## 3. compose 파일 구조와 실행 명령

| 파일 | 추적 | 내용 | 실행 |
|---|---|---|---|
| `compose.yaml`(루트) | 예 | **로컬 기준 + 공통 정의.** 지금은 `db`만. 서비스가 생길 때마다 더한다(`api` P0-24, `engine` M2). 선택 서비스는 `profiles:` | `docker compose up -d` |
| `compose.prod.yaml`(루트) | 예(OPS) | 운영 덮어쓰기: `image:`를 GHCR SHA 태그로, `build:` 제거, `db` 인증을 비밀번호(secrets)로 하고 `POSTGRES_HOST_AUTH_METHOD: scram-sha-256`을 **명시**(키를 빼면 공통 파일의 trust가 남는다 — 아래), `proxy` 추가, `restart: always`, `logging` 회전, `mem_limit`, 필요하면 `ports: !reset []` | `docker compose -f compose.yaml -f compose.prod.yaml up -d` |
| `compose.override.yaml` | **아니오**(`.gitignore`에 이미 있음) | 개인 로컬 조정(포트 바꾸기 등) — Compose가 자동 병합 | 자동 |
| `deploy/Caddyfile`, `deploy/backup/*.sh` | 예(OPS) | 프록시·백업 | — |

- 선택 이유: **공통 파일 + 운영 덮어쓰기 파일 + profiles(선택 서비스)**. 대안 — 환경마다 완전한 파일 두 개(`compose.dev.yaml`·`compose.prod.yaml`)는 중복이 커지고 서로 어긋난다. profiles만으로 dev/prod를 가르는 안은 같은 서비스의 설정(인증·포트)을 profile로 바꿀 수 없어 서비스 이름이 갈라진다(`db-dev`/`db-prod`).
- 병합 주의: `ports`는 목록이 **더해진다.** 지우려면 `!reset []`, 바꾸려면 `!override`(도입 판은 **확인 필요** — 병합 참조 페이지에 판 언급이 없다. Compose v5.1.3에서 `!reset null` 동작 확인, 2026-11-08). `docker compose config`가 `!reset`을 반영하지 않는다는 버그 보고(docker/compose #12162)가 있어 운영에서는 `docker compose ps`·`docker port`로 확인한다. 그래서 포트 원칙 자체를 "호스트 포트는 127.0.0.1에만"으로 두어 덮어쓰기 실수가 있어도 외부에 열리지 않게 한다(§5).
- **trust는 로컬·CI 일회성 전용이다.** 운영·공유 환경에서는 쓰지 않는다. 운영으로 바꿀 때 함정 셋:
  1. `environment`는 키 단위로 병합된다. `compose.prod.yaml`에 비밀번호만 더하면 공통 `compose.yaml`의 `POSTGRES_HOST_AUTH_METHOD: trust`가 병합 결과에 그대로 남는다(`docker compose -f compose.yaml -f compose.prod.yaml config`로 확인, 2026-11-08). 그래서 운영 덮어쓰기는 `POSTGRES_HOST_AUTH_METHOD: scram-sha-256`을 명시한다(키를 지우는 `!reset null`도 동작하지만 값이 보이는 쪽을 쓴다). 배포 전 확인은 `config` 출력에서 이 키를 본다.
  2. postgres 이미지의 `POSTGRES_*` 변수는 **빈 데이터 디렉터리로 처음 시작할 때만** 적용된다(Docker Hub postgres 문서). 이미 만들어진 볼륨의 `pg_hba.conf`는 변수를 바꿔도 그대로다. 운영 DB는 **새 볼륨으로 초기화**하고 로컬 볼륨을 옮겨 쓰지 않는다(데이터는 덤프·복원으로). 기존 볼륨의 인증을 바꿔야 하면 `pg_hba.conf`를 직접 고쳐 다시 읽히는 별도 절차로 한다(OPS).
  3. OPS 완료 기준에 "비밀번호 없는 TCP 접속이 거부된다" 시험을 넣는다(다른 컨테이너에서 `psql -h db …`. 컨테이너 안 유닉스 소켓 접속은 이미지 기본 설정상 비밀번호 없이 될 수 있다 — **확인 필요**).

### 3.1 로컬 compose 이름 규칙 (Phase 0에 확정)

| 항목 | 값 | 이유 |
|---|---|---|
| 프로젝트 이름 | 최상위 `name: antlab` | 메인 체크아웃·워크트리 어디서 실행해도 같은 프로젝트·볼륨(디렉터리 이름에 기대지 않음) |
| 서비스 이름 | `db` | 목표 구조의 이름. 앞으로 `api`의 JDBC URL이 `jdbc:postgresql://db:5432/antlab` |
| `container_name` | **쓰지 않음**(Compose 기본 `antlab-db-1`) | 고정 이름은 `-p`로 프로젝트를 바꿔도 같은 이름이라 격리 시험이 이름 충돌로 실패한다 |
| 볼륨 | `db-data`(실제 이름 `antlab_db-data`) | 판 번호를 볼륨 이름에 넣지 않는다. 메이저 업그레이드는 덤프·복원으로(§6) |
| 포트 | `127.0.0.1:${ANTLAB_DB_PORT:-5432}:5432` | 127.0.0.1에만. 변수로 바꿔 격리 시험 |
| 이미지 | `postgres:17` | 운영은 마이너까지 고정, 올릴 때는 백업 뒤 |

### 3.2 격리 시험 규칙 (작업자·QA·CI)

소유자가 쓰는 `antlab` 프로젝트(컨테이너·볼륨·5432 포트)를 건드리지 않는다.

```bash
ANTLAB_DB_PORT=55432 docker compose -p antlab-qa up -d     # 격리 프로젝트(볼륨 antlab-qa_db-data)
# ... db/README의 순서로 시험 (PGPORT=55432) ...
docker compose -p antlab-qa down -v                        # 이 프로젝트의 볼륨만 지움
```

- `down -v`는 **그 프로젝트의 볼륨을 지운다.** `-p antlab-qa` 없이 `-v`를 쓰면 소유자 데이터가 지워진다.
- 소유자 컨테이너가 5432를 쓰는 중에 `ANTLAB_DB_PORT` 없이 시험용 프로젝트를 올리면 포트 충돌로 실패한다(시험하지 않고 경고만).
- 비기본 포트로 띄운 프로젝트를 다시 `up`할 때도 같은 `ANTLAB_DB_PORT`를 붙인다(빠뜨리면 5432로 다시 계산돼 소유자 컨테이너와 충돌한다). 잠깐 멈출 때는 `stop`/`start`를 쓴다. 계속 다른 포트를 쓰려면 루트 `.env`에 `ANTLAB_DB_PORT`를 둔다(셸 변수가 `.env`보다 우선 — Compose 문서).

## 4. 이미지 빌드·아키텍처·레지스트리

### 4.1 이미지 빌드 방식

| 이미지 | 선택 | 대안 | 이유 |
|---|---|---|---|
| `api` | **Dockerfile 멀티 스테이지**: `eclipse-temurin:21-jdk`(Gradle 빌드) → `eclipse-temurin:21-jre`(실행), 레이어 분리된 jar, 비루트 사용자, `ENTRYPOINT`는 exec 형식(PID 1이 SIGTERM을 받게) | Spring Boot `bootBuildImage`(Paketo buildpacks). 공식 문서: `imagePlatform`(`linux/arm64` 등) 지원, 기본 빌더 `paketobuildpacks/builder-noble-java-tiny:latest` | 세 이미지(api·engine·proxy)를 같은 도구(Dockerfile + buildx)로 만들고, 무엇이 들어가는지 소유자가 읽을 수 있다. buildpacks는 JVM 메모리 계산기가 붙는 장점이 있지만 블랙박스이고 arm64·amd64 교차 빌드 문제 보고가 있다(spring-boot #40697). MVP에서 최종 확정 |
| `engine` | `python:3.13-slim`(tools/fetch 검증 버전 3.13과 같음), `requirements.txt` `==` 고정, 비루트, `uvicorn` exec 형식 | `python:3.13`(전체) 또는 distroless | slim이면 pandas·numpy의 manylinux aarch64 휠로 컴파일 없이 설치된다(휠 가용성은 M2에서 **확인 필요**) |
| `proxy` | 멀티 스테이지: `node:{LTS}-slim`에서 `vite build` → `caddy:2`에 `/srv`·`Caddyfile` 복사 | 정적 파일을 볼륨에 두고 Caddy 공식 이미지 그대로 | 이미지 하나 = 배포 단위 하나(롤백이 태그 교체 한 번) |

- JVM 메모리: 컨테이너 한도(`mem_limit`)를 주고 `-XX:MaxRAMPercentage=75` 같은 비율로 쓴다(JDK가 컨테이너 한도를 인식 — 값은 OPS에서 측정 후 확정).
- 태그: `:{git 짧은 SHA}` + `:main`(최신). 운영 compose는 **SHA 태그**를 쓴다(앱 되돌리기 = 이전 SHA로 `up -d --wait`. DB 스키마는 함께 돌아가지 않는다 — §7 "되돌리기").
- 첫 Dockerfile과 함께 루트 `.dockerignore`를 만든다: `.env`, `.env.*`, `data/`, `db/sample/*.csv` 중 무시 파일, `.git/`, `**/.venv/`, `node_modules/` 등. 빌드 컨텍스트에 `.env`가 들어가면 `COPY . .` 한 줄로 이미지에 키가 실린다.

### 4.2 아키텍처 — arm64 단일부터

- 소유자 개발 기기는 **arm64**(Apple Silicon — `uname -m` = `arm64`, 2026-11-08)이고 목표 VM(OCI Ampere A1)도 arm64다. 이 기기가 받은 `postgres:17` 이미지는 `docker image inspect` 결과 `linux/arm64`다(2026-11-08) — 에뮬레이션이 아닌 arm64판이다.
- CI에서 GitHub 호스팅 `ubuntu-24.04-arm` 러너(공개 저장소 무료, 2025-08 GA)로 **네이티브** 빌드해 QEMU 에뮬레이션을 피한다. 저장소는 공개(`gh repo view`: PUBLIC, 2026-11-08).
- amd64는 다른 클라우드로 옮기거나 amd64 기기에서 이미지를 받을 일이 생기면 `docker buildx --platform linux/amd64,linux/arm64`(또는 러너 2개 + manifest 병합)로 더한다.
- **문서와 다름:** MACRO_ETF §6.4는 "main 병합 때 멀티 아키텍처 이미지 푸시"인데, 실제 소비자가 arm64뿐이라 빌드 시간과 복잡도를 줄인다. 필요해지면 문서대로 한다.

### 4.3 레지스트리 — GHCR

- `ghcr.io/yunyongs/antlab-{api,engine,proxy}`. Actions 안에서는 `GITHUB_TOKEN`으로 푸시한다. `org.opencontainers.image.source` 라벨로 저장소에 연결한다(공식 문서 권고 — 연결하지 않으면 `GITHUB_TOKEN` 푸시가 막히는 경우가 있다).
- **새 패키지의 기본 공개 범위는 비공개**(공식 문서). 공개로 바꾸면 VM이 인증 없이 받는다. 비공개로 두면 VM에 `read:packages`만 가진 PAT(classic)를 `docker login`으로 둔다. → OPS 시작 때 소유자 결정 Q3(§12). 이미지에 비밀값이 없다는 규칙(§9)이 공개의 전제다.
- 레이어 10 GB 한도·업로드 10분 제한(공식 문서) — 이 프로젝트 규모에서는 문제없다.
- CI 정책(MACRO_ETF §6.4 유지): PR마다 빌드·테스트·lint, 이미지 푸시는 `main` 머지 뒤에만, 배포는 소유자가 수동으로 실행하는 워크플로(`workflow_dispatch` → SSH로 `docker compose pull` → 덤프 → `up -d --wait --wait-timeout 180` → API 확인, §7).

## 5. 네트워크·포트

1. **외부(0.0.0.0)에 바인딩하는 포트는 `proxy`의 80·443뿐이다.** 다른 서비스의 `ports:`는 언제나 `127.0.0.1:` 접두사를 쓴다. Docker가 공개한 포트는 호스트 방화벽(ufw 등) 규칙을 우회할 수 있다는 점이 흔한 사고 원인이다(Docker 문서 "Packet filtering and firewalls": 게시한 포트의 트래픽은 ufw 규칙보다 먼저 라우팅된다, 2026-11-08 확인) — 127.0.0.1 바인딩이면 이 문제가 생기지 않는다. Docker 28.0.0 이전 판은 같은 L2 세그먼트의 다른 호스트가 127.0.0.1 게시 포트에 닿을 수 있었다(Docker 문서 Port publishing) — 운영 VM은 Docker 28 이상을 쓴다. OCI 보안 목록(Security List)도 22·80·443만 연다.
2. 네트워크 셋: `edge`(proxy ↔ api·n8n), `backend`(api ↔ db·engine, `internal: true` — 바깥 인터넷으로 나가지 못함), `egress`(api·agent-runner의 외부 호출: KIS 시세, FRED, OpenRouter). `db`·`engine`은 `backend`에만 붙는다 → 엔진은 외부 데이터를 부를 수 없다(사후 정보 배제 PLAN §4.5를 네트워크가 한 번 더 막는다).
   `internal: true`는 **운영 덮어쓰기(`compose.prod.yaml`)에서만** 건다. internal 네트워크에만 붙은 컨테이너는 `ports:`가 있어도 호스트에 게시되지 않는다(Docker Desktop 29.5.2·Compose v5.1.3에서 시험, 2026-11-08 — 운영 Linux 엔진에서도 OPS 때 확인). 로컬 `compose.yaml`의 `backend`는 일반 브리지라 db·engine이 127.0.0.1에 열린다. 포트 게시와 외부 송신 차단은 별개 조건이므로 OPS에서 호스트→db, api→db·engine, db·engine→인터넷을 각각 시험한다.
3. 운영 DB 관리 접속은 SSH 터널 또는 `docker compose exec db psql`. DB 포트를 인터넷에 열지 않는다.
4. 엔진은 Spring만 부른다(MACRO_ETF §6.5 "Python은 내부 네트워크, 서비스 토큰"): 같은 `backend` 네트워크 + 공유 비밀 `ANTLAB_ENGINE_TOKEN`.
5. 에이전트 러너는 DB에 쓰지 않는다(PLAN §10) — `egress`로 api의 TurnView·주문 API만 부르고 `backend`에 붙지 않는다.

## 6. 볼륨·백업·복원·메이저 업그레이드

- 이름 붙은 볼륨만: `db-data`, `caddy-data`(인증서 — 영속 필수, Caddy 문서), `caddy-config`, `n8n-data`, `ollama-models`. 바인드 마운트는 설정 파일(`Caddyfile`)만, 읽기 전용.
- 백업(OPS, 초안): **VM의 cron 하나**가 매일 밤 실행한다(compose `backup` profile은 두지 않는다 — 일정·자격 증명·실패 알림이 두 곳으로 갈라지지 않게). 스크립트는 `set -euo pipefail`로 덤프·암호화·업로드 단계마다 실패를 잡아 알린다(`job_run` + Slack, OPS).
  1. DB 덤프: `docker compose exec -T db pg_dump -U <소유 역할> -d antlab -Fc`. `pg_dump`는 **DB 하나만** 덤프하고 역할·테이블스페이스 같은 클러스터 전역 객체는 넣지 않는다(PostgreSQL 17 문서).
  2. 역할 덤프: `docker compose exec -T db pg_dumpall -U <슈퍼유저> --globals-only` — 역할(`antlab_owner`·`antlab_app`·`antlab_engine`)과 권한을 다시 만들기 위해서다. 역할 비밀번호(해시)가 기본으로 들어간다.
  3. 두 파일을 암호화(`age` 또는 `gpg` — 회원 이메일·비밀번호 해시가 들어가므로)해 OCI Object Storage(Always Free 20 GB, S3 호환 API — 업로드 도구는 OPS에서 `oci` CLI/`rclone` 중 결정)에 올린다. 보존은 7일 일간 + 4주 주간(초안).

  역할 이름·슈퍼유저 이름은 MVP의 DB 역할 결정(§7) 뒤 확정한다. 블록 볼륨 백업(무료 5개)은 VM 통째 복구용 보조다.
- **복원 시험**을 OPS 완료 기준에 넣는다: 새 볼륨의 빈 컨테이너에 ① 역할 덤프 적용(`psql`) → ② `pg_restore -d antlab` → ③ 대사 쿼리 PASS → ④ `antlab_app`·`antlab_engine`으로 비밀번호 접속, 엔진 역할의 쓰기 거부 확인 → ⑤ 그 DB로 `api`가 시작해 readiness 통과.
- **메이저 업그레이드 주의:** `postgres:18`부터 이미지의 `PGDATA`가 `/var/lib/postgresql/18/docker`, 선언 `VOLUME`이 `/var/lib/postgresql`로 바뀌었다(docker-library/postgres PR #1259, 2025-06 병합). 17 볼륨을 18 이미지에 그대로 붙이면 시작하지 않거나 빈 DB가 된다. 18로 올릴 때는 위 백업 두 파일(DB 덤프 + 역할 덤프) → 새 볼륨 → 복원(또는 `pg_upgrade`)으로 한다. 지금 17의 마운트 경로 `/var/lib/postgresql/data`는 맞다. AI 단계에 pgvector 이미지로 바꿀 때도 같은 덤프·복원 절차를 쓴다.

## 7. 마이그레이션과 DB 역할

- **선택: 앱 시작 때 Flyway**(SPRING_JPA §3 그대로). `api`가 하나뿐인 단일 VM에서는 마이그레이션 경합이 없다. 배포 스크립트는 ① `up` 전에 `pg_dump`를 한 번 뜨고(§6), ② `docker compose -f compose.yaml -f compose.prod.yaml up -d --wait --wait-timeout 180`으로 모든 서비스가 healthy가 될 때까지 기다린 뒤, ③ proxy를 거친 실제 API 요청 한 번(경로는 OPS에서 정함)이 성공해야 배포 성공으로 기록한다. `up -d`만 쓰면 컨테이너가 만들어지는 순간 성공으로 끝나 Flyway·`validate` 실패를 잡지 못한다. `--wait`는 서비스가 unhealthy이면 종료 코드 1로 끝난다(Compose v5.1.3에서 시험, 2026-11-08 — 공식 문서는 이 종료 코드를 따로 적지 않는다). 실패하면 워크플로를 실패로 끝내고 **자동으로 되돌리지 않는다** — 아래 "되돌리기"대로 소유자가 판단한다.
- **되돌리기 — 앱과 스키마는 따로다.** 이전 SHA로 `up`하면 앱만 돌아가고, 새 판이 시작하며 적용한 Flyway 마이그레이션은 DB에 그대로 남는다. ① 새 마이그레이션이 없거나 이전 앱과 호환되는 변경(테이블·열 추가, 제약 완화)뿐이면 이전 SHA로 되돌린다. ② 이전 앱이 읽지 못하는 변경(열 이름 변경·삭제, 타입 변경, 제약 강화)은 이미지 롤백으로 복구되지 않는다 — `api`를 멈춰 쓰기를 막고 배포 직전 덤프로 복원하거나(§6 복원 절차), 고친 판을 다시 배포한다(전진 수정). 그래서 비호환 변경은 두 배포로 나눈다(새 구조 추가 → 다음 배포에서 옛 구조 삭제).
- 대안: 별도 일회성 서비스 `migrate`(profile `tools`, 같은 api 이미지에 `--spring.main.web-application-type=none` 또는 `flyway/flyway` 이미지) → `docker compose run --rm migrate` 뒤 `up -d api`. 여러 인스턴스·긴 마이그레이션이 생기면 이 안으로.
- DB 역할(MVP 때 확정, 초안): Flyway는 소유 역할 `antlab_owner`(DDL), 앱은 `antlab_app`(DML만), 엔진은 `antlab_engine`(**SELECT만**). Spring은 `spring.flyway.user`/`password`로 마이그레이션 접속을 따로 줄 수 있다. 엔진의 읽기 전용 역할은 PLAN §10 "DB에 쓰는 것은 Java뿐"을 DB 권한으로 강제한다.
- Phase 0의 `db/*.sql`은 실습용 그대로다. Phase 0 API의 P0-23에서 Flyway `V1__…`–`V3__…`로 옮기면(`db/01`–`03`의 `[스키마]` 구간과 같은 내용) 그 시점부터 스키마의 정본은 Flyway다(SPRING_JPA §3). 로컬 API DB는 같은 `db` 서비스 안의 `antlab_api`이고 시험은 Testcontainers라 소유자의 `antlab` 프로젝트를 건드리지 않는다.

## 8. 헬스체크·종료·로그

- 헬스체크: `db` `pg_isready`(지금), `api` `/actuator/health/liveness`·`/readiness` — 이 health 그룹이 자동으로 켜지는 조건은 판마다 다르다. Spring Boot 4.1.2 문서는 "자동으로 켜진다"(끌 때 `management.endpoint.health.probes.enabled=false`), 3.5.16 문서는 "Kubernetes 환경에서만 자동, 다른 환경은 이 속성으로 켠다"이다(2026-11-08 확인). Docker Compose는 Kubernetes가 아니므로 **판과 관계없이 `management.endpoint.health.probes.enabled=true`를 명시**하고, P0-22에서 4.1.2로 두 URL이 200(`{"status":"UP"}`)을 주고 `env`·`info`·`beans`는 404임을 확인했다(`ActuatorProbesTest`). readiness 그룹에는 `db`를 넣었으므로 DB가 죽으면 readiness는 Hikari 연결 대기(기본 30초) 뒤 응답한다 — 헬스체크 `timeout`과의 관계는 P0-24에서 정한다. 기본으로 HTTP에 노출되는 엔드포인트는 `health`뿐이다, `engine` `/healthz`. `depends_on: condition: service_healthy`로 시작 순서를 잡는다. 헬스체크 명령에 쓸 `curl`/`wget`이 실행 이미지에 있는지는 이미지 고를 때 **확인 필요**(없으면 Java 한 줄 헬스 클라이언트나 `wget` 추가).
- 그레이스풀 셧다운: Spring Boot 4.1.2·3.5.16 문서 모두 "내장 웹 서버에서 기본 켜짐"이다(2026-11-08 확인). 3.5 이상이면 정할 것은 대기 시간 `spring.lifecycle.timeout-per-shutdown-phase`(예 20s)뿐이고, 3.4 이하를 고르면 그 판 문서로 다시 확인한다. compose `stop_grace_period: 30s` > 20s.
- 로그: 모든 서비스는 **stdout/stderr에만**(파일 로그 금지). 운영 compose에 `logging: driver json-file, options max-size 10m, max-file 3`(json-file은 `max-size` 기본이 무제한이라 회전하지 않고, `max-file`은 `max-size`가 있어야 효과가 있다 — Docker 문서, 2026-11-08 확인). 키·토큰은 로그에서 `****`(tools/fetch 규칙 유지). 구조화 로그(JSON)는 MVP에서 Spring의 `logging.structured.format.console`(지원 판 **확인 필요**)을 검토한다. 수집·대시보드(Loki 등)는 과함 — OPS 뒤 필요할 때.

## 9. 비밀값·설정·모델 공급 경로

### 9.1 비밀값

| 위치 | 무엇 | 규칙 |
|---|---|---|
| 로컬 `.env`(루트, git 무시 — 이미 `.gitignore`) | KIS 키, `OPENROUTER_API_KEY`, `OLLAMA_*`, 로컬 DB 포트 등 | `.env.example`에 **이름만**(P0-08 규칙). Compose는 루트 `.env`를 변수 치환에 읽는다 |
| 운영 VM `/opt/antlab/.env`(권한 600, 저장소 밖) | 운영 값 | 소유자가 SSH로 직접 둔다 |
| 운영 compose `secrets:`(파일 → `/run/secrets/<이름>`) | DB 비밀번호, JWT 키, 엔진 토큰 | postgres 이미지는 `POSTGRES_PASSWORD_FILE` 지원(Docker Hub 공식 이미지 문서 — `_FILE`은 `POSTGRES_PASSWORD`·`USER`·`DB`·`INITDB_ARGS`만). Spring은 `spring.config.import=optional:configtree:/run/secrets/`로 파일을 속성으로 읽는다(이름과 세부는 P0-24에서 compose `secrets:`와 함께 **확인 필요**). `POSTGRES_HOST_AUTH_METHOD`는 `_FILE`이 없어 값으로 직접 쓴다(§3 "trust는 로컬·CI 전용"). |
| GitHub Actions secrets | 배포용 SSH 키·호스트(OPS), 필요하면 OpenRouter 시험 키 | GHCR 푸시는 `GITHUB_TOKEN`(비밀값 추가 불필요). 공개 저장소의 포크 PR에는 secrets가 전달되지 않는다(GitHub 문서 **확인 필요**) → CI 시험은 비밀값 없이 돌아야 한다 |

- **이미지에 비밀값을 굽지 않는다**(§4.1 `.dockerignore`).
- **구독 CLI 자격 증명(로그인 디렉터리)은 어떤 컨테이너에도 마운트하지 않고, 이미지에 구독 CLI를 설치하지 않는다**(PLAN §4.16: 구독 CLI는 소유자 기기·소유자가 시작하는 소규모 배치만, 서버·스케줄러·CI 없음).

### 9.2 설정 — 12-factor

- 설정은 **환경 변수가 이긴다.** Spring: `application.yml`에는 `${ANTLAB_…:기본값}` 자리표시자와 로컬 기본값만, 운영 값은 환경 변수(Spring relaxed binding: `SPRING_DATASOURCE_URL` 등)·configtree. `application-prod.yml`에 비밀값 금지(`.gitignore`가 `application-local.yml`·`application-secret.yml`을 이미 무시).
- 우리 변수 이름 접두사는 `ANTLAB_`(예: `ANTLAB_DB_PORT`, `ANTLAB_ENGINE_URL`, `ANTLAB_ENGINE_TOKEN`). 표준이 있는 것은 표준 이름 그대로 쓴다(`POSTGRES_*`, `SPRING_*`, `PG*`, `KIS_*`, `OPENROUTER_API_KEY`, `OLLAMA_BASE_URL`).
- 프로필은 SPRING_JPA §10의 `local`·`test`·`prod` 셋 그대로, 컨테이너에서는 `SPRING_PROFILES_ACTIVE=prod`.
- Python 엔진(M2) 기준: 설정은 `os.environ`이 먼저이고 `.env` 파일은 로컬 편의 대체만, `/healthz`, 비루트, 외부 네트워크 없음, DB는 읽기 전용 역할. `tools/fetch`는 `.env`를 명시 경로로만 읽고 `os.environ`을 바꾸지 않는다(`common.py`).
- Phase 0 API(Phase 1 JDBC 콘솔 앱은 건너뛰었다)의 접속 정보도 코드에 쓰지 않는다: `ANTLAB_DB_URL`(기본 `jdbc:postgresql://localhost:5432/antlab_api`), 사용자·비밀번호는 `ANTLAB_DB_USER`(기본 OS 사용자 `${USER}` — compose `POSTGRES_USER`와 같은 규칙)·`ANTLAB_DB_PASSWORD`(기본 빈 값)로 확정했다. `PGUSER`/`PGPASSWORD`는 libpq 변수라 JDBC 드라이버가 읽지 않으므로 Spring에는 쓰지 않는다. `prod`에서 `ANTLAB_DB_URL`을 필수(기본값 없음)로 바꾸는 일은 P0-24에서 한다. 로컬 compose `db`에 그대로 붙는다.

### 9.3 모델 공급 경로 (서버 컨테이너에서)

| 경로 | 서버 컨테이너에서 | 근거 |
|---|---|---|
| OpenRouter | **유일한 자동 경로.** `OPENROUTER_API_KEY`는 운영 `.env`/secret, 지출 상한·호출 제한기는 PLAN §4.16 그대로 | PLAN §4.16 |
| 구독 CLI | **서버·CI·스케줄러에서 쓰지 않는다.** 이미지에 설치 금지, 로그인 디렉터리 마운트 금지, 어댑터는 설정 기본값 꺼짐(`ANTLAB_MODEL_SUBSCRIPTION_ENABLED=false` — 이름 초안, AG-03). 소유자가 기기에서 직접 시작하는 소규모 배치가 서버 API(또는 로컬 스택)를 부른다 | PLAN §4.16 "구독 CLI의 운용 원칙", 약관 미확인 |
| Ollama | `OLLAMA_BASE_URL`이 있을 때만 켜짐. 로컬 스택은 호스트 Ollama, 운영은 원격 API(자동 실행 여부는 AG-02) | PLAN §4.16 |

## 10. 호스팅

### 10.1 OCI Always Free 한도 (https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm, 2026-11-08 확인)

- VM.Standard.A1.Flex: 월 **1,500 OCPU 시간 · 9,000 GB 시간 = 2 OCPU · 12 GB 메모리** 상당. 이전에 널리 알려진 4 OCPU · 24 GB가 아니다. 블로그들은 2026-06에 절반으로 줄었다고 하나 공식 페이지에는 변경 시점 언급이 없다 — 시점은 **확인 필요**, 현재 값은 확인됨.
- 블록 볼륨: 홈 리전에서 부트 + 블록 합계 200 GB, 볼륨 백업 5개. 오브젝트 스토리지 20 GB. 아웃바운드 전송 월 10 TB.
- **유휴 회수:** "Idle Always Free compute instances may be reclaimed" — 7일 동안 CPU p95 < 20%, 네트워크 < 20%, 메모리 < 20%(A1만)이면 유휴. 사용자가 적은 게임 서버는 쉽게 이 조건에 든다. 종량제(Pay As You Go) 전환 시 무료 한도 안이면 과금 없이 회수가 멈춘다는 것은 2차 자료뿐 — **공식 확인 필요**(OPS 때).
- A1 용량 부족("Out of capacity")으로 생성이 안 되는 리전이 있다는 보고 — **확인 필요**.

### 10.2 메모리 예산 (12 GB, 손 계산 — 실측 아님)

| 구성 | 한도(초안) |
|---|---|
| OS + Docker | 1.0 GB |
| `db`(shared_buffers 1 GB 기준) | 2.0 GB |
| `api`(JVM, MaxRAMPercentage 75) | 1.5 GB |
| `engine`(pandas; 예상 분포 N=2000 경로 × 60턴 × 180종목 float64 ≈ 173 MB 배열 하나) | 2.0 GB |
| `proxy` | 0.1 GB |
| `agent-runner` | 0.3 GB |
| `n8n`(선택) | 0.5–1.0 GB(**확인 필요**) |
| 합계 | 약 7.4–7.9 GB → 여유 4 GB |

- 2 OCPU(Ampere 코어 2개)에서 Gradle 빌드는 하지 않는다 — 이미지는 CI에서 만들고 VM은 받기만 한다.
- **Ollama는 VM에 두지 않는다**: GPU 없이 2 OCPU로는 쓸 만한 모델의 턴당 응답이 느리고, 모델 메모리가 db·api와 다툰다. Ollama는 소유자 Mac(호스트 프로세스, 로컬 스택에서 `http://host.docker.internal:11434`) 또는 원격 Ollama API(`OLLAMA_BASE_URL`, PLAN §4.16 — 설정 전 꺼짐)로 쓴다.
- OPS에서 `docker stats`로 실측해 `mem_limit`을 확정한다.

### 10.3 대안 비교

| 안 | 비용 | 장점 | 단점 |
|---|---|---|---|
| **A (선택) OCI Always Free A1 VM 1대 + Compose + Caddy** | 0(한도 안) | 기존 계획(MACRO_ETF §6.4·A.1), arm64 = 개발 기기와 같은 아키텍처, 20 GB 오브젝트 스토리지로 백업, 10 TB 전송 | 2 OCPU·12 GB, 유휴 회수, 리전 용량, 단일 장애점 |
| B OCI 종량제 계정, 같은 무료 shape | 0(한도 안) + 카드 등록 | 유휴 회수 위험 제거(공식 **확인 필요**), 필요하면 shape 확장 | 한도 넘으면 과금 — 예산 알림 필요 |
| C 관리형 PG(OCI Database with PostgreSQL / GCP Cloud SQL) + 컨테이너 | 월 수십 달러 이상(**가격 확인 필요**) | 백업·패치·HA 관리형 | 학습 프로젝트·사용자 1명에 과함, pgvector·판 지원 확인 필요 |
| D 다른 클라우드 소형 ARM VM(예: Hetzner CAX 계열, AWS Lightsail) | 월 수 달러(**가격 확인 필요**) | 회수 없음, 단순 | 유료, OCI 오브젝트 스토리지 대신 다른 백업처 |
| E 소유자 Mac + 터널(Cloudflare Tunnel 등) | 0 | 바로 가능, 공개 데모 시연용 | 기기가 꺼지면 서비스도 꺼짐 — 상시 서비스 불가 |

- 선택: **A로 시작하고, OPS 시작 때 B(종량제 전환)를 소유자에게 묻는다.** compose 구조는 A–E 어디서나 같아서(C는 `db` 서비스만 빠짐) 지금 규칙은 호스팅 결정과 무관하다. Kubernetes(OKE)는 학습 과제로만(MACRO_ETF 부록 A.3).

## 11. 단계별 준비 체크리스트

| 단계 | 배포 준비 일 |
|---|---|
| Phase 0 | compose 이름 규칙(§3.1), 격리 시험 규칙(§3.2), 포트 원칙(§5), 비밀값·설정 규칙(§9), 문서의 Homebrew → Docker Compose, (선택) SQL 검증 CI(아래) |
| Phase 1 | 건너뜀(2026-11-09) — 접속 정보 환경 변수(§9.2)는 Phase 0 API(P0-22)가 이어받는다 |
| Phase 0 API | Spring 컨테이너 설정 체크리스트(SPRING_JPA §10.1, P0-22), api Dockerfile·`.dockerignore`, compose `api`(P0-24), Flyway 기준선(P0-23), 시험은 Testcontainers |
| MVP | DB 역할 3개 초안(§7), 구조화 로그(configtree는 P0-24에서 이름만 확인하고 실제 사용은 OPS — §9.1) |
| M1 | CI Gradle + Testcontainers PG17(SPRING_JPA §9) — Phase 0 API 시험을 합성 표본 픽스처로 CI에서, api 이미지 **빌드만**(푸시 없음, SPRING_JPA §10.1-8), 수집기 외부 호출은 `egress`만 |
| M2 | engine Dockerfile·`/healthz`·읽기 전용 역할·`backend` 전용 네트워크, 서비스 토큰 |
| WEB · APP | proxy 이미지(웹 정적 + Caddy)는 APP·OPS, 로컬은 Vite 개발 서버를 호스트에서(컨테이너 아님 — WEB부터, `/api` 프록시) |
| AG-02 | agent-runner(profile `agents`), OpenRouter만 자동, 구독 경로 어댑터 꺼짐(§9.3) |
| AI | db 이미지를 pgvector판으로 교체(덤프·복원 절차 §6) |
| OPS | 아래 "OPS로 미루는 것" 전부, 복원 시험, 공개 데모 |
| R3(릴리스 v2) | 실계좌 키·주문 경로가 들어오므로 호스팅·비밀값 관리(Vault·클라우드 비밀 관리 서비스)·접근 통제를 다시 평가 — v1 구조를 그대로 쓰지 않는다 |

**OPS로 미루는 것:** 클라우드 계정·지역·종량제 전환, 도메인·DNS, `compose.prod.yaml`, `deploy/Caddyfile`, GHCR 푸시·공개 범위, 배포 워크플로(소유자가 수동 실행 — MACRO_ETF §6.4 "수동 배포" 유지), 백업·복원 시험, VM 하드닝(SSH 키만, 자동 보안 업데이트), 메모리 한도 실측, 모니터링·알림(`job_run` + Slack은 기존 설계).

**Phase 0의 선택 단계 — SQL 검증 CI(별도 `[환경]` PR):** 01·02·03 스키마 + `_check` 3개를 PR마다 GitHub Actions의 `postgres:17` 서비스 컨테이너에서 돌린다. 10·11 적재는 CI에서 **불가**하다 — 10이 읽는 파생 CSV 3개와 `hand_check.csv`가 git 무시 파일이라 CI에 없다(db/README). 초안: 작업 컨테이너 `postgres:17`(psql 17 클라이언트를 그대로 씀), 서비스 `db`에 `POSTGRES_HOST_AUTH_METHOD: trust`(CI 일회성), `--health-cmd pg_isready`, db/README (c) 순서의 `psql -X -v ON_ERROR_STOP=1` 6개, `git diff --check`. 작업 컨테이너에 `git`이 없을 때 `actions/checkout`의 동작은 **확인 필요**(안 되면 PGDG `postgresql-client-17` 설치 또는 서비스 컨테이너 안 psql). **문서와 다름:** PLAN §11은 CI를 M1에 두는데, 이것은 그 앞당긴 작은 첫 단계이고 M1의 Gradle + Testcontainers CI는 그대로 M1이다.

## 12. 열린 결정

### 12.1 OPS 시작 때 소유자 결정 (지금은 묻지 않는다)

- **Q1 클라우드 계정·리전:** OCI 계정을 만들지, 홈 리전(서울/춘천 — Always Free 자원은 홈 리전에만)과 **종량제 전환(카드 등록 — 유휴 회수 방지)** 여부. 작성자 추천: OCI + 종량제 전환 + 예산 알림.
- **Q2 도메인:** Caddy 자동 HTTPS는 공개 DNS A/AAAA 레코드가 VM을 가리키고 80·443이 열려 있어야 한다(Caddy 공식 문서 — IP 주소·`.local` 등에는 공개 인증서가 안 나옴). 도메인 구매(연 비용 **확인 필요**) 또는 무료 동적 DNS 하위 도메인.
- **Q3 GHCR 이미지 공개 범위:** 공개(작성자 추천 — 코드가 이미 공개, 이미지에 비밀값 없음, VM에 PAT 불필요) / 비공개(VM에 읽기 전용 PAT).
- **Q4 공개 데모 범위·시점:** PLAN §11 "릴리스 v1의 공개 시점은 그 단계를 시작할 때" — 배포가 곧 외부 공개이므로 OPS의 첫 결정.
- ~~Spring Boot 3.x / 4.x 판~~ — **결정됨(소유자 Gate 0, 2026-11-09): 4.1.x(Java 21)**, 커리큘럼 프로젝트(slx-ai-campus)의 판(4.1.2)과 같다(PLAN §10). 이 문서의 3.x 대조 문장(§8 probes 등)은 P0-22 계획에서 4.1.x 기준으로 확인한다.

### 12.2 작성자 결정 기록 (소유자 자동 채택, 2026-11-08)

| # | 결정 | 선택 | 대안 | 위치 |
|---|---|---|---|---|
| D-1 | compose 이름 규칙 | §3.1 표 | 초안 유지(격리 시험 실패, 워크트리마다 다른 프로젝트) | §3.1 |
| D-2 | compose 파일 구조 | 공통 + `compose.prod.yaml` 덮어쓰기 + profiles | 환경별 완전한 파일 / profiles만 | §3 |
| D-3 | 포트 원칙 | 호스트 포트는 127.0.0.1에만, 외부는 proxy 80·443 | 운영에서 `!reset`으로만 지우기 | §5 |
| D-4 | api 이미지 | Dockerfile 멀티 스테이지 | `bootBuildImage` (P0-24에서 최종) | §4.1 |
| D-5 | 아키텍처 | arm64 단일, 네이티브 arm 러너 | 처음부터 amd64+arm64 | §4.2 |
| D-6 | 웹 서빙 | Caddy가 정적 파일까지 | nginx 컨테이너 | §2 |
| D-7 | Flyway | 앱 시작 때 + 배포 전 덤프 | 별도 `migrate` 서비스 | §7 |
| D-8 | 비밀값 | 로컬 `.env` / 운영 VM `.env`(600) + secrets / Actions는 배포용만 | — | §9 |
| D-9 | 문서 위치 | 새 `docs/DEPLOY.md` + 포인터 | PLAN §10 확장 | 이 문서 |
| D-10 | CI 첫 단계 | Phase 0에 SQL 검증 CI(별도 PR) | M1까지 기다림 | §11 |
| D-11 | Ollama | VM에 두지 않음 | VM 올리기 | §10.2 |

## 출처 (모두 2026-11-08 확인)

- OCI Always Free 자원: https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm
- Spring Boot 그레이스풀 셧다운: https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html
- Spring Boot Actuator 엔드포인트·probes: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
- Spring Boot Gradle 플러그인 OCI 이미지: https://docs.spring.io/spring-boot/gradle-plugin/packaging-oci-image.html · 교차 아키텍처 문제 보고: https://github.com/spring-projects/spring-boot/issues/40697
- Compose profiles: https://docs.docker.com/compose/how-tos/profiles/ · 파일 병합: https://docs.docker.com/compose/how-tos/multiple-compose-files/merge/ · 병합 참조(`!reset`·`!override`): https://docs.docker.com/reference/compose-file/merge/ · 렌더링 버그: https://github.com/docker/compose/issues/12162
- Compose secrets: https://docs.docker.com/compose/how-tos/use-secrets/ · postgres 공식 이미지(`_FILE`): https://hub.docker.com/_/postgres · PG18 PGDATA 변경: https://github.com/docker-library/postgres/pull/1259
- GitHub Actions PostgreSQL 서비스 컨테이너: https://docs.github.com/en/actions/use-cases-and-examples/using-containerized-services/creating-postgresql-service-containers · arm64 러너(공개 저장소 GA): https://github.blog/changelog/2025-08-07-arm64-hosted-runners-for-public-repositories-are-now-generally-available/
- GHCR: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
- Caddy 자동 HTTPS: https://caddyserver.com/docs/automatic-https
