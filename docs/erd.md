# Ant Lab — MVP 테이블 ERD (P0-01)

[PLAN.md §5](PLAN.md#5-도메인-mvp-테이블)의 MVP 테이블 8개를 컬럼·타입·키·관계·제약까지 그린 논리/물리 설계이다. P0-02의 DDL(`db/01_schema.sql`)과 P0-07의 대사 쿼리(`db/05_reconciliation.sql`)가 이 문서를 그대로 옮겨 쓴다. 테이블·컬럼 이름은 PLAN §5를 따르며, PLAN이 "핵심 컬럼만" 적은 곳에 보조 컬럼(`stock_price.id`, `holding.id` 등)과 `execution.realized_pnl`이 더해졌다([설계 결정](#설계-결정) D9 참고).

표기는 소유자의 ERDCloud 노트 형식을 따른다. 약어: PK(Primary Key, 기본 키), FK(Foreign Key, 외래 키), UK(Unique Key, 유일 키), NN(NOT NULL, 빈 값 불가). 엔티티는 `논리명 (물리명)`, 컬럼은 `타입 물리명 키 "논리명 | NN | 기본값"`. 선에 붙은 기호는 최소(`o` 선택, `|` 필수), 엔티티 쪽 기호는 최대(`|` 1, `{` N)이고, `--`은 식별 관계, `..`은 비식별 관계이다. Mermaid는 선 모양이 키 구성과 맞는지 검사하지 않으므로 아래 표로 따로 확인한다.

## 공통 규칙

- **엔진과 문자셋:** MySQL 8.4, InnoDB(외래 키·트랜잭션), `utf8mb4`. 8.0.16 이상에서만 `CHECK`가 강제되므로 8.4를 전제로 한다.
- **시각:** 모든 시각 컬럼은 `DATETIME(6)`에 KST로 저장한다. `DATETIME`은 시간대를 저장하지 않으므로 이 규칙을 코드와 시드가 지킨다(M1 PostgreSQL 이전 시 `TIMESTAMPTZ` 여부는 M1에서 결정). `DEFAULT CURRENT_TIMESTAMP(6)`는 세션 시간대로 값을 만들므로, MySQL 서버 `time_zone = '+09:00'`(또는 접속 세션마다 `SET time_zone = '+09:00'`)을 전제로 한다. 공식 Docker 이미지는 기본이 UTC라 설정하지 않으면 `created_at`만 UTC로 들어가 "오늘" 기준이 9시간 어긋난다.
- **금액:** 모든 금액 컬럼은 `DECIMAL(19,4)`이다. 현금 값(`amount`, `balance`, `balance_after`, `reserved`, `fee`, `tax`)은 원 단위 정수 값만 저장하고, 가격(`price`, `limit_price`, `fill_price`)도 원 단위 정수 값(호가 단위)이다. `avg_price`·`realized_pnl`만 소수 4자리까지 저장한다. 반올림은 코드 한 곳에서 한 번만 하고(PLAN §5 회계 규칙), DB는 `CHECK (col = ROUND(col, 0))`로 **저장값**에 원 미만이 남으면 거부한다(D1). 단 소수 다섯째 자리 이하만 있는 값(예: `100.00001`)은 `DECIMAL(19,4)`로 바뀌면서 먼저 반올림되어(`100.0000`) 경고(Note 1265)만 남기고 통과하므로, 입력 원본의 소수 거부는 DB에 넣기 전 코드가 맡는다.
- **외래 키:** 모두 `ON DELETE RESTRICT ON UPDATE RESTRICT`(`CASCADE`·`SET NULL` 없음). DDL에 `RESTRICT`를 명시한다 — MySQL 기본은 `NO ACTION`이고 InnoDB에서는 `RESTRICT`와 같지만, PostgreSQL에서는 검사 시점이 달라 명시가 이식에 유리하다. 이유 두 가지: ① 원장은 추가 전용이라 부모 행이 지워지거나 바뀌며 원장·체결이 따라 사라지면 안 된다. ② MySQL은 `CHECK`에 쓰인 컬럼(`cash_ledger.ref_order_id`)에 `ON UPDATE CASCADE`·`SET NULL`·`SET DEFAULT` 참조 동작을 허용하지 않는다(ERROR 3823). `ON DELETE CASCADE`는 허용되지만 ①의 이유로 쓰지 않는다.
- **구분값:** `VARCHAR` + `CHECK (col IN (...))`. `ENUM`은 쓰지 않는다.
- **이름:** `orders`는 `ORDER`가 예약어라 복수형이다.

## ERD

```mermaid
erDiagram
    MEMBER["회원 (member)"] {
        BIGINT id PK "회원번호 | NN | AUTO_INCREMENT"
        VARCHAR(255) email UK "이메일 | NN"
        VARCHAR(50) name "이름 | NN"
        VARCHAR(255) password "비밀번호 해시 | NN"
        DATETIME(6) created_at "가입일시 | NN | CURRENT_TIMESTAMP(6)"
    }
    CASH_ACCOUNT["현금 계좌 (cash_account)"] {
        BIGINT member_id PK, FK "회원번호 | NN"
        DECIMAL(19,4) balance "잔액 (원 단위) | NN | 0"
        DECIMAL(19,4) reserved "예약금 (원 단위) | NN | 0"
        BIGINT version "낙관적 락 버전 | NN | 0"
    }
    STOCK["종목 (stock)"] {
        BIGINT id PK "종목번호 | NN | AUTO_INCREMENT"
        CHAR(6) ticker UK "종목코드 | NN"
        VARCHAR(100) name "종목명 | NN"
        VARCHAR(10) market "시장 | NN"
    }
    STOCK_PRICE["종목 가격 (stock_price)"] {
        BIGINT id PK "가격번호 | NN | AUTO_INCREMENT"
        BIGINT stock_id FK "종목번호 | NN"
        DECIMAL(19,4) price "가격 (원 단위 정수) | NN"
        DATETIME(6) traded_at "가격 기준일시 | NN"
    }
    ORDERS["주문 (orders)"] {
        BIGINT id PK "주문번호 | NN | AUTO_INCREMENT"
        BIGINT member_id FK "회원번호 | NN"
        BIGINT stock_id FK "종목번호 | NN"
        VARCHAR(4) side "매수매도 구분 | NN"
        VARCHAR(6) type "주문 유형 | NN"
        INT quantity "수량 | NN"
        DECIMAL(19,4) limit_price "지정가 (원 단위 정수) | NULL"
        VARCHAR(10) status "주문 상태 | NN | PENDING"
        DATETIME(6) created_at "주문일시 | NN | CURRENT_TIMESTAMP(6)"
    }
    EXECUTION["체결 (execution)"] {
        BIGINT id PK "체결번호 | NN | AUTO_INCREMENT"
        BIGINT order_id FK "주문번호 | NN"
        DECIMAL(19,4) fill_price "체결가 (원 단위 정수) | NN"
        INT quantity "체결 수량 | NN"
        DECIMAL(19,4) fee "수수료 (원 단위) | NN | 0"
        DECIMAL(19,4) tax "세금 (원 단위) | NN | 0"
        DECIMAL(19,4) realized_pnl "실현 손익 (매도만) | NULL"
        DATETIME(6) executed_at "체결일시 | NN"
    }
    HOLDING["보유 (holding)"] {
        BIGINT id PK "보유번호 | NN | AUTO_INCREMENT"
        BIGINT member_id FK "회원번호 | NN"
        BIGINT stock_id FK "종목번호 | NN"
        INT quantity "보유 수량 | NN"
        DECIMAL(19,4) avg_price "평균단가 | NN | 0 (수량 0이면 0)"
    }
    CASH_LEDGER["현금 원장 (cash_ledger)"] {
        BIGINT id PK "원장번호 | NN | AUTO_INCREMENT"
        BIGINT member_id FK "회원번호 | NN"
        VARCHAR(12) type "원장 유형 | NN"
        DECIMAL(19,4) amount "부호 있는 금액 (원 단위) | NN"
        DECIMAL(19,4) balance_after "거래 후 잔액 (원 단위) | NN"
        BIGINT ref_order_id FK "참조 주문번호 | NULL (DEPOSIT은 항상 NULL)"
        DATETIME(6) created_at "기록일시 | NN | CURRENT_TIMESTAMP(6)"
    }
    MEMBER ||--|| CASH_ACCOUNT : "R1 현금 계좌를 가진다"
    MEMBER ||..o{ ORDERS : "R2 주문한다"
    STOCK ||..o{ ORDERS : "R3 주문 대상이다"
    ORDERS ||..o{ EXECUTION : "R4 체결된다"
    MEMBER ||..o{ HOLDING : "R5 보유한다"
    STOCK ||..o{ HOLDING : "R6 보유된다"
    STOCK ||..o{ STOCK_PRICE : "R7 가격이 기록된다"
    CASH_ACCOUNT ||..|{ CASH_LEDGER : "R8 원장에 기록된다"
    ORDERS |o..o{ CASH_LEDGER : "R9 원장을 만든다"
```

### 관계 요약

| # | 부모 → 자식 | 카디널리티 | 선 | 근거 |
|---|---|---|---|---|
| R1 | `member` → `cash_account` | 1 : 1 | 식별 `--` | `cash_account`의 PK가 곧 FK `member_id`. 가입 시 계좌가 함께 생긴다(스토리 1) |
| R2 | `member` → `orders` | 1 : 0..N | 비식별 `..` | |
| R3 | `stock` → `orders` | 1 : 0..N | 비식별 `..` | |
| R4 | `orders` → `execution` | 1 : 0..N | 비식별 `..` | `PENDING`·`REJECTED` 주문은 체결 0건, `execution.order_id`는 UNIQUE가 아님(부분 체결 대비) |
| R5 | `member` → `holding` | 1 : 0..N | 비식별 `..` | |
| R6 | `stock` → `holding` | 1 : 0..N | 비식별 `..` | |
| R7 | `stock` → `stock_price` | 1 : 0..N | 비식별 `..` | |
| R8 | `cash_account` → `cash_ledger` | 1 : 1..N | 비식별 `..` | 최소 1건 = 계좌 개설 `DEPOSIT` 행. DDL로는 강제할 수 없어 대사가 검사한다 |
| R9 | `orders` → `cash_ledger` | 0..1 : 0..N | 비식별 `..` | `ref_order_id`는 NULL 허용: `DEPOSIT`은 항상 NULL, `ADJUSTMENT`는 주문 정정에 연결할 수 있어 선택 |

## 엔티티 정의서

### 회원 (member)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 회원번호 | id | BIGINT | NN | AUTO_INCREMENT | |
| UK | 이메일 | email | VARCHAR(255) | NN | | 중복 불가 |
| | 이름 | name | VARCHAR(50) | NN | | |
| | 비밀번호 해시 | password | VARCHAR(255) | NN | | 평문 금지, 해시만 저장(BCrypt 60자 수용). 이름은 PLAN 용어 유지 |
| | 가입일시 | created_at | DATETIME(6) | NN | CURRENT_TIMESTAMP(6) | |

### 현금 계좌 (cash_account)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK, FK | 회원번호 | member_id | BIGINT | NN | | `member.id` 참조. PK = FK(식별 1:1) |
| | 잔액 | balance | DECIMAL(19,4) | NN | 0 | 신용 없음: `CHECK (balance >= 0)`, `CHECK (balance = ROUND(balance, 0))`. 원 단위 정수 값만 저장 |
| | 예약금 | reserved | DECIMAL(19,4) | NN | 0 | `CHECK (reserved >= 0 AND reserved <= balance)`, `CHECK (reserved = ROUND(reserved, 0))`. 주문 가능 금액 = balance − reserved. MVP(시장가만)에서는 항상 0 |
| | 낙관적 락 버전 | version | BIGINT | NN | 0 | 동시성(설계 질문 5) |

### 종목 (stock)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 종목번호 | id | BIGINT | NN | AUTO_INCREMENT | |
| UK | 종목코드 | ticker | CHAR(6) | NN | | 예: `005930`. 문자열이라 앞자리 0이 보존된다. 숫자 전용 `CHECK`는 두지 않는다(신규 코드에 영문이 섞일 수 있음) |
| | 종목명 | name | VARCHAR(100) | NN | | |
| | 시장 | market | VARCHAR(10) | NN | | `CHECK (market IN ('KOSPI','KOSDAQ'))` |

### 종목 가격 (stock_price)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 가격번호 | id | BIGINT | NN | AUTO_INCREMENT | 대리 키 |
| FK | 종목번호 | stock_id | BIGINT | NN | | `stock.id` 참조 |
| | 가격 | price | DECIMAL(19,4) | NN | | `CHECK (price > 0)`, `CHECK (price = ROUND(price, 0))`. 원 단위 정수 값(호가 단위). 타입은 금액 타입으로 통일한다 |
| | 가격 기준일시 | traded_at | DATETIME(6) | NN | | `UNIQUE (stock_id, traded_at)` — 최신가·종가 조회 인덱스를 겸한다 |

### 주문 (orders)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 주문번호 | id | BIGINT | NN | AUTO_INCREMENT | |
| FK | 회원번호 | member_id | BIGINT | NN | | `member.id` 참조 |
| FK | 종목번호 | stock_id | BIGINT | NN | | `stock.id` 참조 |
| | 매수매도 구분 | side | VARCHAR(4) | NN | | `CHECK (side IN ('BUY','SELL'))` |
| | 주문 유형 | type | VARCHAR(6) | NN | | `CHECK (type IN ('MARKET','LIMIT'))` |
| | 수량 | quantity | INT | NN | | `CHECK (quantity > 0)` — 1주 단위 정수(PLAN §3) |
| | 지정가 | limit_price | DECIMAL(19,4) | NULL | | `MARKET`이면 NULL, `LIMIT`이면 NULL이 아니고 0보다 큼(아래 CHECK). 원 단위 정수 값(호가 단위, `stock_price.price`와 같음): `CHECK (limit_price = ROUND(limit_price, 0))`(NULL이면 통과) |
| | 주문 상태 | status | VARCHAR(10) | NN | 'PENDING' | `CHECK (status IN ('PENDING','FILLED','CANCELED','REJECTED'))` |
| | 주문일시 | created_at | DATETIME(6) | NN | CURRENT_TIMESTAMP(6) | |

### 체결 (execution)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 체결번호 | id | BIGINT | NN | AUTO_INCREMENT | |
| FK | 주문번호 | order_id | BIGINT | NN | | `orders.id` 참조. UNIQUE 아님(주문 1 : 체결 0..N) |
| | 체결가 | fill_price | DECIMAL(19,4) | NN | | `CHECK (fill_price > 0)`, `CHECK (fill_price = ROUND(fill_price, 0))`. 원 단위 정수 값(호가 단위, `stock_price.price`와 같음). 체결 대금 = `fill_price × quantity`도 정수가 되어 원장 `amount`와 오차 없이 맞는다(대사 4번) |
| | 체결 수량 | quantity | INT | NN | | `CHECK (quantity > 0)` |
| | 수수료 | fee | DECIMAL(19,4) | NN | 0 | `CHECK (fee >= 0)`, `CHECK (fee = ROUND(fee, 0))`. MVP 요율 기본 0(PLAN §3). 원 단위 정수 값 |
| | 세금 | tax | DECIMAL(19,4) | NN | 0 | `CHECK (tax >= 0)`, `CHECK (tax = ROUND(tax, 0))`. 매도에만 발생(매수 체결의 `tax` = 0은 대사가 검사). 원 단위 정수 값 |
| | 실현 손익 (매도만) | realized_pnl | DECIMAL(19,4) | NULL | | 매도 체결에만 값, 매수는 NULL. 소수 4자리까지 저장. 계산 규칙은 PLAN §5 "원가 기준" |
| | 체결일시 | executed_at | DATETIME(6) | NN | | |

### 보유 (holding)

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 보유번호 | id | BIGINT | NN | AUTO_INCREMENT | 대리 키 |
| FK | 회원번호 | member_id | BIGINT | NN | | `member.id` 참조 |
| FK | 종목번호 | stock_id | BIGINT | NN | | `stock.id` 참조 |
| | 보유 수량 | quantity | INT | NN | | 전량 매도 후에도 행을 유지하고 0으로 둔다. 0 이상 규칙(`quantity >= 0`)은 아래 `avg_price` 행의 CHECK가 강제한다 |
| | 평균단가 | avg_price | DECIMAL(19,4) | NN | 0 | `CHECK ((quantity = 0 AND avg_price = 0) OR (quantity > 0 AND avg_price > 0))` — 수량 0이면 평균단가 0, 보유 중이면 0보다 큼(한 행 안의 규칙이라 CHECK로 강제). 소수 4자리까지 저장. 전량 매도 시 0으로 되돌린다. 계산 규칙은 설계 질문 2 답변 후 PLAN §5 "원가 기준"에 기록. `avg_price` 값 자체는 Phase 0에서 대사하지 않는다(체결 이력 재생이 필요, D9와 같은 이유) |

`UNIQUE (member_id, stock_id)` (PLAN §5).

### 현금 원장 (cash_ledger) — 추가 전용

| 키 | 논리명 | 물리명 | 타입 | NULL | 기본값 | 코멘트 |
| --- | --- | --- | --- | --- | --- | --- |
| PK | 원장번호 | id | BIGINT | NN | AUTO_INCREMENT | 같은 회원 안에서 기록 순서 = id 순서(전제: 원장 행을 넣는 모든 트랜잭션은 직전 `balance_after`를 읽기 **전에**, 같은 트랜잭션 안에서 `cash_account` 행을 `SELECT … FOR UPDATE`로 잠그거나 `version`을 올리는 `UPDATE`를 먼저 한다. 둘 다 InnoDB 행 잠금을 커밋까지 잡아 회원별로 직렬화한다. AUTO_INCREMENT 값은 커밋 순서가 아니라 할당 순서이므로 이 직렬화가 있어야 성립한다. 잠금만으로는 최신 값을 읽는다는 보장이 없다: InnoDB 기본 격리 수준(REPEATABLE READ)의 일반 `SELECT`는 그 트랜잭션에서 처음 실행한 일반 `SELECT` 시점의 스냅샷을 읽으므로, 잠그기 전에 가격 조회 같은 일반 `SELECT`를 했다면 잠근 뒤에도 다른 트랜잭션이 커밋한 원장 행을 보지 못한다. 그래서 직전 `balance_after`는 `SELECT … FOR UPDATE`(잠금 읽기)로 읽고, 갱신 판단에 쓰는 `holding` 행(수량·평균단가)도 같은 방식으로 읽는다). "최신 `balance_after`"는 id가 가장 큰 행(`created_at` 동률 회피) |
| FK | 회원번호 | member_id | BIGINT | NN | | **`cash_account.member_id`** 참조(원장은 계좌의 원장) |
| | 원장 유형 | type | VARCHAR(12) | NN | | `CHECK (type IN ('DEPOSIT','BUY','SELL','FEE','TAX','ADJUSTMENT'))` |
| | 부호 있는 금액 | amount | DECIMAL(19,4) | NN | | 입금 +, 출금 −. Σ amount = balance(PLAN §5 대사). 원 단위 정수 값: `CHECK (amount = ROUND(amount, 0))`. 부호 CHECK는 아래 |
| | 거래 후 잔액 | balance_after | DECIMAL(19,4) | NN | | `CHECK (balance_after >= 0)`, `CHECK (balance_after = ROUND(balance_after, 0))`. 직전 행 기준 누적(설계 질문 6) |
| FK | 참조 주문번호 | ref_order_id | BIGINT | NULL | | `orders.id` 참조. `DEPOSIT`은 항상 NULL, `BUY`·`SELL`·`FEE`·`TAX`는 필수, `ADJUSTMENT`는 선택 |
| | 기록일시 | created_at | DATETIME(6) | NN | CURRENT_TIMESTAMP(6) | |

## 제약과 인덱스

### PK

| 테이블 | PK |
|---|---|
| `member` | `id` |
| `cash_account` | `member_id` |
| `stock` | `id` |
| `stock_price` | `id` |
| `orders` | `id` |
| `execution` | `id` |
| `holding` | `id` |
| `cash_ledger` | `id` |

### FK (모두 `ON DELETE RESTRICT ON UPDATE RESTRICT`)

| 자식 컬럼 | 부모 컬럼 | 관계 |
|---|---|---|
| `cash_account.member_id` | `member.id` | R1 |
| `orders.member_id` | `member.id` | R2 |
| `orders.stock_id` | `stock.id` | R3 |
| `execution.order_id` | `orders.id` | R4 |
| `holding.member_id` | `member.id` | R5 |
| `holding.stock_id` | `stock.id` | R6 |
| `stock_price.stock_id` | `stock.id` | R7 |
| `cash_ledger.member_id` | `cash_account.member_id` | R8 |
| `cash_ledger.ref_order_id` | `orders.id` | R9 |

### UNIQUE

| 테이블 | 대상 | 목적 |
|---|---|---|
| `member` | `email` | 중복 가입 방지 |
| `stock` | `ticker` | 종목코드 중복 방지 |
| `stock_price` | `(stock_id, traded_at)` | 같은 종목·시각 가격 중복 방지, 최신가·종가 조회 인덱스 겸용 |
| `holding` | `(member_id, stock_id)` | 회원·종목당 보유 행 하나 |

### CHECK

| 테이블 | 조건 |
|---|---|
| `cash_account` | `balance >= 0` |
| `cash_account` | `reserved >= 0 AND reserved <= balance` |
| `cash_account` | 원 단위: `balance = ROUND(balance, 0)` |
| `cash_account` | 원 단위: `reserved = ROUND(reserved, 0)` |
| `stock` | `market IN ('KOSPI','KOSDAQ')` |
| `stock_price` | `price > 0` |
| `stock_price` | 원 단위: `price = ROUND(price, 0)` |
| `orders` | `side IN ('BUY','SELL')` |
| `orders` | `type IN ('MARKET','LIMIT')` |
| `orders` | `quantity > 0` |
| `orders` | `(type = 'MARKET' AND limit_price IS NULL) OR (type = 'LIMIT' AND limit_price IS NOT NULL AND limit_price > 0)` |
| `orders` | 원 단위: `limit_price = ROUND(limit_price, 0)`(NULL이면 통과) |
| `orders` | `status IN ('PENDING','FILLED','CANCELED','REJECTED')` |
| `execution` | `fill_price > 0` |
| `execution` | 원 단위: `fill_price = ROUND(fill_price, 0)` |
| `execution` | `quantity > 0` |
| `execution` | `fee >= 0` |
| `execution` | `tax >= 0` |
| `execution` | 원 단위: `fee = ROUND(fee, 0)` |
| `execution` | 원 단위: `tax = ROUND(tax, 0)` |
| `holding` | 평균단가 0 규칙(`quantity >= 0`, `avg_price >= 0`을 포함): `(quantity = 0 AND avg_price = 0) OR (quantity > 0 AND avg_price > 0)` |
| `cash_ledger` | `type IN ('DEPOSIT','BUY','SELL','FEE','TAX','ADJUSTMENT')` |
| `cash_ledger` | 부호: `(type IN ('DEPOSIT','SELL') AND amount > 0) OR (type IN ('BUY','FEE','TAX') AND amount < 0) OR (type = 'ADJUSTMENT' AND amount <> 0)` |
| `cash_ledger` | `balance_after >= 0` |
| `cash_ledger` | 원 단위: `amount = ROUND(amount, 0)` |
| `cash_ledger` | 원 단위: `balance_after = ROUND(balance_after, 0)` |
| `cash_ledger` | 주문 참조: `(type IN ('BUY','SELL','FEE','TAX') AND ref_order_id IS NOT NULL) OR (type = 'DEPOSIT' AND ref_order_id IS NULL) OR type = 'ADJUSTMENT'` |

### INDEX (FK 자동 인덱스 외에 직접 만드는 것)

PostgreSQL(M1)은 FK에 인덱스를 자동으로 만들지 않는다. M1 이전 시 FK 9개 중 다른 인덱스의 앞 컬럼이 아닌 것(`execution.order_id`, `holding.stock_id`, `orders.stock_id`, `cash_ledger.ref_order_id`)에 인덱스를 추가한다. 지금은 MySQL이 자동으로 만든다.

| 테이블 | 인덱스 | 쓰는 곳 |
|---|---|---|
| `orders` | `(member_id, created_at)` | `GET /api/orders` 기간·페이징(PLAN §7) |
| `cash_ledger` | `(member_id, id)` | `GET /api/cash/ledger` 페이징, 최신 `balance_after` 조회 |

## DDL로 막지 못해 대사(P0-07)가 검사할 규칙

`CHECK`는 한 행 안의 컬럼만 볼 수 있고 다른 테이블이나 행 집합은 보지 못한다. 아래는 DDL로 강제할 수 없어 대사가 잡아야 한다.

1. 회원마다 `cash_account` 1행(R1의 최소 1), 계좌마다 `DEPOSIT` 원장 행이 1건 이상(R8의 최소 1).
2. Σ `cash_ledger.amount` = `cash_account.balance`.
3. 잔액 일치와 연속성: id가 가장 큰 `cash_ledger` 행의 `balance_after` = `cash_account.balance`이고, 같은 회원 원장을 id 순으로 볼 때 각 행 `balance_after` = 직전 행 `balance_after` + `amount`(첫 행은 `amount`). `LAG()`로 검사하며 중간 행이 깨지면 어느 행인지 보고한다(설계 질문 6).
4. 주문별 원장 ↔ 체결(같은 돈을 두 곳에 기록하므로, D4): 주문마다 Σ `cash_ledger.amount`(`ref_order_id` = 그 주문인 `BUY`·`SELL`·`FEE`·`TAX`·`ADJUSTMENT` 행 모두) = (매수 주문이면 −, 매도 주문이면 +) Σ (`fill_price × quantity`) − Σ `execution.fee` − Σ `execution.tax`. 원장 행이나 체결이 없는 쪽은 `COALESCE`로 0 처리한다(0원은 행을 생략). `ADJUSTMENT`를 합에 넣어야 정정한 뒤 불일치가 0건이 된다. 원장 `BUY`/`SELL` 행의 `type` = 참조 주문의 `side`.
5. 원장 행의 `member_id` = 참조 주문의 `orders.member_id`(`ref_order_id`가 있는 행).
6. `holding.quantity` = Σ 매수 체결 수량 − Σ 매도 체결 수량(`execution` ⋈ `orders.side`). 양방향으로 검사한다: `holding`에 있는데 체결과 맞지 않는 (회원, 종목)과, 체결은 있는데 `holding` 행이 없는 (회원, 종목) 모두 보고한다(MySQL은 FULL OUTER JOIN이 없어 `LEFT JOIN` 두 개를 `UNION`). `avg_price`는 Phase 0에서 대사하지 않는다(체결 이력 재생이 필요).
7. 주문 상태 ↔ 체결: Σ `execution.quantity` ≤ `orders.quantity`이고, `FILLED`면 =, `PENDING`·`REJECTED`면 체결 0건(MVP 시장가 기준 — V2 부분 체결 때 상태와 함께 다시 정한다).
8. 체결 값 규칙: 매수 체결에 `realized_pnl` 값이 있거나 `tax` ≠ 0, 매도 체결에 `realized_pnl`이 NULL.
9. `reserved` 검사식 — 설계 질문 3에 답한 뒤 P0-07에서 정의한다.
10. 원장 행의 UPDATE/DELETE — 추가 전용은 규칙과 권한으로만 지켜지며 DDL 제약이 아니다(쿼리 대상이 아닌 규칙). 정정은 `ADJUSTMENT` 행 추가로 한다.

## 설계 결정

소유자가 확정한 결정(2026-11-02)이다. "탈락"은 검토했지만 택하지 않은 대안이다.

| # | 결정 | 근거 | 탈락한 대안 |
|---|---|---|---|
| D1 | 모든 금액을 `DECIMAL(19,4)`로 한다. 현금 값(`amount`, `balance`, `balance_after`, `reserved`, `fee`, `tax`)과 가격(`price`, `limit_price`, `fill_price`)은 원 단위 정수 값만 저장하고 `CHECK (col = ROUND(col, 0))`로 DB가 강제, `avg_price`·`realized_pnl`은 소수 4자리까지 저장 | 설계 질문 1, PLAN §5 회계 규칙 "반올림". 타입이 하나라 단순하고 평균단가의 소수를 표현하며, M1 PostgreSQL `NUMERIC(19,4)`(MACRO_ETF §7)와 같아 이전이 무손실이다. 타입은 `DECIMAL(19,4)`로 통일하되 원 단위 정수 `CHECK`로 `DECIMAL(15,0)`(탈락한 대안)의 장점 — 소수가 원장에 못 들어간다는 DB 보장 — 만 가져왔다. `reserved`는 `balance`에서 빼서 주문 가능 금액을 만들므로 같은 단위여야 해서 포함하고, 가격(`price`·`limit_price`·`fill_price`)도 정수로 묶어야 체결 대금(가격 × 수량)이 정수가 되어 대사 4번이 원 미만 차이를 내지 않는다(호가 단위 자체는 강제하지 않음). `avg_price`·`realized_pnl`은 계산 결과라 소수를 허용한다. **V3 외화(소수 통화) 도입 시 이 CHECK들을 바꿔야 한다.** `DOUBLE`은 이진 부동소수점이라 0.1 같은 값을 정확히 표현하지 못해 합계 대사가 어긋난다 | `BIGINT`(원) + `avg_price`만 `DECIMAL`(타입이 섞여 M1에서 다시 변경), `DECIMAL(15,0)`(수수료 중간값 저장 불가, 장점만 `CHECK`로 채택) |
| D2 | `cash_account.reserved`를 유지하고 `CHECK (reserved >= 0 AND reserved <= balance)` | 설계 질문 3, PLAN §5 `cash_account`. V2 지정가 주문을 스키마 변경 없이 수용하고, 한 행만 잠그면 동시성(설계 질문 5)을 다룰 수 있다. 대기 주문 합계와의 일치는 P0-07이 검사한다 | PENDING 주문 합계로 매번 계산(집계·다중 행 잠금), 별도 `cash_hold` 테이블(MVP 과설계) |
| D3 | 주문 1 : 체결 0..N, `execution.order_id`는 UNIQUE 아님 | PLAN §5 `orders`/`execution`, §8 V2 지정가·부분 체결. 거부·대기 주문은 체결 0건으로 자연스럽게 표현된다 | 1:1(V2에서 제약 제거 필요), 테이블 통합(상태와 사실이 섞임) |
| D4 | 원장 `type`은 `DEPOSIT`/`BUY`/`SELL`/`FEE`/`TAX`/`ADJUSTMENT`, 부호 있는 `amount` + 부호 `CHECK`, 계좌 개설 시 `DEPOSIT` 행(`ref_order_id` NULL을 `CHECK`로 강제, `ADJUSTMENT`는 주문 참조 선택). 수수료·세금은 별도 `FEE`·`TAX` 행(0원이면 행 생략) | PLAN §5 대사 "Σ amount = balance"를 만족하려면 부호 있는 금액과 개설 입금 행이 필요하다. 비용 합계를 원장만으로 확인할 수 있고(일간 분해의 "비용" 항), MACRO_ETF §7.1과 같은 구조라 M1 통합이 쉽다. 초기 지급은 스토리 1의 "가상 현금을 받는다"에 맞춰 `DEPOSIT`이다 | 비용을 BUY/SELL 금액에 포함(조인 필요, 구조 다름), 부호 CHECK 없음(부호 실수를 DB가 못 잡음) |
| D5 | `stock_price`를 이력 테이블로 두고 최신가·종가는 쿼리로 구한다 | PLAN §4 설계 규칙(주문 로직은 가격 소스를 모름), 일간 손익 분해의 어제·오늘 종가, 스토리 2·5. 대리 키 + `UNIQUE (stock_id, traded_at)` | `stock.current_price` 컬럼(같은 값 두 곳), 별도 `stock_latest_price` 테이블(MVP 과설계) |
| D6 | `cash_account` PK = `member_id`(식별 1:1), `holding`·`stock_price`는 대리 키 `id` + UNIQUE, `cash_ledger.member_id`는 `cash_account.member_id` 참조 | PLAN §5 컬럼 목록과 맞고(`cash_account`에 id 없음), 1:1을 DB가 보장하며, JPA 복합 키를 피하고, 원장이 "계좌의 원장"임이 FK로 드러난다. M1의 `account_id` 전환도 가장 작다 | 모든 테이블 대리 키(`cash_account`에 PLAN에 없는 id), `holding` 복합 PK(JPA 학습 부담, M1에서 PK 변경) |
| D7 | 전량 매도해도 `holding` 행을 유지하고 `quantity = 0`, `avg_price = 0`. 이 쌍을 `CHECK ((quantity = 0 AND avg_price = 0) OR (quantity > 0 AND avg_price > 0))`로 강제한다 | PLAN §5 대사 "보유 수량 = 매수 − 매도", 설계 질문 2. 체결은 있는데 보유 행이 없으면 곧 이상 신호이고, 매도 SQL이 UPDATE 하나로 끝난다. 포트폴리오 쿼리는 `quantity > 0`으로 거른다 | 행 삭제(매도 SQL 분기, 정상 부재와 누락 구분 어려움) |
| D8 | 구분값은 `VARCHAR` + `CHECK (col IN (...))` | 10-16 수업 문법 그대로이고 PostgreSQL로 그대로 이동하며 JPA `@Enumerated(EnumType.STRING)`에 대응한다 | MySQL `ENUM`(M1에서 재작성), 코드 테이블 + FK(테이블 4개 추가, 과설계) |
| D9 | `execution.realized_pnl DECIMAL(19,4) NULL`을 추가한다(매도 체결만 값, 매수는 NULL). PLAN §5에 반영함 | 스토리 4 "실현 손익이 기록된다", 설계 질문 2·4. 이동평균 원가는 매수마다 바뀌므로 매도 시점의 값을 남기지 않으면 현재 `holding.avg_price`로 과거 매도의 실현 손익을 다시 계산할 수 없다. 비용(수수료·세금)을 실현 손익에서 뺄지는 설계 질문 4로 남긴다 | 저장하지 않고 체결 이력 재생(재귀 CTE·윈도 함수, Phase 0 범위 초과), `avg_price_at_fill`만 저장(비용 포함 여부를 쿼리마다 맞춰야 함) |
| D10 | 표기는 `docs/erd.md`의 Mermaid `erDiagram` + 엔티티 정의서 | GitHub가 바로 렌더링하고 텍스트라 PR diff로 리뷰할 수 있으며, 소유자가 이미 쓰는 노트 형식이다. 결정 근거를 같은 파일에 둔다 | dbdiagram.io DBML(DDL 자동 생성이 직접 작성 목표를 우회), 이미지(diff·리뷰 불가) |

**종가 정의:** 어느 날의 종가는 그날(KST) `traded_at`이 가장 큰 `stock_price` 행의 `price`이다. 최신가는 종목별 `MAX(traded_at)` 행이다.

## 하류 기능 약속

| 소비 기능 | 약속 |
|---|---|
| P0-02 DDL | 이 문서의 표를 그대로 `CREATE TABLE`로 옮긴다. 생성 순서: `member` → `cash_account` → `stock` → `stock_price` → `orders` → `execution` → `holding` → `cash_ledger`. CHECK·UNIQUE·INDEX 목록도 모두 옮기고, CHECK·UNIQUE·FK에 이름을 붙인다(`ck_`/`uq_`/`fk_` 접두, 예: `ck_orders_limit_price`, `uq_holding_member_stock`). 자동 이름(`orders_chk_4`)은 오류 메시지에서 규칙을 알 수 없다. CHECK는 모두 컬럼 선언에 붙이지 않고 컬럼 목록 뒤에 `CONSTRAINT ck_… CHECK (…)` 테이블 제약으로 쓴다. MySQL은 다른 컬럼을 참조하는 컬럼 CHECK(예: `reserved … CHECK (reserved <= balance)`)를 ERROR 3813으로 거부한다 |
| P0-03 시드 | 회원 생성 시 `cash_account(balance = 10,000,000)`와 `cash_ledger(type = 'DEPOSIT', amount = 10,000,000, balance_after = 10,000,000)`를 함께 넣는다. 안 넣으면 P0-07의 1번(개설 `DEPOSIT` 존재)과 2번(Σ amount = balance) 검사가 시작부터 실패한다. 종목마다 `stock_price`를 최소 1행 넣는다(시장가 매수 체결가의 근거) |
| P0-04 매수 | 체결 1건 → `execution` 1행 + 원장 `BUY` 1행(체결 대금 = fill_price × quantity, 음수) + 비용이 0보다 크면 `FEE` 행. `balance_after`는 직전 행 기준 누적, `execution.realized_pnl`은 NULL. 계좌 잠금과 잠금 읽기는 `cash_ledger.id` 코멘트의 전제를 따른다 |
| P0-05 매도 | 체결 1건 → `execution` 1행 + 원장 `SELL` 1행(양수) + 비용이 0보다 크면 `FEE`·`TAX` 행. `realized_pnl`을 기록한다(D9). 보유 수량보다 많이 팔 수 없고, 전량 매도 시 `holding.quantity = 0`(D7). 계좌 잠금과 잠금 읽기는 `cash_ledger.id` 코멘트의 전제를 따른다 |
| P0-06 포트폴리오 | 최신가 = `stock_price`에서 종목별 `MAX(traded_at)` 행. 보유는 `quantity > 0`만 조회한다 |
| P0-07 대사 | 위 "DDL로 막지 못해 대사가 검사할 규칙"의 1–9번을 쿼리로 구현한다(10번은 규칙이라 쿼리 대상이 아님). 각 불일치를 시차·조정·조사로 분류한다(설계 질문 7, PLAN §9). 정정은 `ADJUSTMENT` 행 추가로 한다 |
