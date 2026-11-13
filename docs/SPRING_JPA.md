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

- **도메인별 패키지**(계층별이 아니라)로 나눈다. `season` 안에 `Season`(엔티티), `SeasonRepository`, `SeasonService`, `SeasonDto`를 함께 둔다. 기능 하나를 고칠 때 한 폴더만 열면 되게 하기 위해서다.
- 계층은 세 개: **컨트롤러**(HTTP ↔ DTO) → **서비스**(트랜잭션, 규칙) → **리포지토리**(Spring Data JPA 인터페이스). 컨트롤러는 리포지토리를 직접 부르지 않는다.
- **API 계약 관리(Phase 0 API부터, PLAN §13 2026-11-09):** 기능을 계획할 때 `docs/api/openapi.yaml`의 해당 경로·스키마를 **먼저** 쓰고 계획 리뷰에서 함께 본다 → 컨트롤러·DTO를 구현하고 springdoc-openapi가 `/v3/api-docs`와 Swagger UI를 낸다(UI는 `/swagger-ui.html`, `local` 프로필에서만 켠다 — 시험은 명세 `/v3/api-docs`만 켠다) → 시험이 그 명세와 저장소 yaml을 대조한다(§9). 웹 타입은 이 yaml에서, 엔진(FastAPI)과 Spring 사이의 타입은 M2에서 만드는 `docs/api/engine.yaml`에서 생성한다. yaml 파일은 P0-27에서 처음 만들었다(풀 경로 + 공통 오류 스키마 `Problem`). 대조에는 자체 정규화 비교기를 쓴다(§9). 차이가 나면 yaml을 기준으로 코드(애너테이션·설정)를 맞춘다.
- **JSON·오류 규칙(Phase 0 API, PLAN §13 2026-11-09):** 접두 `/api/v1`, 금액·비율은 JSON 문자열(부동소수 금지, PLAN §5.2)로 보낸다. 오류는 `ProblemDetail` + `code`(SQL 힌트 코드와 같은 글자)로 보낸다. 5xx 응답에는 예외 내용을 싣지 않는다(고정 문장, 내용은 로그에만). `BigDecimal`은 지수 없는 문자열이고 펼친 자릿수 상한은 64다(입력 초과 400, 출력 초과 500 — 업무별 자릿수는 DTO `@Digits`가 정한다). 출력 500은 응답을 보내기 전에만 된다 — 큰 응답을 이미 보내기 시작한 뒤 초과하면 상태는 200 그대로이고 JSON은 닫히지 않은 채 끝난다(값이 빠진 응답이 정상 JSON처럼 보이지 않게 자동 닫기를 껐다). 따라서 응답 DTO의 금액은 반올림해 scale을 정한 값만 담는다(PLAN §5.2). JSON 정수 필드에 소수점·지수 표기 숫자(`1.5`·`2.0`·`1e1`)가 오면 400이다(`accept-float-as-int: false`). 따옴표 숫자(`"3"`)도 400이다 — `JsonConfig`가 정수 논리 형식의 문자열 입력을 거절하도록 강제 변환을 껐고, 금액·비율(`BigDecimal`)은 영향 없이 계속 문자열로 받는다(#119). 오류 응답의 `type`은 `about:blank`라 생략된다(RFC 9457) — `code`·`title`·`status`·`detail`·`instance`는 늘 있다(`openapi.yaml`의 `Problem`에서 `type`은 선택·생략, `title`·`status`·`detail`·`instance`·`code`는 필수, `errors`는 선택이다). 모든 엔드포인트 명세에는 공통 500 응답(`components.responses.InternalError`, 본문 `Problem`·`code: INTERNAL_ERROR`)이 있다 — 코드는 springdoc `OpenApiCustomizer`(`OpenApiConfig`)가 명세를 다 만든 뒤 모든 운영에 한 번에 붙이고, 계약 yaml에도 같은 참조를 적는다(대조 시험이 둘의 일치를 본다). `problem+json` 보장은 DispatcherServlet 안까지다 — Tomcat이 직접 거절하는 요청(잘못된 `%` 인코딩·너무 큰 헤더)과 Security 필터 예외(M4)는 따로 다룬다.
- **DTO**(Data Transfer Object): 화면·API에 보낼 값만 담은 단순 객체. 엔티티를 그대로 JSON으로 내보내면 지연 로딩·순환 참조·비공개 컬럼(`season_path`)이 새므로 금지한다. Java `record`로 만든다.

## 3. 스키마 관리

| 항목 | 결정 | 이유 |
|---|---|---|
| 마이그레이션 | **Flyway**. `db/`의 DDL을 `src/main/resources/db/migration/V1__schema.sql`부터 번호 순으로 둔다. **Phase 0 API(P0-23)에서는** `V1__schema_history.sql`·`V2__schema_season.sql`·`V3__schema_account.sql`이 `db/01`–`03`의 `-- [스키마]` … `-- [/스키마]` 구간과 글자 단위로 같고(동일성 시험이 둘을 묶는다), 이 시점부터 Flyway가 스키마 정본이다. 이후 변경은 `V4__…`부터이고 `db/01`–`03`은 Phase 0 SQL 실습본으로 고정한다. SEC-01 adds `V4__auth.sql` (no `db/` twin; pinned by `SchemaParityTest`). / SEC-01이 `V4__auth.sql`을 더한다(`db/` 짝 없음, `SchemaParityTest`가 고정) | 스키마 변경을 코드처럼 버전 관리하고, 앱 시작 때 자동 적용. 테스트 DB도 같은 파일로 만들어 "DDL ↔ 엔티티" 불일치를 시작 시점에 잡는다 |
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
| 비중·확률(포메이션 비중, 국면 확률) | `Integer` + `@JdbcTypeCode(SqlTypes.SMALLINT)`(비중, 0–100) / `BigDecimal(5,4)`(확률) | `SMALLINT` / `NUMERIC(5,4)` | | 포메이션은 정수 퍼센트이고 합은 100 CHECK로 검증한다. 모든 `SMALLINT` 열에 같은 규칙을 적용한다 — `@JdbcTypeCode` 없이 `Integer`/`int`를 쓰면 `validate`가 `int2`와 `integer`의 불일치로 시작을 거부한다(P0-27 실측) |
| 구분값(주문 방향·상태, 원장 유형, 포지션, 사건 범주) | `enum` | `VARCHAR(n)` + CHECK, 길이와 값 목록은 `erd.md`에 따른다(예: `side VARCHAR(4)` `BUY`/`SELL`, 원장 `type` `DEPOSIT`/`BUY`/`SELL`/`FEE`/`TAX`/`ADJUSTMENT`). PostgreSQL의 기본 비교는 대소문자·악센트·뒤 공백을 구분하므로 별도 콜레이션이 필요 없다 | `@Enumerated(EnumType.STRING)` | `ORDINAL`(숫자) 금지 — enum 순서를 바꾸면 데이터가 뒤틀린다. DDL의 CHECK 목록과 enum 상수는 **글자 단위로 같아야** 하며 테스트가 대조한다(§9). `@Column(length = n)`은 ERD와 같게 설정한다 — Hibernate 7.4의 `validate`는 형 계열만 보고 길이·정밀도·NULL 허용은 보지 않으므로(P0-27 실측) 이 빈칸은 `ColumnContractTest`가 `information_schema`와 대조해 메운다 |
| 날짜 | `LocalDate` | `DATE` | | 달(`month_start`)·기준일·상장일 |
| 시각 | `Instant` | `TIMESTAMPTZ` (P0-03 결정 1, 2026-11-05) | | 저장값은 시간대와 무관한 순간. 표시·"오늘" 조회는 `Asia/Seoul`로 변환(DTO 또는 `AT TIME ZONE`). DB `timezone = 'Asia/Seoul'` |
| 비율(수익률, 환율 변화, 참여율, 변동성, 낙폭, z-score, 강도) | `BigDecimal` | `NUMERIC(12,8)` | `@Column(precision = 12, scale = 8)` | 금액이 아닌 비율은 전부 이 하나(P0-03 기본값 2). 확률만 `NUMERIC(5,4)` |
| 기본 키 | `Long` | `BIGINT GENERATED ALWAYS AS IDENTITY` | `@Id @GeneratedValue(strategy = IDENTITY)` | §4.2 |
| 낙관적 잠금 버전 | **`Long`(래퍼)** | `BIGINT NOT NULL DEFAULT 0` | `@Version` | `season`, `cash_account`에만(§5.3). 원시 `long`이 아닌 이유: Spring Data `save()`는 버전 속성이 원시형이면 "id가 있으면 기존 엔티티"로 판단한다. 키를 직접 정하는 `cash_account`(§4.2)는 새 객체도 id가 있어 `merge`(SELECT 뒤 INSERT)로 가므로, 버전을 래퍼로 두어 `null` = 새 엔티티 → `persist`(INSERT 한 번)가 되게 한다. 두 엔티티를 같은 규칙으로 맞춘다. 시험: `MutableEntityTest` |

`antlab.common.money.MoneyMath`는 상태 없는 `public static` 계산 클래스다. 공개 계약은 `initialPrice`, `priceAt`, `feeRate`, `fee`, `avgPrice`, `affordable`, `teamQty`, `drawdown`, `valuation`, `totalValue`, 성적표용 `fractionMean`(26 `sr_frac_mean` 짝)·`pad40Divide`(40자리 나눗셈, PG 몫 자릿수 규칙)·`sampleStddev8`(PG `stddev_samp` 단계)·`round8`·`ceilCount`와 `ReturnFactors`다. 입력 단위와 범위는 아래 표를 따른다. `priceAt`은 저장된 0턴 가격과 현지·환율 변화 목록을 받아 누적 곱을 유지하고, 가격·수수료는 마지막에 원 단위 `HALF_UP`, 평균단가는 소수 4자리, 낙폭은 소수 8자리로 한 번만 반올림한다. 이 클래스가 반올림이 일어나는 유일한 코드 위치다. 입력 검증 때문에 계산 결과가 0이 되는 경로는 반환할 수 있으며, 저장 경계가 양수 제약을 적용한다.

| 메서드 | 입력 단위·범위 | 반환 | 반올림 |
|---|---|---|---|
| `initialPrice` | 기준가(현지 통화)·기준 환율: 양수, `NUMERIC(19,4)`로 정확히 표현 | 원 단위 | 0자리 HALF_UP 한 번 |
| `priceAt` | 저장된 0턴 가격(양의 원 단위), 턴 순서의 `ReturnFactors`(현지 ≥ −1, 환율 > −1, `NUMERIC(12,8)`; KRW 종목은 환율 0) | 원 단위(0 가능) | 정확 곱 뒤 0자리 HALF_UP 한 번 |
| `feeRate` | 시장 `KR`·`US`, 시즌 요율 3개 각각 0 이상 1 미만·소수 8자리 이하 | 합산 요율, scale 8 | 없음 |
| `fee`·`teamQty` | 가격(양의 원 단위), 합산 요율(0 이상, `NUMERIC(12,8)` — 1 이상도 허용) | 원 단위 / 정수 주 | 0자리 HALF_UP 한 번 / 정수 몫(SQL `div`) |
| `avgPrice` | 기존 수량 ≥ 0·기존 평균(수량 0이면 0, 아니면 양수, 소수 4자리 이하), 신규 수량 > 0·체결가(양의 원 단위) | scale 4 | 정확 분자 ÷ 수량, 4자리 HALF_UP 한 번 |
| `affordable`·`valuation`·`totalValue` | 0 이상 원 단위(가격은 양수) | 불리언 / 원 단위 | 없음 |
| `drawdown` | 총액 ≥ 0, 고점 > 0, 원 단위 | scale 8, 0 이하 | 8자리 HALF_UP 한 번(음수 절반은 0에서 먼 쪽) |

공통: null 거부, 반환 금액은 `NUMERIC(19,4)` 범위 검사, 오류는 값 없는 `IllegalArgumentException`(서비스가 도메인 오류로 바꾼다).

### 4.2 키와 식별자

- 모든 테이블은 대리 키 `id BIGINT GENERATED ALWAYS AS IDENTITY`. 예외는 `cash_account`: PK = `season_id`(식별 1:1, `erd.md` D6 — 시즌당 계좌 하나를 DB가 보장). `IDENTITY` 전략은 INSERT마다 DB가 번호를 주므로 Hibernate가 INSERT를 모아서 보내지 못한다(배치 불가). 도메인 테이블은 한 요청에 수십 행이라 문제없고, 수만 행을 넣는 역사 데이터는 JPA를 쓰지 않는다(§7).
- 자연 키(회원 이메일, 종목 코드, 시즌 × 턴 번호)는 `UNIQUE` 제약으로만 지키고 PK로 쓰지 않는다.
- `equals`/`hashCode`는 **시즌·계좌 쪽 엔티티(P0-28)에서는 재정의하지 않는다.** `Set`·`@OneToMany`가 없어 비교할 일이 없고, 한 영속성 컨텍스트 안의 같은 행은 JPA가 같은 인스턴스로 돌려주며, id 비교는 프록시 클래스 비교를 잘못 짜는 흔한 버그가 있다. 비교가 필요해지는 기능이 그때 규칙과 시험을 더한다(P0-27의 읽기 전용 엔티티는 id 비교를 이미 갖고 있다). 컬렉션이 필요하면 `List`.

### 4.3 연관 관계

- **`@ManyToOne(fetch = LAZY)`만 쓴다.** 기본값 `EAGER`는 주문 하나를 읽을 때 시즌·회원·종목을 전부 끌어온다. `LAZY`는 실제로 접근할 때 읽는다.
- **`@OneToMany` 컬렉션은 두지 않는 것을 기본**으로 한다(시즌 → 주문 목록 같은 것). 필요한 목록은 리포지토리 쿼리로 가져온다(`findBySeasonIdOrderByTurnNo`). 양방향 매핑은 금지 — 양쪽 동기화 실수가 잦고, 이 도메인에서 얻는 것이 없다.
- 외래 키 컬럼을 그냥 `Long seasonId`로 들고 다니고 싶은 유혹이 있다. 규칙: **같은 패키지 안의 연관은 `@ManyToOne`**, **패키지를 넘는 참조(account → season, season → member)는 엔티티 연관 대신 `Long` id**로 둔다. 패키지 간 결합을 낮추기 위해서다.
- 불변 엔티티(§4.4)끼리의 연관은 전부 `Long` id.
- P0-28의 실제 적용: `season_path`·`turn`·`turn_price`·`formation_change`·`turn_feed`·`lineup`은 같은 `season` 패키지의 `Season`을, `cash_ledger`는 같은 `account` 패키지의 `CashAccount`를 `@ManyToOne(LAZY, optional = false)`로 가리킨다. 나머지(`season.member_id`는 `member`, `orders.season_id`·`holding.season_id`는 `season`, `reconciliation_log.season_id`는 `season`, `*.asset_id`는 `asset`, `execution.order_id`는 불변끼리)는 `Long` id다. 복합 FK 3개(`orders`의 전술 변경·피드, `cash_ledger`의 주문)도 컬럼 그대로 `Long`·`int`로 두고 일관성은 DB가 지킨다(erd D29). `member`(회원)와 `scoring`(`reconciliation_log`)도 자기 패키지를 갖는다(§2).

### 4.4 불변 엔티티

| 테이블 | 이유 | 매핑 |
|---|---|---|
| `cash_ledger`, `execution`, `orders` | 추가 전용 원장. 정정은 반대 부호 `ADJUSTMENT` 행. `orders`는 접수와 체결이 한 번에 일어나고(§5.2 5단계) 넣은 뒤 바뀌지 않으므로 불변이다 — 지정가(EXT)가 오면 다시 본다(erd 7.5 규칙 16) | `@Immutable`(Hibernate), setter 없음, 생성자만. `@Immutable`은 UPDATE를 무시하고 경고하므로, 테스트에서 "원장 수정 시도 → 변경 없음"을 확인한다 |
| `season_path`, `turn`, `turn_price`, `formation_change`, `reconciliation_log`, `turn_feed` | 이력·턴 스냅샷·정본 가격·"사용자가 본 그대로"의 피드(PLAN §4.13) | 같음 |
| `asset_group`, `asset`, `month_block`, `asset_month_return`, `fx_month`, `month_event`, `asset_position`, `asset_month_attention` | 읽기 전용 역사·종목 풀 데이터(적재 스크립트가 쓴다 — Phase 0는 SQL, 이후 Java 배치. Python은 읽기만, PLAN §10) | `@Immutable` + 리포지토리는 조회 메서드만(`save` 없음 — `Repository<T, ID>`를 직접 상속해 `findBy…`만 선언) |

`RefreshToken` (SEC-01) is a mutable entity whose only state change is the named method `revoke(RevokeReason, Instant)`; it has no repository until SEC-03. / `RefreshToken`(SEC-01)은 가변 엔티티이고 상태 변경은 이름 있는 메서드 `revoke(RevokeReason, Instant)` 하나뿐이며, 리포지토리는 SEC-03까지 없다.

추가 전용 엔티티의 리포지토리는 `AppendOnlyRepository<T, ID>`(`antlab.common.persistence`, `Repository`를 상속한 `save`·`findById`만)를 상속해서 `delete*`·`saveAll`이 컴파일되지 않는다. 가변 엔티티의 리포지토리도 `Repository`를 직접 상속하고 필요한 메서드만 선언하며, `JpaRepository`는 쓰지 않는다(`RepositoryShapeTest`가 패키지를 가리지 않고 확인). 불변 엔티티는 모든 컬럼을 받는 공개 생성자 하나와 보호된 기본 생성자만 갖고, 가변 엔티티는 setter 대신 이름 있는 상태 변경 메서드(`Season.advanceTurn`·`changeFormation`, `CashAccount.applyLedgerAmount`, `Holding.applyBuy`·`applySell`, `Lineup.close`, `RefreshToken.revoke`)만 갖는다. JPQL 일괄 `UPDATE`는 불변 엔티티에 대해 거부된다(Hibernate 7.4 기본, `InterpretationException`). 네이티브 SQL `UPDATE`는 막지 못한다 — 그 길은 DB 권한(MVP)이 막는다.

Hibernate의 `@Immutable`은 "이 엔티티는 바뀌지 않는다"는 선언이다. 변경 감지(dirty checking: 트랜잭션 끝에 엔티티가 바뀌었는지 비교해 UPDATE를 만드는 것)를 건너뛰어 읽기 성능도 좋아진다.

### 4.5 감사 컬럼

- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`를 모든 테이블에 둔다. DB 기본값은 적재 스크립트나 직접 SQL이 넣는 행에 사용하고, JPA가 넣는 행에는 Auditing 값을 우선한다(DB 기본값 `now()`는 트랜잭션 시작 시각이라 한 트랜잭션의 행이 같은 값을 갖는다 — 행 사이 순서가 필요한 곳은 erd 7.5 규칙 16). Spring Data JPA Auditing(`@CreatedDate`, `@EnableJpaAuditing`)으로 값을 채운다. 단, 주문·전술 변경의 생성 시각은 서비스가 잠금 뒤 확정해 생성 시 전달한다. 그 밖의 감사 시각에는 기존 Auditing을 사용한다(P0-37: 두 엔티티는 감사 리스너를 끄고 전달받은 시각을 그대로 저장한다 — 검사한 시각과 저장한 시각이 같아야 한다).
- `updated_at`은 가변 엔티티(`member`, `season`, `lineup`, `cash_account`, `holding`, `refresh_token`)에만.
- 시각은 앱 서버의 `Clock` 빈에서 받는다(시각의 출처를 한 곳에 두기 위해 — 시험은 흐르는 시계만 쓰고 `Clock.fixed`는 쓰지 않는다, erd §9 Phase 0 API ⑤). JPA가 넣는 행의 `created_at`은 `persist` 순간의 `Instant.now(clock)`이라, 서비스가 락을 얻은 뒤의 시각이다(규칙 16의 전제).

### 4.6 비공개 컬럼

`season_path.month_block_id`는 시즌 종료 전까지 사용자에게 보이면 안 된다(PLAN §4.2). 규칙: 이 테이블은 `season` 패키지 안에서만 읽고, DTO에는 "턴 번호"만 나간다. 복기 API는 `season.status = FINISHED`일 때만 월 블록의 날짜·사건을 조인한다. 상태 검사는 서비스에서 하고, 리포지토리에 `findRevealed…` 메서드를 따로 둬 이름에서 드러나게 한다. 같은 규칙이 `erd.md` 정의서에서 `[복기 전용]`으로 표시한 컬럼 전부(`month_block.month_start`·`event_summary`, `month_event.title`·`source`·`hindsight`, `asset.listed_on`)와 표본 수 3 미만인 사건 유형의 카드 범주·유형·결과·단계·방향(PLAN §4.11 고유 사건 예외)에 적용된다 — 시즌 중 DTO에서 제외하고 복기 API에서만 조인한다.

**노출 상한 시험(P0-27).** 응답 record의 각 DB 열 구성 요소에는 `@ExposedFrom("표.열")`(`antlab.common.exposure`)를 붙여 원천 열을 밝힌다. 중첩 record와 그 목록의 구성 요소는 애너테이션 없이 해당 record를 다시 검사한다. `ExposureContractTest`는 `/api/v1/**` 핸들러의 반환형을 따라가 해당 열의 DB 코멘트 태그가 `[노출]`인지 확인한다. `[복기 전용]`·`[내부]` 원천은 0건이다. 식별자 열(`id`·`*_id`)은 태그와 무관하게 **식별자 허용 목록**(시험 상수 `ExposureRules.IDENTIFIER_ALLOWED`, 지금은 `asset.id`·`asset.asset_group_id`·`season.id`·`turn_price.asset_id` 넷이다 — `season.id`는 시즌 생성 순서라 역사 날짜와 무관하고 `turn_price.asset_id`는 이미 허용된 `asset.id`와 같은 값이다(P0-30). 목록이 더 늘면 DTO 옆 표시 방식으로 바꾸는 안(P0-27 대안 C)을 다시 본다)으로만 통과한다. FK 열이 `[노출]`이어도 부모 PK가 `[내부]`이면 같은 값이므로 우회를 막는다. **영구 금지**는 `month_block.id`·모든 `*.month_block_id`·`month_event.id`·`turn_feed.month_event_id`와, `month_block_id` 열이 있는 표(`asset_month_return`·`fx_month`·`asset_month_attention` 등)의 `id`다. 적재 순서가 달 순서(`ORDER BY month_start`)라 번호 자체가 날짜 단서이기 때문이다. 목록·중첩 record는 해당 record를 다시 검사하고, 실제 응답 JSON의 키가 record 구성 요소 이름과 같은지와 본문에 날짜 꼴 문자열·금지 키가 없는지도 본다.

**성적표(P0-38)의 좁은 예외 둘.** ① 끝난 시즌 전용 원천 `season_path.month_block_id`(역할 수행 — 경로의 달 순서로 잰 실측): 핸들러에 `@FinishedSeasonOnly`가 있어야 하고 진행 중 시즌은 409이며, 구성 요소는 다른 원천과 함께 집계한 `BigDecimal`·`Integer`일 때만 통과한다(블록 번호 값 자체는 여전히 영구 금지). ② 건수 전용 원천 `reconciliation_log.status`·`turn_no`·`attempt_no`: 같은 표지가 필요하고 구성 요소는 `Integer`만. 다른 핸들러가 두 원천을 쓰면 실패한다(`ExposureRules.FINISHED_ONLY_ALLOWED`·`COUNT_ONLY_ALLOWED`). P0-39 복기가 같은 장치를 넓혀 쓴다.

**Review (P0-39).** The review handler carries both `@FinishedSeasonOnly` and `@ReviewReveal`. Only there do `[복기 전용]` value columns and `season.seed`·`engine_version`·`data_snapshot_date` pass (these three are tagged `[내부]` and listed in `ExposureRules.REVIEW_INTERNAL_ALLOWED`), and a test freezes `@ReviewReveal` to that one path. The allowance is by tag, not by column: any non-identifier `[복기 전용]` column added to the review DTO passes, so such an addition is decided in review. Block ids, event ids and the event key stay permanently forbidden. A repository method that selects or returns a `[복기 전용]` value column must be named `findRevealed…`, and only `SeasonReviewService` calls those methods. The one exemption is the engine input `MonthBlockRepository.findReplayMonths`, and a test freezes its callers to the engine. The entity fields of those values on `MonthBlock`, `MonthEvent` and `Asset`, and `SeasonPath.monthBlockId`, have no getter. `TurnFeed` keeps its getters until the MVP feed decides how `kind` is read. `month_block_id` used only as a join key does not count. `JdbcClient` SQL is outside this name scan, so the response-side exposure test covers it.

**복기(P0-39).** 복기 핸들러는 `@FinishedSeasonOnly`와 `@ReviewReveal`을 함께 단다. 이 핸들러에서만 `[복기 전용]` 값 열과 `season.seed`·`engine_version`·`data_snapshot_date`(`[내부]` 태그, `ExposureRules.REVIEW_INTERNAL_ALLOWED`)가 통과하고, 시험이 `@ReviewReveal`을 이 경로 하나로 고정한다. 허용은 열 단위가 아니라 태그 단위다 — 식별자가 아닌 `[복기 전용]` 열을 복기 DTO에 더하면 통과하므로 그런 추가는 리뷰에서 정한다. 블록·사건 번호와 사건 키는 여전히 영구 금지다. `[복기 전용]` 값 열을 고르거나 돌려주는 리포지토리 메서드는 이름이 `findRevealed`로 시작해야 하고 `SeasonReviewService`만 부른다. 예외는 엔진 입력 `MonthBlockRepository.findReplayMonths` 하나이고, 시험이 그 호출처를 엔진으로 고정한다. `MonthBlock`·`MonthEvent`·`Asset`의 그 값 필드와 `SeasonPath.monthBlockId`에는 getter가 없다. `TurnFeed`는 MVP 피드가 `kind`를 읽는 방식을 정할 때까지 getter를 둔다. 조인 키로만 쓰는 `month_block_id`는 대상이 아니다. `JdbcClient` SQL은 이 이름 훑기 밖이므로 응답 쪽 노출 시험에 맡긴다.

## 5. 트랜잭션 경계와 락

### 5.1 규칙

- `@Transactional`은 **서비스 메서드**에만. 컨트롤러·리포지토리에는 붙이지 않는다.
- 조회 전용 메서드는 `@Transactional(readOnly = true)` — Hibernate가 변경 감지를 끄고 JDBC 연결이 읽기 전용 트랜잭션(`SET TRANSACTION READ ONLY`)으로 처리한다.
- `spring.jpa.open-in-view=false`. 켜 두면 컨트롤러·JSON 직렬화 중에도 DB 세션이 열려 지연 로딩이 몰래 일어난다. 끄면 서비스 밖에서 지연 로딩이 실패하므로, 필요한 것을 서비스에서 DTO로 다 채워 내보내는 습관이 생긴다.
- 도메인 예외는 모두 `RuntimeException`의 자식(`InsufficientCashException`, `TurnMismatchException` …). Spring은 **unchecked 예외에서만 자동 롤백**하므로 checked 예외를 쓰면 돈이 반만 움직인 채 커밋될 수 있다.
- 하나의 HTTP 요청은 하나의 서비스 트랜잭션으로 처리한다. 서비스가 다른 서비스를 부르면 같은 트랜잭션에 참여한다(기본 전파 `REQUIRED`). `REQUIRES_NEW`는 쓰지 않는다 — 대사 불일치 기록은 안쪽 트랜잭션이 롤백된 **뒤** 새 트랜잭션에서 한다(§6).

### 5.2 주문 체결(턴 사이 매매)

```
OrderService.place(seasonId, expectedTurnNo, side, assetId, qty, declaredPosition)
  1. season = seasonRepo.findByIdAndMemberIdForUpdate(seasonId, memberId)   -- 회원 조건을 포함한 비관적 락 한 번
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

- 비관적 락(`@Lock(LockModeType.PESSIMISTIC_WRITE)`. PostgreSQL에서 Hibernate 7은 `FOR UPDATE`가 아니라 **`FOR NO KEY UPDATE OF <별칭>`**을 낸다 — 같은 행의 `FOR NO KEY UPDATE`끼리, 그리고 SQL 실습의 `FOR UPDATE`와도 충돌하므로 직렬화는 같고, 자식 행 INSERT의 FK 검사(`FOR KEY SHARE`)만 기다리지 않는다): 같은 시즌에 두 요청이 동시에 오면 둘째가 **기다렸다가** 첫째의 결과 위에서 검증한다. PostgreSQL의 기본 격리 수준 READ COMMITTED에서 보통 SELECT는 락을 잡지 않으므로, 락 없이 잔액을 읽으면 두 요청이 같은 잔액을 보고 둘 다 "충분하다"고 판단해 둘 다 커밋한다 — 잔액이 음수가 된다. 행 잠금은 둘째 SELECT를 첫째 트랜잭션이 끝날 때까지 기다리게 하고, 풀리면 **커밋된 최신 행**을 다시 읽는다(Phase 0의 두 세션 실습 P0-15가 이것을 보여 준다).
- **Phase 0 API(P0-31)의 주문은 위 의사 코드와 세 가지 점에서 다르다.** ① 1단계는 소유 확인과 잠금을 **한 번의 잠금 조회**로 한다. 잠그지 않은 조회로 시즌을 먼저 영속성 컨텍스트에 올리면, 이후 잠금 조회가 기다린 끝에 최신 행을 읽더라도 Hibernate는 이미 관리 중인 엔티티(옛 `current_turn_no`·`status`)를 그대로 돌려주기 때문이다(턴 전환과 겹친 주문이 옛 턴으로 통과한다 — 실측으로 확인). 따라서 첫 시즌 조회가 곧 잠금 조회다. ② 4단계의 "단일 종목 한도"는 P0 API가 강제하지 않는다(erd 6.5에서 분리되어 있고, SQL `trade_buy`도 강제하지 않는다). ③ 0턴 팀 구성과 1턴 이후 매수는 같은 경로로 처리한다(SQL은 `order_buy`·`trade_buy`로 나뉘지만 Java 경로에서는 그 턴의 마지막 전술 변경 번호를 INSERT할 때 넣는다). 주문과 전술 변경은 시즌 잠금 뒤 같은 턴의 앞선 이벤트보다 늦은 시각을 확정하고 그 값을 저장한다. 같은 시각이나 역행은 `EVENT_TIME_CONFLICT`로 거부한다(P0-37; 시각은 검증이 끝난 뒤 첫 INSERT 직전에 확정하므로 다른 검증 오류의 우선순위는 그대로다. 비교는 같은 시즌·턴 안에서만 하고 마이크로초로 자른다). 주문이 묶는 "그 턴의 마지막 전술 변경"은 **번호(id) 최대**로 고른다 — 정상 쓰기에서는 시즌 잠금과 시각 가드가 번호 순서와 시각 순서를 같게 만들어 SQL `tr_last_change`(시각 순)와 같은 결과이며, 어긋난 변조 데이터는 대사 규칙 16이 검출한다. 서버는 출발점을 `DIRECT`로 고정한다. **매도(P0-36)**도 같은 메서드의 분기다 — 1–3단계와 그 턴의 마지막 전술 변경 번호는 매수와 공유하고, 4단계에서는 보유 ≥ 수량인지 확인한다(아니면 422 `INSUFFICIENT_HOLDING`을 반환하며, 금액은 이 검사 뒤에 계산한다). 실현 손익은 `MoneyMath.realizedPnl`(체결 직전 평균단가, 소수 4자리)로 계산하고, 수수료는 매수와 같은 요율을 적용한다. 원장은 `SELL` → `FEE` 순서로 기록한다. 전량 매도하면 보유를 `(0, 0)`으로 만들고 구간 `end_turn`을 현재 턴으로 설정한다. SQL `trade_sell`과 달리 보유 행을 잠그지 않으며(시즌 잠금 아래에서만 바뀐다 — §6), 0턴 매도를 막지 않는다.
- 락 순서는 항상 **season → cash_account**다. 턴 전환(§5.3)도 같은 순서라 교착이 나지 않는다. 시즌 **시작**(P0-30)만 순서 맨 앞에 **member**를 추가한다. 새 시즌 행은 커밋 전에는 다른 세션에 보이지 않아 잠글 필요가 없으므로, 같은 회원의 동시 시작이 둘 다 "진행 중 2개"를 세어 4개가 되는 경합만 `member` 행 잠금(`FOR NO KEY UPDATE`)으로 직렬화한다 — member → season → cash_account. 주문·턴 전환은 member를 잠그지 않으므로 교착이 나지 않는다.
- 멱등성: 요청에 `expectedTurnNo`를 받아 "이미 넘어간 턴에 대한 주문"을 거부한다. 같은 주문을 두 번 보내는 것(더블 클릭)은 EXT에서 `Idempotency-Key` 헤더 + `orders.client_key UNIQUE`로 막는다. Phase 0 API와 MVP는 턴 번호 검사까지.

### 5.3 턴 전환

```
TurnService.advance(seasonId, expectedTurnNo)
  1. season = findByIdForUpdate; currentTurnNo == expectedTurnNo 검사
  2. account = findBySeasonIdForUpdate
  3. nextBlock = seasonPathRepo.findBySeasonIdAndTurnNo(seasonId, expectedTurnNo + 1)
  4. returns = 1..expectedTurnNo+1 블록의 종목별 현지 수익률·환율 변화(턴 순서)   -- 풀의 모든 종목(사용자는 아무 종목이나 살 수 있다). 시즌 기준일 풀(`asset_position`)로 거르고 `fx_month`와 결합
     (이력 없는 달도 행은 항상 있다 — MVP는 적재 때 넣은 PROXY_INDEX 행(ETF는 추종 지수가 먼저이고 없으면 대표 지수, 개별 주식은 확장 구간에서 대표 지수), EXT는 지수 + 고유 변동성, PLAN §4.7)
  5. 풀의 종목마다 price' = MoneyMath.priceAt(저장된 0턴 가격, 이 턴까지의 `ReturnFactors` 목록) — 전 턴의 반올림 가격을 시작값으로 쓰지 않는다 → INSERT turn_price (약 180행)
  6. 적립금(EXT) → cash_ledger. (ETF 보수는 원장에서 빼지 않는다 — 수정 종가에 이미 반영, PLAN §4.6)
  7. INSERT turn(턴 번호, 평가액, 현금, 누적 낙폭, 포지션별 평가액 — 현금은 골키퍼, 비중은 조회)
  7b. (P0 API에서는 하지 않음 — 피드는 MVP, PLAN §13 2026-11-09) 소음 피드·독려 알림 생성 → INSERT turn_feed (PLAN §4.13). 생성기(FeedGenerator)의 입력 타입은 "이 턴까지의 가격·관심 지수·사건 태그"만 담는 값 객체로 제한해 다음 블록을 넘길 수 없게 한다. 난수는 시즌 시드 + 턴 번호에서 파생
  8. (P0 API에서는 하지 않음 — 기준선은 성적표 조회 때 계산, 저장 테이블 없음) 기준 포트폴리오 4개도 같은 가격으로 한 턴 진행(리밸런싱·비용 규칙은 PLAN §4.6·§4.10·§6.1, scoring 패키지, 같은 트랜잭션)
  9. 대사(§6) — 불일치면 예외 → 전부 롤백
 10. season.currentTurnNo += 1; 마지막 턴이면 status = FINISHED 후 성적표 계산(별도 트랜잭션으로 분리해도 됨 — 초안: 같은 트랜잭션)
```

Phase 0 API 구현(P0-33)에서는 이 흐름을 `TurnService`가 맡는다. 가격은 `TurnPriceCalculator`, 스냅샷은 `TurnSnapshotCalculator`가 계산한다. 1단계에서는 턴보다 상태를 먼저 검사한다(마지막 턴을 넘긴 뒤의 둘째 요청은 `SEASON_NOT_ACTIVE`, 중간 턴은 `TURN_MISMATCH`). 23 SQL과 다른 점은 두 가지다. ① `ABANDONING` 시즌은 포기 기능(MVP)이 들어올 때까지 거절한다. ② 현금 계좌 행이 없으면 데이터 손상으로 보고 500으로 멈춘다(23은 스냅샷 0행 → 대사 `NO_ACCOUNT`). 대사 불일치가 발생하면 예외 하나로 전부 롤백한다. 롤백 뒤 새 트랜잭션에 17행을 기록하는 일은 `TurnFacade`(P0-34)가 맡는다 — 실패 묶음이 끼어든 성공 묶음보다 뒤 번호를 받을 수 있다. 따라서 턴의 성공 여부는 마지막 시도 번호가 아니라 `turn` 행(= 17행 `MATCH` 묶음)의 존재로 판단한다.

- Phase 0 정본은 `db/23_turn_advance_def.sql`(erd D37)이다. 1–5단계의 전제 오류(`SEASON_NOT_FOUND`·`SEASON_NOT_ACTIVE`·`TURN_MISMATCH`·`NO_PATH`·`NO_RETURN`)는 쓰기 전에 거절하고 기록하지 않는다. 9단계 대사 불일치만 롤백한 뒤 17행을 기록한다(§6). 낙폭은 `MoneyMath.drawdown`으로 한 번만 반올림하고(HALF_UP), 골키퍼 평가액은 총액에서 다른 세 포지션을 뺀 값이다.
- `turn(season_id, turn_no) UNIQUE`가 "같은 턴 두 번"을 DB 수준에서 막는다. 락과 턴 번호 검사가 먼저 걸러 주지만, UNIQUE는 마지막 안전망이다.
- `season`과 `cash_account`에 `@Version`도 둔다. 비관적 락이 있는데 왜 또? 락을 거는 경로(주문·턴)가 아닌 **다른 경로**(시즌 포기, 관리자 상태 변경)가 같은 행을 고칠 때 "읽은 뒤 누가 바꿨다"를 잡기 위해서다. 둘은 서로 다른 일을 한다: 비관적 락은 돈 계산의 직렬화, 낙관적 락은 상태 변경의 충돌 감지.
- 시즌 포기(PLAN §4.1-7)는 "남은 턴을 매매 없이 자동 진행"이므로 `advance`를 반복 호출하는 것과 같다. 60턴을 한 트랜잭션에서 돌리면 길어지므로 **턴마다 별도 트랜잭션**으로 돌리고 중간 실패 시 재개할 수 있게 한다(`season.status = ABANDONING`).

### 5.4 전술 변경·포지션 지정

- 전술 변경은 `season` 락을 얻은 뒤 `season.current_goalkeeper`·`current_defender`·`current_midfielder`·`current_forward`를 갱신하고 `formation_change`를 INSERT. 전술 변경은 `formation_change` INSERT와 이 네 컬럼 갱신을 한 트랜잭션으로 처리한다. 같은 턴의 여러 변경을 모두 남긴다 — 마지막 것만 남지 않는다(복기용). 턴 전환과 같은 락 순서(P0-37: `FormationChangeService`는 계좌를 잠그지 않는다 — 계좌·보유·원장을 쓰지 않는다). 요청 오류(합 ≠ 100·골키퍼 0·같은 포메이션·사유 200자 초과)는 400 `INVALID_ARGUMENT`, 저장된 현재 포메이션이 마지막 변경의 이후 값과 다른 상태 불일치는 409 `INVALID_FORMATION`이다(SQL은 둘 다 `INVALID_FORMATION` HINT). 0턴에는 바꿀 수 없다(409 `TURN_MISMATCH`). 변경 뒤의 매도·매수는 보통 주문이고, 주문 트랜잭션 안에서 `orders.formation_change_id`를 채운다(어느 변경에 묶는지의 규칙은 PLAN §5.1).
- 세금(EXT)이 들어오면 원장 행(`TAX`)을 매도 주문 트랜잭션과 턴 전환 트랜잭션에서 추가한다. 세율·과세 시점·이연 표시는 PLAN §4.6이 정한다. 이 문서는 "어느 트랜잭션에서 원장에 쓰는가"만 정한다.
- SQL 정본(P0-19): `db/25_trade_formation_def.sql`의 `trade_buy`·`trade_sell`·`formation_change`·`lineup_change`가 위 순서를 그대로 구현한다(Java 이전 시 골든 대조 — P0-36·37). 진입점은 0턴을 거부하고, 주문·변경의 `created_at`은 잠금 뒤 `clock_timestamp()`(테스트 `Clock`은 잠금 뒤에도 흐르는 시계), 앞선 이벤트와 시각이 같거나 거꾸로면 쓰기 전에 멈춘다. 전량 매도는 보유 행을 0·0으로 남기고 열린 구간을 `end_turn = t`로 닫으며, 과거 스냅샷 k에 기여하는 구간은 `start_turn < k ≤ end_turn`이다.
- 포지션 지정·변경은 `lineup` 행의 기존 구간을 닫은 뒤 새 행을 연다(P0-37: 단독 변경은 내부 서비스 `LineupChangeService`이고 공개 HTTP 경로가 없다. 닫는 UPDATE를 새 구간 INSERT보다 먼저 내보내야 열린 구간 유일 인덱스를 지킨다). 주문과 같은 트랜잭션(신규 매수 시) 또는 단독 트랜잭션(보유 중 변경). 단독 변경도 전술 변경처럼 `season` 락 → 상태(`IN_PROGRESS`)·`expectedTurnNo` 검사 → 구간 닫기·열기 순서를 따른다(턴 전환과 겹칠 때 옛 턴 번호로 구간을 닫지 않도록).
- 변경 비용은 저장하지 않고, 내부 조회 `FormationCostQuery`가 체결을 주문별로 먼저 합쳐 변경별로 낸다(SQL `formation_costs`와 같다).

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
- 불일치 시 턴 전환 트랜잭션을 **롤백**하고 시즌은 그 턴에 멈춘다(`currentTurnNo` 그대로, 다시 시도 가능). 로그는 **롤백이 끝난 뒤** 바깥의 얇은 서비스가 남긴다(`TurnFacade.advance`: 트랜잭션 없음 → 안쪽 `TurnService.advance` 호출 → 예외를 잡아 `ReconciliationLogWriter.record`를 새 트랜잭션으로). 안쪽 트랜잭션이 `season`을 행 잠금(`FOR NO KEY UPDATE`)으로 잡고 있을 때 `REQUIRES_NEW`로 로그를 기록하면, 로그 쪽 `attempt_no` 배정이 같은 `season` 행을 다시 잠그려다 자기 자신을 기다리는 교착(시간 초과)이 난다. 따라서 "롤백 뒤에 쓴다"가 규칙이다. (`FOR NO KEY UPDATE`는 자식 INSERT의 FK 검사 `FOR KEY SHARE`와 충돌하지 않으므로 FK 검사 자체가 기다리는 것은 아니다 — 이유는 `attempt_no` 배정이다. `TurnFacadeTransactionTest`가 확인한다(`REQUIRES_NEW`면 자기 잠금 대기).) 분류(시차·조정·조사)는 MVP에서 전부 "조사"로 두고 사람이 확인한다.
- 이 검사는 Phase 0의 대사 쿼리를 JPQL/네이티브 쿼리로 옮긴 것이다. SQL 그대로 쓰기 위해 `@Query(nativeQuery = true)`를 허용한다. Phase 0 정본은 `db/22_reconcile_season_def.sql`(erd D36)이고, MVP는 같은 DB 고정본에서 Java 결과가 `recon_rules`와 같은 골든 테스트를 둔다.
- **P0-32의 Java 구조.** `ReconciliationLogRepository`에 수동 검사 규칙 15개가 `find…` 네이티브 쿼리 15개로 들어 있다(추가 전용 저장소의 조회 접두사 규칙을 지킨다). 기록·평가·수동 검사는 서비스 셋으로 나눈다.
  - `ReconciliationService.evaluateManual(시즌, 턴)` → `RuleResult` 15개(규칙 번호 순). 순수 값(record)이라 롤백을 건너 운반할 수 있고(SQL의 jsonb 지역 변수와 같은 역할), 판정·차이·분류는 `RuleResult.of` 한 곳에서 붙인다(반올림 없음). `MANDATORY`(호출자 트랜잭션 안에서만)이고 시작에서 시즌 잠금을 다시 잡는다(같은 트랜잭션이면 즉시 — 턴은 호출자가 잠근 뒤 읽어 넘긴다), 시작에 `flush`, 격리 수준이 READ COMMITTED가 아니면 쓰기 전에 거절한다.
  - `ReconciliationLogWriter.record(시즌, 턴, 결과)` → 새 `attempt_no`. 시즌 잠금 → 코드 집합 검사(수동 15개 또는 전체 17개) → `max + 1` → 행마다 INSERT. 수동 검사·턴 전환·SQL(`recon_log`)이 한 번호 공간을 쓴다. 전파는 `REQUIRED`라 수동 검사·성공 턴에서는 합류하고, 롤백 뒤 기록(P0-34)에서는 바깥 트랜잭션이 없어 새로 열린다.
  - `ManualReconciliationService.run(시즌)`은 한 트랜잭션이다: 시즌 잠금(첫 시즌 조회, 소유 확인 포함) → 현재 턴 읽기 → 평가 → 기록. 잠금이 있어 읽기 전용으로 둘 수 없다(PostgreSQL은 읽기 전용 트랜잭션의 `FOR NO KEY UPDATE`를 25006으로 거절한다).
- **복사 규칙(계산식을 다시 만들지 않는다).** 쿼리 15개는 22 규칙 함수 본문에 기계적 치환만 한 사본이다: 매개변수 → `CAST(:seasonId AS bigint)`·`CAST(:turnNo AS smallint)`, `recon_detail`·`recon_expected_price`·`recon_price`를 본문 그대로 인라인(재귀 CTE 포함) (규칙 12의 인라인 부분 질의에는 열 별칭 목록 `x(turn_no, asset_id, price)`를 붙이고, `recon_price`에 넘기는 인자는 괄호로 감싼다), 바깥 껍데기로 열 별칭과 jsonb → text. `ReconciliationSqlCopyTest`가 같은 치환을 22 원문에 적용해 `@Query` 값과 공백 정규화 뒤 글자 단위로 비교하므로, 22를 고치고 Java를 안 고치면 깨진다.
- **읽기 일관성.** 쿼리가 15문장이라 READ COMMITTED에서는 문장마다 스냅샷이 다르다(SQL은 STABLE 함수 15개를 한 문장으로 불러 한 스냅샷을 얻는다, erd D36). Java는 "시즌에 딸린 행을 쓰는 모든 경로가 먼저 `season` 행을 잠근다"는 불변식으로 같은 결과를 얻는다 — 지금 있는 쓰기 경로(주문·기록기)와 SQL 쓰기 스크립트(`db/21`·`23`·`25`)가 그렇고, 앞으로의 턴 전환(P0-33)·매도(P0-36)·전술·라인업 변경(P0-37)도 `season` 잠금이 첫 단계여야 한다. 규칙 하나는 한 문장이라 그 자체로 한 스냅샷이다 — 잠금이 지키는 것은 검사 턴과 데이터의 짝, 그리고 한 시도의 15행이 같은 상태를 본다는 점이다. 역사 테이블(수익률·환율·풀)은 API가 쓰지 않고 적재 스크립트만 쓰므로 Phase 0에서는 적재와 API 실행을 동시에 하지 않는다.
- **23505 재시도는 옮기지 않았다(22 `recon_log`와 다름).** PostgreSQL에서 고유 제약 위반은 트랜잭션을 망가뜨려 같은 트랜잭션에서 다시 읽을 수 없고(세이브포인트가 필요하다), 모든 기록 경로가 같은 `season` 잠금 아래라 시도 번호가 겹치지 않는다. 동시 수동 검사 2개가 시도 1·2로 직렬화됨을 시험이 확인한다. 단 롤백 뒤 기록(`TurnFacade`, P0-34)은 바깥 트랜잭션이 없어 호출마다 새 트랜잭션이므로 해당 고유 제약의 23505만 재시도해 최대 3번 기록한다(22와 같은 상한). 기록이 다른 이유로 실패하면 500이고 상태는 롤백된 그대로다. 기록 INSERT가 CHECK에 걸려 500이 되면 PostgreSQL 오류 문장(`Failing row contains …`)이 값과 함께 서버 로그에 남을 수 있다(개발 엔드포인트와 같은 한계 — 후속 이슈).
- **개발 엔드포인트.** `POST /api/v1/dev/seasons/{seasonId}/reconciliation`은 `local` 프로필에서만 빈이 만들어지고(`@Profile("local & !prod")`, Swagger에서도 `@Hidden`, `openapi.yaml`에는 넣지 않는다) 다른 프로필에는 경로 자체가 없다. 호출마다 새 시도 번호로 15행을 기록하고, 불일치여도 200 + `result: MISMATCH`로 커밋한다(되돌릴 턴 전환이 없다). 응답은 규칙별 코드·상태·건수에 기대·실제·차이·`detail`을 `reconciliation_log` 행 그대로 담는다(소유자 결정 2026-11-11, 가공·반올림 없음, 금액은 문자열 `BigDecimal`). 개발 엔드포인트는 노출 상한 검사 예외다(local 전용, prod 미등록 — `ExposureContractTest`가 `/api/v1/dev/` 경로를 명시적으로 뺀다). 접근 제한: `local` 프로필은 `server.address: 127.0.0.1`로만 열고, 고정 헤더 `X-Antlab-Dev: true`(상수는 `DevReconciliationController` 한 곳)가 없으면 403 `FORBIDDEN`이다. 기록 INSERT가 CHECK에 걸리면 PostgreSQL 오류 문장(`Failing row contains …`)이 값과 함께 서버 로그에 남을 수 있다.

## 7. 역사 데이터 적재

- 월 블록·종목 월 수익률(확장 구간 1997-01부터와 본 구간, PLAN §4.7 — 종목 180 × 달 약 348 ≈ 63,000행, 준비 구간 별도)은 JPA로 넣지 않는다. **MVP 전에 한 번** 전체를 적재한다(PLAN §11 MVP): 공개 CSV(수정 종가, 환율, 거시 지표)를 내려받아 월별로 집계하는 적재 스크립트 — Phase 0 표본 스크립트의 확장이며 `COPY`(psql `\copy`) 또는 `JdbcTemplate.batchUpdate`로 넣는다. 시스템 판정 포지션(`asset_position`)도 같은 스크립트가 계산한다. 국면 확률은 M2까지 비어 있다.
- **Phase 0 API는 Phase 0 표본(종목 20·월 블록 100)만 쓴다.** `antlab_api` DB에는 Flyway가 스키마를 만든 뒤 `psql`로 `db/10`–`13`을 적재하고(§3 시드 행), Java 적재기는 위 전체 적재(MVP 전)의 몫이다. 이 API의 엔티티는 역사·종목 풀 테이블을 읽기만 한다.
- M1 수집기(Spring 스케줄 잡)도 `JdbcTemplate` 배치로 쓴다. 엔티티는 읽기 전용(§4.4)이므로 쓰는 쪽과 읽는 쪽이 코드에서 갈린다.
- 사건 태그(`events/`)와 케이스(`cases/`)는 파일 → 테이블 적재 CLI 하나로.

## 8. 조회와 성능

- **N+1 금지:** 목록을 순회하면서 연관 데이터를 하나씩 읽지 않는다. 성적표·턴 결과처럼 여러 테이블을 묶어 조회할 때는 **JPQL 생성자 표현식**(`SELECT new antlab.account.HoldingViewRow(…)`(P0-35)) 또는 네이티브 쿼리로 DTO를 바로 만든다. 엔티티 그래프(`@EntityGraph`)는 연관이 꼭 필요한 곳(시즌 상세)에만 사용한다.
- 리포트성 쿼리(통계 보드 EXT, 리그 순위 APP)는 MyBatis를 쓸 수 있다(PLAN §10). 한 테이블을 두 도구가 **쓰지는** 않는다 — MyBatis는 읽기만.
- 인덱스는 DDL에 `idx_` 접두로 둔다(P0-02 규칙). JPA의 `@Index`는 쓰지 않는다(`validate`는 인덱스를 검사하지 않으므로 DDL이 유일한 출처).
- 목록 API는 `Pageable`을 사용한다. 시즌 목록과 주문 이력은 페이지로 제공한다. **단 Phase 0 API(P0-30)의 `GET /api/v1/seasons`는 고정 회원 한 명의 시즌 전부를 `id` 내림차순으로 주고 페이지로 나누지 않는다** — 페이지 메타(`page`·`totalItems`)는 DB 열이 아니므로 노출 상한 검사에 예외 규칙이 필요하고, 시즌 수도 적다. 따라서 메타 예외 규칙과 함께 MVP/WEB에서 붙인다.
- 턴 전환 1회의 쿼리 수는 **보유 종목 수와 무관해야 한다**(`TurnAdvanceQueryCountTest`는 보유 1종목과 3종목에서 문장 수가 같은지 확인한다). 문장 수는 풀 크기(`turn_price` INSERT)와 규칙 수(대사 17 + 기록 17)에 비례한다. P0-33의 실측 결과(풀 4종목 합성 시즌)는 52문장이다 — 잠금 3 · 대사 규칙·격리 확인 18 · `reconciliation_log` INSERT 17 · `turn_price` INSERT 4 · `turn` INSERT 1 · `season` UPDATE 1 · 그 밖의 조회 8. P0-35에서는 직전 턴 총액 조회 1문장이 더해져 53문장이 된다(2턴 이후 전환; 1턴 전환은 52문장 그대로). 턴 조회·보유 조회는 각각 4문장이며, 보유 수와 무관하다(`TurnViewQueryCountTest`·`HoldingsQueryCountTest`). 성적표 조회는 13문장(Hibernate 7 · 네이티브 원천 5 · 변경 비용 1)이며 보유 종목 수와 무관하다(`SeasonReportTransactionTest` — 네이티브 문장은 `SqlRecorder`에 잡히지 않아 원천 문장 수를 상수로 단언한다). 초안의 "20개 이하" 목표는 Phase 0 API의 실패 조건이 아니다(기능 표). 풀이 약 180종목인 MVP에서는 `turn_price`·`reconciliation_log`를 배치로 쓰는 방법과 함께 목표를 다시 정한다. 문장 수는 Hibernate `StatementInspector`(시험 도구 `SqlRecorder`)로 센다.

## 9. 테스트 전략

| 수준 | 도구 | 대상 |
|---|---|---|
| 매핑 | `@JpaMappingTest`(= `@DataJpaTest` + 실제 PostgreSQL, Phase 0 API는 Testcontainers 컨테이너, 아래) | 엔티티 ↔ DDL 일치(`validate`는 형 계열만 → `ColumnContractTest`가 길이·정밀도·NULL까지), enum ↔ CHECK 목록 글자 단위 대조(`EnumCheckContractTest`), 불변 엔티티 변경 무시(`ImmutableEntityTest`), 저장소 모양(`RepositoryShapeTest` — 읽기 전용·추가 전용·가변 세 분류, 패키지 고정 없음). 모든 `@Entity`를 훑으므로 엔티티를 추가하면 자동으로 포함된다. `MappingModel`은 `@MappedSuperclass` 부모 필드, `@Embedded` + `@AttributeOverride` 펼친 열, `@ManyToOne` 열, `@Version`, JSONB(`String` + `@JdbcTypeCode(JSON)`)를 읽는다. 시즌·계좌 엔티티의 불변(`AppendOnlyEntityTest` — `SqlRecorder`로 센 UPDATE 0건), 가변·낙관적 락·잠금 SQL·대기(`MutableEntityTest`·`LockingTest`), DB 제약 대표 세 건(`SeasonAccountConstraintTest`), Hibernate `validate`의 SMALLINT 거부(`HibernateValidateSpikeTest`)가 같은 방식으로 돈다 |
| 서비스 | `@SpringBootTest` + 실제 PostgreSQL, 테스트마다 `@Transactional` 롤백 | 주문 체결 전부/전무, 턴 전환, 대사 항등식(골든에서는 P0-25 하네스가 띄우는 일회용 컨테이너에서 `db/20`–`26`으로 만든 연습 시즌을 API로 재생해 행 단위로 비교 — PLAN §13 2026-11-09) |
| 동시성 | 스레드 2개로 같은 시즌에 동시 주문·턴 전환 | 둘 중 하나만 성공하거나 순서대로 처리되고, 끝나면 항등식 성립 |
| 엔진 대조 | 고정 벡터(`p0-boot6-v1_vectors.csv`) + M2 Python 병행 대사, 손 경로는 `FixedPathSource`로 P0-30 골든 | 같은 시드를 쓰면 같은 달 목록이 나오고(번호가 아니라 `month_start` 기준), 같은 수익률이면 같은 가격(원 단위)이 나온다 |
| 금액 계산 | `MoneyMathTest` + `MoneyMathSqlParityTest` | 순수 Java 시험과 PostgreSQL 17 임시 연결의 P0-15 순수 함수 대조를 분리해 실행 |
| 계약 | `@SpringBootTest` | springdoc이 낸 `/v3/api-docs`와 저장소 `docs/api/openapi.yaml`을 경로·필드·타입·필수·응답 코드로 대조한다 — 코드와 yaml이 갈라지면 실패(`OpenApiContractTest`). 도구는 자체 정규화 비교기(`OpenApiNormalizer`·`OpenApiDiff`, 새 라이브러리 없음)이며 비교기 자체는 `OpenApiNormalizerTest`가 검증한다. `/api/v1/**` 운영의 operationId·parameters·requestBody·responses(상태 코드와 미디어 형식), 스키마의 type·format·enum·required·properties·items·제약, 닿는 컴포넌트 이름(`$ref`는 이름으로)을 비교한다. 설명·예시·태그·info·속성 순서는 무시한다. 응답 코드는 운영 고유의 성공·도메인 오류만 적는다 — springdoc이 `@RestControllerAdvice`의 일반 오류(400·404·405…)를 운영마다 붙이지 않음을 확인했다. 노출 상한은 `ExposureContractTest`가 맡는다(§4.6) Error codes and prose are checked outside the structural diff: every non-2xx response lists its codes in `x-error-codes` (405·406·415 once in `x-framework-errors`), and `ErrorCodeContractTest` checks them against `ErrorHttpMapping` and the response text; every problem response in an integration test must be listed (`ContractErrorCheck`); `OpenApiDescriptionParityTest` checks that operation, response and schema descriptions equal the annotation text. / 구조 비교 밖에서 오류 코드와 설명 문장을 따로 확인한다: 비 2xx 응답마다 `x-error-codes`에 코드를 적고(405·406·415는 `x-framework-errors` 한 곳) `ErrorCodeContractTest`가 `ErrorHttpMapping`·응답 설명 글과 대조하며, 통합 시험의 모든 문제 응답이 계약에 적혀 있어야 하고(`ContractErrorCheck`), `OpenApiDescriptionParityTest`가 연산·응답·스키마 설명이 어노테이션 문장과 같은지 확인한다. Descriptions are compared both ways under a paragraph rule (blank line = paragraph, `- ` = list item, other line breaks fold), and every contract text must be English first, then Korean. / 설명은 양방향으로 문단 규칙(빈 줄 = 문단, `- ` = 목록 항목, 그 밖의 줄바꿈은 접음)에 따라 비교하고, 계약의 모든 설명은 영어 먼저·한국어 뒤여야 한다. |
| API | `MockMvc` | 검증 오류 400, 턴 불일치 409, 잔액 부족 422(초안), 비공개 컬럼이 응답에 없음 |
| E2E | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `RestClient` | One season over a real port (start → team → 12 turns → report → review), 17 MATCH per turn, `90` PASS (`OneSeasonE2ETest`, needs the sample CSVs); every operation's errors are ProblemDetail with a contract `code`, probed over a real port (`ApiErrorSweepTest`, always on). / 실제 포트로 한 판(시작 → 팀 → 12턴 → 성적표 → 복기), 턴마다 대사 17개 MATCH, `90` PASS(`OneSeasonE2ETest`, 표본 CSV 필요). 모든 연산의 오류가 ProblemDetail + 계약에 있는 `code`인지 실제 포트로 훑는다(`ApiErrorSweepTest`, 항상 실행). |

- **H2를 쓰지 않는다.** CHECK·`NUMERIC` 반올림·`FOR UPDATE`·문자열 비교의 동작이 PostgreSQL과 달라서, 시험이 통과해도 믿을 수 없다. **Phase 0 API(2026-11-09 결정, PLAN §13)는 로컬 시험부터 Testcontainers PostgreSQL 17**을 쓴다. 이는 처음에 적은 compose `db` 서비스의 `antlab_test` 안(`db/README.md`의 데이터베이스 생성 블록)을 바꾼 것이다. 이렇게 하면 시험이 소유자의 컨테이너를 건드리지 않는다(DEPLOY §3.2 격리 규칙을 저절로 지킨다). 시험 실행마다 빈 DB에서 시작하고, M1 CI와 같은 방식으로 실행한다. 시험은 로컬에서 실행하고 CI(M1)가 같은 시험을 합성 표본으로 돌린다. 이 DB에는 Flyway 파일을 적용한다. SQL 골든 시험(P0-25)은 하네스가 띄우는 일회용 컨테이너에서 `db/` 스크립트를 `psql`로 실행해 Java 결과와 행 단위로 비교한다.

- 테스트 DB는 `./gradlew test`를 실행할 때마다 새 Testcontainers PostgreSQL 17로 시작하고 Flyway `migrate`만 실행한다. 같은 구성의 시험 클래스는 Spring 시험 문맥과 해당 컨테이너를 공유한다. 따라서 데이터를 쓰는 시험은 자신이 쓴 행을 정리하고 identity 값(번호표)을 가정하지 않는다. 빈 테이블을 전제로 하는 시험(`FlywayMigrationTest`·`FlywaySchemaCheckTest`)은 표지 속성을 사용해 별도 문맥(컨테이너)에서 실행한다. 공유 시험 문맥에서는 `clean`을 호출하지 않는다. 실습 DB `antlab`과 API 로컬 DB `antlab_api`에도 접속하지 않는다(프로필 `test`의 URL은 Testcontainers가 준다).

**SQL 골든 하네스(P0-25, 시험 전용 `src/test/java/antlab/support`).**

- **역할별 DB.** 하네스가 띄우는 일회용 컨테이너(또는 같은 컨테이너의 별도 DB)에서, Flyway를 적용한 API 시험 DB와 별개로 빈 DB를 새로 만든다. 그 DB에서 `db/01`–`03`부터 실행해 SQL 기준 DB와 재생 DB를 만든다. SQL 기준 DB에는 Flyway를 실행하지 않고, API DB에는 01–03 실습 DDL을 실행하지 않는다. `clean → migrate`는 Flyway 시험 DB에만 해당한다. SQL 기준 DB의 재생은 새 DB를 만들어 격리한다. 하네스는 자기가 만든 DB만 지운다.
- **psql과 Java 사이의 커밋 경계.** 스크립트는 컨테이너 안의 `psql`로 실행한다. `\copy`·`\ir`·`\gset`·`\if`·세션 임시 함수·프로시저 안의 COMMIT/ROLLBACK을 원본 그대로 사용하기 위해서다. 행 조회는 JDBC로 한다. 두 연결은 서로 다른 세션이므로, 시험 메서드의 미커밋 `@Transactional` 상태에 준비 데이터를 두지 않는다. `_def`가 만드는 임시 함수는 정의 로딩과 질의를 같은 psql 세션에서 실행한다. 결과는 `COPY … TO STDOUT`의 CSV(NULL 표지 `\N`)로 받아 NULL과 빈 문자열을 구분한다.
- **판정.** 검증 SQL은 종료 코드 0이고 출력 끝 두 줄이 등록한 판정 열 이름과 정확히 `PASS`일 때만 통과다(앞쪽 PASS·다른 검사 이름·판정 뒤 추가 결과는 실패).
- **정규화 비교(`RowSetDiff`).** 대리 키·시즌·회원 식별자는 비교하지 않고 자연 키(시장·종목 코드, 턴 번호, 재생 행동 키)로 대응한다. 제외하는 열은 이름과 이유를 명세에 적는다. NUMERIC은 `BigDecimal.compareTo`로 정확히 비교하며 자릿수 차이만 허용한다(오차 허용·반올림 없음). NULL은 빈 값·0과 구분한다. 중복 키는 집합으로 합치지 않고 보고한다. 진행 턴의 `turn_price`는 풀 전체를 비교하고, 대사 로그의 시도 번호는 지우지 않는다.
- **입력과 보고.** 입력 파일은 경로와 SHA-256으로 고정한다. 추적하지 않는 시세 파생 CSV 네 개만 `ANTLAB_GOLDEN_SAMPLE_DIR`(없으면 작업본의 `db/sample`)에서 읽는다. 파일이 없으면(환경 변수를 지정하지 않은 경우) 이유를 밝히고 실제 표본 골든만 건너뛴다. 환경 변수를 지정했는데 파일이 없으면 실패한다. 실패 메시지와 시험 보고서에는 스크립트 이름·종료 코드·판정 분류·행 키·다른 열 이름·건수만 싣는다. 가격·금액·원문 출력은 싣지 않는다.
- **범위.** P0-25의 재생 완료는 "같은 입력으로 SQL을 두 번 실행하면 같다"와 재생 입력 추출까지다. Java API를 통한 재생과 업무별 동등성은 P0-32·33·36·38의 완료 기준이다. 실행법은 `db/README.md`의 「SQL 골든 하네스」를 본다.

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
# 블록 길이(6)·허용 경로 길이·재생 시작·엔진 판은 설정이 아니라 SimpleBootstrap 상수다(P0-29) — 설정으로 두면 같은 engine_version 이 다른 경로를 낼 수 있어 재현성이 깨진다.
# projection-paths: 2000 은 MVP 에서 antlab.engine.* 설정으로 더한다
antlab.pool.base-date: ${ANTLAB_POOL_BASE_DATE:2026-01-02}   # P0-27: 기준일 풀(매년 1월 첫 거래일). P0-30 시즌 시작도 같은 값을 쓴다
# P0-30: 새 시즌에 적는 설정(요청에서 받지 않는다). 시즌 행의 8개 설정 컬럼은 DB 기본값이 없으므로 값이 빠지면 기동 실패(@Validated)
antlab.season.initial-cash: 30000000        # PLAN §4.1-1 기본 초기 자금
antlab.season.fee-rate-kr: 0.00015          # PLAN §4.6
antlab.season.fee-rate-us: 0.0025
antlab.season.fx-spread-rate: 0.0040        # 환전 비용 0.40%(소유자, 2026-11-08)
antlab.season.data-snapshot-date: 2026-11-07   # db/sample/README 상태 줄 = db/20 설정 표
# 진행 중 시즌 상한 3은 설정이 아니라 SeasonStartService 상수다 — 설정이면 같은 판에서 규칙이 바뀐다
# 인증(SEC) 전 임시 고정 회원. 이메일은 RFC 2606 예약 도메인(실제 주소 금지)
antlab.single-member.enabled: true            # prod 문서는 false
antlab.single-member.email: ${ANTLAB_SINGLE_MEMBER_EMAIL:player@example.invalid}
antlab.single-member.name: 감독
```

프로필은 `application.yml` 안의 `---` 문서 셋으로 구성된다. 공통 프로필에는 프로필을 지정하지 않아도 안전하게 쓸 수 있는 값(SQL 로그·명세 꺼짐)을 둔다. `local`은 SQL 로그와 명세·Swagger UI를 켜며 `bootRun`·`bootTestRun`에서 기본으로 사용한다. `prod`는 OPS 설정이며 springdoc을 끄고 `server.forward-headers-strategy: framework`을 사용한다. 시험 프로필인 `test`는 `src/test/resources/application-test.yml`에만 있으므로 jar에 포함되지 않는다(Testcontainers, 명세만 켬, Hibernate 통계 켬 — 쿼리 수 단언용; Flyway clean은 새 컨테이너 원칙상 쓰지 않는다). `local` 값을 `application-local.yml` 파일로 두지 않는 까닭은 `.gitignore`가 그 파일을 무시하기 때문이다(소유자 개인 덮어쓰기는 `config/application-local.yml`). `prod`는 고정 회원이 켜져 있으면(`antlab.single-member.enabled=true`) 시작을 거부하고(`SingleMemberProductionGuard`), 문서 값은 `false`다. 고정 회원을 끈 동안 시즌 API(`/api/v1/seasons/**`)는 `CurrentMember`의 대체 구현이 503 `MEMBER_UNAVAILABLE`로 거절한다(M4 JWT 전까지, PLAN §13 2026-11-09 — compose `api` 컨테이너가 `prod`로 뜨므로 기동 자체를 막으면 상태 확인이 깨진다. 한 판은 로컬 `bootRun`(local)으로 한다). 비밀값(DB 비밀번호)은 환경 변수나 `/run/secrets` 파일로만 둔다. `prod`의 `spring.datasource.url`은 `${ANTLAB_DB_URL}`로 **기본값이 없다**(빠뜨리면 localhost로 가지 않고 기동에 실패 — P0-24, `ProdProfileConfigTest`). `prod`는 `spring.config.import: optional:configtree:/run/secrets/`도 가진다. 파일 `/run/secrets/spring.datasource.password`의 내용이 속성 `spring.datasource.password`가 되고(디렉터리가 없어도 기동), 같은 속성을 환경 변수 `SPRING_DATASOURCE_PASSWORD`로도 주면 환경 변수가 우선한다 — 운영에서는 한 가지로만 준다. 운영 secrets의 실제 사용은 OPS다.

### 10.1 컨테이너에서 실행 (Phase 0 API 체크리스트 초안)

Spring 프로젝트를 만들 때(Phase 0 API, P0-22·P0-24) 아래를 지킨다. 같은 이미지가 로컬·CI·운영에서 돌고, 운영 값은 환경 변수와 `/run/secrets`가 정한다. 구조 전체는 [DEPLOY.md](DEPLOY.md).

1. `application.yml`은 `${ANTLAB_…:기본값}` 자리표시자와 로컬 기본값만 두고, 운영 값은 환경 변수·configtree(`prod`의 `spring.config.import=optional:configtree:/run/secrets/`, 파일 이름이 속성 이름 — 위 §10, P0-24에서 임시 파일로 매핑·우선순위를 확인)로 준다. `application-prod.yml`에는 비밀값을 쓰지 않는다.
2. 프로필은 위의 `local`·`test`·`prod` 셋이고, 컨테이너에서는 `SPRING_PROFILES_ACTIVE=prod`다. `prod`는 SQL 로그를 끄고 `open-in-view: false`를 유지한다.
3. Actuator는 `health`만 노출하고 probes(liveness·readiness)를 `management.endpoint.health.probes.enabled=true`로 **명시해** 켠다(3.x는 Kubernetes 밖에서 자동으로 켜지지 않는다 — [DEPLOY.md](DEPLOY.md) §8. 4.1.2 문서는 자동으로 켜진다고 설명하지만, 이 설정을 명시해 동작을 고정한다 — 명시했기 때문에 자동 켜짐 여부를 독립적으로 확인하지는 못했고, `ActuatorProbesTest`가 liveness·readiness 200을 확인한다). `info`는 HTTP로 열지 않고, git SHA는 OPS의 이미지 태그(SHA)·metadata로 추적한다([DEPLOY.md](DEPLOY.md) §8).
4. 그레이스풀 셧다운: 4.1.2는 기본 `graceful`이고(설정 메타데이터 확인, `server.shutdown: graceful`도 명시) `spring.lifecycle.timeout-per-shutdown-phase=20s`로 둔다(`SettingsAssertionTest`).
5. 프록시 뒤: `server.forward-headers-strategy=framework`(Caddy의 `X-Forwarded-*`로 HTTPS·원래 IP 인식 — 속성 이름은 4.1.2 설정 메타데이터로 확인했고 `prod` 문서에 있다).
6. Flyway는 시작 때 적용하고 `ddl-auto: validate`를 유지한다.
7. 로그는 stdout만, 키·토큰은 마스킹한다.
8. Dockerfile(멀티 스테이지)·`.dockerignore`와 `compose.yaml`의 `api`(`build: .`)는 P0-24에서 만들었다(루트 `Dockerfile` — 펼친 bootJar를 의존성·loader·앱 레이어로 나눠 복사하고, 비루트 UID 10001로 실행하며, exec `ENTRYPOINT`로 `JarLauncher`를 사용한다). CI는 이미지 **빌드만** 하고 푸시하지 않는다(M1).
9. Spring Boot 판은 **4.1.x(Java 21)로 정했다**(소유자 Gate 0, 2026-11-09 — 커리큘럼 프로젝트가 4.1.2, PLAN §10). 이 문서의 3.x 전제는 위 3·4번에서 4.1.2 기준으로 바꿨다. 스타터 이름(`spring-boot-starter-webmvc` 등)과 직렬화 라이브러리(Jackson 3, 패키지 `tools.jackson`, `JsonMapperBuilderCustomizer`는 `org.springframework.boot.jackson.autoconfigure`)는 P0-22에서 실행으로 확인했다.
10. API 계약: 기능마다 `docs/api/openapi.yaml`을 계획 단계에서 먼저 쓰고(§2), springdoc 명세와 yaml을 대조하는 시험(§9)을 통과시킨다. springdoc은 3.2.0(Boot 4.1.x와 호환)이고 Swagger UI 경로는 `/swagger-ui.html`이다. 공통 문서에서 끄고 `local`에서만 켜며(시험은 명세만), `BigDecimal`은 `SpringDocUtils.getConfig().replaceWithSchema(...)`로 명세에서도 `type: string`으로 맞췄다(`JsonMoneyFormatTest`).

## 11. Phase 1(JDBC) → MVP(JPA) 대응표

Phase 1은 건너뛰었다(PLAN §11, 2026-11-09). 이 표는 SQL(Phase 0)에서 직접 한 일 → Phase 0 API·MVP의 JPA로 읽는다: 첫 열의 "손으로"는 JDBC 코드가 아니라 SQL 실습(P0-15 두 세션 `FOR UPDATE`, P0-17 롤백)이다.

| 손으로(SQL 실습·JDBC) | Phase 0 API·MVP에서 |
|---|---|
| `Connection.setAutoCommit(false)` … `commit()/rollback()` | `@Transactional` |
| `PreparedStatement` INSERT/UPDATE | `repository.save(entity)` + 변경 감지 |
| `ResultSet` → 객체 | 엔티티 매핑, DTO 생성자 표현식 |
| `SELECT … FOR UPDATE` 직접 작성 | `@Lock(PESSIMISTIC_WRITE)` 리포지토리 메서드(`findByIdForUpdate`, `findBySeasonIdForUpdate`) — PostgreSQL에서는 `FOR NO KEY UPDATE`로 나가지만 같은 행의 `FOR UPDATE`와 서로 기다린다 |
| 대사 SQL | 같은 SQL을 `@Query(nativeQuery = true)`로 |
| 경로 생성 코드(단순 부트스트랩) | JDBC 판은 만들지 않았다(Phase 1 건너뜀). 대신 `engine` 패키지의 `SimpleBootstrap`을 처음부터 JPA 조회(`findReplayMonths`)로 사용한다 |

트랜잭션·락·반올림을 SQL에서 손으로 한 번 해 보는 것이 이 문서의 결정들을 이해하는 가장 빠른 길이다.

## 12. 열린 결정(구현 중 확정)

1. 성적표 계산을 마지막 턴 전환과 같은 트랜잭션에 둘지 분리할지(초안: 같이. 느리면 분리하고 `season.status = SCORING` 추가). Phase 0 API는 성적표를 저장하지 않고 조회로 계산하므로(P0-38) 이 질문은 저장이 생기는 MVP(`season_score`)로 미룬다.
2. ~~`holding`을 엔티티로 두고 UPSERT할지, `execution` 집계로만 계산할지~~ — **결정됨(P0-28, 2026-11-10): 가변 엔티티.** 전량 매도 뒤에도 행을 남기고(수량 0·평균단가 0, erd D7) 대사가 체결 합계와 대조한다.
3. ~~네이티브 쿼리와 JPQL의 경계~~ — **결정됨(P0-32, 2026-11-11): 대사는 네이티브(22 SQL 사본), 나머지는 JPQL.** 대사는 윈도 함수·`FULL JOIN`·`jsonb_agg`·재귀 CTE를 쓰고 "같은 SQL을 두 곳에서 돌려 같은 답"이 목적이라 다시 쓰지 않는다. 성적표(P0-38)는 원천 조회만 네이티브(JdbcClient — 집계 FILTER·generate_series)이고 계산은 Java다(26 사본이 아니다 — 26 본문 대부분이 plpgsql이라 기계적 사본이 성립하지 않고, 값은 골든 셋이 묶는다).
4. `Idempotency-Key`(EXT) 저장 방식.
5. ~~PostgreSQL 이전 때 `IDENTITY` 처리~~ — 처음부터 PostgreSQL이라 해소(2026-11-05). `GENERATED ALWAYS AS IDENTITY` + JPA `IDENTITY`로 간다. 시퀀스 + `SEQUENCE` 전략(배치 INSERT 가능)으로 바꿀지는 성능이 문제될 때만.
6. `turn_price`를 풀 전체(턴마다 약 180행, 5년 시즌 10,800행)로 둘지 "보유 + 기준 종목"만 두고 나머지는 주문 때 계산할지(초안: 풀 전체 — 조회가 단순하고 병행 대사가 전수 비교가 된다).
