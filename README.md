# my Fund Manager (가칭, 이전 가제 Ant Lab)

**포트폴리오 매니저 훈련 게임**이다. 사용자는 축구 감독 게임의 감독처럼 가상의 운용 책임자가 되어, 지금 시장에 실제로 있는 종목(국내·미국 주식과 상장지수펀드(ETF, Exchange-Traded Fund))으로 팀을 짜고, 아직 오지 않은 시간을 한 달씩 넘기며 팀을 고쳐 나간다. 시스템은 과거의 실제 달들을 재조합해 수천 개의 가능한 미래를 만들고, 사용자는 그중 하나를 살아 내며 시즌이 끝나면 성적표와 복기를 받는다. 목적은 돈을 버는 것이 아니라 **운용 판단력이 느는 것**이고, 궁극의 질문은 "우수한 펀드 매니저는 무엇이 다른가"다 — 그 답을 속성으로 정의하고, 재고, 훈련한다([PLAN §1.1](docs/PLAN.md)).

세 기둥으로 서로 다른 능력을 기른다.

| 기둥 | 기르는 능력 | 방법 |
|---|---|---|
| 감독 모드 | 위험을 견디고 행동을 다스리는 것 | 실제 달 재조합, 1턴 = 1개월, 포지션(골키퍼·수비·미드필더·공격수)과 포메이션, 전술 변경, 사건 카드, 뉴스 모양의 소음과 매매를 부르는 플랫폼 환경 |
| 케이스 훈련 | 세상이 바뀔 때 이기는 기업을 가려내는 것 | 이름을 가린 과거 전환기의 후보 기업을 당시 실제 숫자로 배분 |
| 전망 모드 | 지금의 세상을 읽고 앞을 내다보는 것 | 실제 시간, 반증 가능한 논제를 기록하고 세상이 펼쳐지며 채점 |

성적은 수익률이 아니라 기준 포트폴리오 대비 위험 조정 성과와 과정 점수로 매기고, 시즌이 쌓이면 레이팅·속성 프로필·명예의 전당이 된다. 대가(버핏, 린치 등)의 공개된 방법론은 규칙·체크리스트로 정리해 배우고 적용하고 겨룰 수 있다.

참가자는 사람만이 아니다. 페르소나를 주입한 LLM **에이전트 펀드 매니저**도 참가자로 계획돼 있다(AG 단계). 사람과 같은 규칙으로 모의 시즌을 직접 운용하고, 자기 매매 기록과 반성으로 지식을 쌓으며, 사람과 같은 리더보드(평가 배수를 첫 열로, 종합 매니저 점수로 순위)에 선다([PLAN §4.16](docs/PLAN.md), [§6.9](docs/PLAN.md)).

> 학습 프로젝트이며 투자 조언이 아니다. 훈련장(모의 시즌)은 실제 돈이나 실계좌 주문을 쓰지 않고, 에이전트의 매매도 모의 시즌 안에서만 일어난다. 실계좌 운용(R3)은 소유자 자금·사람 승인을 전제로 한 후속 계획이다. 예상 분포는 예측이 아니라 과거 데이터로 만든 가능성의 범위다.

