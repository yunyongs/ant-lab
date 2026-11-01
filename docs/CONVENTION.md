# GIT 컨벤션 — Ant Lab

> **1인 포트폴리오 저장소.** 팀 프로젝트 [3WHY_java-latecatcher](https://github.com/20260921-saltlux-llm-agent-service-2nd/3WHY_java-latecatcher)의 컨벤션을 1인용으로 옮겼다. 이슈 → 브랜치 → 커밋 → PR → 머지의 연결은 그대로 두고, 팀 리뷰(approve 2)는 셀프 리뷰로, 담당자 구분은 없앴다.
> git 명령은 **소유자가 직접** 실행한다. Claude는 이 규칙에 맞는 명령과 커밋 메시지 초안을 안내만 한다.
> 예시의 이슈·PR 번호는 **설명용**이다. 실제 번호는 GitHub가 붙인다.

---

## 한눈에 보기

기능 하나(**M1-02 FRED 수집기**)가 이슈부터 머지까지 가는 동안 번호가 어디에 붙는지 보여 준다.


| 단계            | 형식                                       | 예                                                    |
| ------------- | ---------------------------------------- | ---------------------------------------------------- |
| 기능            | 단계별 기능 번호 ([0절](#0-번호-체계--기능-번호와-이슈-번호)) | `M1-02` FRED 수집기                                     |
| 이슈            | `[기능번호] 제목`                              | `[M1-02] FRED 수집기 — 발표일과 함께 저장` → GitHub가 **#24** 부여 |
| 라벨            | 종류 1개 + 파트 1개 이상                         | `◆ Feature` `collector` `db`                           |
| 브랜치           | `종류/이슈번호-기능번호-영문이름`                      | `feat/24-M1-02-fred-collector`                       |
| 커밋            | `태그: 제목` + body + `Refs #이슈번호`           | `feat: FRED 시계열을 발표일과 함께 저장` … `Refs #24`            |
| **main pull** | push 전에 최신 `main`을 당겨 충돌·빌드 확인           | `git pull origin main` → `git status` → 빌드·테스트       |
| push          | 충돌이 없을 때만                                | `git push -u origin feat/24-M1-02-fred-collector`    |
| PR            | `태그: [기능번호] 제목 #이슈번호`                    | `feat: [M1-02] FRED 수집기 #24` → GitHub가 **#27** 부여    |
| PR 본문         | `Closed #이슈번호`                           | `Closed #24`                                         |
| 리뷰            | 셀프 리뷰 체크리스트 ([6절](#6-리뷰--1인-셀프-리뷰))      | 필요하면 Claude 리뷰를 함께 받는다                               |
| 머지 커밋         | PR 제목 + 변경 사항 + `Closed #이슈번호`           | `feat: [M1-02] FRED 수집기 #24` … `Closed #24`          |
| 결과            | 이슈 자동 close                              | #24 닫힘                                               |


### 도구 — GitHub 사이트가 기본


| 단계                    | 기본: GitHub 사이트                                       | 옵션: gh CLI / 터미널                     |
| --------------------- | ---------------------------------------------------- | ------------------------------------ |
| 이슈 만들기                | **Issues → New issue** → 템플릿 고르기                     | `gh issue create`                    |
| 브랜치 만들기               | **이슈 화면 → Development → Create a branch**            | `gh issue develop` / `git switch -c` |
| 커밋 · main pull · push | 터미널 또는 IntelliJ의 Git 메뉴                              | `git commit`, `git pull`, `git push` |
| PR 만들기                | **Compare &amp; pull request**                       | `gh pr create`                       |
| 셀프 리뷰                 | **Files changed** 탭에서 한 줄씩 읽기                        | `gh pr diff`                         |
| 머지                    | **Merge pull request** → 메시지 고치기 → **Confirm merge** | `gh pr merge --merge`                |


---

## 0. 번호 체계 — 기능 번호와 이슈 번호


| 번호        | 누가 정하나         | 모양      | 특징                                       |
| --------- | -------------- | ------- | ---------------------------------------- |
| **기능 번호** | 소유자 (단계별 기능 표) | `M1-02` | 프로젝트 내내 바뀌지 않는다. **`#`을 붙이지 않는다**        |
| **이슈 번호** | GitHub 자동      | `#24`   | 이슈를 만들 때 정해진다                            |
| **PR 번호** | GitHub 자동      | `#27`   | 이슈와 같은 번호 카운터를 쓴다. 그래서 PR 번호는 이슈 번호와 다르다 |


### 기능 번호 — `단계코드-두자리`


| 단계 코드        | 단계                        | 기능 표가 있는 곳                                                                    |
| ------------ | ------------------------- | ----------------------------------------------------------------------------- |
| `P0`         | Phase 0 — MySQL 스키마와 트랜잭션 | [PLAN.md §9](PLAN.md#9-phase-0-checklist-mysql) 체크리스트 순서대로 `P0-01` \~ `P0-06` |
| `P1`         | Phase 1 — JDBC 콘솔 앱       | 단계를 시작할 때 PLAN.md에 표를 만든다                                                     |
| `MVP`        | Spring Boot REST API      | [PLAN.md §6](PLAN.md#6-mvp-user-stories) 사용자 스토리 순서대로 `MVP-01` \~ `MVP-06`    |
| `V2`         | 지정가 주문, 알림 등              | 단계를 시작할 때 PLAN.md에 표를 만든다                                                     |
| `M1` \~ `M7` | 매크로 ETF 트랙                | 단계를 시작할 때 [MACRO\_ETF.md §9](MACRO_ETF.md#9-roadmap)에 표를 만든다                  |


### 규칙

1. **기능 번호 앞에 `#`을 붙이지 않는다.** `#`은 GitHub에서 이슈·PR 링크 기호다.
2. **단계를 시작할 때 기능 표부터 만든다.** 기능 번호 없이 이슈를 만들지 않는다.
3. **한 기능에 이슈가 여러 개일 수 있다.** 기능을 쪼개거나 머지 뒤에 버그가 나오면, 기능 번호는 같고 이슈 번호만 다르다.
4. **기능 번호가 없는 일**은 대괄호에 영역을 쓴다.
   
   | 영역     | 언제                                      |
   | ------ | --------------------------------------- |
   | `[환경]` | 빌드 설정, `.gitignore`, Docker, CI, 저장소 설정 |
   | `[문서]` | README, PLAN, MACRO_ETF, CONVENTION    |
   | `[논의]` | 혼자 정하기 전에 선택지를 정리해 둘 주제 (설계 결정 기록용)     |
   

### 번호가 들어가는 곳


| 어디        | 기능 번호 | 이슈 번호 | 예                               |
| --------- | :-----: | :-----: | ------------------------------- |
| 이슈 제목     | ✓     | (자동)  | `[M1-02] FRED 수집기 — 발표일과 함께 저장` |
| 브랜치       | ✓     | ✓     | `feat/24-M1-02-fred-collector`  |
| 커밋 header | —     | —     | `feat: FRED 시계열을 발표일과 함께 저장`    |
| 커밋 마지막 줄  | —     | ✓     | `Refs #24`                      |
| PR 제목     | ✓     | ✓     | `feat: [M1-02] FRED 수집기 #24`    |
| PR 본문     | —     | ✓     | `Closed #24`                    |
| 머지 커밋     | ✓     | ✓     | PR 제목 그대로 + `Closed #24`        |


커밋 header에는 번호를 넣지 않는다. 커밋은 작게 여러 개 만들기 때문이다. 마지막 줄의 `Refs #24`가 이슈 #24의 타임라인에 커밋을 자동으로 연결한다.

---

## 1. Commit Rule

- 설명은 **한글**로 쓴다. 태그와 코드·기술 용어(`BigDecimal`, `pgvector` 등)는 원래 표기 그대로 둔다.
- 하나의 커밋 = 하나의 변경. 여러 기능을 한 커밋에 섞지 않는다.
- **header만 적지 말고 body까지 꼭 적는다.**
- **마지막 줄에 `Refs #이슈번호`** 를 적는다.
- 커밋 메시지는 Claude가 초안을 쓸 수 있지만, 최종 커밋은 소유자가 하므로 **`Co-Authored-By` 트레일러를 붙이지 않는다.**
- 비밀값(`.env`, KIS app key/secret, Slack signing secret, API 키)은 절대 커밋하지 않는다.

### Header — `태그: 제목`

`:` 뒤에만 공백이 있다. 번호와 탭 문자는 넣지 않는다.


| 태그         | 언제                                                  |
| ---------- | --------------------------------------------------- |
| `feat`     | 새로운 기능 추가                                           |
| `fix`      | 버그 수정 (삭제 포함)                                       |
| `docs`     | 문서 수정 (README, docs/, 주석)                           |
| `refactor` | 동작은 같고 구조·이름만 바꿀 때                                  |
| `test`     | 테스트 추가·수정                                           |
| `chore`    | 빌드 설정, 의존성, `.gitignore`, Docker, CI, 템플릿 등 코드 외 작업 |



| ✕ 잘못된 예                     | 무엇이 문제인가               | ✓ 고친 예                          |
| --------------------------- | ---------------------- | ------------------------------- |
| `feat: M1-02 FRED 수집기`      | header에 기능 번호          | `feat: FRED 시계열을 발표일과 함께 저장`    |
| `feat: 수집기 추가 - 재시도 - 로그 …` | body를 header 한 줄에 이어 씀 | header 한 줄 + 빈 줄 + `-` 목록 body  |
| `feat:수집기`, `feat : 수집기`    | `:` 앞뒤 공백              | `feat: 수집기`                     |
| `update`, `수정`              | 태그도 내용도 없음             | `fix: 휴일에 수집 잡이 실패로 기록되던 문제 수정` |


### Body

- 양에 구애받지 말고 **최대한 상세히** 쓴다.
- **어떻게**보다 **무엇을, 왜** 바꿨는지 쓴다.
- header 다음에 한 줄 띄우고 `-`로 시작하는 목록으로 쓴다.
- body 다음에 한 줄 띄우고 `Refs #이슈번호`로 끝낸다.

```text
feat: FRED 시계열을 발표일과 함께 저장

- 값마다 기준일(ref_date)과 공개일(available_at)을 함께 저장해,
  백테스트가 그날 알 수 없던 값을 쓰지 않게 했다 (MACRO_ETF.md §2.4).
- 같은 기준일을 다시 받으면 새 vintage로 쌓아 수정 이력을 남긴다.
- 저장은 (indicator_id, ref_date, vintage) UNIQUE 제약으로 중복을 막는다.

Refs #24
```

```text
fix: IRP 위험자산 70% 초과 시 신규 매수를 연금저축으로 돌리도록 수정

- 비중 계산에서 평가액 대신 매수 금액을 써서 70%를 넘겨도 IRP에 매수가 잡혔다.
- 평가액 기준으로 바꾸고, 초과 시 같은 ETF를 연금저축 매수로 옮긴다.

Refs #41
```

> `git pull origin main`으로 생기는 `Merge branch 'main' …` 커밋은 기본 메시지 그대로 둔다.

---

## 2. Branch Rule

### 이름 — `종류/이슈번호-기능번호-영문이름`


| 부분   | 규칙                                                               | 예                |
| ---- | ---------------------------------------------------------------- | ---------------- |
| 종류   | 커밋 태그와 같은 단어: `feat`, `fix`, `refactor`, `test`, `docs`, `chore` | `feat`           |
| 이슈번호 | 숫자만. **`#`을 붙이지 않는다**                                            | `24`             |
| 기능번호 | 기능 번호 그대로(하이픈 포함). 기능 번호가 없는 일은 생략                               | `M1-02`          |
| 영문이름 | 영어 단어 2\~3개                                                      | `fred-collector` |


전부 **소문자**와 하이픈(`-`)만 쓴다. 기능번호의 단계 코드(`P0`, `MVP`, `M1` …)만 대문자다. 한글과 공백은 쓰지 않는다.

```text
feat/24-M1-02-fred-collector        # 기능
feat/25-M1-03-ecos-collector        # 다음 기능
fix/41-M4-05-irp-cap-check          # 머지 뒤 발견한 버그
test/43-M2-04-regime-golden-test    # 테스트만 추가
feat/12-P0-02-schema-ddl            # Phase 0
chore/30-ci-gradle-pytest           # [환경] — 기능 번호 없음
docs/35-macro-etf-plan              # [문서] — 기능 번호 없음
```

**`#`을 빼는 이유:** URL에서 `#`은 페이지 안 위치를 뜻해서 `github.com/…/tree/feat/#24-…` 링크가 브랜치를 열지 못한다. 일부 셸에서는 `#`이 든 이름을 따옴표로 감싸야 한다.

### 흐름 — `기능 브랜치` → `main`

- `main`에는 **동작하는 코드만** 있어야 한다.
- `main`에 직접 push하지 않는다 (Ruleset으로 막아 둔다, [7절](#7-github-저장소-설정-체크리스트)).
- 브랜치는 **이슈를 만든 뒤, 그 이슈 화면에서** 최신 `main`을 기준으로 만든다.
- **push하기 전에 최신 `main`을 pull해서 충돌과 빌드를 확인한다.**
- 이슈 하나에 브랜치 하나. 이름을 잘못 지었으면 `git branch -m 새이름`으로 바꾼다.
- 머지된 브랜치는 지운다.

### 브랜치 만들기 — 이슈 화면에서

**기본 — GitHub 사이트**

1. 이슈 화면 오른쪽 사이드바 **Development** → **Create a branch**.
2. **Branch name**의 자동 이름(`24-m1-02-fred-…`)을 **규칙대로 고친다** → `feat/24-M1-02-fred-collector`.
3. **Change branch source**가 `main`인지 확인하고, **Checkout locally** → **Create branch**.
4. 로컬로 가져온다.
   ```bash
    git fetch origin
    git switch feat/24-M1-02-fred-collector
   ```

**옵션 — gh CLI** (이슈 연결까지 사이트와 같다)

```bash
gh issue develop 24 --name feat/24-M1-02-fred-collector --base main --checkout
```

**옵션 — 터미널만** (이슈와 브랜치는 연결되지 않고, PR 본문의 `Closed #24`로만 이어진다)

```bash
git switch main && git pull origin main
git switch -c feat/24-M1-02-fred-collector
```

### 작업 순서 — 구현 → 커밋 → main pull → 충돌·빌드 확인 → push


| 순서  | 할 일                          | 명령                                                                                             |
| --- | ---------------------------- | ---------------------------------------------------------------------------------------------- |
| 1   | 이슈 화면에서 브랜치를 만들고 로컬로 가져온다    | `git fetch origin` → `git switch 브랜치이름`                                                        |
| 2   | 구현하고 커밋한다                    | `git add 파일` → `git commit`                                                                    |
| 3   | **최신 `main`을 내 브랜치로 pull한다** | `git pull origin main`                                                                         |
| 4   | **충돌이 없는지 확인한다**             | `git status`에 `both modified`가 없어야 한다                                                          |
| 5   | 충돌이 있으면 해결하고 커밋한다            | `<<<<<<<` \~ `>>>>>>>` 구간 정리 → `git add` → `git commit`                                        |
| 6   | **바꾼 영역의 빌드·테스트를 돌린다**       | Spring `./gradlew test` · Python `pytest` · React `npm run build` · SQL은 스크립트 실행 · 문서만 바꿨으면 생략 |
| 7   | 그 다음에 push한다                 | `git push -u origin 브랜치이름`                                                                     |


---

## 3. Label Rule

라벨은 **이슈와 PR**에 붙인다. 종류 라벨 하나가 이슈 템플릿, 이슈 제목 앞머리, 브랜치 종류, 커밋·PR 태그를 함께 정한다.


| 묶음     | 몇 개 붙이나            | 무엇을 나타내나           |
| ------ | ------------------ | ------------------ |
| **종류** | 이슈·PR마다 **정확히 1개** | 무슨 일인가             |
| **파트** | **1개 이상**          | 어느 영역을 바꾸나         |
| **상태** | 필요할 때만             | 머지된 기능을 다시 고치는 중인가 |


### 종류 라벨


| 종류 라벨         | 이슈 템플릿                      | 이슈 제목 앞머리                     | 브랜치 종류                       | 커밋·PR 태그         |
| ------------- | --------------------------- | ----------------------------- | ---------------------------- | ---------------- |
| `◆ Feature`   | `1-feature.md`              | `[M1-02]`                     | `feat/`                      | `feat`           |
| `● Bug`       | `2-bug.md`                  | `[M4-05]` (기능 무관하면 `[환경]`)    | `fix/`                       | `fix`            |
| `▲ Refactor`  | `3-refactor.md`             | `[M2-01]` (여러 기능에 걸치면 `[환경]`) | `refactor/`                  | `refactor`       |
| `■ Environment` | `4-environment.md`          | `[환경]` 또는 `[문서]`              | `chore/` (설정) / `docs/` (문서) | `chore` / `docs` |
| `◇ Test`      | `1-feature.md`로 만들고 라벨만 바꾼다 | `[M2-04]`                     | `test/`                      | `test`           |
| `○ Suggestion` | `5-suggestion.md`           | `[논의]`                        | 만들지 않는다                      | —                |


`○ Suggestion` 이슈는 코드를 바꾸지 않는다. 결론이 나면 결정을 댓글로 남기고, 해당 문서의 Decision log(예: MACRO_ETF.md 부록 B)에 옮긴 뒤 닫는다.

### 파트 라벨 — 바꾸는 영역으로 고른다


| 파트 라벨       | 바꾸는 것                                           | 예                                          |
| ----------- | ----------------------------------------------- | ------------------------------------------ |
| `db`        | `db/` SQL, Flyway 마이그레이션, 스키마                   | `01_schema.sql`, `V3__indicator_value.sql` |
| `domain`    | Spring 도메인·서비스 (원장, 보유, 주문 상태, 제안·승인)           | `LedgerService`, `ProposalService`         |
| `api`       | Spring 컨트롤러, DTO, 보안·JWT, Slack 연동              | `RegimeController`, `SecurityConfig`       |
| `collector` | 데이터 수집과 스케줄 잡 (FRED, ECOS, KIS, CNN), `job_run` | `FredCollector`                            |
| `quant`     | Python 퀀트: 국면 채점, 목표 비중, 백테스트, 리스크 지표           | `regime.py`, `backtest.py`                 |
| `ai`        | Python AI: RAG, LangGraph, 리포트, 자연어 질의          | `pipeline.py`                              |
| `web`       | React 앱과 차트                                     | `RegimeMap.tsx`                            |
| `infra`     | Docker, 배포, OCI, 백업, n8n                        | `docker-compose.yml`, `Caddyfile`          |
| `config`    | 빌드 파일, `.gitignore`, `.github/` (템플릿, CI 워크플로)  | `build.gradle`, `ci.yml`                   |
| `docs`      | `README.md`, `docs/`                            | PLAN, MACRO\_ETF, CONVENTION               |


### 상태 라벨


| 상태 라벨     | 언제                           | 함께 붙이는 종류 라벨        |
| --------- | ---------------------------- | ------------------- |
| `수정(fix)` | **이미 main에 머지된 기능**을 다시 고칠 때 | `● Bug` 또는 `▲ Refactor` |


### 붙이는 규칙

1. **이슈:** 템플릿이 종류 라벨을 자동으로 붙인다. 파트 라벨은 직접 붙인다.
2. **PR:** 이슈의 라벨을 그대로 붙인다. 이슈에 없던 영역을 더 바꿨다면 그 파트 라벨을 추가한다.
3. **종류가 바뀌면** 이슈 라벨을 바꾸고, 브랜치 이름도 `git branch -m`으로 맞춘다.
4. **GitHub 기본 라벨은 쓰지 않는다** (지운다, [7절](#7-github-저장소-설정-체크리스트)). 하지 않기로 한 이슈는 **Close as not planned**로 닫는다.

---

## 4. Issue Rule

### 제목 — `[기능번호] 제목`

기능 번호가 없는 일은 `[환경]`, `[문서]`, `[논의]` 중 하나를 쓴다. 제목은 **무엇을 하는지 한 줄로** 쓴다. 종류는 제목이 아니라 **라벨**로 표시한다.

```text
[P0-02] MVP 테이블 DDL 작성 — PK·FK·CHECK 제약
[M1-02] FRED 수집기 — 발표일과 함께 저장
[M1-02] FRED 수집기 — 과거 데이터 백필
[M4-05] IRP 70% 초과 시 신규 매수가 IRP에 잡힘
[환경] GitHub Actions로 Gradle·pytest 실행
[문서] 매크로 ETF 트랙 설계 문서 추가
[논의] 국면 확률의 k 값을 2로 둘지 3으로 둘지
```

### 내용 — 템플릿을 쓴다

`.github/ISSUE_TEMPLATE/`의 템플릿을 쓴다. 모든 이슈 첫 섹션에 **기능 번호**와 **예상 작업 시간**을 적는다. 예상 시간은 PR에서 실제 시간과 비교한다.


| 템플릿         | 항목                                                                   |
| ----------- | -------------------------------------------------------------------- |
| ◆ Feature   | ▸ Describe (기능 번호, 관련 문서 절, 예상 시간) / ✓ Tasks / + More                |
| ● Bug       | ● 버그 설명 (기능 번호, 들어온 곳, 예상 시간) / ✓ 예상 결과 / ✕ 실제 결과 / → 재현 단계 / ≡ 로그 / ◎ 환경 / + More |
| ▲ Refactor  | ▸ Describe (왜 바꾸나) / ✓ Tasks / + More                                |
| ■ Environment | ▸ Describe (현재 상황) / ✓ Tasks / + More                                |
| ○ Suggestion | ○ 주제 / ◇ 선택지 / + More                                                |


**옵션 — gh CLI.** 본문에 백틱이 있으면 셸이 실행해 버리므로 본문은 파일로 넘긴다.

```bash
gh issue create \
  --title "[M1-02] FRED 수집기 — 발표일과 함께 저장" \
  --label "◆ Feature,collector,db" --assignee @me \
  --body-file issue.md
```

---

## 5. PR Rule

PR 본문은 **`.github/PULL_REQUEST_TEMPLATE.md`가 정본**이다. 자동으로 채워지는 네 항목을 그대로 쓴다.

1. **이슈를 먼저** 만들고, 그 이슈 화면에서 브랜치를 만든다.
2. PR 제목은 **`태그: [기능번호] 제목 #이슈번호`** 다. 기능 번호가 없으면 `[환경]`, `[문서]`를 쓴다.
3. 라벨은 이슈와 같은 것을 붙인다.
4. PR 하나는 **동작하는 기능 하나**. 30분 안에 다시 읽을 수 있는 크기로 유지한다. 커지면 이슈를 쪼갠다.
5. 머지 방식은 **Merge commit** (squash 아님). 공들여 쓴 커밋 body를 `main` 히스토리에 남기기 위해서다.
6. 단계가 시작되거나 끝나는 PR은 `README.md`의 Status 표와 `docs/PLAN.md` 로드맵을 함께 고친다.

```text
feat: [M1-02] FRED 수집기 #24
fix: [M4-05] IRP 70% 초과 시 신규 매수를 연금저축으로 #41
chore: [환경] GitHub Actions로 Gradle·pytest 실행 #30
docs: [문서] 매크로 ETF 트랙 설계 문서 추가 #35
```

> GitHub는 브랜치 이름으로 PR 제목을 자동으로 채운다. **PR을 만들 때 제목을 반드시 고친다.**

### 템플릿 항목별로 쓰는 것


| 항목                   | 쓰는 것                                                                                                 |
| -------------------- | ---------------------------------------------------------------------------------------------------- |
| **관련 이슈번호**          | `Closed #이슈번호`. 닫지 않고 참고만 할 이슈는 `관련 #번호`                                                             |
| **해결하는 데 얼마나 걸렸나요?** | 이슈의 **예상 시간 대비 실제 시간** (`예상 4h / 실제 6h`). 차이가 크면 이유 한 줄                                              |
| **PR Point**         | ① **변경 사항과 이유** — `-` 목록 (머지 커밋 body가 된다) ② **다시 볼 곳** — 고민한 선택지와 고른 이유 |
| **참고 사항**            | ① **확인 방법** — 실행 명령, 입력값, 기대 결과 ② 실습한 학습 항목 (커리큘럼 단계) ③ 후속 이슈 ④ 화면이 있으면 캡처                           |


### 머지 커밋 메시지

기본 메시지(`Merge pull request #27 from yunyongs/feat/…`)는 로그에 아무것도 남기지 않으므로 **머지할 때 고쳐 쓴다.**


|            | 규칙                       |
| ---------- | ------------------------ |
| **Header** | **PR 제목 그대로**            |
| **Body**   | PR Point의 **"변경 사항"** 목록 |
| **마지막 줄**  | `Closed #이슈번호`           |


```text
feat: [M1-02] FRED 수집기 #24

- FRED 시계열을 기준일·공개일과 함께 저장한다.
- 같은 기준일 재수신은 새 vintage로 쌓는다.
- 실패하면 job_run에 기록하고 Slack #antlab-alerts로 알린다.

Closed #24
```

**옵션 — gh CLI**

```bash
gh pr merge 27 --merge \
  --subject "feat: [M1-02] FRED 수집기 #24" \
  --body-file merge-body.md     # "변경 사항" 목록 + Closed #24
```

**머지 후 로컬 정리**

```bash
git switch main
git pull origin main
git branch -d feat/24-M1-02-fred-collector
```

---

## 6. 리뷰 — 1인 셀프 리뷰

GitHub는 본인 PR을 approve할 수 없다. 그래서 approve 대신 아래 순서로 스스로 리뷰한 뒤 머지한다.

1. PR을 연 뒤 **Files changed** 탭에서 한 줄씩 다시 읽는다. 가능하면 시간을 두고(다음 날) 읽는다.
2. 아래 체크리스트를 PR 코멘트로 남기고 하나씩 체크한다.
3. **(선택) Claude 리뷰:** 큰 PR이나 핵심 로직(국면 채점, 원장, 주문, 승인)은 Claude에게 리뷰를 요청한다. 지적을 반영한 수정은 새 커밋으로 남긴다.
4. CI가 생긴 뒤(M1)에는 CI가 통과해야 머지한다.

체크리스트:

- [ ] PR 제목이 `태그: [기능번호] 제목 #이슈번호` 형식이고, 브랜치 이름이 `종류/이슈번호-기능번호-영문이름` 형식인가
- [ ] 종류 라벨 1개와 파트 라벨이 실제로 바뀐 영역과 맞나
- [ ] 이슈의 Tasks가 모두 체크됐나
- [ ] 커밋마다 `태그: 제목` + body + `Refs #이슈번호`가 있나 (`Co-Authored-By` 트레일러 없음)
- [ ] 돈은 `BigDecimal` / `NUMERIC`인가 (`double`, `float`, `DOUBLE`, `FLOAT` 없음)
- [ ] 비밀값(`.env`, 키, 비밀번호)이 섞이지 않았나
- [ ] 다른 기능의 파일이 섞이지 않았나 (`git diff --stat main...HEAD`)
- [ ] "This branch has conflicts" 또는 "out-of-date"가 없나
- [ ] PR `참고 사항`의 확인 방법대로 돌려 봤을 때 실제로 동작하나
- [ ] 단계가 시작·종료됐다면 README Status와 PLAN 로드맵을 고쳤나

---

## 7. GitHub 저장소 설정 체크리스트

한 번만 하면 된다. 끝낸 항목은 체크한다.

- [ ] **라벨** — 아래 명령으로 종류 6개 + 파트 10개 + `수정(fix)`를 만들고, GitHub 기본 라벨을 지운다. 종류 라벨 이름은 기호까지(`◆ Feature`) 포함해야 이슈 템플릿의 `labels:`와 맞는다
- [ ] **`.github/ISSUE_TEMPLATE/`** — `1-feature` ~ `5-suggestion` 5종 (이 저장소에 있음, `main`에 머지되면 적용)
- [ ] **`.github/PULL_REQUEST_TEMPLATE.md`** — 정본
- [ ] **Settings → General → Pull Requests:** **Allow merge commits**만 체크(squash·rebase 해제), 드롭다운 **Pull request title and description**, **Automatically delete head branches** 체크
- [ ] **Settings → Rules → Rulesets → New branch ruleset:** 대상 `main`, Enforcement **Active**, **Require a pull request before merging**(Required approvals **0** — 1인이라 approve 불가), 허용 머지 방식 **Merge**만, **Block force pushes**, **Restrict deletions**. **Require linear history는 켜지 않는다** (Merge commit과 충돌)
- [ ] (M1부터) CI가 생기면 Ruleset에 **Require status checks to pass**를 켠다
- [ ] (선택) **Milestones** — 단계별(`P0`, `P1`, `MVP`, `M1` … `M7`)로 만들어 이슈를 묶는다. 진행률이 포트폴리오에서 한눈에 보인다

```bash
# 종류 라벨 6개
gh label create "◆ Feature"     --color A2EEEF --description "새 기능 또는 명세 추가" --force
gh label create "● Bug"         --color D73A4A --description "버그" --force
gh label create "▲ Refactor"    --color FBCA04 --description "동작은 같고 구조·이름만 변경" --force
gh label create "■ Environment" --color 0E8A16 --description "환경 설정, CI, 문서" --force
gh label create "◇ Test"        --color 5319E7 --description "테스트 추가" --force
gh label create "○ Suggestion"  --color D876E3 --description "설계 논의, 제안" --force

# 파트 라벨 10개
for part in db domain api collector quant ai web infra config docs; do
  gh label create "$part" --color C5DEF5 --description "파트: $part" --force
done

# 상태 라벨 1개
gh label create "수정(fix)" --color E99695 --description "이미 main에 머지된 기능을 다시 고칠 때" --force

# GitHub 기본 라벨 삭제
for l in "accessibility" "bug" "documentation" "duplicate" "enhancement" "good first issue" \
         "help wanted" "invalid" "question" "wontfix"; do
  gh label delete "$l" --yes
done

# 설정 확인
gh api repos/yunyongs/ant-lab \
  --jq '{allow_merge_commit, allow_squash_merge, allow_rebase_merge, merge_commit_title, merge_commit_message, delete_branch_on_merge}'
```

---

## 8. 단계 완료 태그

단계가 끝나면 그 단계의 마지막 머지 커밋에 태그를 붙인다: `p0-done`, `p1-done`, `mvp-done`, `m1-done` … `m7-done`.

```bash
git switch main && git pull origin main
git tag -a m1-done -m "M1 Data foundation 완료"
git push origin m1-done
```

---

## 9. 전체 예시 — P0-02 DDL 작성

1. **이슈:** **Issues → New issue → ◆ Feature** → 제목 `[P0-02] MVP 테이블 DDL 작성 — PK·FK·CHECK 제약` → 라벨 `db` 추가 → **Create** → #12
2. **브랜치:** 이슈 #12 → **Development → Create a branch** → `feat/12-P0-02-schema-ddl` → `git fetch origin && git switch feat/12-P0-02-schema-ddl`
3. **커밋:** 작게 여러 번.
   ```text
    feat: member, cash_account, stock 테이블 DDL 작성
   
    - 금액 컬럼은 DECIMAL(19,4)로 두어 부동소수 오차를 막았다.
    - cash_account.balance에 CHECK (balance >= 0)을 걸었다.
   
    Refs #12
   ```
4. **main pull → 확인 → push:** `git pull origin main` → `git status` → `mysql < db/01_schema.sql`로 실행 확인 → `git push -u origin feat/12-P0-02-schema-ddl`
5. **PR:** 제목 `feat: [P0-02] MVP 테이블 DDL 작성 #12`, 본문 `Closed #12`, 라벨 `◆ Feature` `db` → #13
6. **셀프 리뷰 → 머지:** 체크리스트 확인 → **Merge pull request** → 메시지를 PR 제목 + 변경 사항 + `Closed #12`로 고쳐 **Confirm merge**
7. **결과:** 이슈 #12가 닫히고, `main` 로그만 보고도 기능과 이슈를 찾을 수 있다.
   ```text
    $ git log --oneline --graph -3
    *   a1b2c3d feat: [P0-02] MVP 테이블 DDL 작성 #12
    |\
    | * 5a4b3c2 feat: member, cash_account, stock 테이블 DDL 작성
    |/
    * dfa14c7 Initial plan for Ant Lab (Korean-stock mock trading)
   ```