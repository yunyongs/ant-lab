# Spring Boot + JPA 기술 설계 (초안, 2026-11-04)

> **이 문서의 역할:** [PLAN.md](PLAN.md) §10의 "Java는 트랜잭션 도메인(시즌 계좌)을 맡는다"를 **어떻게** 구현할지 정한다. 대상은 Phase 0 API(P0-22부터, 시즌 한 판 REST API — 2026-11-09)이고 MVP(Spring Boot + JPA)가 이어받는다. Phase 1(JDBC)은 건너뛰었으므로(PLAN §11) 손으로 하던 일(SQL 단계의 P0-15·P0-17 실습)이 JPA로 옮겨 갈 때 무엇이 어디로 가는지를 §11에 적는다. 이 문서가 Phase 0 API에서 일부러 따르지 않는 곳은 해당 절에 "P0 API에서는"으로 표시한다. 커리큘럼에서 아직 배우지 않은 용어는 처음 나올 때 한 줄로 설명한다. "초안" 표시는 구현 중 바뀔 수 있고, 바뀌면 이 문서에 적는다.

## 1. 원칙

1. **DB가 진실이고 JPA는 도구다.** 스키마(DDL)는 `db/`의 SQL이 정의하고, 엔티티는 그 스키마에 **맞춰** 쓴다. JPA가 테이블을 만들거나 고치게 두지 않는다(§3).
2. **금액은 `BigDecimal`, 수익률은 `BigDecimal`로 저장하고 엔진 안에서만 `double`.** PLAN §5.2의 범위 규칙을 코드로 옮긴 것이다(§4.1).
3. **돈이 움직이는 일은 한 트랜잭션에서 전부 되거나 전부 안 된다.** 주문 체결과 턴 전환이 그 둘이다(§5).
4. **추가 전용 테이블은 엔티티도 불변이다.** 원장·체결·경로·이력 데이터에는 수정 메서드가 없다(§4.4).
5. **턴을 넘길 때마다 대사한다.** 항등식을 쿼리로 검사하고 결과를 남긴다(§6).
6. **엔티티는 서비스 밖으로 나가지 않는다.** 컨트롤러는 DTO만 본다(§2).

## 2. 프로젝트 구조

```
src/main/java/antlab/
├── common/        금액 계산(MoneyMath), 예외, 감사(created_at), 설정
├── member/        회원, 레이팅
├── asset/         종목 풀, 종목 그룹, 시스템 판정 포지션
├── history/       월 블록, 종목 월 수익률, 환율, 사건 태그 — 읽기 전용
├── season/        시즌, 경로, 턴, 라인업, 전술 변경
├── account/       시즌 계좌: 현금, 주문, 체결, 보유, 원장
├── engine/        Java 단순 부트스트랩(경로 생성 — Phase 0 API, P0-29), 예상 분포(MVP 범위)
├── scoring/       기준 포트폴리오, 성적표, 점수, 대사 로그
└── api/           REST 컨트롤러, 요청·응답 DTO, 예외 → HTTP 매핑
```

- **도메인별 패키지**(계층별이 아니라)로 나눈다. `season` 안에 `Season`(엔티티), `SeasonRepository`, `SeasonService`, `SeasonDto`가 함께 있다. 기능 하나를 고칠 때 한 폴더만 열면 되게 하기 위해서다.
- 계층은 세 개: **컨트롤러**(HTTP ↔ DTO) → **서비스**(트랜잭션, 규칙) → **리포지토리**(Spring Data JPA 인터페이스). 컨트롤러는 리포지토리를 직접 부르지 않는다.
- **API 계약 관리(Phase 0 API부터, PLAN §13 2026-11-09):** 기능의 계획 단계에서 `docs/api/openapi.yaml`의 해당 경로·스키마를 **먼저** 쓰고 계획 리뷰에서 함께 본다 → 컨트롤러·DTO를 구현하고 springdoc-openapi가 `/v3/api-docs`와 Swagger UI를 낸다(UI는 `/swagger-ui.html`, `local` 프로필에서만 켠다 — 시험은 명세 `/v3/api-docs`만 켠다) → 시험이 그 명세와 저장소 yaml을 대조한다(§9). 웹 타입은 이 yaml에서, 엔진(FastAPI)과 Spring 사이의 타입은 M2에서 만드는 `docs/api/engine.yaml`에서 생성한다. yaml 파일은 첫 엔드포인트 기능(P0-27)의 계획에서 만들고(공통 오류 스키마는 P0-22 것을 옮긴다), 대조 도구는 그때 정한다.
- **JSON·오류 규칙(Phase 0 API, PLAN §13 2026-11-09):** 접두 `/api/v1`, 금액·비율은 JSON 문자열(부동소수 금지, PLAN §5.2), 오류는 `ProblemDetail` + `code`(SQL 힌트 코드와 같은 글자). 5xx 응답에는 예외 내용을 싣지 않는다(고정 문장, 내용은 로그에만). `BigDecimal`은 지수 없는 문자열이고 펼친 자릿수 상한은 64다(입력 초과 400, 출력 초과 500 — 업무별 자릿수는 DTO `@Digits`가 정한다). 출력 500은 응답을 보내기 전에만 된다 — 큰 응답을 이미 보내기 시작한 뒤 초과하면 상태는 200 그대로이고 JSON은 닫히지 않은 채 끝난다(값이 빠진 응답이 정상 JSON처럼 보이지 않게 자동 닫기를 껐다). 그래서 응답 DTO의 금액은 반올림해 scale을 정한 값만 담는다(PLAN §5.2). JSON 정수 필드에 소수점·지수 표기 숫자(`1.5`·`2.0`·`1e1`)가 오면 400이다(`accept-float-as-int: false`). 오류 응답의 `type`은 `about:blank`라 생략된다(RFC 9457) — `code`·`title`·`status`·`detail`·`instance`는 늘 있다(`openapi.yaml`에서 optional로 둘지 명시할지는 P0-27). `problem+json` 보장은 DispatcherServlet 안까지다 — Tomcat이 직접 거절하는 요청(잘못된 `%` 인코딩·너무 큰 헤더)과 Security 필터 예외(M4)는 따로 다룬다.
- **DTO**(Data Transfer Object): 화면·API에 보낼 값만 담은 단순 객체. 엔티티를 그대로 JSON으로 내보내면 지연 로딩·순환 참조·비공개 컬럼(`season_path`)이 새므로 금지한다. Java `record`로 만든다.