Releases are four: **v0.1** backend dashboard and web, **v1** web-based simulation including AI features and AI agents, **v2** desktop and mobile app simulation, **v3** real investment (R3).
릴리스는 넷이다: **v0.1**은 백엔드용 대시보드(Grafana)와 프런트엔드용 web, **v1**은 웹 기반 모의 투자(AI 기능과 AI 에이전트의 모의 투자까지), **v2**는 앱 기반(데스크탑·모바일) 모의 투자, **v3**는 실제 투자(소유자 자금·사람 승인, R3)다([PLAN §1.2](docs/PLAN.md#12-릴리스--v01v1v2v3)).

- 계획, 게임 규칙, 도메인, 점수, 로드맵: [docs/PLAN.md](docs/PLAN.md)
- Spring Boot + JPA 기술 설계: [docs/SPRING_JPA.md](docs/SPRING_JPA.md)
- 도메인 ERD와 엔티티 정의서: [docs/erd.md](docs/erd.md) (PLAN §5.1 기준)
- 엔진 방법론(국면·지표·사후 정보 배제·백테스트): [docs/MACRO_ETF.md](docs/MACRO_ETF.md)
- 배포 구조·규칙(Docker Compose, 이미지, 비밀값, 호스팅): [docs/DEPLOY.md](docs/DEPLOY.md)
- Git 컨벤션(이슈, 브랜치, 커밋, 풀 리퀘스트(PR, Pull Request)): [docs/CONVENTION.md](docs/CONVENTION.md)
- 이 프로젝트와 함께 성장하는 학습 저장소: [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus)

## 현황

| 단계 | 내용 | 상태 |
|---|---|---|
| Plan v2 | 제품 방향 전환(포트폴리오 매니저 훈련 게임), 기술 설계 | 승인 (2026-11-05) |
| Plan v3 | 에이전트 펀드 매니저(사람·에이전트 공존, 모의 직접 매매, 지식 축적, 종합 리더보드) | 승인 (2026-11-07) |
| Plan v3.1 | 릴리스 구분(이후 2026-11-12에 v0.1·v1·v2·v3로 재정의), 단계 코드 EXT·APP·AI·OPS, 확장 구간(1997-01–)과 새 기술 전환기 케이스(닷컴 ↔ 생성형 AI) | 소유자 결정 반영 (2026-11-07) |
| 로드맵 재배치 | Phase 0에 시즌 한 판 API(P0-22–P0-40), Phase 1 건너뜀, WEB 단계, Spring Boot 4.1 | 소유자 결정 반영 (2026-11-09) |
| Phase 0 — SQL(PostgreSQL 17) → API | 새 도메인의 ERD·DDL과 역사 데이터 표본을 준비한다. SQL 트랜잭션으로 시즌 1판을 실행해 대사·집계 쿼리를 확인한 뒤, **시즌 한 판 REST API**를 Spring Boot·JPA·Flyway로 구현한다. 표본 데이터를 기준으로 SQL 결과를 골든으로 삼는다 | Done (2026-11-13) — the SQL part P0-03–P0-21 (final verdict `p0_21_check`) and the one-season API P0-22–P0-40: one season plays end to end over HTTP and every turn reconciles. Feature table: P0-03–P0-40 ([PLAN §11.1](docs/PLAN.md#111-phase-0-체크리스트-sql-postgresql-17)); the PLAN v1 DDL (P0-02) was superseded. / 완료 (2026-11-13) — SQL 구간 P0-03–P0-21(최종 판정 `p0_21_check`)과 시즌 한 판 API P0-22–P0-40: HTTP로 한 판을 끝까지 두고 모든 턴의 대사가 맞는다. 기능 표 P0-03–P0-40([PLAN §11.1](docs/PLAN.md#111-phase-0-체크리스트-sql-postgresql-17)), PLAN v1 DDL(P0-02)은 대체됨 |
| Phase 1 — JDBC | 콘솔 앱(시즌·팀·턴·매매·성적표), 단순 부트스트랩 | **건너뜀** (2026-11-09, Phase 0 API에 흡수) |
| MON — 모니터링(Grafana) | Actuator·Micrometer → Prometheus → Grafana 대시보드(API 응답 시간·오류율, JVM·DB, 게임 지표)를 구축한다 | Done (2026-11-13) — MON-01–MON-05: Prometheus endpoint on the management port, compose `monitoring` profile, game counters with bounded labels, three dashboards as code, and a smoke script that plays one season and checks Prometheus and Grafana ([how to watch](#watch-one-season-in-grafana-모니터링으로-한-판-보기)) / 완료 (2026-11-13) — MON-01–MON-05: 관리 포트의 Prometheus 엔드포인트, compose `monitoring` 프로필, 유한 라벨 게임 카운터, 코드로 관리하는 대시보드 세 개, 한 판을 두고 Prometheus·Grafana를 확인하는 스모크 스크립트([보는 방법](#watch-one-season-in-grafana-모니터링으로-한-판-보기)) |
| SEC — 인증(JWT) | Spring Security + JWT — 가입·로그인, 토큰 발급·검증·갱신을 구현하고 고정 회원을 실제 회원으로 교체한다(M4에서 앞당겼다) | Done — SEC-01 authentication schema, SEC-02 security baseline and signup (2026-11-13), SEC-03 login, refresh rotation and logout (2026-11-13), SEC-04 bearer validation and per-API permissions (2026-11-14), SEC-05 fixed member removed (2026-11-14), SEC-06 auth E2E, secrets and docs (2026-11-14). / 완료 — SEC-01 인증 스키마, SEC-02 보안 기초·가입 (2026-11-13), SEC-03 로그인·갱신 회전·로그아웃 (2026-11-13), SEC-04 Bearer 검증·API별 권한 (2026-11-14), SEC-05 고정 회원 제거 (2026-11-14), SEC-06 인증 E2E·비밀값·문서 (2026-11-14) |
| WEB — 한 판 웹(React) | 시즌 시작·팀 구성·턴 진행·매매·전술 변경·성적표 화면, 모바일 폭 우선 반응형 | Done (2026-11-14) — WEB-01–WEB-11: Vite + TypeScript React skeleton; API types, client and error catalog from openapi.yaml; signup, login and session handling; season list and season start; team build at turn 0; turn advance and turn result; between-turn trading and formation change; report card; season review; end-to-end play at two widths (local); web CI. / 완료 (2026-11-14) — WEB-01–WEB-11: Vite + TypeScript React 골격 · openapi.yaml에서 API 타입·클라이언트·오류 목록 생성 · 가입·로그인·세션 처리 · 시즌 목록·시즌 시작 · 0턴 팀 구성 · 턴 넘기기·턴 결과 · 턴 사이 매매·전술 변경 · 성적표 · 시즌 복기 · 두 폭 종단 플레이 시험(로컬) · 웹 CI |
| MVP — Spring Boot + JPA | 전체 역사 데이터 적재, 예상 분포, 피드·랭킹·알림, 점수·레이팅 | In progress / 진행 중 — MVP-01, MVP-08–MVP-11, MVP-15 done / 완료 |
| M1–M3 | 수집기·CI, Python 엔진, 점수 백테스트 | M1 in progress — M1-01 A: Gradle CI (tests with a reviewed skipped-test list, SQL schema job); M1-03: runtime smokes of the image (start-up failure, graceful shutdown with a request in flight); others planned / M1 진행 중 — M1-01 A: Gradle CI(건너뜀 시험 목록을 검토하는 시험, SQL 스키마 검증 job); M1-03: 이미지 런타임 스모크(기동 실패, 처리 중 요청이 있는 정상 종료); 나머지는 계획됨 |
| M2 | Python engine: monthly blocks, regime labels, recombination (calendar alignment, regime-conditional), expected distribution; parallel reconciliation with the Java engine / Python 엔진: 월 블록·국면 라벨·재조합(달력 정렬, 국면 조건부)·예상 분포, Java 엔진과 병행 대사 | In progress — M2-01 engine skeleton, M2-02 bootstrap parity, M2-07 regime scoring library / 진행 중 — M2-01 엔진 골격, M2-02 부트스트랩 대사, M2-07 국면 채점 라이브러리 |
| EXT · M4 · APP · AI · OPS | 비용·케이스 훈련·대가 라이브러리, 전망 모드·리그·전당 API, 모바일·데스크탑 앱과 웹의 나머지 화면, AI 복기, 배포 | 계획됨 ([로드맵](docs/PLAN.md#11-로드맵커리큘럼-순서-유지)) |
| AG | 에이전트 펀드 매니저(OpenRouter·구독 모델·Ollama, 페르소나, 누출 탐지 시험, 리더보드) | 계획됨 (PLAN v3, 2026-11-07). In progress — AG-01 PR A1 (OpenRouter call ledger and limiter) done; PR A2: detection dry-run (sample reader, prompt rendering, call plan). AG-02 PR B (runner, B1–B4) done. / 진행 중 — AG-01 PR A1(OpenRouter 호출 장부·제한기) 완료, PR A2: 탐지 dry-run(표본 읽기·프롬프트 렌더링·호출 계획). AG-02 PR B(러너, B1–B4) 완료 |
| R1 · R2 · R3 | 거장 추적(13F·X), 우수 매니저의 복제 봇, 에이전트의 실전 운용(소유자 자금, 사람 승인부터) | 계획됨 (PLAN v2.1, 2026-11-06) |

## Try it yourself (직접 확인하기)

A step-by-step path to check every finished part with your own hands: database, backend, API, web, monitoring, engine, agent runner, collector and guards. Every command here was run on a throwaway Compose project (`antlab-try`, other ports) and the printed output was compared with the «Expect» lines. Your own database (`antlab-db-1`, port 5432) and port 8080 are never touched. Commands marked «not run here» were not executed while writing this guide.
지금까지 만든 것을 단계별로 직접 확인하는 안내다: DB, 백엔드, API, 웹, 모니터링, 엔진, 에이전트 러너, 수집 잡, 보안 가드. 아래 명령은 모두 일회용 Compose 프로젝트(`antlab-try`, 다른 포트)에서 실제로 실행해 «기대 결과» 출력과 견주었다. 내 DB(`antlab-db-1`, 5432)와 8080은 건드리지 않는다. «직접 실행 확인 안 함»으로 표시한 것은 이 안내를 쓰는 동안 실행하지 않았다.

Ports used below (all on `127.0.0.1`): DB 55433, API 18083, Prometheus 19093, Grafana 13013, engine 18003. Run everything from the repository root in the VS Code terminal (`` Ctrl+` ``). The expected outputs show values of the shipped sample; ids and token text differ per run.
아래에서 쓰는 포트(모두 `127.0.0.1`): DB 55433, API 18083, Prometheus 19093, Grafana 13013, 엔진 18003. 모든 명령은 저장소 루트에서 VS Code 터미널(`` Ctrl+` ``)로 실행한다. 기대 결과의 값은 동봉 표본 기준이며 id와 토큰 문자열은 실행마다 다르다.

### Step 1. Prerequisites and environment (준비물과 환경)

**What it checks / 확인하는 것:** the tools exist and the git-ignored secret file has the right names.
도구가 있고, git이 무시하는 비밀값 파일에 이름이 맞게 들어 있는지.

Needs: Docker Desktop (running), JDK 21, `uv`, Node 26 (`web/.nvmrc`), `psql` 16 or later, `curl`, `jq`, `openssl`, and the git-ignored sample CSVs in `db/sample/` ([how to build them](db/sample/README.md#표본-만들기-추적하지-않는-csv-만들기)). `.env.try` is git-ignored (`.env.*`). It holds names with random values; never paste the values anywhere.
필요한 것: Docker Desktop(켜 둔 상태), JDK 21, `uv`, Node 26(`web/.nvmrc`), `psql` 16 이상, `curl`, `jq`, `openssl`, `db/sample/`의 git 무시 표본 CSV([만드는 법](db/sample/README.md#표본-만들기-추적하지-않는-csv-만들기)). `.env.try`는 git이 무시한다(`.env.*`). 이름과 임의 값이 들어 있으며 값은 어디에도 붙이지 않는다.

```bash
docker --version
java -version
uv --version
node -v
psql --version
ls db/sample
```

```bash
{
  echo "ANTLAB_DB_PORT=55433"
  echo "ANTLAB_API_PORT=18083"
  echo "ANTLAB_PROMETHEUS_PORT=19093"
  echo "ANTLAB_GRAFANA_PORT=13013"
  echo "ANTLAB_ENGINE_PORT=18003"
  echo "ANTLAB_JWT_SECRET=$(openssl rand -base64 32)"
  echo "GF_SECURITY_ADMIN_PASSWORD=$(openssl rand -hex 16)"
  echo "ANTLAB_ENGINE_TOKEN=$(openssl rand -hex 32)"
  echo "ANTLAB_AGENT_RUNNER_TOKEN=$(openssl rand -hex 32)"
} > .env.try
chmod 600 .env.try
```

**Expect / 기대 결과:** versions print (Java `21.x`, Node `v26.x`, `psql` 16+); `ls db/sample` lists `asset.csv`, `asset_group.csv`, `asset_month_return.csv`, `asset_position.csv`, `fx_month.csv`, `hand_check.csv`, `month_block.csv`, `build_sample.sql`; `.env.try` has nine lines. Other names in `.env.example` (`KIS_APP_KEY`, `KIS_APP_SECRET`, `OPENROUTER_API_KEY`) are not needed in this guide.
버전이 출력되고(Java `21.x`, Node `v26.x`, `psql` 16+), `ls db/sample`에 위 CSV와 `build_sample.sql`이 보이며, `.env.try`는 아홉 줄이다. `.env.example`의 다른 이름(`KIS_APP_KEY`, `KIS_APP_SECRET`, `OPENROUTER_API_KEY`)은 이 안내에 필요 없다.

**If it fails / 안 될 때:** a missing CSV makes `db/10_load_sample.sql` stop at its first line (Step 2); build them from `db/sample/README.md`. Docker not running → every `docker` command fails; start Docker Desktop.
CSV가 없으면 2단계의 `db/10_load_sample.sql`이 첫 줄에서 멈춘다. `db/sample/README.md`대로 만든다. Docker가 꺼져 있으면 모든 `docker` 명령이 실패한다. Docker Desktop을 켠다.

- [ ] Step 1 confirmed / 1단계 확인됨: tools present, `.env.try` created, no value pasted anywhere.

### Step 2. Database and schema (DB 기동과 스키마)

**What it checks / 확인하는 것:** PostgreSQL 17 starts, Flyway V1–V7 are applied, the two app roles exist, and the sample loads with every `_check` printing `PASS`.
PostgreSQL 17이 뜨고, Flyway V1–V7이 적용되고, 앱 역할 둘이 있고, 표본 적재의 `_check`가 모두 `PASS`인지.

```bash
docker compose --env-file .env.try -p antlab-try --profile api --profile monitoring --profile engine up -d --build --wait --wait-timeout 900
```

The first build downloads Gradle and dependencies and takes minutes. Then the schema and roles:
첫 빌드는 Gradle과 의존성을 내려받아 몇 분 걸린다. 그다음 스키마와 역할:

```bash
export PGHOST=127.0.0.1 PGPORT=55433 PGUSER=$USER
psql -X -d antlab_api -Atc "select version,success from flyway_schema_history order by installed_rank"
psql -X -d postgres -Atc "select rolname from pg_roles where rolname like 'antlab%' order by 1"
```

**Expect / 기대 결과:** five services `healthy`; seven lines `1|t` … `7|t`; roles `antlab_app` and `antlab_owner` (the app connects as `antlab_app`, Flyway as `antlab_owner`, MVP-20).
서비스 다섯 개가 `healthy`, `1|t`부터 `7|t`까지 일곱 줄, 역할 `antlab_app`·`antlab_owner`(앱은 `antlab_app`, Flyway는 `antlab_owner`로 접속, MVP-20).

Load the sample (stop the API first, as the loader writes to the same database):
표본 적재(같은 DB에 쓰므로 API를 먼저 멈춘다):

```bash
docker compose --env-file .env.try -p antlab-try --profile api stop api
psql -X -d postgres -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'"
for f in 10_load_sample 11_load_events 12_calc_position 13_calc_attention; do
  psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/$f.sql && psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/${f}_check.sql || break
done
docker compose --env-file .env.try -p antlab-try --profile api start api
```

**Expect / 기대 결과:** four times a one-row table with `PASS` (the 13 step takes about two minutes). Practice SQL `db/20_…`–`db/26_…` and the final verdict `db/90_full_run_check.sql` run on a separate practice database; the order is in [db/README.md «전체 순서»](db/README.md#전체-순서) (not run here).
`PASS`만 든 한 행 표가 네 번 나온다(13단계는 2분쯤 걸린다). 연습 SQL `db/20_…`–`db/26_…`과 최종 판정 `db/90_full_run_check.sql`은 별도 실습 DB에서 돈다. 순서는 [db/README.md「전체 순서」](db/README.md#전체-순서)(직접 실행 확인 안 함).

**If it fails / 안 될 때:** `api` keeps restarting → `docker compose --env-file .env.try -p antlab-try logs api` (usually a missing `ANTLAB_JWT_SECRET` in `.env.try`). A port is taken → change it in `.env.try`. `psql` connects to another database → you skipped the `export` line.
`api`가 계속 재시작 → `docker compose --env-file .env.try -p antlab-try logs api`(대개 `.env.try`에 `ANTLAB_JWT_SECRET`이 없다). 포트가 겹치면 `.env.try`에서 바꾼다. `psql`이 다른 DB에 붙으면 `export` 줄을 빼먹은 것이다.

- [ ] Step 2 confirmed / 2단계 확인됨: services healthy · V1–V7 `t` · two roles · four `PASS`.

### Step 3. Backend start and tests (백엔드 기동·시험)

**What it checks / 확인하는 것:** the API answers readiness, and the Java tests pass.
API가 readiness에 답하고 Java 시험이 통과하는지.

```bash
curl -fsS http://127.0.0.1:18083/readyz
./gradlew test
```

**Expect / 기대 결과:** `{"status":"UP"}`. `./gradlew test` ends with `BUILD SUCCESSFUL` (on the verification run the full suite (1788 tests) did not finish cleanly: the test JVM ran out of heap («Java heap space», 8 failures in `TurnViewGoldenTest`, `TurnViewQueryCountTest`, `AppRoleStartupTest`, `EngineTransportTest`), while the same four classes pass when run alone with `./gradlew test --tests '*AppRoleStartupTest' --tests '*TurnViewQueryCountTest' --tests '*EngineTransportTest' --tests '*TurnViewGoldenTest'`; if you see «Java heap space», run by package as in the sections below and tell the maintainer). It uses throwaway PostgreSQL containers only (Docker Desktop must run). With the sample CSVs in `db/sample` the SQL golden (about 2–3 minutes) runs too; without them only that part is skipped with a reason. Add `-PexcludeSqlGolden` to skip it.
`{"status":"UP"}`. `./gradlew test`는 `BUILD SUCCESSFUL`로 끝난다(검증 실행에서는 전체 시험(1788건)이 깨끗이 끝나지 않았다. 시험 JVM의 힙이 부족해(«Java heap space», `TurnViewGoldenTest`·`TurnViewQueryCountTest`·`AppRoleStartupTest`·`EngineTransportTest`에서 8건 실패) 같은 네 클래스를 단독으로 돌리면 통과한다(`./gradlew test --tests '*AppRoleStartupTest' --tests '*TurnViewQueryCountTest' --tests '*EngineTransportTest' --tests '*TurnViewGoldenTest'`). «Java heap space»가 보이면 아래 절들처럼 패키지별로 돌리고 담당자에게 알린다). 일회용 PostgreSQL 컨테이너만 쓴다(Docker Desktop 필요). `db/sample`에 표본 CSV가 있으면 SQL 골든(2–3분)도 돌고, 없으면 그 부분만 이유를 밝히고 건너뛴다. 빼려면 `-PexcludeSqlGolden`.

Run the app from source instead of the container (profile `local`, Swagger on) against the same database:
컨테이너 대신 소스로 앱을 띄울 때(`local` 프로필, Swagger 켜짐, 같은 DB):

```bash
ANTLAB_DB_URL=jdbc:postgresql://127.0.0.1:55433/antlab_api ANTLAB_JWT_SECRET="$(grep ^ANTLAB_JWT_SECRET .env.try | cut -d= -f2-)" ./gradlew bootRun --args='--server.port=18084 --management.server.port=18085'
```

**Expect / 기대 결과:** `Started AntlabApplication in 3 seconds`; `curl -fsS http://127.0.0.1:18084/readyz` → `{"status":"UP"}`; http://127.0.0.1:18084/swagger-ui.html opens (302 first). Stop with `Ctrl+C`. `./gradlew bootTestRun` (own throwaway database, no secrets) is the safest alternative: not run here.
`Started AntlabApplication in 3 seconds`, `readyz`가 `{"status":"UP"}`, http://127.0.0.1:18084/swagger-ui.html 이 열린다(처음엔 302). `Ctrl+C`로 멈춘다. 일회용 DB를 쓰는 `./gradlew bootTestRun`이 가장 안전한 대안이다(직접 실행 확인 안 함).

**If it fails / 안 될 때:** «Docker 환경을 찾을 수 없음» → start Docker Desktop. `bootRun` fails on the JWT key → the variable is empty (`.env.try` line missing). Port in use → another `--server.port`.
«Docker 환경을 찾을 수 없음» → Docker Desktop을 켠다. `bootRun`이 JWT 키로 실패하면 변수가 비어 있다(`.env.try` 줄 확인). 포트가 쓰이면 `--server.port`를 바꾼다.

- [ ] Step 3 confirmed / 3단계 확인됨: `readyz` UP · `./gradlew test` BUILD SUCCESSFUL · (optional) `bootRun` UP.

### Step 4. Play one season through the API (API 한 시즌 체험)

**What it checks / 확인하는 것:** sign up → log in → start a season → pool and prices → buy → advance turns → sell and formation change → report card, all over HTTP with a bearer token. The VS Code way is [docs/api/one-season.http](docs/api/one-season.http) (REST Client extension, set `@base = http://127.0.0.1:18083`, send the blocks top to bottom). The curl way:
가입 → 로그인 → 시즌 시작 → 풀·가격 → 매수 → 턴 진행 → 매도·전술 변경 → 성적표를 토큰으로 HTTP에서 진행하는지. VS Code로는 [docs/api/one-season.http](docs/api/one-season.http)(REST Client 확장, `@base = http://127.0.0.1:18083`로 바꾸고 위에서부터 보낸다). curl로는:

```bash
B=http://127.0.0.1:18083
curl -sS -X POST $B/api/v1/auth/signup -H 'Content-Type: application/json' -d '{"email":"player@example.test","name":"player","password":"correct-horse-battery"}'
TOKEN=$(curl -sS -X POST $B/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"player@example.test","password":"correct-horse-battery"}' | jq -r .accessToken)
curl -sS -X POST $B/api/v1/seasons -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"riskLimit":"-0.20","formation":{"goalkeeper":10,"defender":30,"midfielder":30,"forward":30},"pathLength":12}'
```

**Expect / 기대 결과:** `{"id":1,"name":"player"}` (201; a second signup is 409 `EMAIL_TAKEN`), a token of about 300 characters (`expiresIn` 900 s), and a season `{"id":1,"status":"IN_PROGRESS","currentTurnNo":0,"pathLength":12,"riskLimit":"-0.20000000",…,"initialCash":"30000000",…}`. Use the season `id` as `SEASON`.
`{"id":1,"name":"player"}`(201, 두 번째 가입은 409 `EMAIL_TAKEN`), 300자쯤 되는 토큰(`expiresIn` 900초), 시즌 `{"id":1,"status":"IN_PROGRESS","currentTurnNo":0,"pathLength":12,"riskLimit":"-0.20000000",…,"initialCash":"30000000",…}`. 시즌 `id`를 `SEASON`으로 쓴다.

```bash
SEASON=1
H=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
POOL=$(curl -sS $B/api/v1/pool)
echo "$POOL" | jq '.assets|length'
curl -sS "${H[@]}" "$B/api/v1/seasons/$SEASON/prices?turnNo=0" | jq -c '{turnNo, first:.prices[0]}'
for i in 0 1 2 3 4 5 6 7; do
  AID=$(echo "$POOL" | jq -r ".assets[$i].assetId"); POS=$(echo "$POOL" | jq -r ".assets[$i].systemPosition")
  curl -sS "${H[@]}" -X POST $B/api/v1/seasons/$SEASON/orders -d "{\"expectedTurnNo\":0,\"side\":\"BUY\",\"assetId\":$AID,\"quantity\":2,\"declaredPosition\":\"$POS\"}"
  echo
done
curl -sS "${H[@]}" $B/api/v1/seasons/$SEASON/holdings | jq -c '{totalValue, n:(.holdings|length)}'
```

**Expect / 기대 결과:** `20`; `{"turnNo":0,"first":{"assetId":1,"price":"677000"}}`; eight lines like `{"orderId":1,"turnNo":0,"side":"BUY","assetId":1,"quantity":2,"fillPrice":"677000","notional":"1354000","fee":"203","cashBalance":"28645797","holdingQuantity":2,…}`; holdings with `n` 8. Before the pool is loaded (Step 2) the pool answers 409 `POOL_NOT_LOADED`; a buy that answers 422 `INSUFFICIENT_CASH` needs a smaller `quantity`.
`20`, `{"turnNo":0,"first":{"assetId":1,"price":"677000"}}`, 매수 응답 여덟 줄(위 예), 보유 `n`이 8. 표본 적재(2단계) 전에는 풀이 409 `POOL_NOT_LOADED`이고, 매수가 422 `INSUFFICIENT_CASH`이면 `quantity`를 줄인다.

Advance the turns. Each answer carries the turn result (`turnNo`, `totalValue`, `periodReturn`, `drawdown`, weights); the body names the turn you leave, so a repeated click cannot advance twice:
턴을 넘긴다. 응답마다 턴 결과(`turnNo`, `totalValue`, `periodReturn`, `drawdown`, 비중)가 오고, 본문이 떠나는 턴을 적으므로 같은 클릭이 두 번 넘기지 못한다:

```bash
for t in 0 1 2 3 4; do
  curl -sS "${H[@]}" -X POST $B/api/v1/seasons/$SEASON/turns -d "{\"expectedTurnNo\":$t}" | jq -c '{turnNo,status,totalValue,periodReturn}'
done
curl -sS "${H[@]}" -X POST $B/api/v1/seasons/$SEASON/orders -d '{"expectedTurnNo":5,"side":"SELL","assetId":1,"quantity":1}' | jq -c .
curl -sS "${H[@]}" -X POST $B/api/v1/seasons/$SEASON/formation-changes -d '{"expectedTurnNo":5,"formation":{"goalkeeper":10,"defender":20,"midfielder":30,"forward":40},"reason":"less defence, more attack"}' | jq -c .
for t in 5 6 7 8 9 10 11; do
  curl -sS "${H[@]}" -X POST $B/api/v1/seasons/$SEASON/turns -d "{\"expectedTurnNo\":$t}" | jq -c '{turnNo,status,totalValue}'
done
curl -sS "${H[@]}" $B/api/v1/seasons/$SEASON/report | jq -c '{seasonStatus,isFinal,finalValue,totalReturn,maxDrawdown,reconMismatchRows,reconTurnBundles}'
curl -sS "${H[@]}" $B/api/v1/seasons/$SEASON/review | jq -c '{seasonStatus,seed,engineVersion,dataSnapshotDate}'
```

**Expect / 기대 결과:** turns `1` … `12`, the last one `"status":"FINISHED"`; the sell answers `"side":"SELL"` with a `realizedPnl`; the formation change answers `"before"`/`"after"`; the report shows `"seasonStatus":"FINISHED","isFinal":true`, `reconMismatchRows` `0` and `reconTurnBundles` `12` (every turn reconciled, nothing mismatched); the review shows the seed and the real dates only now that the season is finished. Prices, returns and ids depend on the seed, so only the shape and the `0` / `12` / `FINISHED` values must match. The same flow runs as a test: `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'` (not run here separately; it is part of `./gradlew test`).
턴이 `1`부터 `12`까지 나오고 마지막이 `"status":"FINISHED"`, 매도 응답은 `"side":"SELL"`에 `realizedPnl`이 있고, 전술 변경은 `"before"`·`"after"`를 돌려주고, 성적표는 `"seasonStatus":"FINISHED","isFinal":true`, `reconMismatchRows` `0`, `reconTurnBundles` `12`(모든 턴 대사, 불일치 없음)를 보이며, 복기는 시즌이 끝난 지금에야 시드와 실제 날짜를 보여 준다. 가격·수익률·id는 시드에 따라 다르므로 모양과 `0`·`12`·`FINISHED`만 맞으면 된다. 같은 흐름이 시험으로도 돈다: `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'`(따로 실행 확인 안 함, `./gradlew test`에 포함).

**If it fails / 안 될 때:** 401 `UNAUTHENTICATED` → the token lasts 15 minutes, log in again. 409 `TURN_MISMATCH` → `expectedTurnNo` is not the current turn (read `GET /api/v1/seasons/$SEASON`). 409 `POOL_NOT_JUDGED` → Step 2 stopped before `12_calc_position`.
401 `UNAUTHENTICATED` → 토큰은 15분이다, 다시 로그인한다. 409 `TURN_MISMATCH` → `expectedTurnNo`가 현재 턴이 아니다(`GET /api/v1/seasons/$SEASON`로 확인). 409 `POOL_NOT_JUDGED` → 2단계가 `12_calc_position` 전에 멈췄다.

- [ ] Step 4 confirmed / 4단계 확인됨: season `FINISHED` · report `reconMismatchRows` 0 · `reconTurnBundles` 12 · review shows dates.

### Step 5. Web screens (웹 화면)

**What it checks / 확인하는 것:** the React app talks to the API through the dev proxy and plays the same season.
React 앱이 개발 프록시로 API와 통신하고 같은 시즌을 두는지.

```bash
cd web
npm ci
ANTLAB_API_PORT=18083 npm run dev -- --port 5199
```

Open http://localhost:5199 (always `localhost`, not `127.0.0.1`). `ANTLAB_API_PORT` points the proxy at the isolated API (default 8080).
http://localhost:5199 를 연다(항상 `localhost`, `127.0.0.1` 아님). `ANTLAB_API_PORT`가 프록시를 격리 API로 향하게 한다(기본 8080).

**Expect / 기대 결과:** `VITE v8.x ready`; the login page shows the status line «API 연결됨 · 종목 20개» (checked in a browser). Then play: sign up → **첫 시즌 시작** → buy at turn 0 → **다음 턴** … → **마지막 턴 넘기기** → **성적표** → **복기** ([web/README.md](web/README.md#play--플레이); the full click-through was not repeated here, `scripts/web_e2e.sh` does it in a headless browser: not run here). Stop with `Ctrl+C`.
`VITE v8.x ready`, 로그인 화면에 상태 줄 «API 연결됨 · 종목 20개»(브라우저로 확인). 이어서 가입 → **첫 시즌 시작** → 0턴 매수 → **다음 턴** … → **마지막 턴 넘기기** → **성적표** → **복기**([web/README.md](web/README.md#play--플레이). 끝까지 클릭하는 것은 여기서 반복하지 않았고, `scripts/web_e2e.sh`가 헤드리스 브라우저로 한다: 직접 실행 확인 안 함). `Ctrl+C`로 멈춘다.

**If it fails / 안 될 때:** the status line says the API is not connected → the proxy port is wrong (`ANTLAB_API_PORT`) or the API is down (`curl` Step 3). «Node» error on `npm ci` → use Node 26 (`web/.nvmrc`). Port 5199 taken → another `--port`.
상태 줄이 API 연결 안 됨이면 프록시 포트(`ANTLAB_API_PORT`)가 틀렸거나 API가 꺼져 있다(3단계 `curl`). `npm ci`의 Node 오류는 Node 26을 쓴다(`web/.nvmrc`). 5199가 쓰이면 다른 `--port`.

- [ ] Step 5 confirmed / 5단계 확인됨: dev server up · status line «API 연결됨 · 종목 20개» · (optional) one season played in the browser.

### Step 6. Monitoring (모니터링)

**What it checks / 확인하는 것:** Prometheus scrapes the API's management port, and Grafana shows the three dashboards filled by the season you just played.
Prometheus가 API 관리 포트를 수집하고 Grafana 대시보드 세 개가 방금 둔 시즌으로 채워지는지.

```bash
curl -sS 'http://127.0.0.1:19093/api/v1/targets' | jq -c '.data.activeTargets[]|{job:.labels.job,health,scrapeUrl}'
curl -sS 'http://127.0.0.1:19093/api/v1/query' --data-urlencode 'query=antlab_turn_advance_total{outcome="committed"}' | jq -c '.data.result[]|{outcome:.metric.outcome,value:.value[1]}'
curl -sS 'http://127.0.0.1:19093/api/v1/query' --data-urlencode 'query=antlab_season_start_total{outcome="committed"}' | jq -c '.data.result[]|{outcome:.metric.outcome,value:.value[1]}'
```

**Expect / 기대 결과** (after about 30 seconds, scrape every 15 s): `{"job":"antlab-api","health":"up","scrapeUrl":"http://api:8081/actuator/prometheus"}`; `committed` turns `12` (or more, one per advanced turn since the start) and seasons `1`. Metric names: `antlab_season_start_total`, `antlab_turn_advance_total`, `antlab_order_rejected_total`, `antlab_reconciliation_mismatch_rules_total`, `antlab_season_halted_total`, `antlab_season_resumed_total` (labels `outcome`, `code`), plus the standard `http_server_requests_seconds_*`, `jvm_*` and `hikaricp_*`.
약 30초 뒤(15초마다 수집): `{"job":"antlab-api","health":"up","scrapeUrl":"http://api:8081/actuator/prometheus"}`, `committed` 턴은 시작 이후 넘긴 턴 수(한 시즌이면 `12`), 시즌은 `1`. 지표 이름: `antlab_season_start_total`, `antlab_turn_advance_total`, `antlab_order_rejected_total`, `antlab_reconciliation_mismatch_rules_total`, `antlab_season_halted_total`, `antlab_season_resumed_total`(라벨 `outcome`·`code`)과 표준 `http_server_requests_seconds_*`·`jvm_*`·`hikaricp_*`.

Grafana: open http://127.0.0.1:13013, user `admin`, password = the value of `GF_SECURITY_ADMIN_PASSWORD` in `.env.try` (open the file in VS Code; never paste it elsewhere). Dashboards › folder `ant-lab`:
Grafana: http://127.0.0.1:13013 을 열고 사용자 `admin`, 비밀번호는 `.env.try`의 `GF_SECURITY_ADMIN_PASSWORD` 값(VS Code로 파일을 연다. 다른 곳에 붙이지 않는다). Dashboards › 폴더 `ant-lab`:

| Dashboard / 대시보드 | Panels / 패널 |
|---|---|
| `ant-lab · API` | Request rate, 5xx ratio, Requests by endpoint, Errors by status, Error ratio 4xx / 5xx, Latency p50 / p95 / p99, p95 by endpoint, Max latency by endpoint, Errors by error code |
| `ant-lab · Game` | Turns committed in range, Reconciliation failures in range, Mismatched rules in range, Orders rejected in range, Seasons started in range, Seasons halted in range, Turn advances by outcome, Reconciliation mismatched rules, Order rejects by code, Season starts by outcome |
| `ant-lab · JVM and DB pool` | Uptime, Hikari timeouts in range, Heap used by pool, Heap committed / max, Non-heap used, GC pause time, GC overhead, Threads, Thread states, Hikari connections, Hikari acquire time, CPU |

**Expect / 기대 결과:** the three dashboards are listed (checked through Grafana's search API); the `Game` panels count your season (set the time range to «Last 15 minutes»). The browser view itself was not screenshotted here. The one-minute automatic check is `scripts/monitoring_smoke.sh` ([DEPLOY §3.2](docs/DEPLOY.md)): in the verification it ended with `monitoring smoke: PASS (50 checks)`.
세 대시보드가 목록에 보이고(Grafana 검색 API로 확인) `Game` 패널이 방금 둔 시즌을 센다(시간 범위 «Last 15 minutes»). 브라우저 화면 자체는 캡처하지 않았다. 1분짜리 자동 확인은 `scripts/monitoring_smoke.sh`다([DEPLOY §3.2](docs/DEPLOY.md)): 검증에서 `monitoring smoke: PASS (50 checks)`로 끝났다.

**If it fails / 안 될 때:** the target is `down` → the API container is not running (Prometheus reaches it by its compose name). Grafana restarts in a loop → `GF_SECURITY_ADMIN_PASSWORD` is empty (`docker compose --env-file .env.try -p antlab-try logs grafana`); the password applies only when the `grafana-data` volume is first created, so a changed value needs a new project. Empty panels → play a season, wait 30 s.
타깃이 `down` → API 컨테이너가 꺼져 있다(Prometheus가 compose 이름으로 닿는다). Grafana가 계속 재시작 → `GF_SECURITY_ADMIN_PASSWORD`가 비었다(`docker compose --env-file .env.try -p antlab-try logs grafana`). 비밀번호는 `grafana-data` 볼륨을 처음 만들 때만 적용되므로 값을 바꾸면 새 프로젝트가 필요하다. 패널이 비면 한 판을 두고 30초 기다린다.

- [ ] Step 6 confirmed / 6단계 확인됨: target `up` · `committed` counters match · three dashboards listed · Game panels move.

### Step 7. Engine (엔진, Python)

**What it checks / 확인하는 것:** the engine tests pass, the container answers `/healthz`, and the two POST routes work with the token header and refuse calls without it.
엔진 시험이 통과하고, 컨테이너가 `/healthz`에 답하고, POST 경로 둘이 토큰 헤더로 동작하며 토큰 없이는 거절되는지.

```bash
cd engine
uv sync --frozen
uv run --frozen ruff check .
uv run --frozen pytest -q
cd ..
```

**Expect / 기대 결과:** `All checks passed!` and `844 passed, 113 skipped, 8 deselected` (skips need an isolated test database, see [engine/README.md](engine/README.md#database--데이터베이스); counts change as tests are added).
`All checks passed!`와 `844 passed, 113 skipped, 8 deselected`(건너뜀은 격리 시험 DB가 필요하다, [engine/README.md](engine/README.md#database--데이터베이스). 시험이 늘면 숫자도 는다).

The engine reads history as the SELECT-only role `antlab_engine`; create it once (after the schema exists), then call the container (`engine` profile, Step 2 started it):
엔진은 SELECT 전용 역할 `antlab_engine`으로 이력을 읽는다. 스키마가 생긴 뒤 한 번 만들고, 컨테이너를 호출한다(`engine` 프로필, 2단계에서 켰다):

```bash
{ echo "SET antlab.expected_database = 'antlab_api';"; cat db/roles/antlab_engine.sql; } | docker compose --env-file .env.try -p antlab-try exec -T db psql -X -v ON_ERROR_STOP=1 -U "$USER" -d antlab_api
ET=$(grep ^ANTLAB_ENGINE_TOKEN .env.try | cut -d= -f2)
curl -sS http://127.0.0.1:18003/healthz
curl -sS -i -X POST http://127.0.0.1:18003/v1/paths -H 'Content-Type: application/json' -d '{}'
curl -sS -X POST http://127.0.0.1:18003/v1/paths -H "X-Antlab-Engine-Token: $ET" -H 'Content-Type: application/json' -d '{"engineVersion":"m2-sb6-v1","seed":"42","length":12,"baseDate":"2026-01-02"}'
curl -sS -X POST http://127.0.0.1:18003/v1/projections -H "X-Antlab-Engine-Token: $ET" -H 'Content-Type: application/json' -d '{"seasonSeed":"42","baseDate":"2026-01-02","n":200,"horizonTurns":60,"riskLimit":"-0.30","portfolios":[{"key":"TEAM","cash":"0.5","weights":[{"asset":"KR:005930","weight":"0.25"},{"asset":"US:SPY","weight":"0.25"}]}]}' | jq -c '{projectionVersion,engineVersion,n,horizonTurns,first:.portfolios[0].bands[0]}'
```

**Expect / 기대 결과:** `SET` and `DO`; `{"status":"ok"}`; `HTTP/1.1 401 Unauthorized` with `{"title":"Unauthenticated","status":401,"detail":"A valid engine token is required.","instance":"/v1/paths","code":"UNAUTHENTICATED"}`; a path `{"engineVersion":"m2-sb6-v1","months":["2023-01",…,"2025-12"],"blocks":[…],"overlapMet":true,…,"historyDigest":"…"}` (12 months, same seed → same months); a projection `{"projectionVersion":"m2-pj-v1","engineVersion":"m2-sb6-v1","n":200,"horizonTurns":60,"first":{"turn":1,"p05":"0.98…","p25":…,"p50":…,"p75":…,"p95":…}}` (value multiples per turn; `key` must be upper case such as `TEAM`). The contract is [docs/api/engine.yaml](docs/api/engine.yaml). The engine has no route to the outside: it only reads the database.
`SET`과 `DO`, `{"status":"ok"}`, `HTTP/1.1 401 Unauthorized`와 위 problem 본문, 경로 `{"engineVersion":"m2-sb6-v1","months":["2023-01",…,"2025-12"],"blocks":[…],"overlapMet":true,…,"historyDigest":"…"}`(12개 달, 같은 시드는 같은 달), 예상 분포 `{"projectionVersion":"m2-pj-v1","engineVersion":"m2-sb6-v1","n":200,"horizonTurns":60,"first":{"turn":1,"p05":"0.98…",…}}`(턴별 가치 배수, `key`는 `TEAM`처럼 대문자). 계약은 [docs/api/engine.yaml](docs/api/engine.yaml). 엔진은 바깥으로 나가는 경로가 없고 DB만 읽는다.

**If it fails / 안 될 때:** 400 `INVALID_ARGUMENT` lists `errors[].field` (e.g. an unknown key such as `engineVersion` on `/v1/projections` or a lower-case `key`). A 5xx on `/v1/paths` → the role script was not run or the sample is not loaded. Engine container not healthy → `ANTLAB_ENGINE_TOKEN` missing in `.env.try`.
400 `INVALID_ARGUMENT`은 `errors[].field`를 알려 준다(예: `/v1/projections`의 모르는 키 `engineVersion`, 소문자 `key`). `/v1/paths`의 5xx는 역할 스크립트를 안 돌렸거나 표본이 없다. 엔진 컨테이너가 healthy가 아니면 `.env.try`에 `ANTLAB_ENGINE_TOKEN`이 없다.

- [ ] Step 7 confirmed / 7단계 확인됨: pytest passes · `/healthz` ok · 401 without token · `/v1/paths` and `/v1/projections` answer with the token.

### Step 8. Agent runner, server side (에이전트 러너, 서버 쪽)

**What it checks / 확인하는 것:** the internal `/internal/agent-runner/v1` routes: register an agent, start its season, read its turn view, submit a decision and advance. Only the API side exists; the runner service itself and every real model call are not built, so no model is called here. You hand-write the decision, and the engine validates it like a real one.
내부 경로 `/internal/agent-runner/v1`: 에이전트 등록 → 시즌 시작 → 턴 화면 → 결정 제출 → 턴 넘기기. API 쪽만 있다. 러너 서비스 자체와 실제 모델 호출은 아직 없으므로 여기서는 모델을 부르지 않고, 결정은 손으로 쓴다(엔진은 실제 결정처럼 검사한다).

The API needs the owner member id: the first member you signed up has id `1`. Add it and restart `api` (the token is already in `.env.try`):
API가 소유자 회원 번호를 알아야 한다. 처음 가입한 회원의 id는 `1`이다. 추가하고 `api`를 다시 만든다(토큰은 `.env.try`에 이미 있다):

```bash
echo "ANTLAB_AGENT_RUNNER_OWNER_ID=1" >> .env.try
docker compose --env-file .env.try -p antlab-try --profile api up -d --wait --no-deps api
```

```bash
RT=$(grep ^ANTLAB_AGENT_RUNNER_TOKEN .env.try | cut -d= -f2)
R=http://127.0.0.1:18083/internal/agent-runner/v1
HR=(-H "X-Antlab-Runner-Token: $RT" -H 'Content-Type: application/json')
Z=$(printf '0%.0s' $(seq 1 64))
curl -sS -i -X POST $R/agents -H 'Content-Type: application/json' -d '{}'
AGENT=$(curl -sS "${HR[@]}" -X POST $R/agents -d '{"personaKey":"value-investor","modelRoute":"OLLAMA","modelId":"example-model","displayName":"Example agent"}' | jq -r .agentId)
SID=$(curl -sS "${HR[@]}" -X POST $R/agents/$AGENT/seasons -d "{\"riskLimit\":\"-0.20\",\"formation\":{\"goalkeeper\":10,\"defender\":30,\"midfielder\":30,\"forward\":30},\"pathLength\":12,\"config\":{\"personaVersion\":1,\"personaSha256\":\"$Z\",\"modelRequested\":\"example-model\",\"temperature\":\"0.2\",\"maxTokens\":2000,\"turnTimeoutMs\":60000,\"promptTemplateVersion\":\"t1\",\"runnerVersion\":\"r1\",\"budgetCalls\":100,\"budgetUsd\":\"1.00\"}}" | jq -r .id)
echo "AGENT=$AGENT SID=$SID"
curl -sS "${HR[@]}" $R/agents/$AGENT/seasons/$SID/turn-view | jq -c '{keys:keys, agent:.agent}'
curl -sS -w ' [%{http_code}]\n' "${HR[@]}" -X POST $R/agents/$AGENT/seasons/$SID/turns -d '{"expectedTurnNo":0}'
curl -sS -w ' [%{http_code}]\n' "${HR[@]}" -X POST $R/agents/$AGENT/seasons/$SID/turns/0/decision -d '{"outcome":"NO_RESPONSE","decision":null,"calls":[{"attemptNo":1,"kind":"TURN_DECISION","status":"TIMEOUT","modelRequested":"example-model","requestText":"hand-written, no model called","latencyMs":0}]}'
curl -sS -w ' [%{http_code}]\n' "${HR[@]}" -X POST $R/agents/$AGENT/seasons/$SID/turns -d '{"expectedTurnNo":0}' | cut -c1-160
curl -sS -w ' [%{http_code}]\n' "${HR[@]}" -X POST $R/agents/$AGENT/seasons/$SID/turns/1/decision -d '{"outcome":"DECISION","decision":{"schema":"turn-decision/1","formation_change":null,"orders":[{"asset":"KR:005930","side":"BUY","quantity":1,"position":"MIDFIELDER","source":{"kind":"DIRECT"}}],"theses":[],"reason":"hand-written example"},"calls":[{"attemptNo":1,"kind":"TURN_DECISION","status":"OK","modelRequested":"example-model","requestText":"hand-written","responseText":"hand-written","latencyMs":0}]}'
```

**Expect / 기대 결과** (ids come from the answers: `AGENT` is the agent member id, `SID` the agent's season id; a fresh project gives `2` and `2` after the Step 4 season): `401` `UNAUTHENTICATED` without the header; `AGENT=2 SID=…` (the registration answers `201`, then `200` when repeated); turn view keys `["agent","holdings","lastTurn","pool","prices","season"]` with `agent.schema` `turn-decision/1`; advancing without a decision → `409 DECISION_MISSING`; the `NO_RESPONSE` decision → `"outcome":"NO_RESPONSE","replayed":false` (200; a repeated `GET …/decision` says `"replayed":true`); advance → `"turnNo":1,"status":"IN_PROGRESS"`; the hand-written `DECISION` → `"orders":[{"index":0,"status":"APPLIED",…}]`. Registering the same persona and model again answers `200` instead of `201`. Contract: [docs/api/agent-runner.yaml](docs/api/agent-runner.yaml). A human access token is never read on these paths, and the runner token is never read on `/api/v1`.
(id는 응답에서 읽는다. `AGENT`는 에이전트 회원 id, `SID`는 그 에이전트의 시즌 id이고, 4단계 시즌만 있는 새 프로젝트에서는 둘 다 `2`다.) 헤더 없으면 `401` `UNAUTHENTICATED`, `AGENT=2 SID=…`(등록은 `201`, 반복하면 `200`), 턴 화면 키 `["agent","holdings","lastTurn","pool","prices","season"]`와 `agent.schema` `turn-decision/1`, 결정 없이 넘기면 `409 DECISION_MISSING`, `NO_RESPONSE` 결정은 `"outcome":"NO_RESPONSE","replayed":false`(200. `GET …/decision`을 다시 부르면 `"replayed":true`), 넘기면 `"turnNo":1,"status":"IN_PROGRESS"`, 손으로 쓴 `DECISION`은 `"orders":[{"index":0,"status":"APPLIED",…}]`. 같은 페르소나·모델을 다시 등록하면 `201` 대신 `200`이다. 계약은 [docs/api/agent-runner.yaml](docs/api/agent-runner.yaml). 이 경로에서는 사람의 접근 토큰을 읽지 않고, `/api/v1`에서는 러너 토큰을 읽지 않는다.

**If it fails / 안 될 때:** every call 401 → `ANTLAB_AGENT_RUNNER_TOKEN` is empty in the container (recreate `api` after editing `.env.try`). 404 `NOT_FOUND` → the owner id is wrong (`ANTLAB_AGENT_RUNNER_OWNER_ID`), or the agent or season id is not yours; one answer for all, by design. 400 `INVALID_ARGUMENT` → shape error in the body (the decision must match [docs/api/turn-decision-1.schema.json](docs/api/turn-decision-1.schema.json)). Live model calls (OpenRouter probe in `ai/`) need your key and cost money: not run here.
모든 호출이 401 → 컨테이너의 `ANTLAB_AGENT_RUNNER_TOKEN`이 비었다(`.env.try`를 고친 뒤 `api`를 다시 만든다). 404 `NOT_FOUND` → 소유자 id(`ANTLAB_AGENT_RUNNER_OWNER_ID`)가 틀렸거나 에이전트·시즌 id가 내 것이 아니다(설계상 모두 같은 응답). 400 `INVALID_ARGUMENT` → 본문 모양 오류(결정은 [docs/api/turn-decision-1.schema.json](docs/api/turn-decision-1.schema.json)과 맞아야 한다). 실제 모델 호출(`ai/`의 OpenRouter 탐침)은 내 키와 비용이 필요해 실행하지 않았다.

Offline checks of the Python side: `cd ai && uv sync --frozen && uv run --frozen pytest -q` → `333 passed, 1 skipped` (no network).
Python 쪽 오프라인 확인: `cd ai && uv sync --frozen && uv run --frozen pytest -q` → `333 passed, 1 skipped`(네트워크 없음).

- [ ] Step 8 confirmed / 8단계 확인됨: 401 without token · agent registered (201, then 200) · turn view · `DECISION_MISSING` before a decision · `APPLIED` after one.

### Step 9. Collector job (수집 잡)

**What it checks / 확인하는 것:** the KIS daily-price collector (M1-05, M1-06) works against fake responses and refuses to run unless explicitly switched on. **No live KIS call has been made so far**: the real run needs your KIS keys, and the live smoke is still waiting for your decision.
KIS 일봉 수집기(M1-05·M1-06)가 가짜 응답으로 동작하고, 명시적으로 켜지 않으면 실행을 거부하는지. **라이브 KIS 호출은 아직 하지 않았다.** 실제 실행에는 내 KIS 키가 필요하고 라이브 스모크는 소유자 결정을 기다린다.

```bash
./gradlew test --tests 'antlab.collect.*' -PexcludeSqlGolden
```

**Expect / 기대 결과:** `BUILD SUCCESSFUL`; the tests use synthetic FAKE responses and a throwaway PostgreSQL container, no network and no KIS key (184 tests, 0 failures, `BUILD SUCCESSFUL`).
`BUILD SUCCESSFUL`. 시험은 합성 FAKE 응답과 일회용 PostgreSQL 컨테이너만 쓰고 네트워크·KIS 키가 없다(184건, 실패 0, `BUILD SUCCESSFUL`).

The one-run gate: a run is requested but the collector is not enabled, so nothing is sent.
일회 실행 게이트: 실행을 요청했지만 수집기가 꺼져 있으면 아무것도 보내지 않는다.

```bash
./gradlew bootJar
ANTLAB_DB_URL=jdbc:postgresql://127.0.0.1:55433/antlab_api ANTLAB_JWT_SECRET="$(grep ^ANTLAB_JWT_SECRET .env.try | cut -d= -f2-)" java -jar build/libs/app.jar --antlab.collect.run-once=KIS_DAILY_PRICE --server.port=0 --management.server.port=0
echo "exit=$?"
```

**Expect / 기대 결과:** the process exits with `exit=1` and the log shows `IllegalStateException: antlab.collect.run-once needs antlab.collect.kis.enabled=true`; no request was sent, and `select count(*) from job_run` and `from asset_daily_price` stay `0`.
프로세스가 `exit=1`로 끝나고 로그에 `IllegalStateException: antlab.collect.run-once needs antlab.collect.kis.enabled=true`가 보인다. 요청은 나가지 않았고 `select count(*) from job_run`, `from asset_daily_price`는 계속 `0`이다.

The real run (`--antlab.collect.kis.enabled=true`, token, rate limits, throwaway database) is described in [KIS daily price collector](#kis-daily-price-collector-local-only-kis-일봉-수집기-로컬-전용): not run here (needs your KIS keys). The `prod` profile refuses to start with the collector on (`CollectProdGuard`).
실제 실행(`--antlab.collect.kis.enabled=true`, 토큰, 속도 제한, 일회용 DB)은 [KIS 일봉 수집기](#kis-daily-price-collector-local-only-kis-일봉-수집기-로컬-전용)에 있다(내 KIS 키가 필요해 직접 실행 확인 안 함). `prod` 프로필은 수집기를 켠 채로 시작하기를 거부한다(`CollectProdGuard`).

**If it fails / 안 될 때:** tests fail on «Docker 환경을 찾을 수 없음» → start Docker Desktop. The gate command connects to a database first: use the isolated port 55433, never `antlab-db-1`.
시험이 «Docker 환경을 찾을 수 없음»으로 실패 → Docker Desktop을 켠다. 게이트 명령은 먼저 DB에 접속한다. 격리 포트 55433을 쓰고 `antlab-db-1`은 쓰지 않는다.

- [ ] Step 9 confirmed / 9단계 확인됨: collector tests pass · gate refuses without `kis.enabled` · you know no live call was made.

### Step 10. CI and security guards (CI·보안 가드)

**What it checks / 확인하는 것:** the guards that fail closed: the firewall answers one fixed problem+json, the app database role cannot delete or change the schema, and the dev reconciliation endpoints exist only in the `local` profile.
닫는 쪽으로 실패하는 가드: 방화벽이 고정된 problem+json 하나로 답하고, 앱 DB 역할이 삭제·스키마 변경을 못 하고, 개발용 대사 엔드포인트가 `local` 프로필에만 있는지.

```bash
B=http://127.0.0.1:18083
curl -sS -i --path-as-is "$B//readyz"
curl -sS -i --path-as-is "$B/api/v1/%ZZ"
curl -sS -i -X FOO "$B/readyz"
curl -sS -i $B/api/v1/seasons
curl -sS -i -X POST $B/api/v1/dev/seasons/1/reconciliation/resume
```

**Expect / 기대 결과:** the first three answer `HTTP/1.1 400` with `{"title":"Bad Request","status":400,"detail":"요청의 주소나 헤더를 해석할 수 없다","instance":"/","code":"MALFORMED_REQUEST"}` (`Content-Type: application/problem+json`; the body echoes nothing of your request); the fourth `401` `UNAUTHENTICATED`; the fifth `401` (the container runs `prod`).
앞의 셋은 `HTTP/1.1 400`과 위 본문(`Content-Type: application/problem+json`, 요청의 어떤 부분도 되풀이하지 않는다), 넷째는 `401` `UNAUTHENTICATED`, 다섯째는 `401`(컨테이너는 `prod`).

```bash
psql -X -U antlab_app -d antlab_api -c "DELETE FROM member"
psql -X -U antlab_app -d antlab_api -c "CREATE TABLE t(x int)"
psql -X -U antlab_app -d antlab_api -Atc "SELECT count(*) FROM member"
{ echo "SET antlab.expected_database = 'antlab_api';"; cat db/roles/antlab_owner_app.sql; } | docker compose --env-file .env.try -p antlab-try exec -T db psql -X -v ON_ERROR_STOP=1 -U "$USER" -d antlab_api
psql -X -d antlab_api -Atc "select rolname, rolsuper, rolcreatedb from pg_roles where rolname like 'antlab_%' order by 1"
```

**Expect / 기대 결과 (MVP-20):** `ERROR:  permission denied for table member`; `ERROR:  permission denied for schema public`; a row count (SELECT is allowed); the role script prints `SET` and `DO` (it is one atomic, repeatable statement; on a volume that already holds data, take a backup first as in [db/README.md «App roles»](db/README.md#app-roles-mvp-20--앱-역할-mvp-20)); roles `antlab_app|f|f`, `antlab_engine|f|f`, `antlab_owner|f|f` (none is a superuser).
`ERROR:  permission denied for table member`, `ERROR:  permission denied for schema public`, 행 수(SELECT는 허용), 역할 스크립트는 `SET`·`DO`를 출력한다(문장 하나라 원자적이고 반복 실행해도 같다. 이미 데이터가 있는 볼륨이면 [db/README.md「App roles」](db/README.md#app-roles-mvp-20--앱-역할-mvp-20)처럼 먼저 백업한다), 역할 `antlab_app|f|f`·`antlab_engine|f|f`·`antlab_owner|f|f`(슈퍼유저 없음).

The dev endpoints (profile `local`, `127.0.0.1` only, outside the contract) need an `ADMIN` member and the fixed header. Run the app as in Step 3 (`bootRun` on 18084), then:
개발용 엔드포인트(`local` 프로필, `127.0.0.1` 전용, 계약 밖)는 `ADMIN` 회원과 고정 헤더가 필요하다. 3단계처럼 앱을 띄운 뒤(`bootRun`, 18084):

```bash
psql -X -d antlab_api -c "UPDATE member SET role='ADMIN' WHERE lower(email)=lower('player@example.test')"
D=http://127.0.0.1:18084
TOKEN=$(curl -sS -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"player@example.test","password":"correct-horse-battery"}' | jq -r .accessToken)
curl -sS -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $TOKEN" $D/api/v1/dev/seasons/1/reconciliation/resume
curl -sS -w ' [%{http_code}]\n' -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Antlab-Dev: true' $D/api/v1/dev/seasons/1/reconciliation/resume
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Antlab-Dev: true' $D/api/v1/dev/seasons/1/reconciliation | jq -c '{seasonId,turnNo,result,mismatchRules}'
```

**Expect / 기대 결과:** `UPDATE 1`; without the header `403`; with it `{"seasonId":1,"status":"FINISHED","resumed":false} [200]` (a season that is not halted is left as it is); the manual reconciliation `{"seasonId":1,"turnNo":12,"result":"MATCH","mismatchRules":0}`. A season halted by repeated reconciliation failures (`HALTED`) answers `resumed:true` and gets one more try; producing a real halt needs a deliberately broken ledger, so that path is covered by `./gradlew test --tests 'antlab.api.dev.*' --tests 'antlab.scoring.*' -PexcludeSqlGolden` and was not reproduced by hand (직접 실행 확인 안 함 / not run by hand). Calls to a halted agent season are refused with `SEASON_HALTED`.
`UPDATE 1`, 헤더 없이 `403`, 헤더를 붙이면 `{"seasonId":1,"status":"FINISHED","resumed":false} [200]`(멈추지 않은 시즌은 그대로 둔다), 수동 대사 `{"seasonId":1,"turnNo":12,"result":"MATCH","mismatchRules":0}`. 대사 실패가 거듭되어 `HALTED`가 된 시즌은 `resumed:true`로 답하고 한 번 더 시도한다. 실제 정지를 만들려면 일부러 원장을 깨야 해서 그 경로는 `./gradlew test --tests 'antlab.api.dev.*' --tests 'antlab.scoring.*' -PexcludeSqlGolden`이 다루고, 손으로 재현하지는 않았다(직접 실행 확인 안 함). 정지된 에이전트 시즌의 호출은 `SEASON_HALTED`로 거절된다.

CI: GitHub Actions runs the Gradle tests (with a reviewed list of allowed skips, `scripts/ci_expected_skips.txt`), the SQL schema job, the image build and the web checks on every pull request; the workflows are in `.github/workflows/` and were not run locally (not run here). Local equivalents of the container checks: `scripts/runtime_smoke.sh startup-fail` and `scripts/runtime_smoke.sh graceful` (both ended with `runtime smoke (…): PASS`, about 2–4 s each after the image exists).
CI: GitHub Actions가 모든 풀 리퀘스트에서 Gradle 시험(검토된 건너뜀 목록 `scripts/ci_expected_skips.txt`), SQL 스키마 job, 이미지 빌드, 웹 검사를 돌린다. 워크플로는 `.github/workflows/`에 있고 로컬에서 실행하지 않았다(직접 실행 확인 안 함). 컨테이너 검사의 로컬 대응은 `scripts/runtime_smoke.sh startup-fail`, `scripts/runtime_smoke.sh graceful`(둘 다 `runtime smoke (…): PASS`로 끝났다. 이미지가 있으면 각 2–4초).

**If it fails / 안 될 때:** the firewall request shows the HTML error page → you are not talking to the API port (check `B`). Dev call 401 → login again (15 minutes), 403 → the member is not `ADMIN` or the header is missing, 404 → the app runs `prod` (use `bootRun`).
방화벽 요청에 HTML 오류 페이지가 보이면 API 포트가 아니다(`B` 확인). 개발 호출 401 → 다시 로그인(15분), 403 → `ADMIN`이 아니거나 헤더가 없다, 404 → 앱이 `prod`로 떠 있다(`bootRun`을 쓴다).

- [ ] Step 10 confirmed / 10단계 확인됨: three firewall 400s identical · `antlab_app` cannot DELETE or CREATE · dev endpoint 403 without header, 200 with it.

### Clean up (정리)

Remove only the guide's own project; it never touches `antlab-db-1`. Stop the dev servers with `Ctrl+C`.
이 안내의 프로젝트만 지운다. `antlab-db-1`은 건드리지 않는다. 개발 서버는 `Ctrl+C`로 멈춘다.

```bash
docker compose --env-file .env.try -p antlab-try --profile api --profile monitoring --profile engine down -v --rmi local
```

**Expect / 기대 결과:** containers, network and the three volumes of `antlab-try` are removed; `docker ps` still lists `antlab-db-1`. `.env.try` stays (git-ignored); delete it by hand when you no longer need it.
`antlab-try`의 컨테이너·네트워크·볼륨 셋이 지워지고 `docker ps`에는 `antlab-db-1`이 그대로 보인다. `.env.try`는 남는다(git 무시). 필요 없으면 직접 지운다.

## 기술 스택

Java 21 · Spring Boot 4.1 · PostgreSQL 17 · JPA (리포트 쿼리에 MyBatis 선택) · springdoc-openapi(저장소 계약 `docs/api/openapi.yaml`) · Python (FastAPI, pandas, 시뮬레이션 엔진) · 모바일 앱 중심 + 데스크탑 앱 + 웹 페이지 (React Native, React — 웹은 WEB 단계, 앱은 이후) · RAG / LangChain / LangGraph (FastAPI·Python 서비스에서 실행, 이후) · Docker Compose(로컬 DB는 지금, 서버 배포는 OPS) · GitHub Actions · GHCR · 오라클 클라우드 인프라(OCI, Oracle Cloud Infrastructure) Ampere VM + Caddy (이후)

## API 개발 실행 (P0-22~)

저장소 루트가 Spring Boot 프로젝트다(`src/`, `build.gradle`, `gradlew`). `db/`의 SQL 파일은 빌드에 들어가지 않는다.

준비물: JDK 21(`java -version`), Docker Desktop(시험과 `bootTestRun`이 일회용 PostgreSQL 17 컨테이너를 쓴다).

- `./gradlew test` — 전체 시험. Docker Desktop이 꺼져 있으면 "Docker 환경을 찾을 수 없음"으로 실패한다. 소유자 DB(`antlab-db-1`)에는 접속하지 않는다. 표본 CSV가 `db/sample`에 있으면 SQL 골든(약 2–3분)도 함께 실행한다. 표본 CSV가 없으면 그 부분만 이유를 밝힌 뒤 건너뛴다. 제외하려면 `-PexcludeSqlGolden` — [db/README.md「SQL 골든 하네스」](db/README.md#sql-골든-하네스-p0-25).
- P0-26 금액 계산 시험은 다음 두 명령으로 나눠 실행한다: `./gradlew test --tests 'antlab.common.money.MoneyMathTest'`(DB 없음)와 `./gradlew test --tests 'antlab.common.money.MoneyMathSqlParityTest'`(일회용 PostgreSQL 17)이다.
- P0-29 경로 생성기 시험만: `./gradlew test --tests 'antlab.engine.*'` — 순수 단위 시험(시드 1,000개 성질 검사·고정 벡터)과 일회용 PostgreSQL 17 통합 시험을 실행한다. 생성 경로는 `month_start`(달)를 기준으로 재현하며, 고정 벡터를 바꿀 때는 `engine_version`을 함께 올린다.
- P0-30 시즌 시작 시험만: `./gradlew test --tests 'antlab.api.season.*' --tests 'antlab.member.*' --tests 'antlab.season.*' -PexcludeSqlGolden` — 합성 고정 데이터(실제 표본 아님)로 시작·조회·상한·동시성·롤백·노출을 확인한다. 실제 표본 골든은 `-PonlySqlGolden --tests '*SeasonStartGoldenTest*'`(표본 CSV 필요).
- P0-32 대사 시험만: `./gradlew test --tests 'antlab.scoring.*' --tests 'antlab.api.dev.*' -PexcludeSqlGolden` — 22 SQL 사본이 같은지, Java 결과가 `recon_rules`와 같은지(합성 상태·깨뜨린 사례·1턴 이후 가격), 기록기, 개발 엔드포인트(`local` 프로필), 동시성을 확인한다. 실제 표본 골든은 `-PonlySqlGolden --tests '*ReconciliationGoldenTest*'`(표본 CSV 필요).
- 개발용 수동 대사 호출(`local` 프로필, `127.0.0.1`에서만 열린다): `curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Antlab-Dev: true' http://127.0.0.1:8080/api/v1/dev/seasons/1/reconciliation` — `ADMIN` 역할의 토큰(`TOKEN`은 `POST /api/v1/auth/login`의 `accessToken`, ADMIN은 erd §9의 SQL로 만든다)과 고정 헤더가 필요하고, 없으면 401 또는 403 `FORBIDDEN`이다. / It needs a token with role `ADMIN` (`TOKEN` = `accessToken` from login; make ADMIN with the SQL in erd §9) and the fixed header; otherwise 401 or 403 `FORBIDDEN`. 응답에 규칙별 기대·실제·차이·`detail`이 그대로 담긴다.
- P0-31 매수 주문 시험만: `./gradlew test --tests 'antlab.api.order.*' -PexcludeSqlGolden` — 합성 고정 데이터(실제 표본 아님)로 정상·음성·1턴 이후 매수·무결성 가드·동시성·잠금 순서·노출을 확인한다. 실제 표본 골든(SQL 팀 구성 ↔ API 주문 재생, 대사 규칙 15개)은 `-PonlySqlGolden --tests '*BuyOrderGoldenTest*'`(표본 CSV 필요).
- `./gradlew bootTestRun` — 일회용 PostgreSQL 17로 앱을 띄운다. 소유자 DB가 필요 없어 가장 안전하다. 8080이 쓰이고 있으면 `./gradlew bootTestRun --args='--server.port=18080'`.
- `./gradlew bootRun` — 로컬 compose `db`의 `antlab_api` DB로 띄운다. 처음 한 번은 빈 `antlab_api`를 만들고(인코딩·시간대 포함) 앱을 띄워 Flyway V1–V4를 적용한 뒤 표본을 적재한다. 명령은 [db/README.md「API DB와 실습 DB」](db/README.md#api-db와-실습-db)에 있다. 접속 정보는 환경 변수 `ANTLAB_DB_URL`·`ANTLAB_DB_USER`·`ANTLAB_DB_PASSWORD`이고, 없으면 `jdbc:postgresql://localhost:5432/antlab_api`·OS 사용자·비밀번호 없음이다. It also needs `ANTLAB_JWT_SECRET` (Base64 of at least 32 bytes, `openssl rand -base64 32`), from the environment or the git-ignored `config/application-local.yml`; no key is stored in the repository. / `ANTLAB_JWT_SECRET`(32바이트 이상의 Base64, `openssl rand -base64 32`)도 필요하다. 환경 변수나 git이 무시하는 `config/application-local.yml`로 준다. 키는 저장소에 두지 않는다.
- 매핑·잠금 시험만: `./gradlew test --tests 'antlab.mapping.*' --tests 'antlab.persistence.*'` — 엔티티 23개의 열·enum·불변 대조, 가변 엔티티와 두 스레드 잠금(`FOR NO KEY UPDATE`)·낙관적 락 시험이 돈다. 일회용 컨테이너만 쓴다.
- 컨테이너로 실행(P0-24) — `db`와 `api`를 한 번에 올린다. `api`는 compose profile `api`라서 `--profile api`를 붙여야 켜진다(붙이지 않은 `docker compose up -d`는 지금처럼 `db`만 켠다). 늘 같이 켜고 싶으면 루트 `.env`에 `COMPOSE_PROFILES=api`를 둔다(선택, 기본은 끔). `bootRun`과 달리 `prod` 프로필이라 Swagger·명세가 꺼져 있고, 같은 이미지가 후속 CI·서버 배포에서도 쓰인다.
  1. Docker Desktop을 켜고 준비 완료(고래 아이콘 안정)를 확인한다. VS Code로 저장소 루트를 열고 통합 터미널(`` Ctrl+` ``)에서 실행한다.
  2. 새 볼륨(처음): `docker compose --profile api up -d --build --wait --wait-timeout 240` — 이미지를 빌드하고, `db`가 빈 `antlab_api`를 만들고, `api`가 Flyway V1–V4를 적용한 뒤 두 서비스가 `healthy`가 되면 끝난다. 첫 빌드는 Gradle 배포판과 의존성을 내려받아 몇 분 걸린다.
  3. 이미 쓰던 볼륨(예: 소유자의 `antlab_db-data`)에는 `POSTGRES_DB` 변경이 적용되지 않는다. `antlab_api`가 있는지 먼저 확인하고 없을 때만 한 번 만든다 — [db/README.md「API 컨테이너」](db/README.md#api-컨테이너-p0-24). 볼륨을 지워서 해결하지 않는다.
  4. Check: `curl -fsS http://127.0.0.1:8080/readyz` returns `{"status":"UP"}`, and `db` and `api` are healthy in Docker Desktop's Containers view. Readiness means the app accepts requests with the database attached, not that the sample is loaded. / 확인: `curl -fsS http://127.0.0.1:8080/readyz` → `{"status":"UP"}`, Docker Desktop의 Containers에서 `db`·`api`가 healthy. readiness는 "요청을 받을 수 있고 DB가 붙어 있다"는 뜻이지 표본 적재가 끝났다는 뜻이 아니다.
  5. 컨테이너와 `bootRun`/`bootTestRun`을 동시에 켜면 8080이 겹친다. 컨테이너 포트는 `ANTLAB_API_PORT=18080 docker compose --profile api up -d`로 바꾼다(다시 `up`할 때도 같은 값을 붙인다). 잠깐 멈출 때는 `docker compose stop`, 다시 켤 때는 `docker compose start`. When two local apps run at once, also move the management port: add `--management.server.port=18081`. / 로컬 앱 두 개를 함께 켤 때는 관리 포트도 옮긴다: `--management.server.port=18081`을 붙인다.
  6. 표본을 적재할 때는 `docker compose stop api`로 앱을 멈추고 [db/README.md](db/README.md#api-db와-실습-db)대로 실행한 뒤 `docker compose start api`.
  7. QA·시험은 소유자 프로젝트를 건드리지 않게 `ANTLAB_DB_PORT=55432 ANTLAB_API_PORT=18080 docker compose --profile api -p antlab-qa up -d --build --wait` 로 띄우고, 끝나면 `docker compose --profile api -p antlab-qa down -v --rmi local`(정리에도 `--profile api`를 붙인다).
- Health and metrics: the API port has only `/readyz` and `/livez`; the Actuator (`/actuator/health/**`, `/actuator/prometheus`) is on the management port `8081` only (`127.0.0.1` for `bootRun`/`bootTestRun`; the container does not publish it). / 헬스·지표: API 포트에는 `/readyz`·`/livez`만 있고, Actuator(`/actuator/health/**`, `/actuator/prometheus`)는 관리 포트 `8081`에만 있다(`bootRun`·`bootTestRun`은 `127.0.0.1`, 컨테이너는 게시하지 않는다).
- 확인 URL: `/readyz`, `POST·GET /api/v1/seasons`·`GET /api/v1/seasons/{seasonId}`·`GET /api/v1/seasons/{seasonId}/prices?turnNo=`·`POST /api/v1/seasons/{seasonId}/orders`(P0-31 시장가 매수; P0-30 — 로그인한 토큰(`Authorization: Bearer`)이 필요하다. 회원마다 자기 시즌만 다룬다(SEC-05부터 모든 프로필)), `/api/v1/pool`(표본 적재와 포지션 계산 뒤 20종목 — 적재 전이면 409 `POOL_NOT_LOADED`, 판정 전이면 409 `POOL_NOT_JUDGED`). 명세 `/v3/api-docs`와 `/swagger-ui.html`은 `local` 프로필(`bootRun`·`bootTestRun`의 기본)에서만 열리며, 프로필을 지정하지 않거나 `prod`이면 꺼져 있다.
- 개인 덮어쓰기 설정은 프로젝트 루트의 `config/application-local.yml`에 둔다(git이 무시하고 jar에 들어가지 않는다). `src/main/resources/`에 두면 jar에 들어가므로 두지 않는다.
- P0-40 one-season E2E (sample CSVs needed): `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'`; the error sweep (`ApiErrorSweepTest`) runs in every `./gradlew test`. / P0-40 한 판 E2E(표본 CSV 필요): `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'`. 오류 응답 전수 시험(`ApiErrorSweepTest`)은 모든 `./gradlew test`에서 돈다.

VS Code(macOS): 확장 "Extension Pack for Java", "Gradle for Java", "Spring Boot Extension Pack". 통합 터미널(`` Ctrl+` ``)에서 `./gradlew …`를 실행하고, 왼쪽 비커 아이콘(시험 탐색기)으로 시험을 하나씩 돌리며, Gradle 패널(코끼리 아이콘)의 `Tasks › application › bootTestRun`으로 앱을 띄운다. 가져오기가 꼬이면 `Cmd+Shift+P` → "Java: Clean Java Language Server Workspace". `.vscode/`는 git이 무시하므로 실행 설정 파일은 저장소에 없다.

## Play one season through the API (API로 한 판)

Start the local database with `docker compose up -d --wait db`, prepare `antlab_api` and load the sample as in [db/README.md «API DB와 실습 DB»](db/README.md#api-db와-실습-db), then run `./gradlew bootRun` (the `local` profile). Open [docs/api/one-season.http](docs/api/one-season.http) in VS Code (REST Client extension). Send block 0 (sign up, once) and block 0b (log in) first; every season call carries the token, and a 401 means: send 0b again. Then send the requests from top to bottom: start a season, read the pool and the turn-0 prices, buy the team, advance the turns (sell and change the formation in between), then read the report and the review. A season has 12 turns, so the whole play is a few dozen clicks.
로컬 DB를 `docker compose up -d --wait db`로 띄우고 [db/README.md «API DB와 실습 DB»](db/README.md#api-db와-실습-db)대로 `antlab_api`를 준비해 표본을 적재한 뒤 `./gradlew bootRun`(`local` 프로필)을 실행한다. VS Code에서 [docs/api/one-season.http](docs/api/one-season.http)를 연다(REST Client 확장). 먼저 0번 블록(가입, 한 번)과 0b 블록(로그인)을 보낸다. 시즌 호출은 모두 토큰을 싣고, 401이 오면 0b를 다시 보낸다. 그다음 위에서부터 차례로 보낸다: 시즌 시작, 종목 풀과 0턴 가격 조회, 팀 매수, 턴 진행(중간에 매도·전술 변경), 성적표와 복기 조회. 한 시즌은 12턴이라 전체가 클릭 수십 번이다.

The `api` container (profile `prod`) and `bootRun` (profile `local`) behave the same: season calls need a token from `POST /api/v1/auth/login`, and each member sees only their own seasons (SEC-05).
`api` 컨테이너(`prod` 프로필)와 `bootRun`(`local` 프로필)은 같게 동작한다: 시즌 호출에는 `POST /api/v1/auth/login`의 토큰이 필요하고, 회원마다 자기 시즌만 본다(SEC-05).

Web (WEB-01): see [web/README.md](web/README.md) — run `cd web && npm ci && npm run dev` and open http://localhost:5173; the dev server forwards `/api` to the API on 127.0.0.1:8080.
웹(WEB-01): [web/README.md](web/README.md) 참고 — `cd web && npm ci && npm run dev` 뒤 http://localhost:5173 을 연다. 개발 서버가 `/api` 요청을 127.0.0.1:8080의 API로 넘긴다.

A full browser check of one season at phone and desktop width: `scripts/web_e2e.sh` (see [web/README.md](web/README.md#end-to-end-check--종단-확인)); it uses its own throwaway containers.
휴대폰·데스크톱 폭에서 한 시즌을 브라우저로 끝까지 확인하려면 `scripts/web_e2e.sh`를 실행한다([web/README.md](web/README.md#end-to-end-check--종단-확인) 참고). 자체 일회용 컨테이너를 쓴다.

The contract covers the `/api/v1` operations. Requests that Tomcat rejects while parsing the URL or headers (a bad percent-encoding such as `%ZZ`, an encoded slash `%2F`, an oversized header) get a 400 `problem+json` with the code `MALFORMED_REQUEST`, written by a Tomcat error report valve because Spring never sees them. The body is one fixed text and echoes nothing of the request (`instance` is always `/`); Tomcat itself may still log the rejected header line (INFO, first time) or request target (DEBUG). Requests the Spring Security firewall rejects (`//`, `;`, `%25`, an unknown HTTP method) get the same body, written by a `RequestRejectedHandler`; the handler logs nothing. Not covered: other Tomcat-stage statuses (for example 505 for an unknown HTTP version) still answer with Tomcat's HTML page.
계약은 `/api/v1` 연산을 다룬다. URL·헤더를 읽는 단계에서 Tomcat이 거절하는 요청(`%ZZ` 같은 잘못된 퍼센트 인코딩, 인코딩된 슬래시 `%2F`, 너무 큰 헤더)은 Spring에 닿지 않으므로 Tomcat 오류 보고 밸브가 `MALFORMED_REQUEST` 코드의 400 `problem+json`으로 답한다. 본문은 고정 문구 하나이고 요청의 어떤 부분도 되풀이하지 않는다(`instance`는 늘 `/`). 다만 Tomcat 자체가 거절한 헤더 줄(INFO, 첫 한 번)이나 요청 대상(DEBUG)을 로그에 남길 수 있다. Spring Security 방화벽이 거절하는 요청(`//`·`;`·`%25`·모르는 HTTP 메서드)도 `RequestRejectedHandler`가 같은 본문으로 답하며 로그는 남기지 않는다. 그 밖의 Tomcat 단계 상태(예: 모르는 HTTP 버전의 505)는 여전히 Tomcat의 HTML 페이지로 답한다.

To run a whole season at once on a throwaway database instead of clicking, use the temporary script in [tools/play/README.md](tools/play/README.md).
클릭 대신 일회용 DB에서 한 시즌을 한 번에 돌려 보려면 [tools/play/README.md](tools/play/README.md)의 임시 스크립트를 쓴다.

The same steps are checked by a test: `OneSeasonE2ETest` plays one season over a real port (with a buy bound to the formation change and a full sell at turn 8) and runs `db/90_full_run_check.sql` unchanged on the result (see the bullet above for the command).
같은 순서를 시험이 확인한다: `OneSeasonE2ETest`가 실제 포트로 한 시즌을 두고(전술 변경에 묶인 매수와 8턴 전량 매도 포함) 그 결과에 `db/90_full_run_check.sql`을 그대로 실행한다(명령은 위 목록 참고).

## Run v0.1 once (v0.1 한 판 실행 요약)

The commands are the ones run end to end on a clean tree; run everything from the repository root. Details are in the sections above and below.
깨끗한 트리에서 처음부터 끝까지 실행해 본 명령만 적었다. 모두 저장소 루트에서 실행한다. 자세한 내용은 위아래 절에 있다.

1. Needs Docker Desktop (running), `psql` 16 or later, Python 3, Node 26 (`web/.nvmrc`) and the sample CSVs in `db/sample/`. In the git-ignored root `.env` set two names (values you generate, never pasted anywhere): `ANTLAB_JWT_SECRET` (`openssl rand -base64 32`) and `GF_SECURITY_ADMIN_PASSWORD` (`openssl rand -hex 16`).
   Docker Desktop(켜 둔 상태), `psql` 16 이상, Python 3, Node 26(`web/.nvmrc`), `db/sample/`의 표본 CSV가 필요하다. git이 무시하는 루트 `.env`에 이름 두 개를 둔다(값은 직접 만들고 어디에도 붙이지 않는다): `ANTLAB_JWT_SECRET`(`openssl rand -base64 32`)과 `GF_SECURITY_ADMIN_PASSWORD`(`openssl rand -hex 16`).
2. Start the database, API, Prometheus and Grafana: `docker compose --profile api --profile monitoring up -d --build --wait --wait-timeout 600` (the first build downloads Gradle and dependencies and takes minutes). Then `curl -fsS http://127.0.0.1:8080/readyz` returns `{"status":"UP"}`.
   DB·API·Prometheus·Grafana를 띄운다: `docker compose --profile api --profile monitoring up -d --build --wait --wait-timeout 600`(첫 빌드는 Gradle과 의존성을 내려받아 몇 분 걸린다). 그다음 `curl -fsS http://127.0.0.1:8080/readyz`가 `{"status":"UP"}`를 돌려준다.
3. Load the sample once: step 3 of the next section (until then `/api/v1/pool` answers 409).
   표본을 한 번 적재한다: 다음 절의 3단계(그 전에는 `/api/v1/pool`이 409다).
4. Web: `cd web && npm ci && npm run dev`, then open http://localhost:5173 (always `localhost`; see [web/README.md](web/README.md#run--실행) for another port). The status line shows «API 연결됨 · 종목 20개».
   웹: `cd web && npm ci && npm run dev` 뒤 http://localhost:5173 을 연다(항상 `localhost`, 다른 포트는 [web/README.md](web/README.md#run--실행)). 상태 줄에 «API 연결됨 · 종목 20개»가 보인다.
5. Play in this order: sign up (you are logged in at once) → **첫 시즌 시작** (risk limit, formation, 12 turns) → at turn 0 buy assets from the pool → **다음 턴** → from turn 1 trade and change the formation → keep advancing to the last turn (**마지막 턴 넘기기**) → **성적표** → **복기** → log out. Details: [web/README.md](web/README.md#play--플레이).
   이 순서로 둔다: 가입(바로 로그인) → **첫 시즌 시작**(위험 선언·포메이션·12턴) → 0턴에 종목 풀에서 매수 → **다음 턴** → 1턴부터 매매·전술 변경 → 마지막 턴(**마지막 턴 넘기기**)까지 진행 → **성적표** → **복기** → 로그아웃. 자세한 내용은 [web/README.md](web/README.md#play--플레이).
6. Grafana (http://127.0.0.1:3000) shows the play about 30 seconds later: next section. Stop with `docker compose --profile api --profile monitoring stop` (data stays).
   Grafana(http://127.0.0.1:3000)에는 한 판 뒤 30초쯤 지나 반영된다: 다음 절. 멈출 때는 `docker compose --profile api --profile monitoring stop`(데이터는 남는다).

## Watch one season in Grafana (모니터링으로 한 판 보기)

Prometheus scrapes the API's management port and Grafana shows three dashboards (API, JVM and DB pool, Game). Everything runs in containers; the API container is needed because Prometheus reaches it by its compose name.
Prometheus가 API의 관리 포트를 수집하고 Grafana가 대시보드 세 개(API, JVM·DB 풀, 게임)를 보여 준다. 모두 컨테이너에서 돈다. Prometheus가 compose 이름으로 API에 닿으므로 API도 컨테이너로 띄운다.

1. In the root `.env` (git-ignored; names are in `.env.example`) set `GF_SECURITY_ADMIN_PASSWORD` (`openssl rand -hex 16`; used when the `grafana-data` volume is first created) and `ANTLAB_JWT_SECRET` (`openssl rand -base64 32`; the API refuses to start without it, SEC-03).
   루트 `.env`(git 무시, 이름은 `.env.example`)에 `GF_SECURITY_ADMIN_PASSWORD`(`openssl rand -hex 16`. `grafana-data` 볼륨을 처음 만들 때 적용)와 `ANTLAB_JWT_SECRET`(`openssl rand -base64 32`. 없으면 API가 시작하지 않는다(SEC-03))를 둔다.
2. Start everything: `docker compose --profile api --profile monitoring up -d --build --wait`. The API container runs the `prod` profile; the play script signs up and logs in.
   모두 띄운다: `docker compose --profile api --profile monitoring up -d --build --wait`. API 컨테이너는 `prod` 프로필이고, 플레이 스크립트가 가입·로그인을 한다.
3. First time only, load the sample into `antlab_api`, from the repository root (the SQL `\copy` paths are relative) and with the git-ignored sample CSVs in `db/sample/` ([how to build them](db/sample/README.md#표본-만들기-추적하지-않는-csv-만들기)). The three `export` values choose the database; set `PGPORT` to the published DB port (`ANTLAB_DB_PORT`, default 5432). Without them psql falls back to its defaults and may connect to a different database. Each step stops at the first failure. Check afterwards: `psql -X -d antlab_api -Atc "select version,success from flyway_schema_history order by installed_rank"` prints four rows, `1|t` to `4|t`.
   처음 한 번만 표본을 `antlab_api`에 적재한다. 저장소 루트에서(SQL의 `\copy` 경로가 상대 경로다) git이 무시하는 표본 CSV를 `db/sample/`에 두고([만드는 법](db/sample/README.md#표본-만들기-추적하지-않는-csv-만들기)) 실행한다. `export` 세 줄이 접속할 DB를 정한다. `PGPORT`는 게시된 DB 포트(`ANTLAB_DB_PORT`, 기본 5432)로 맞춘다. 빼면 psql이 기본 접속으로 가서 다른 DB에 붙을 수 있다. 각 단계는 첫 실패에서 멈춘다. 끝난 뒤 확인: `psql -X -d antlab_api -Atc "select version,success from flyway_schema_history order by installed_rank"`가 `1|t`부터 `4|t`까지 네 줄을 낸다.

   ```bash
   docker compose stop api
   export PGHOST=127.0.0.1 PGPORT=5432 PGUSER=$USER
   psql -X -d postgres -c "ALTER DATABASE antlab_api SET timezone TO 'Asia/Seoul'"
   for f in 10_load_sample 11_load_events 12_calc_position 13_calc_attention; do
     psql -X -v ON_ERROR_STOP=1 -d antlab_api -f db/$f.sql && psql -X -q -v ON_ERROR_STOP=1 -d antlab_api -f db/${f}_check.sql || break
   done
   docker compose start api
   ```
4. Play: `python3 tools/play/play_season.py --base http://127.0.0.1:8080`.
   한 판 두기: `python3 tools/play/play_season.py --base http://127.0.0.1:8080`.
5. Open http://127.0.0.1:3000, sign in as `admin` with your `GF_SECURITY_ADMIN_PASSWORD`, then Dashboards › folder `ant-lab`. Game panels fill within about 30 seconds (one scrape every 15 s). The game counters start at 0 for known label pairs, so their first event counts; the first response with a new status or error code is not counted.
   http://127.0.0.1:3000 을 열고 `admin`과 `GF_SECURITY_ADMIN_PASSWORD`로 로그인한 뒤 Dashboards › 폴더 `ant-lab`. 게임 패널은 30초쯤 안에 채워진다(15초마다 수집). 게임 카운터는 알려진 라벨 조합이 0으로 시작하므로 첫 이벤트도 세지만, 새 상태 코드·오류 코드의 첫 응답은 세지 않는다.
6. Stop: `docker compose --profile api --profile monitoring stop` (data stays).
   멈춤: `docker compose --profile api --profile monitoring stop`(데이터는 남는다).

Quick check without touching your containers: `scripts/monitoring_smoke.sh` builds a throwaway project `antlab-smoke` with a random one-run signing key in its temp env file, loads the sample (`ANTLAB_GOLDEN_SAMPLE_DIR`, default `db/sample`), plays one season, checks that Prometheus scrapes the API and that every panel of the three dashboards answers, and removes the project (also on failure). It needs Docker, `psql` 16 or later and Python 3, and takes about a minute once the images are built (the first build longer). Run one at a time. To look around afterwards, export `GF_SECURITY_ADMIN_PASSWORD` and add `--keep`: Grafana is then on http://127.0.0.1:13014 and the script prints the command that removes the project.
내 컨테이너를 건드리지 않고 빠르게 확인하려면 `scripts/monitoring_smoke.sh`를 실행한다. 일회용 프로젝트 `antlab-smoke`를 임시 env 파일의 실행 한 번짜리 임의 서명 키로 만들고, 표본(`ANTLAB_GOLDEN_SAMPLE_DIR`, 기본 `db/sample`)을 적재하고, 한 판을 둔 뒤, Prometheus가 API를 수집하는지와 대시보드 세 개의 모든 패널이 답하는지 확인하고, 실패해도 프로젝트를 지운다. Docker, `psql` 16 이상, Python 3이 필요하고 이미지가 빌드된 뒤에는 1분쯤 걸린다(첫 빌드는 더 길다). 한 번에 하나만 실행한다. 끝난 뒤 둘러보려면 `GF_SECURITY_ADMIN_PASSWORD`를 export하고 `--keep`을 붙인다. 그러면 Grafana가 http://127.0.0.1:13014 에 남고, 스크립트가 프로젝트를 지우는 명령을 출력한다.


## KIS daily price collector (local only) (KIS 일봉 수집기, 로컬 전용)

The collector reads the daily closes of the 20 sample assets from the KIS quote API and stores each close as an append-only vintage in `asset_daily_price`, with one `job_run` row per run (M1-06). It is off by default, it can send only three kinds of request (token, domestic daily, overseas daily), and the `prod` profile refuses to start with it switched on (no KIS real key on the server, DEPLOY §2). Nothing schedules it yet (M1-10): you run it by hand, once.
수집기는 표본 20종목의 일별 종가를 KIS 시세 API에서 읽어 `asset_daily_price`에 값이 바뀔 때마다 새 빈티지로 추가하고, 실행마다 `job_run` 한 줄을 남긴다(M1-06). 기본은 꺼져 있고, 보낼 수 있는 요청은 세 가지(토큰, 국내 일봉, 해외 일봉)뿐이며, `prod` 프로필은 켠 채로 시작하기를 거부한다(서버에 KIS 실전 키 없음, DEPLOY §2). 아직 일정에 걸려 있지 않으므로(M1-10) 직접 한 번 돌린다.

- Tests (no network, no KIS key; Docker Desktop must be running): `./gradlew test --tests 'antlab.collect.*' -PexcludeSqlGolden`. They use synthetic FAKE responses and a throwaway PostgreSQL 17 container.
  시험(네트워크·KIS 키 없음, Docker Desktop 필요): `./gradlew test --tests 'antlab.collect.*' -PexcludeSqlGolden`. 합성 FAKE 응답과 일회용 PostgreSQL 17 컨테이너만 쓴다.
- A real run needs a **throwaway database**, never the owner database `antlab-db-1` (`antlab_api`): start a separate Compose project on another port (`ANTLAB_DB_PORT=55432 docker compose -p antlab-collect up -d --wait db`), create a database and load only the asset rows (`db/sample/asset_group.csv`, `db/sample/asset.csv`; steps in [db/README.md](db/README.md#api-db와-실습-db)), and drop it afterwards (`docker compose -p antlab-collect down -v`). Quotes are stored there only, never committed.
  실제 실행에는 **일회용 DB**가 필요하다. 소유자 DB `antlab-db-1`(`antlab_api`)은 쓰지 않는다. 다른 포트의 별도 Compose 프로젝트를 띄우고(`ANTLAB_DB_PORT=55432 docker compose -p antlab-collect up -d --wait db`), 데이터베이스를 만들어 종목 행만 적재한 뒤(`db/sample/asset_group.csv`, `db/sample/asset.csv`, 절차는 [db/README.md](db/README.md#api-db와-실습-db)), 끝나면 지운다(`docker compose -p antlab-collect down -v`). 시세는 그 DB에만 담기고 커밋하지 않는다.
- Environment (shell only, values are never printed or pasted): `ANTLAB_DB_URL`, `ANTLAB_DB_USER`, `ANTLAB_DB_PASSWORD` for the throwaway database, `ANTLAB_JWT_SECRET` (the app does not start without it), `KIS_APP_KEY`, `KIS_APP_SECRET`, and `KIS_TOKEN_CACHE_DIR` as an **absolute** path (`<repository root>/data/cache/kis`), which shares one token file with `tools/fetch`. The Java app does not read `.env` by itself.
  환경 변수(셸에서만, 값은 출력·붙여넣기 금지): 일회용 DB용 `ANTLAB_DB_URL`·`ANTLAB_DB_USER`·`ANTLAB_DB_PASSWORD`, `ANTLAB_JWT_SECRET`(없으면 앱이 시작하지 않는다), `KIS_APP_KEY`, `KIS_APP_SECRET`, **절대 경로**의 `KIS_TOKEN_CACHE_DIR`(`<저장소 루트>/data/cache/kis`; `tools/fetch`와 토큰 파일 하나를 함께 쓴다). Java 앱은 `.env`를 스스로 읽지 않는다.
- Run once (build first with `./gradlew bootJar`): `java -jar build/libs/app.jar --antlab.collect.kis.enabled=true --antlab.collect.run-once=KIS_DAILY_PRICE --antlab.collect.targets=KR:005930,KR:069500,US:SPY --antlab.collect.kis.max-requests-per-run=15 --server.address=127.0.0.1 --server.port=0 --management.server.address=127.0.0.1 --management.server.port=0`. Flyway migrates the empty database first. The process exits with code 0 when the run SUCCEEDED and 1 otherwise (PARTIAL, FAILED, or a fresh run already open). Without `--antlab.collect.targets` all 20 targets of `src/main/resources/collect/kis-targets.csv` run; a target that does not match the `asset` table makes the run end FAILED with `TARGETS_INVALID` before any request. The web server is still started (on a random local port) because the application needs it; it is not used.
  한 번 실행(먼저 `./gradlew bootJar`): 위 명령. Flyway가 빈 DB를 먼저 마이그레이션한다. 실행이 SUCCEEDED면 종료 코드 0, 그 밖(PARTIAL·FAILED·이미 실행 중)이면 1이다. `--antlab.collect.targets`를 빼면 `src/main/resources/collect/kis-targets.csv`의 20개 대상을 모두 돈다. `asset` 표와 맞지 않는 대상이 있으면 요청 전에 `TARGETS_INVALID`로 FAILED 처리된다. 애플리케이션이 웹 서버를 필요로 해서 임의의 로컬 포트로 함께 뜨지만 쓰이지 않는다.
- What a run asks for: each target from 10 days before its latest stored date (30 days back when nothing is stored) up to **yesterday in the market's own time zone**, so a session in progress is never stored. Running again with the same answers adds no rows; a changed close adds the next vintage and keeps the earlier one. Limits: at most 2 requests per second, at most 60 requests per run (`antlab.collect.kis.max-requests-per-run`), one token per run (reused from the shared file for about a day).
  한 번의 요청 범위: 대상마다 저장된 마지막 날짜의 10일 전(저장된 것이 없으면 30일 전)부터 **그 시장 시간대의 어제**까지이므로 진행 중인 장은 저장하지 않는다. 같은 응답으로 다시 돌리면 행이 늘지 않고, 종가가 바뀌었으면 다음 빈티지를 더하며 앞의 행은 그대로 둔다. 한도: 초당 2건, 실행당 60건(`antlab.collect.kis.max-requests-per-run`), 실행당 토큰 1개(공유 파일에서 하루쯤 재사용).
- Never run it together with `tools/fetch` (both use the same token file), and never put the keys, a token or a price into a chat, an issue, a commit or a log. A run that stops with `KIS_RATE_LIMIT` or `REQUEST_CAP` is safe to start again later: finished targets are kept.
  `tools/fetch`와 동시에 돌리지 않는다(같은 토큰 파일을 쓴다). 키·토큰·가격을 채팅·이슈·커밋·로그에 붙이지 않는다. `KIS_RATE_LIMIT`이나 `REQUEST_CAP`으로 멈춘 실행은 나중에 다시 시작해도 된다. 끝난 대상은 남는다.
