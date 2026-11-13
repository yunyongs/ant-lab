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
| MON — 모니터링(Grafana) | Actuator·Micrometer → Prometheus → Grafana 대시보드(API 응답 시간·오류율, JVM·DB, 게임 지표)를 구축한다 | In progress (2026-11-13) — MON-01: Prometheus endpoint on the internal management port 8081 / 진행 중 (2026-11-13) — MON-01: 내부 관리 포트 8081의 Prometheus 엔드포인트 |
| SEC — 인증(JWT) | Spring Security + JWT — 가입·로그인, 토큰 발급·검증·갱신을 구현하고 고정 회원을 실제 회원으로 교체한다(M4에서 앞당겼다) | In progress — SEC-01 authentication schema (2026-11-13). / 진행 중 — SEC-01 인증 스키마 (2026-11-13) |
| WEB — 한 판 웹(React) | 시즌 시작·팀 구성·턴 진행·매매·전술 변경·성적표 화면, 모바일 폭 우선 반응형 | In progress (2026-11-13) — WEB-01: Vite + TypeScript React skeleton in web/ / 진행 중 (2026-11-13) — WEB-01: web/의 Vite + TypeScript React 골격 |
| MVP — Spring Boot + JPA | 전체 역사 데이터 적재, 예상 분포, 피드·랭킹·알림, 점수·레이팅 | 시작 전 |
| M1–M3 | 수집기·CI, Python 엔진, 점수 백테스트 | 계획됨 |
| EXT · M4 · APP · AI · OPS | 비용·케이스 훈련·대가 라이브러리, 전망 모드·리그·전당 API, 모바일·데스크탑 앱과 웹의 나머지 화면, AI 복기, 배포 | 계획됨 ([로드맵](docs/PLAN.md#11-로드맵커리큘럼-순서-유지)) |
| AG | 에이전트 펀드 매니저(OpenRouter·구독 모델·Ollama, 페르소나, 누출 탐지 시험, 리더보드) | 계획됨 (PLAN v3, 2026-11-07) |
| R1 · R2 · R3 | 거장 추적(13F·X), 우수 매니저의 복제 봇, 에이전트의 실전 운용(소유자 자금, 사람 승인부터) | 계획됨 (PLAN v2.1, 2026-11-06) |

## 기술 스택

Java 21 · Spring Boot 4.1 · PostgreSQL 17 · JPA (리포트 쿼리에 MyBatis 선택) · springdoc-openapi(저장소 계약 `docs/api/openapi.yaml`) · Python (FastAPI, pandas, 시뮬레이션 엔진) · 모바일 앱 중심 + 데스크탑 앱 + 웹 페이지 (React Native, React — 웹은 WEB 단계, 앱은 이후) · RAG / LangChain / LangGraph (이후) · Docker Compose(로컬 DB는 지금, 서버 배포는 OPS) · GitHub Actions · GHCR · 오라클 클라우드 인프라(OCI, Oracle Cloud Infrastructure) Ampere VM + Caddy (이후)

## API 개발 실행 (P0-22~)

저장소 루트가 Spring Boot 프로젝트다(`src/`, `build.gradle`, `gradlew`). `db/`의 SQL 파일은 빌드에 들어가지 않는다.

준비물: JDK 21(`java -version`), Docker Desktop(시험과 `bootTestRun`이 일회용 PostgreSQL 17 컨테이너를 쓴다).

- `./gradlew test` — 전체 시험. Docker Desktop이 꺼져 있으면 "Docker 환경을 찾을 수 없음"으로 실패한다. 소유자 DB(`antlab-db-1`)에는 접속하지 않는다. 표본 CSV가 `db/sample`에 있으면 SQL 골든(약 2–3분)도 함께 실행한다. 표본 CSV가 없으면 그 부분만 이유를 밝힌 뒤 건너뛴다. 제외하려면 `-PexcludeSqlGolden` — [db/README.md「SQL 골든 하네스」](db/README.md#sql-골든-하네스-p0-25).
- P0-26 금액 계산 시험은 다음 두 명령으로 나눠 실행한다: `./gradlew test --tests 'antlab.common.money.MoneyMathTest'`(DB 없음)와 `./gradlew test --tests 'antlab.common.money.MoneyMathSqlParityTest'`(일회용 PostgreSQL 17)이다.
- P0-29 경로 생성기 시험만: `./gradlew test --tests 'antlab.engine.*'` — 순수 단위 시험(시드 1,000개 성질 검사·고정 벡터)과 일회용 PostgreSQL 17 통합 시험을 실행한다. 생성 경로는 `month_start`(달)를 기준으로 재현하며, 고정 벡터를 바꿀 때는 `engine_version`을 함께 올린다.
- P0-30 시즌 시작 시험만: `./gradlew test --tests 'antlab.api.season.*' --tests 'antlab.member.*' --tests 'antlab.season.*' -PexcludeSqlGolden` — 합성 고정 데이터(실제 표본 아님)로 시작·조회·상한·동시성·롤백·노출을 확인한다. 실제 표본 골든은 `-PonlySqlGolden --tests '*SeasonStartGoldenTest*'`(표본 CSV 필요).
- P0-32 대사 시험만: `./gradlew test --tests 'antlab.scoring.*' --tests 'antlab.api.dev.*' -PexcludeSqlGolden` — 22 SQL 사본이 같은지, Java 결과가 `recon_rules`와 같은지(합성 상태·깨뜨린 사례·1턴 이후 가격), 기록기, 개발 엔드포인트(`local` 프로필), 동시성을 확인한다. 실제 표본 골든은 `-PonlySqlGolden --tests '*ReconciliationGoldenTest*'`(표본 CSV 필요).
- 개발용 수동 대사 호출(`local` 프로필, `127.0.0.1`에서만 열린다): `curl -fsS -X POST -H 'X-Antlab-Dev: true' http://127.0.0.1:8080/api/v1/dev/seasons/1/reconciliation` — 고정 헤더가 없으면 403 `FORBIDDEN`이다. 응답에 규칙별 기대·실제·차이·`detail`이 그대로 담긴다.
- P0-31 매수 주문 시험만: `./gradlew test --tests 'antlab.api.order.*' -PexcludeSqlGolden` — 합성 고정 데이터(실제 표본 아님)로 정상·음성·1턴 이후 매수·무결성 가드·동시성·잠금 순서·노출을 확인한다. 실제 표본 골든(SQL 팀 구성 ↔ API 주문 재생, 대사 규칙 15개)은 `-PonlySqlGolden --tests '*BuyOrderGoldenTest*'`(표본 CSV 필요).
- `./gradlew bootTestRun` — 일회용 PostgreSQL 17로 앱을 띄운다. 소유자 DB가 필요 없어 가장 안전하다. 8080이 쓰이고 있으면 `./gradlew bootTestRun --args='--server.port=18080'`.
- `./gradlew bootRun` — 로컬 compose `db`의 `antlab_api` DB로 띄운다. 처음 한 번은 빈 `antlab_api`를 만들고(인코딩·시간대 포함) 앱을 띄워 Flyway V1–V3를 적용한 뒤 표본을 적재한다. 명령은 [db/README.md「API DB와 실습 DB」](db/README.md#api-db와-실습-db)에 있다. 접속 정보는 환경 변수 `ANTLAB_DB_URL`·`ANTLAB_DB_USER`·`ANTLAB_DB_PASSWORD`이고, 없으면 `jdbc:postgresql://localhost:5432/antlab_api`·OS 사용자·비밀번호 없음이다.
- 매핑·잠금 시험만: `./gradlew test --tests 'antlab.mapping.*' --tests 'antlab.persistence.*'` — 엔티티 23개의 열·enum·불변 대조, 가변 엔티티와 두 스레드 잠금(`FOR NO KEY UPDATE`)·낙관적 락 시험이 돈다. 일회용 컨테이너만 쓴다.
- 컨테이너로 실행(P0-24) — `db`와 `api`를 한 번에 올린다. `api`는 compose profile `api`라서 `--profile api`를 붙여야 켜진다(붙이지 않은 `docker compose up -d`는 지금처럼 `db`만 켠다). 늘 같이 켜고 싶으면 루트 `.env`에 `COMPOSE_PROFILES=api`를 둔다(선택, 기본은 끔). `bootRun`과 달리 `prod` 프로필이라 Swagger·명세가 꺼져 있고, 같은 이미지가 후속 CI·서버 배포에서도 쓰인다.
  1. Docker Desktop을 켜고 준비 완료(고래 아이콘 안정)를 확인한다. VS Code로 저장소 루트를 열고 통합 터미널(`` Ctrl+` ``)에서 실행한다.
  2. 새 볼륨(처음): `docker compose --profile api up -d --build --wait --wait-timeout 240` — 이미지를 빌드하고, `db`가 빈 `antlab_api`를 만들고, `api`가 Flyway V1–V3를 적용한 뒤 두 서비스가 `healthy`가 되면 끝난다. 첫 빌드는 Gradle 배포판과 의존성을 내려받아 몇 분 걸린다.
  3. 이미 쓰던 볼륨(예: 소유자의 `antlab_db-data`)에는 `POSTGRES_DB` 변경이 적용되지 않는다. `antlab_api`가 있는지 먼저 확인하고 없을 때만 한 번 만든다 — [db/README.md「API 컨테이너」](db/README.md#api-컨테이너-p0-24). 볼륨을 지워서 해결하지 않는다.
  4. Check: `curl -fsS http://127.0.0.1:8080/readyz` returns `{"status":"UP"}`, and `db` and `api` are healthy in Docker Desktop's Containers view. Readiness means the app accepts requests with the database attached, not that the sample is loaded. / 확인: `curl -fsS http://127.0.0.1:8080/readyz` → `{"status":"UP"}`, Docker Desktop의 Containers에서 `db`·`api`가 healthy. readiness는 "요청을 받을 수 있고 DB가 붙어 있다"는 뜻이지 표본 적재가 끝났다는 뜻이 아니다.
  5. 컨테이너와 `bootRun`/`bootTestRun`을 동시에 켜면 8080이 겹친다. 컨테이너 포트는 `ANTLAB_API_PORT=18080 docker compose --profile api up -d`로 바꾼다(다시 `up`할 때도 같은 값을 붙인다). 잠깐 멈출 때는 `docker compose stop`, 다시 켤 때는 `docker compose start`. When two local apps run at once, also move the management port: add `--management.server.port=18081`. / 로컬 앱 두 개를 함께 켤 때는 관리 포트도 옮긴다: `--management.server.port=18081`을 붙인다.
  6. 표본을 적재할 때는 `docker compose stop api`로 앱을 멈추고 [db/README.md](db/README.md#api-db와-실습-db)대로 실행한 뒤 `docker compose start api`.
  7. QA·시험은 소유자 프로젝트를 건드리지 않게 `ANTLAB_DB_PORT=55432 ANTLAB_API_PORT=18080 docker compose --profile api -p antlab-qa up -d --build --wait` 로 띄우고, 끝나면 `docker compose --profile api -p antlab-qa down -v --rmi local`(정리에도 `--profile api`를 붙인다).
- Health and metrics: the API port has only `/readyz` and `/livez`; the Actuator (`/actuator/health/**`, `/actuator/prometheus`) is on the management port `8081` only (`127.0.0.1` for `bootRun`/`bootTestRun`; the container does not publish it). / 헬스·지표: API 포트에는 `/readyz`·`/livez`만 있고, Actuator(`/actuator/health/**`, `/actuator/prometheus`)는 관리 포트 `8081`에만 있다(`bootRun`·`bootTestRun`은 `127.0.0.1`, 컨테이너는 게시하지 않는다).
- 확인 URL: `/readyz`, `POST·GET /api/v1/seasons`·`GET /api/v1/seasons/{seasonId}`·`GET /api/v1/seasons/{seasonId}/prices?turnNo=`·`POST /api/v1/seasons/{seasonId}/orders`(P0-31 시장가 매수; P0-30 — 고정 회원 하나만 사용하므로 `local`·`bootRun`에서만 쓴다. 컨테이너 `prod`는 SEC 전까지 503 `MEMBER_UNAVAILABLE`을 반환한다), `/api/v1/pool`(표본 적재와 포지션 계산 뒤 20종목 — 적재 전이면 409 `POOL_NOT_LOADED`, 판정 전이면 409 `POOL_NOT_JUDGED`). 명세 `/v3/api-docs`와 `/swagger-ui.html`은 `local` 프로필(`bootRun`·`bootTestRun`의 기본)에서만 열리며, 프로필을 지정하지 않거나 `prod`이면 꺼져 있다.
- 개인 덮어쓰기 설정은 프로젝트 루트의 `config/application-local.yml`에 둔다(git이 무시하고 jar에 들어가지 않는다). `src/main/resources/`에 두면 jar에 들어가므로 두지 않는다.
- P0-40 one-season E2E (sample CSVs needed): `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'`; the error sweep (`ApiErrorSweepTest`) runs in every `./gradlew test`. / P0-40 한 판 E2E(표본 CSV 필요): `./gradlew test -PonlySqlGolden --tests '*OneSeasonE2ETest*'`. 오류 응답 전수 시험(`ApiErrorSweepTest`)은 모든 `./gradlew test`에서 돈다.

VS Code(macOS): 확장 "Extension Pack for Java", "Gradle for Java", "Spring Boot Extension Pack". 통합 터미널(`` Ctrl+` ``)에서 `./gradlew …`를 실행하고, 왼쪽 비커 아이콘(시험 탐색기)으로 시험을 하나씩 돌리며, Gradle 패널(코끼리 아이콘)의 `Tasks › application › bootTestRun`으로 앱을 띄운다. 가져오기가 꼬이면 `Cmd+Shift+P` → "Java: Clean Java Language Server Workspace". `.vscode/`는 git이 무시하므로 실행 설정 파일은 저장소에 없다.

## Play one season through the API (API로 한 판)

Start the local database with `docker compose up -d --wait db`, prepare `antlab_api` and load the sample as in [db/README.md «API DB와 실습 DB»](db/README.md#api-db와-실습-db), then run `./gradlew bootRun` (the `local` profile). Open [docs/api/one-season.http](docs/api/one-season.http) in VS Code (REST Client extension) and send the requests from top to bottom: start a season, read the pool and the turn-0 prices, buy the team, advance the turns (sell and change the formation in between), then read the report and the review. A season has 12 turns, so the whole play is a few dozen clicks.
로컬 DB를 `docker compose up -d --wait db`로 띄우고 [db/README.md «API DB와 실습 DB»](db/README.md#api-db와-실습-db)대로 `antlab_api`를 준비해 표본을 적재한 뒤 `./gradlew bootRun`(`local` 프로필)을 실행한다. VS Code에서 [docs/api/one-season.http](docs/api/one-season.http)를 열고(REST Client 확장) 위에서부터 차례로 보낸다: 시즌 시작, 종목 풀과 0턴 가격 조회, 팀 매수, 턴 진행(중간에 매도·전술 변경), 성적표와 복기 조회. 한 시즌은 12턴이라 전체가 클릭 수십 번이다.

The `api` container runs the `prod` profile and answers 503 for season calls until SEC adds sign-in, so it is health-check only for now; use `bootRun` to play.
`api` 컨테이너는 `prod` 프로필이라 SEC에서 로그인이 생기기 전까지 시즌 호출에 503을 주므로 지금은 헬스체크 용도뿐이다. 직접 둘 때는 `bootRun`을 쓴다.

Web (WEB-01): see [web/README.md](web/README.md) — run `cd web && npm ci && npm run dev` and open http://localhost:5173; the dev server forwards `/api` to the API on 127.0.0.1:8080.
웹(WEB-01): [web/README.md](web/README.md) 참고 — `cd web && npm ci && npm run dev` 뒤 http://localhost:5173 을 연다. 개발 서버가 `/api` 요청을 127.0.0.1:8080의 API로 넘긴다.

The contract covers the `/api/v1` operations. Requests that Tomcat rejects while parsing the URL or headers (a bad percent-encoding such as `%ZZ`, an encoded slash `%2F`, an oversized header) get Tomcat's plain HTML 400 before Spring sees them; a proxy in front of the server is expected to stop those, and a follow-up issue tracks making them `problem+json`.
계약은 `/api/v1` 연산을 다룬다. URL·헤더를 읽는 단계에서 Tomcat이 거절하는 요청(`%ZZ` 같은 잘못된 퍼센트 인코딩, 인코딩된 슬래시 `%2F`, 너무 큰 헤더)은 Spring에 닿기 전에 Tomcat의 HTML 400이 나간다. 이런 요청은 서버 앞의 프록시가 막는 것을 전제로 하고, `problem+json`으로 통일하는 일은 후속 이슈로 둔다.

To run a whole season at once on a throwaway database instead of clicking, use the temporary script in [tools/play/README.md](tools/play/README.md).
클릭 대신 일회용 DB에서 한 시즌을 한 번에 돌려 보려면 [tools/play/README.md](tools/play/README.md)의 임시 스크립트를 쓴다.

The same steps are checked by a test: `OneSeasonE2ETest` plays one season over a real port (with a buy bound to the formation change and a full sell at turn 8) and runs `db/90_full_run_check.sql` unchanged on the result (see the bullet above for the command).
같은 순서를 시험이 확인한다: `OneSeasonE2ETest`가 실제 포트로 한 시즌을 두고(전술 변경에 묶인 매수와 8턴 전량 매도 포함) 그 결과에 `db/90_full_run_check.sql`을 그대로 실행한다(명령은 위 목록 참고).