## 3. 스키마 관리

| 항목 | 결정 | 이유 |
|---|---|---|
| 마이그레이션 | **Flyway**. `db/`의 DDL을 `src/main/resources/db/migration/V1__schema.sql`부터 번호 순으로 둔다. **Phase 0 API(P0-23)에서는** `V1__schema_history.sql`·`V2__schema_season.sql`·`V3__schema_account.sql`이 `db/01`–`03`의 `-- [스키마]` … `-- [/스키마]` 구간과 글자 단위로 같고(동일성 시험이 둘을 묶는다), 이 시점부터 Flyway가 스키마 정본이다. 이후 변경은 `V4__…`부터이고 `db/01`–`03`은 Phase 0 SQL 실습본으로 고정한다 | 스키마 변경을 코드처럼 버전 관리하고, 앱 시작 때 자동 적용. 테스트 DB도 같은 파일로 만들어 "DDL ↔ 엔티티" 불일치를 시작 시점에 잡는다 |
| `spring.jpa.hibernate.ddl-auto` | **`validate`** 고정. `update`·`create`는 어떤 프로필에서도 쓰지 않는다 | Hibernate(JPA 구현체)가 테이블을 만들면 CHECK·인덱스 이름 규칙(`ck_`, `idx_`)이 사라진다. `validate`는 엔티티와 테이블이 다르면 **시작을 거부**해 경계면 버그를 가장 일찍 드러낸다 |
| 시드 데이터 | Flyway `R__seed_*.sql`(반복 가능) — 종목 풀, 월 블록 표본. **Phase 0 API에서는 쓰지 않는다:** 가격 파생 CSV가 git 무시라 저장소에 넣을 수 없으므로 API 전용 DB `antlab_api`에 Flyway가 스키마를 만든 뒤 `psql`로 `db/10`–`13`을 적재한다(`db/README.md`) | 역사 데이터는 JPA로 넣지 않는다(§7) |
| 데이터베이스 | **PostgreSQL 17, Phase 0부터**(PLAN §10·§13, 2026-11-05). Flyway 파일은 처음부터 PostgreSQL 문법(`GENERATED … AS IDENTITY`, `NUMERIC`, `TIMESTAMPTZ`/`TIMESTAMP` 선택은 `erd.md` v2). 다른 DB를 동시에 지원하는 `{vendor}` 분기는 두지 않는다 | 이전(M1)이 없어져 DDL·CHECK·테스트를 한 번만 쓴다. 로컬 실습 DB는 Docker Compose `db` 서비스(`db/README.md`, 배포 구조는 [DEPLOY.md](DEPLOY.md)), 검증은 임시 소켓 인스턴스(`initdb` + `pg_ctl`, 저장소 밖 datadir) |

Flyway는 Spring 챕터의 범위를 조금 넘는 도구다. 처음에는 `V1__schema.sql` 한 파일만 두고, "앱을 띄우면 테이블이 검증된다"는 것만 경험한 뒤 기능마다 `V2__…`를 더한다(Phase 0 API는 `db/01`–`03`이 이미 셋으로 나뉘어 있어 V1–V3로 시작한다).

## 4. 엔티티 매핑 규칙

### 4.1 타입

| 종류 | Java | SQL | JPA 표기 | 비고 |
|---|---|---|---|---|
| 금액(현금, 대금, 평가액, 원장) | `BigDecimal` | `NUMERIC(19,4)` | `@Column(precision = 19, scale = 4)` | `double`/`float` 금지. 원 단위 정수 값은 DDL의 CHECK(`col = ROUND(col, 0)`, `erd.md` D1)가 지킨다 |
| 수익률(월 수익률, 환율 변화) | `BigDecimal` | `NUMERIC(12,8)` | `@Column(precision = 12, scale = 8)` | 저장은 정확한 소수. 엔진 계산(경로 생성·예상 분포)은 메모리에서 `double`로 하고, 가격으로 바꾸는 곳은 `MoneyMath` 한 군데 |
| 수량(주) | `int` | `INTEGER` | | 정수 주. `erd.md`의 정의를 따른다(₩30,000,000으로 1,000원짜리를 사도 30,000주) |
| 비중·확률(포메이션 비중, 국면 확률) | `int`(비중, 0–100) / `BigDecimal(5,4)`(확률) | `SMALLINT` / `NUMERIC(5,4)` | | 포메이션은 정수 퍼센트, 합 100 CHECK |
| 구분값(주문 방향·상태, 원장 유형, 포지션, 사건 범주) | `enum` | `VARCHAR(n)` + CHECK, 폭과 값 목록은 `erd.md`(예: `side VARCHAR(4)` `BUY`/`SELL`, 원장 `type` `DEPOSIT`/`BUY`/`SELL`/`FEE`/`TAX`/`ADJUSTMENT`). PostgreSQL의 기본 비교는 대소문자·악센트·뒤 공백을 구분하므로 별도 콜레이션이 필요 없다 | `@Enumerated(EnumType.STRING)` | `ORDINAL`(숫자) 금지 — enum 순서를 바꾸면 데이터가 뒤틀린다. DDL의 CHECK 목록과 enum 상수는 **글자 단위로 같아야** 하며 테스트가 대조한다(§9). `@Column(length = n)`을 ERD와 같게 — `validate`가 길이도 검사한다 |
| 날짜 | `LocalDate` | `DATE` | | 달(`month_start`)·기준일·상장일 |
| 시각 | `Instant` | `TIMESTAMPTZ` (P0-03 결정 1, 2026-11-05) | | 저장값은 시간대와 무관한 순간. 표시·"오늘" 조회는 `Asia/Seoul`로 변환(DTO 또는 `AT TIME ZONE`). DB `timezone = 'Asia/Seoul'` |
| 비율(수익률, 환율 변화, 참여율, 변동성, 낙폭, z-score, 강도) | `BigDecimal` | `NUMERIC(12,8)` | `@Column(precision = 12, scale = 8)` | 금액이 아닌 비율은 전부 이 하나(P0-03 기본값 2). 확률만 `NUMERIC(5,4)` |
| 기본 키 | `Long` | `BIGINT GENERATED ALWAYS AS IDENTITY` | `@Id @GeneratedValue(strategy = IDENTITY)` | §4.2 |
| 낙관적 잠금 버전 | `long` | `BIGINT NOT NULL DEFAULT 0` | `@Version` | `season`, `cash_account`에만(§5.3) |

`MoneyMath`(common): 턴별 가격 = round(시작가 × 누적 곱 Π(1+rᵢ)) (rᵢ = 원화 결합 수익률 (1+r현지)(1+r환율) − 1, 시작가 = 0턴 원화 가격)(누적 곱은 `BigDecimal` 그대로 들고 있다가 **마지막에 한 번** `RoundingMode.HALF_UP`으로 원 단위), 평가액 합계, 평균단가(소수 4자리) — **반올림이 일어나는 유일한 클래스**. PLAN §5.2 "반올림은 코드 한 곳에서"의 그 한 곳이다. 매달 반올림한 값에 다시 수익률을 곱하면 Python 엔진과 1원씩 어긋나므로 누적 곱 방식이 정본이다.

### 4.2 키와 식별자

- 모든 테이블은 대리 키 `id BIGINT GENERATED ALWAYS AS IDENTITY`. 예외는 `cash_account`: PK = `season_id`(식별 1:1, `erd.md` D6 — 시즌당 계좌 하나를 DB가 보장). `IDENTITY` 전략은 INSERT마다 DB가 번호를 주므로 Hibernate가 INSERT를 모아서 보내지 못한다(배치 불가). 도메인 테이블은 한 요청에 수십 행이라 문제없고, 수만 행을 넣는 역사 데이터는 JPA를 쓰지 않는다(§7).
- 자연 키(회원 이메일, 종목 코드, 시즌 × 턴 번호)는 `UNIQUE` 제약으로만 지키고 PK로 쓰지 않는다.
- `equals`/`hashCode`는 `id`로만. `id`가 아직 없는(저장 전) 엔티티는 같은 인스턴스일 때만 같다. 엔티티를 `Set`에 넣지 않는 것이 더 간단하므로 연관 컬렉션은 `List`.

### 4.3 연관 관계

- **`@ManyToOne(fetch = LAZY)`만 쓴다.** 기본값 `EAGER`는 주문 하나를 읽을 때 시즌·회원·종목을 전부 끌어온다. `LAZY`는 실제로 접근할 때 읽는다.
- **`@OneToMany` 컬렉션은 두지 않는 것을 기본**으로 한다(시즌 → 주문 목록 같은 것). 필요한 목록은 리포지토리 쿼리로 가져온다(`findBySeasonIdOrderByTurnNo`). 양방향 매핑은 금지 — 양쪽 동기화 실수가 잦고, 이 도메인에서 얻는 것이 없다.
- 외래 키 컬럼을 그냥 `Long seasonId`로 들고 다니고 싶은 유혹이 있다. 규칙: **같은 패키지 안의 연관은 `@ManyToOne`**, **패키지를 넘는 참조(account → season, season → member)는 엔티티 연관 대신 `Long` id**로 둔다. 패키지 간 결합을 낮추기 위해서다.
- 불변 엔티티(§4.4)끼리의 연관은 전부 `Long` id.

### 4.4 불변 엔티티

| 테이블 | 이유 | 매핑 |
|---|---|---|
| `cash_ledger`, `execution` | 추가 전용 원장. 정정은 반대 부호 `ADJUSTMENT` 행 | `@Immutable`(Hibernate), setter 없음, 생성자만. `@Immutable`은 UPDATE를 무시하고 경고하므로, 테스트에서 "원장 수정 시도 → 변경 없음"을 확인한다 |
| `season_path`, `turn`, `turn_price`, `formation_change`, `reconciliation_log`, `turn_feed` | 이력·턴 스냅샷·정본 가격·"사용자가 본 그대로"의 피드(PLAN §4.13) | 같음 |
| `asset_group`, `asset`, `month_block`, `asset_month_return`, `fx_month`, `month_event`, `asset_position`, `asset_month_attention` | 읽기 전용 역사·종목 풀 데이터(적재 스크립트가 쓴다 — Phase 0는 SQL, 이후 Java 배치. Python은 읽기만, PLAN §10) | `@Immutable` + 리포지토리는 조회 메서드만(`save` 없음 — `Repository<T, ID>`를 직접 상속해 `findBy…`만 선언) |

Hibernate의 `@Immutable`은 "이 엔티티는 바뀌지 않는다"는 선언이다. 변경 감지(dirty checking: 트랜잭션 끝에 엔티티가 바뀌었는지 비교해 UPDATE를 만드는 것)를 건너뛰어 읽기 성능도 좋아진다.

### 4.5 감사 컬럼

- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`를 모든 테이블에. DB 기본값은 적재 스크립트·직접 SQL이 넣는 행을 위한 것이고, JPA가 넣는 행은 Auditing 값이 우선한다(DB 기본값 `now()`는 트랜잭션 시작 시각이라 한 트랜잭션의 행이 같은 값을 갖는다 — 행 사이 순서가 필요한 곳은 erd 7.5 규칙 16). Spring Data JPA Auditing(`@CreatedDate`, `@EnableJpaAuditing`)으로 채운다.
- `updated_at`은 가변 엔티티(`member`, `season`, `lineup`, `cash_account`, `holding`)에만.
- 시각은 앱 서버의 `Clock` 빈에서 받는다(테스트에서 고정 시각을 넣기 위해).

### 4.6 비공개 컬럼

`season_path.month_block_id`는 시즌 종료 전까지 사용자에게 보이면 안 된다(PLAN §4.2). 규칙: 이 테이블은 `season` 패키지 안에서만 읽고, DTO에는 "턴 번호"만 나간다. 복기 API는 `season.status = FINISHED`일 때만 월 블록의 날짜·사건을 조인한다. 상태 검사는 서비스에서 하고, 리포지토리에 `findRevealed…` 메서드를 따로 둬 이름에서 드러나게 한다. 같은 규칙이 `erd.md` 정의서에서 `[복기 전용]`으로 표시한 컬럼 전부(`month_block.month_start`·`event_summary`, `month_event.title`·`source`·`hindsight`, `asset.listed_on`)와 표본 수 3 미만인 사건 유형의 카드 범주·유형·결과·단계·방향(PLAN §4.11 고유 사건 예외)에 적용된다 — 시즌 중 DTO에서 제외하고 복기 API에서만 조인한다.

## 5. 트랜잭션 경계와 락

### 5.1 규칙

- `@Transactional`은 **서비스 메서드**에만. 컨트롤러·리포지토리에는 붙이지 않는다.
- 조회 전용 메서드는 `@Transactional(readOnly = true)` — Hibernate가 변경 감지를 끄고 JDBC 연결이 읽기 전용 트랜잭션(`SET TRANSACTION READ ONLY`)으로 처리한다.
- `spring.jpa.open-in-view=false`. 켜 두면 컨트롤러·JSON 직렬화 중에도 DB 세션이 열려 지연 로딩이 몰래 일어난다. 끄면 서비스 밖에서 지연 로딩이 실패하므로, 필요한 것을 서비스에서 DTO로 다 채워 내보내는 습관이 생긴다.
- 도메인 예외는 모두 `RuntimeException`의 자식(`InsufficientCashException`, `TurnMismatchException` …). Spring은 **unchecked 예외에서만 자동 롤백**하므로 checked 예외를 쓰면 돈이 반만 움직인 채 커밋될 수 있다.
- 하나의 HTTP 요청 = 하나의 서비스 트랜잭션. 서비스가 다른 서비스를 부르면 같은 트랜잭션에 참여한다(기본 전파 `REQUIRED`). `REQUIRES_NEW`는 대사 로그 기록 한 곳에만(§6).

### 5.2 주문 체결(턴 사이 매매)

```
OrderService.place(seasonId, expectedTurnNo, side, assetId, qty, declaredPosition)
  1. season = seasonRepo.findByIdForUpdate(seasonId)        -- 비관적 락
     - status == IN_PROGRESS, season.currentTurnNo == expectedTurnNo 아니면 TurnMismatch(409)
  2. account = cashAccountRepo.findBySeasonIdForUpdate(seasonId)   -- 비관적 락
  3. price = turnPriceRepo.find(seasonId, currentTurnNo, assetId)   -- 정본 가격(원 단위, 환산 포함)
  4. 검증: 매수면 현금 ≥ 대금 + 비용, 매도면 보유 ≥ 수량, 단일 종목 한도
  5. INSERT orders(status=FILLED)  -- 턴 단위 게임이라 접수와 체결이 한 번
  6. INSERT execution
  7. holding UPSERT(수량, 평균단가 — MoneyMath)
  8. INSERT cash_ledger(BUY −대금 | SELL +대금), INSERT cash_ledger(FEE, −비용)
  9. account.balance 갱신 = balance ± …  (원장 합과 같아야 함, §6)
 10. lineup: 신규 보유면 선언 포지션 행 추가, 전량 매도면 구간 종료
```

- 비관적 락(`@Lock(LockModeType.PESSIMISTIC_WRITE)` = `SELECT … FOR UPDATE`): 같은 시즌에 두 요청이 동시에 오면 둘째가 **기다렸다가** 첫째의 결과 위에서 검증한다. PostgreSQL의 기본 격리 수준 READ COMMITTED에서 보통 SELECT는 락을 잡지 않으므로, 락 없이 잔액을 읽으면 두 요청이 같은 잔액을 보고 둘 다 "충분하다"고 판단해 둘 다 커밋한다 — 잔액이 음수가 된다. `FOR UPDATE`는 둘째 SELECT를 첫째 트랜잭션이 끝날 때까지 기다리게 하고, 풀리면 **커밋된 최신 행**을 다시 읽는다(Phase 0의 두 세션 실습 P0-15가 이것을 보여 준다).
- 락 순서는 항상 **season → cash_account**. 턴 전환(§5.3)도 같은 순서라 교착이 나지 않는다.
- 멱등성: 요청에 `expectedTurnNo`를 받아 "이미 넘어간 턴에 대한 주문"을 거부한다. 같은 주문을 두 번 보내는 것(더블 클릭)은 EXT에서 `Idempotency-Key` 헤더 + `orders.client_key UNIQUE`로 막는다. Phase 0 API와 MVP는 턴 번호 검사까지.

### 5.3 턴 전환

```
TurnService.advance(seasonId, expectedTurnNo)
  1. season = findByIdForUpdate; currentTurnNo == expectedTurnNo 검사
  2. account = findBySeasonIdForUpdate
  3. nextBlock = seasonPathRepo.findBySeasonIdAndTurnNo(seasonId, expectedTurnNo + 1)
  4. returns = assetMonthReturnRepo.findByMonthBlockId(nextBlock)   -- 풀의 모든 종목(사용자는 아무 종목이나 살 수 있다). 시즌 기준일 풀(`asset_position`)로 거르고 `fx_month`와 결합
     (이력 없는 달도 행은 항상 있다 — MVP는 적재 때 넣은 PROXY_INDEX 행(ETF는 추종 지수가 먼저이고 없으면 대표 지수, 개별 주식은 확장 구간에서 대표 지수), EXT는 지수 + 고유 변동성, PLAN §4.7)
  5. 풀의 종목마다 누적 곱 갱신 → price' = MoneyMath.priceAt(시작가, 누적 곱) → INSERT turn_price (약 180행)
  6. 적립금(EXT) → cash_ledger. (ETF 보수는 원장에서 빼지 않는다 — 수정 종가에 이미 반영, PLAN §4.6)
  7. INSERT turn(턴 번호, 평가액, 현금, 누적 낙폭, 포지션별 평가액 — 현금은 골키퍼, 비중은 조회)
  7b. (P0 API에서는 하지 않음 — 피드는 MVP, PLAN §13 2026-11-09) 소음 피드·독려 알림 생성 → INSERT turn_feed (PLAN §4.13). 생성기(FeedGenerator)의 입력 타입은 "이 턴까지의 가격·관심 지수·사건 태그"만 담는 값 객체로 제한해 다음 블록을 넘길 수 없게 한다. 난수는 시즌 시드 + 턴 번호에서 파생
  8. (P0 API에서는 하지 않음 — 기준선은 성적표 조회 때 계산, 저장 테이블 없음) 기준 포트폴리오 4개도 같은 가격으로 한 턴 진행(리밸런싱·비용 규칙은 PLAN §4.6·§4.10·§6.1, scoring 패키지, 같은 트랜잭션)
  9. 대사(§6) — 불일치면 예외 → 전부 롤백
 10. season.currentTurnNo += 1; 마지막 턴이면 status = FINISHED 후 성적표 계산(별도 트랜잭션으로 분리해도 됨 — 초안: 같은 트랜잭션)
```

- Phase 0 정본은 `db/23_turn_advance_def.sql`(erd D37)이다. 1–5단계의 전제 오류(`SEASON_NOT_FOUND`·`SEASON_NOT_ACTIVE`·`TURN_MISMATCH`·`NO_PATH`·`NO_RETURN`)는 쓰기 전에 거절하고 기록하지 않는다. 9단계 대사 불일치만 롤백한 뒤 17행을 기록한다(§6). 낙폭은 `MoneyMath.drawdown`으로 한 번만 반올림하고(HALF_UP), 골키퍼 평가액은 총액에서 다른 세 포지션을 뺀 값이다.
- `turn(season_id, turn_no) UNIQUE`가 "같은 턴 두 번"을 DB 수준에서 막는다. 락과 턴 번호 검사가 먼저 걸러 주지만, UNIQUE는 마지막 안전망이다.
- `season`과 `cash_account`에 `@Version`도 둔다. 비관적 락이 있는데 왜 또? 락을 거는 경로(주문·턴)가 아닌 **다른 경로**(시즌 포기, 관리자 상태 변경)가 같은 행을 고칠 때 "읽은 뒤 누가 바꿨다"를 잡기 위해서다. 둘은 서로 다른 일을 한다: 비관적 락은 돈 계산의 직렬화, 낙관적 락은 상태 변경의 충돌 감지.
- 시즌 포기(PLAN §4.1-7)는 "남은 턴을 매매 없이 자동 진행"이므로 `advance`를 반복 호출하는 것과 같다. 60턴을 한 트랜잭션에서 돌리면 길어지므로 **턴마다 별도 트랜잭션**으로 돌리고 중간 실패 시 재개할 수 있게 한다(`season.status = ABANDONING`).

### 5.4 전술 변경·포지션 지정

- 전술 변경: `season` 락 → `season.current_formation_*` 갱신 + `formation_change` INSERT. 같은 턴에 두 번 바꾸면 마지막 것만 남지 않고 둘 다 기록된다(복기용). 턴 전환과 같은 락 순서. 변경 뒤의 매도·매수는 보통 주문이고, 주문 트랜잭션 안에서 `orders.formation_change_id`를 채운다(어느 변경에 묶는지의 규칙은 PLAN §5.1).
- 세금(EXT)이 들어오면 원장 행(`TAX`)을 매도 주문 트랜잭션과 턴 전환 트랜잭션에서 추가한다. 세율·과세 시점·이연 표시는 PLAN §4.6이 정한다. 이 문서는 "어느 트랜잭션에서 원장에 쓰는가"만 정한다.
- 포지션 지정·변경: `lineup` 행의 구간을 닫고 새 행을 연다. 주문과 같은 트랜잭션(신규 매수 시) 또는 단독 트랜잭션(보유 중 변경). 단독 변경도 전술 변경처럼 `season` 락 → 상태(`IN_PROGRESS`)·`expectedTurnNo` 검사 → 구간 닫기·열기 순서를 따른다(턴 전환과 겹칠 때 옛 턴 번호로 구간을 닫지 않도록).

## 6. 대사(턴마다)

턴 전환 9단계에서 리포지토리의 집계 쿼리로 항등식을 검사한다.

| 항등식 | 쿼리 |
|---|---|
| 현금 잔액 = Σ 원장 금액 | `SELECT COALESCE(SUM(amount),0) FROM cash_ledger WHERE season_id = ?` ↔ `cash_account.balance` |
| 보유 수량 = Σ 매수 − Σ 매도 | `execution`을 `asset_id`로 묶어 합산 ↔ `holding.quantity` |
| 평가액 = Σ 보유 × `turn_price` | `turn.holdings_value` ↔ `holding` ⋈ `turn_price` 합, `turn.cash_balance` ↔ `cash_account.balance`(총액 `total_value` = 둘의 합은 CHECK) |
| `turn_price` = round(시작가 × Π(1+r)) (r = 원화 결합 수익률 (1+r현지)(1+r환율) − 1, 시작가 = 0턴 원화 가격) | 저장값 ↔ `asset_month_return`과 `season_path`로 재계산(표본 종목 몇 개만, 전수는 M2 병행 대사 — 단 Phase 0 API의 골든(P0-33)은 SQL 규칙 12와 같은 **풀 전체**로 비교한다. 로그 행이 같아야 하기 때문이다) |
| 현재 포메이션 = 마지막 `formation_change.after` (없으면 시작 포메이션) | 중복 컬럼과 이력의 일치 |

- 결과는 `reconciliation_log`(시즌, 턴, 항등식, 기대, 실제, 차이, 분류)에 남긴다. 일치해도 한 행(기록이 있어야 "검사했다"를 증명한다).
- 불일치 시: 턴 전환 트랜잭션은 **롤백**하고 시즌은 그 턴에 멈춘다(`currentTurnNo` 그대로, 다시 시도 가능). 로그는 **롤백이 끝난 뒤** 바깥의 얇은 서비스(`TurnFacade.advance`: 트랜잭션 없음 → 안쪽 `TurnService.advance` 호출 → 예외를 잡아 `ReconciliationLogService.record`를 새 트랜잭션으로)가 남긴다. 안쪽 트랜잭션이 `season`을 `FOR UPDATE`로 잡고 있는 동안 `REQUIRES_NEW`로 로그를 넣으면 `reconciliation_log.season_id`의 FK 검사가 그 락을 기다리다 시간 초과가 나므로, "롤백 뒤에 쓴다"가 규칙이다. 분류(시차·조정·조사)는 MVP에서 전부 "조사"로 두고 사람이 본다.
- 이 검사는 Phase 0의 대사 쿼리를 JPQL/네이티브 쿼리로 옮긴 것이다. SQL 그대로 쓰기 위해 `@Query(nativeQuery = true)`를 허용한다. Phase 0 정본은 `db/22_reconcile_season_def.sql`(erd D36)이고, MVP는 같은 DB 고정본에서 Java 결과가 `recon_rules`와 같은 골든 테스트를 둔다.

## 7. 역사 데이터 적재

- 월 블록·종목 월 수익률(확장 구간 1997-01부터와 본 구간, PLAN §4.7 — 종목 180 × 달 약 348 ≈ 63,000행, 준비 구간 별도)은 JPA로 넣지 않는다. **MVP 전에 한 번** 전체를 적재한다(PLAN §11 MVP): 공개 CSV(수정 종가, 환율, 거시 지표)를 내려받아 월별로 집계하는 적재 스크립트 — Phase 0 표본 스크립트의 확장이며 `COPY`(psql `\copy`) 또는 `JdbcTemplate.batchUpdate`로 넣는다. 시스템 판정 포지션(`asset_position`)도 같은 스크립트가 계산한다. 국면 확률은 M2까지 비어 있다.
- **Phase 0 API는 Phase 0 표본(종목 20·월 블록 100)만 쓴다.** `antlab_api` DB에는 Flyway가 스키마를 만든 뒤 `psql`로 `db/10`–`13`을 적재하고(§3 시드 행), Java 적재기는 위 전체 적재(MVP 전)의 몫이다. 이 API의 엔티티는 역사·종목 풀 테이블을 읽기만 한다.
- M1 수집기(Spring 스케줄 잡)도 `JdbcTemplate` 배치로 쓴다. 엔티티는 읽기 전용(§4.4)이므로 쓰는 쪽과 읽는 쪽이 코드에서 갈린다.
- 사건 태그(`events/`)와 케이스(`cases/`)는 파일 → 테이블 적재 CLI 하나로.

## 8. 조회와 성능

- **N+1 금지:** 목록을 돌며 연관을 하나씩 읽는 패턴. 성적표·턴 결과처럼 여러 테이블을 묶는 조회는 **JPQL 생성자 표현식**(`SELECT new antlab.season.TurnResultDto(…)`) 또는 네이티브 쿼리로 DTO를 바로 만든다. 엔티티 그래프(`@EntityGraph`)는 연관이 꼭 필요한 곳(시즌 상세)에만.
- 리포트성 쿼리(통계 보드 EXT, 리그 순위 APP)는 MyBatis를 쓸 수 있다(PLAN §10). 한 테이블을 두 도구가 **쓰지는** 않는다 — MyBatis는 읽기만.
- 인덱스는 DDL에 `idx_` 접두로 둔다(P0-02 규칙). JPA의 `@Index`는 쓰지 않는다(`validate`는 인덱스를 검사하지 않으므로 DDL이 유일한 출처).
- 목록 API는 `Pageable`. 시즌 목록·주문 이력은 페이지로.
- 턴 전환 1회의 쿼리 수 목표(초안): 보유 종목 수와 무관하게 20개 이하. 테스트에서 쿼리 수를 센다(`DataSource` 프록시 또는 Hibernate 통계).

## 9. 테스트 전략

| 수준 | 도구 | 대상 |
|---|---|---|
| 매핑 | `@DataJpaTest` + **실제 PostgreSQL**(Phase 0 API는 Testcontainers 컨테이너, 아래) | 엔티티 ↔ DDL 일치(`validate`가 시작 시점에 검사), enum ↔ CHECK 목록 글자 단위 대조, 불변 엔티티 UPDATE 무시 |
| 서비스 | `@SpringBootTest` + 실제 PostgreSQL, 테스트마다 `@Transactional` 롤백 | 주문 체결 전부/전무, 턴 전환, 대사 항등식(골든: P0-25 하네스가 같은 컨테이너에서 `db/20`–`26`으로 만든 연습 시즌을 API로 재생해 행 단위로 비교 — PLAN §13 2026-11-09) |
| 동시성 | 스레드 2개로 같은 시즌에 동시 주문·턴 전환 | 둘 중 하나만 성공하거나 순서대로 처리되고, 끝나면 항등식 성립 |
| 엔진 대조 | Java 부트스트랩 vs Phase 0 손 계산 | 같은 시드 → 같은 경로, 같은 수익률 → 같은 가격(원 단위) |
| 계약 | `@SpringBootTest` | springdoc이 낸 `/v3/api-docs`와 저장소 `docs/api/openapi.yaml`을 경로·필드·타입·필수·응답 코드로 대조 — 코드와 yaml이 갈라지면 실패(도구와 정규화는 첫 엔드포인트 기능 P0-27 계획에서 정함) |
| API | `MockMvc` | 검증 오류 400, 턴 불일치 409, 잔액 부족 422(초안), 비공개 컬럼이 응답에 없음 |

- **H2를 쓰지 않는다.** CHECK·`NUMERIC` 반올림·`FOR UPDATE`·문자열 비교의 동작이 PostgreSQL과 달라서, 시험이 통과해도 믿을 수 없다. **Phase 0 API(2026-11-09 결정, PLAN §13)는 로컬 시험부터 Testcontainers PostgreSQL 17**을 쓴다. 이는 처음에 적은 compose `db` 서비스의 `antlab_test` 안(`db/README.md`의 데이터베이스 생성 블록)을 바꾼 것이다. 이렇게 하면 시험이 소유자의 컨테이너를 건드리지 않고(DEPLOY §3.2 격리 규칙을 저절로 지킨다), 시험 실행마다 빈 DB에서 시작하며, M1 CI와 같은 방식으로 실행된다. 시험은 로컬에서 실행하고 CI(M1)가 같은 시험을 합성 표본으로 돌린다. 이 DB는 Flyway 파일을 적용한 빈 DB이며, SQL 골든 시험(P0-25)은 같은 컨테이너에서 `db/` 스크립트를 `psql`로 돌려 Java 결과와 행 단위로 비교한다.
- 테스트 DB는 `./gradlew test`를 실행할 때마다 새 Testcontainers PostgreSQL 17로 시작하고 Flyway `migrate`만 실행한다. 같은 구성의 시험 클래스는 Spring 시험 문맥과 해당 컨테이너를 공유한다. 따라서 데이터를 쓰는 시험은 자신이 쓴 행을 정리하고 identity 값(번호표)을 가정하지 않는다. 빈 테이블을 전제로 하는 시험(`FlywayMigrationTest`·`FlywaySchemaCheckTest`)은 표지 속성으로 별도 문맥(컨테이너)에서 실행한다. 공유 시험 문맥에서는 `clean`을 호출하지 않으며, 실습 DB `antlab`과 API 로컬 DB `antlab_api`에는 접속하지 않는다(프로필 `test`의 URL은 Testcontainers가 준다).

## 10. 설정

P0-22에서 확정한 `src/main/resources/application.yml`의 핵심이다(시험 `SettingsAssertionTest`·`ProfileDocumentTest`가 값을 고정한다).

```yaml
spring:
  datasource:
    url: ${ANTLAB_DB_URL:jdbc:postgresql://localhost:5432/antlab_api}   # Phase 0 API 로컬 DB(DEPLOY §9.2)
    username: ${ANTLAB_DB_USER:${USER:antlab}}
    password: ${ANTLAB_DB_PASSWORD:}
    hikari:
      connection-init-sql: SET TIME ZONE 'Asia/Seoul'   # 세션 시간대를 JVM 기본값과 떼어 놓는다
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: Asia/Seoul
  jackson:
    deserialization:
      accept-float-as-int: false   # 정수 필드에 소수가 오면 400
  lifecycle:
    timeout-per-shutdown-phase: 20s
management:
  endpoints.web.exposure.include: health
  endpoint.health:
    probes.enabled: true
    group.readiness.include: readinessState,db   # liveness 는 기본(DB 가 죽어도 재시작하지 않는다)
springdoc:                                         # 공통은 끔, local 에서만 켠다
  api-docs.enabled: false
  swagger-ui.enabled: false
# 엔티티가 생기면: hibernate.default_batch_fetch_size: 100(지연 로딩을 IN 쿼리로 묶는다)
spring.flyway:
  enabled: true
  locations: classpath:db/migration
  default-schema: public
  schemas: public
  baseline-on-migrate: false
  clean-disabled: true
  validate-on-migrate: true
# P0-23 이후: antlab.engine.*(projection-paths: 2000, block-mean-length: 6)
```

프로필은 `application.yml` 안의 `---` 문서 셋이다. 공통 프로필에는 프로필 없이 떠도 안전한 값(SQL 로그·명세 꺼짐)을 둔다. `local`은 SQL 로그와 명세·Swagger UI를 켜며 `bootRun`·`bootTestRun`이 기본으로 준다. `prod`는 OPS 설정이며 springdoc을 끄고 `server.forward-headers-strategy: framework`을 사용한다. 시험 프로필 `test`만 `src/test/resources/application-test.yml`에 있으므로 jar에 들어가지 않는다(Testcontainers, 명세만 켬; Flyway clean은 새 컨테이너 원칙상 쓰지 않는다). `local` 값을 `application-local.yml` 파일로 두지 않는 까닭은 `.gitignore`가 그 파일을 무시하기 때문이다(소유자 개인 덮어쓰기는 `config/application-local.yml`). `prod`는 고정 회원 구현이 켜져 있으면 시작을 거부한다(M4 JWT 전까지, PLAN §13 2026-11-09). 비밀값(DB 비밀번호)은 환경 변수로만 둔다.

### 10.1 컨테이너에서 실행 (Phase 0 API 체크리스트 초안)

Spring 프로젝트를 만들 때(Phase 0 API, P0-22·P0-24) 아래를 지킨다. 같은 이미지가 로컬·CI·운영에서 돌고, 운영 값은 환경 변수와 `/run/secrets`가 정한다. 구조 전체는 [DEPLOY.md](DEPLOY.md).

1. `application.yml`은 `${ANTLAB_…:기본값}` 자리표시자와 로컬 기본값만 두고, 운영 값은 환경 변수·configtree(`spring.config.import=optional:configtree:/run/secrets/`, 세부 **확인 필요**)로 준다. `application-prod.yml`에는 비밀값을 쓰지 않는다.
2. 프로필은 위의 `local`·`test`·`prod` 셋이고, 컨테이너에서는 `SPRING_PROFILES_ACTIVE=prod`다. `prod`는 SQL 로그를 끄고 `open-in-view: false`를 유지한다.
3. Actuator는 `health`만 노출하고 probes(liveness·readiness)를 `management.endpoint.health.probes.enabled=true`로 **명시해** 켠다(3.x는 Kubernetes 밖에서 자동으로 켜지지 않는다 — [DEPLOY.md](DEPLOY.md) §8. 4.1.2 문서는 자동 켜짐이지만 명시해서 고정한다 — 명시 때문에 자동 켜짐 여부는 독립적으로 확인하지 못했고, `ActuatorProbesTest`가 liveness·readiness 200을 확인한다). `info`에 git SHA를 넣는다.
4. 그레이스풀 셧다운: 4.1.2는 기본 `graceful`이고(설정 메타데이터 확인, `server.shutdown: graceful`도 명시) `spring.lifecycle.timeout-per-shutdown-phase=20s`로 둔다(`SettingsAssertionTest`).
5. 프록시 뒤: `server.forward-headers-strategy=framework`(Caddy의 `X-Forwarded-*`로 HTTPS·원래 IP 인식 — 속성 이름은 4.1.2 설정 메타데이터로 확인했고 `prod` 문서에 있다).
6. Flyway는 시작 때 적용하고 `ddl-auto: validate`를 유지한다.
7. 로그는 stdout만, 키·토큰은 마스킹한다.
8. Dockerfile(멀티 스테이지)·`.dockerignore`를 api 첫 PR에서 만들고, `compose.yaml`에 `api`(`build: .`)를 더한다. CI는 이미지 **빌드만** 하고 푸시하지 않는다.
9. Spring Boot 판은 **4.1.x(Java 21)로 정했다**(소유자 Gate 0, 2026-11-09 — 커리큘럼 프로젝트가 4.1.2, PLAN §10). 이 문서의 3.x 전제는 위 3·4번에서 4.1.2 기준으로 바꿨다. 스타터 이름(`spring-boot-starter-webmvc` 등)과 직렬화 라이브러리(Jackson 3, 패키지 `tools.jackson`, `JsonMapperBuilderCustomizer`는 `org.springframework.boot.jackson.autoconfigure`)는 P0-22에서 실행으로 확인했다.
10. API 계약: 기능마다 `docs/api/openapi.yaml`을 계획 단계에서 먼저 쓰고(§2), springdoc 명세와 yaml을 대조하는 시험(§9)을 통과시킨다. springdoc은 3.2.0(Boot 4.1.x와 호환)이고 Swagger UI 경로는 `/swagger-ui.html`이다. 공통 문서에서 끄고 `local`에서만 켜며(시험은 명세만), `BigDecimal`은 `SpringDocUtils.getConfig().replaceWithSchema(...)`로 명세에서도 `type: string`으로 맞췄다(`JsonMoneyFormatTest`).

## 11. Phase 1(JDBC) → MVP(JPA) 대응표

Phase 1은 건너뛰었다(PLAN §11, 2026-11-09). 이 표는 SQL(Phase 0)에서 직접 한 일 → Phase 0 API·MVP의 JPA로 읽는다: 첫 열의 "손으로"는 JDBC 코드가 아니라 SQL 실습(P0-15 두 세션 `FOR UPDATE`, P0-17 롤백)이다.

| 손으로(SQL 실습·JDBC) | Phase 0 API·MVP에서 |
|---|---|
| `Connection.setAutoCommit(false)` … `commit()/rollback()` | `@Transactional` |
| `PreparedStatement` INSERT/UPDATE | `repository.save(entity)` + 변경 감지 |
| `ResultSet` → 객체 | 엔티티 매핑, DTO 생성자 표현식 |
| `SELECT … FOR UPDATE` 직접 작성 | `@Lock(PESSIMISTIC_WRITE)` 리포지토리 메서드 |
| 대사 SQL | 같은 SQL을 `@Query(nativeQuery = true)`로 |
| 경로 생성 코드(단순 부트스트랩) | 그대로 `engine` 패키지로 이동 |

트랜잭션·락·반올림을 SQL에서 손으로 한 번 해 보는 것이 이 문서의 결정들을 이해하는 가장 빠른 길이다.

## 12. 열린 결정(구현 중 확정)

1. 성적표 계산을 마지막 턴 전환과 같은 트랜잭션에 둘지 분리할지(초안: 같이. 느리면 분리하고 `season.status = SCORING` 추가). Phase 0 API는 성적표를 저장하지 않고 조회로 계산하므로(P0-38) 이 질문은 저장이 생기는 MVP(`season_score`)로 미룬다.
2. `holding`을 엔티티로 두고 UPSERT할지, `execution` 집계로만 계산할지(초안: 엔티티 — 조회가 잦고 대사로 집계와 대조).
3. 네이티브 쿼리와 JPQL의 경계(초안: 대사·집계는 네이티브, 나머지는 JPQL).
4. `Idempotency-Key`(EXT) 저장 방식.
5. ~~PostgreSQL 이전 때 `IDENTITY` 처리~~ — 처음부터 PostgreSQL이라 해소(2026-11-05). `GENERATED ALWAYS AS IDENTITY` + JPA `IDENTITY`로 간다. 시퀀스 + `SEQUENCE` 전략(배치 INSERT 가능)으로 바꿀지는 성능이 문제될 때만.
6. `turn_price`를 풀 전체(턴마다 약 180행, 5년 시즌 10,800행)로 둘지 "보유 + 기준 종목"만 두고 나머지는 주문 때 계산할지(초안: 풀 전체 — 조회가 단순하고 병행 대사가 전수 비교가 된다).
