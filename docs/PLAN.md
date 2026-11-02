# Ant Lab — 프로젝트 계획

한국 주식 모의투자 플랫폼이다. 사용자는 가상 현금을 받아 KOSPI/KOSDAQ 종목을 시뮬레이션 가격 또는 실제 가격으로 사고팔고, 포트폴리오를 추적한다. 이후 버전에서는 매매 일지, 가격 알림, 기업 공시 기반 인공지능(AI, Artificial Intelligence) 기능을 추가한다.

학습 프로젝트이다. [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus) 부트캠프 커리큘럼과 함께 성장하며, 각 버전은 현재 챕터에서 배운 내용을 사용한다. 최소 기능 제품(MVP, Minimum Viable Product)까지는 모든 코드를 직접 작성하고, 매크로 상장지수펀드(ETF, Exchange-Traded Fund) 트랙에서는 소유자가 핵심 로직을 작성하고 모든 파일을 검토한다 ([MACRO_ETF.md §9.1](MACRO_ETF.md#91-역할-분담) 참고).

> "Ant Lab"(개미 연구소)은 가제이며 바뀔 수 있다.

---

## 1. 목표

- 실제 도메인을 모델링한다: 주문, 체결, 보유, 현금을 다루며 금액은 항상 맞아떨어져야 한다.
- 하나의 코드베이스를 키워 가며 커리큘럼을 실습한다: 구조적 질의 언어(SQL, Structured Query Language) → 자바 데이터베이스 연결(JDBC, Java Database Connectivity) → Spring Boot + 자바 영속성 API(JPA, Java Persistence API) → React → 검색 증강 생성(RAG, Retrieval-Augmented Generation)/LangChain → Docker/지속적 통합·지속적 배포(CI/CD, Continuous Integration/Continuous Deployment).
- 도메인 모델링, 동시 요청에서의 정합성, AI 기능을 보여 주는 포트폴리오를 완성한다.

비목표: 실제 돈, 실계좌 증권사 주문(매크로 트랙은 한국투자증권(KIS, Korea Investment & Securities) 모의투자 계좌로만 주문을 보낸다), 투자 조언.

## 2. 기술 스택

| 영역 | 선택 | 비고 |
|------|--------|-------|
| 언어 | Java 21 | |
| 백엔드 | Spring Boot 3.x, Gradle | Spring 챕터 시작 시 start.spring.io로 생성 |
| 데이터베이스 | MySQL 8 → PostgreSQL 16 | Phase 0–MVP는 MySQL(커리큘럼), 매크로 트랙(M1)부터 pgvector를 포함한 PostgreSQL |
| 매핑 | JPA (주) | 리포트/통계 쿼리에 MyBatis 선택 사용 |
| 테스트 | JUnit 5, Spring Boot Test | 주문 동시성 테스트 |
| 퀀트·AI 서비스 | Python, FastAPI, pandas | 매크로 트랙 M2 |
| 프런트엔드 | React | M5 (기존 V3) |
| AI | RAG, LangChain, LangGraph | M6 (기존 V4) |
| 운영 | Docker Compose, GitHub Actions, 오라클 클라우드 인프라(OCI, Oracle Cloud Infrastructure) | M1부터 CI, M7에 배포 (기존 V5) |

## 3. 한국 시장 규칙

| 규칙 | 실제 시장 | MVP | 이후 |
|------|-------------|-----|-------|
| 거래 시간 | 평일 09:00–15:30 한국 표준시(KST, Korea Standard Time), 공휴일 휴장 | 무시(언제든 거래) | 장외 시간 주문은 거부하거나 대기열에 넣음 |
| 호가 단위 | 허용되는 가격 단위가 가격대에 따라 다름 | 불필요(시장가 주문만) | 지정가 검증 |
| 가격제한폭 | 전일 종가 대비 ±30% | 가격 생성기가 ±30% 안에서만 움직임 | 범위를 벗어난 지정가 주문 거부 |
| 수수료와 세금 | 매수/매도 시 증권사 수수료, 매도 시 거래세 | 요율 설정 가능, 기본값 0 | 실제 요율(구현 시점의 세율 확인) |
| 결제 | T+2 | 즉시 | 선택: "출금 가능 금액"과 "주문 가능 금액" 구분 |
| 주문 단위 | 1주, 소수점 불가 | 정수 수량 | 동일 |

## 4. 가격 데이터 소스

| 소스 | 장점 | 단점 | 시기 |
|--------|------|------|------|
| 시뮬레이션 생성기(랜덤 워크) | 애플리케이션 프로그래밍 인터페이스(API, Application Programming Interface) 키 불필요, 오프라인, 속도 조절 가능 | 실제 가격 아님 | MVP |
| 공공데이터포털 (data.go.kr) 주식시세정보 | 무료, 공식 일별 시세 | 하루 지연 | V2 |
| 한국투자증권 KIS Developers Open API | 실시간 시세(표현 상태 전송(REST, Representational State Transfer) + WebSocket), 모의투자 모드 | 계좌와 토큰 관리 필요 | V3 |
| 전자공시시스템(DART, Data Analysis, Retrieval and Transfer System) Open API | 공식 기업 공시 | 텍스트 위주 | V4 (AI) |

설계 규칙: 주문 로직은 가격이 어디서 오는지 몰라야 한다. 시뮬레이션 소스를 실제 소스로 바꿔도 주문 코드는 건드리지 않아야 한다.

## 5. 도메인 (MVP 테이블)

핵심 컬럼만 적는다. 데이터 정의 언어(DDL, Data Definition Language)는 `db/`에 직접 작성한다. 컬럼 타입·키·제약·관계를 포함한 전체 ERD는 [erd.md](erd.md)를 본다.

| 테이블 | 핵심 컬럼 |
|-------|-------------|
| `member` | id, email (unique), name, password, created_at |
| `cash_account` | member_id, balance, reserved, version |
| `stock` | id, ticker (unique, 예: `005930`), name, market (KOSPI/KOSDAQ) |
| `stock_price` | stock_id, price, traded_at |
| `orders` | id, member_id, stock_id, side (BUY/SELL), type (MARKET/LIMIT), quantity, limit_price, status (PENDING/FILLED/CANCELED/REJECTED), created_at |
| `execution` | id, order_id, fill_price, quantity, fee, tax, realized_pnl (매도만), executed_at |
| `holding` | member_id, stock_id, quantity, avg_price — unique (member_id, stock_id) |
| `cash_ledger` | id, member_id, type, amount, balance_after, ref_order_id, created_at |

이후 추가: `watchlist`, `alert_rule`, `journal_entry`.

### 회계 규칙

- **원가 기준:** 매수와 매도 시 평균단가가 어떻게 바뀌는지 정하고(설계 질문 2), `db/03_buy_sell.sql`을 작성하기 전에 그 규칙을 여기에 기록한다.
  - 실현 손익은 매도 체결 시 `execution.realized_pnl`에 기록한다(매수 체결은 NULL). 이동평균 원가는 매수마다 바뀌므로 매도 시점의 값을 남겨야 과거 매도의 실현 손익을 다시 계산할 수 있다. 비용(수수료·세금)을 실현 손익에서 뺄지는 설계 질문 4로 남겨 둔다.
- **반올림:** 모든 금액 컬럼은 `DECIMAL(19,4)`이다(설계 질문 1, [erd.md](erd.md)의 D1). 현금 값(`amount`, `balance`, `balance_after`, `reserved`, `fee`, `tax`)과 가격(`price`, `limit_price`, `fill_price`)은 원 단위 정수 값만 저장하고 DB가 `CHECK (col = ROUND(col, 0))`로 강제하며, `avg_price`와 `realized_pnl`은 소수 4자리까지 저장한다. 수수료와 세금은 전체 정밀도로 계산한 뒤, 하나의 규칙에 따라 코드의 한 곳에서 한 번만 반올림한다(V2에서 실제 요율을 추가할 때 증권사의 정확한 규칙을 확인한다).
- **일간 손익 분해:** 입출금이 없을 때, 오늘의 포트폴리오 가치 변동(현금 + 오늘 종가 기준 보유 평가액)은 세 부분으로 나뉘며, 합이 전체 변동과 같아야 한다:
  - 가격 효과 = (오늘 종가 − 어제 종가) × 어제 수량,
  - 매매 효과 = 오늘 매도 건별 (체결가 − 오늘 종가) × 수량의 합 + 오늘 매수 건별 (오늘 종가 − 체결가) × 수량의 합,
  - 비용 = − (수수료 + 세금).

  실현 손익은 다른 숫자이다. 매수 이후의 이익을 세는데, 그중 상당 부분은 이전 날들의 평가에 이미 반영되었으므로 따로 보고하며(사용자 스토리 4) 일간 분해에 더하지 않는다. 사용자 스토리 5에서 이 분해를 보여 준다.
- **대사:** 장부는 스스로 일치해야 한다(Phase 0 항목 `P0-07`, 이후 MVP의 일일 작업): `cash_ledger.amount`의 합은 `cash_account.balance`와 같고, 최신 `balance_after`는 잔액과 같으며, 각 보유의 수량은 매수 체결 수량에서 매도 체결 수량을 뺀 값과 같다. 모든 차이는 시차, 조정, 조사 중 하나로 분류하며(설계 질문 7), 오류는 기존 원장 행을 수정하지 않고 새 원장 행을 추가해 바로잡는다.

### DDL 작성 전에 답할 설계 질문

1. 원화 가격은 정수이지만 평균단가는 보통 정수가 아니다. 각 금액 컬럼에 어떤 MySQL 타입이 맞으며, `DOUBLE`은 왜 위험한가?
2. ₩70,000에 10주를 보유한 상태에서 ₩76,000에 5주를 더 산다. 새 평균단가는 얼마인가? 3주를 팔면 평균단가는 어떻게 되는가?
3. ₩1,000,000을 보유하고 있고 ₩800,000짜리 지정가 매수 주문이 대기 중이다. 지금 ₩500,000 시장가 매수를 낼 수 있는가? `reserved`는 무엇을 위한 것인가?
4. 실현 손익과 미실현 손익의 차이는 무엇이며, 각각 어느 테이블에서 나오는가?
5. 같은 10주에 대한 매도 주문 두 건이 동시에 들어온다. 둘 다 성공하는 것을 어떻게 막는가?
6. `cash_account.balance`가 이미 있는데 왜 모든 원장 행에 `balance_after`를 두는가?
7. 대사 결과 원장 합계와 `cash_account.balance`가 ₩1 차이 난다. 가능한 원인 세 가지를 들고, 각각이 시차(저절로 해소됨), 조정(정정 분개 필요), 조사 대상 중 무엇인지 말하라.

## 6. MVP 사용자 스토리

기능 번호는 아래 순서대로 `MVP-01`부터 `MVP-06`까지이다([CONVENTION.md](CONVENTION.md) 참고).

1. 회원 가입하면 가상 현금 ₩10,000,000을 받는다.
2. 시간에 따라 변하는(시뮬레이션) 현재가와 함께 종목 목록을 본다.
3. 시장가 매수를 낸다: 현금이 줄고, 주식을 받고, 체결이 기록된다 — 전부 되거나 전부 안 된다.
4. 시장가 매도를 낸다: 보유하지 않은 주식은 팔 수 없고, 실현 손익이 기록된다.
5. 포트폴리오를 본다: 보유 종목별 수량, 평균단가, 현재 평가액, 수익률 %와 합계, 그리고 오늘 변동의 원인(가격 효과, 매매 효과, 비용).
6. 주문 내역과 현금 내역을 본다.

## 7. API 초안 (Spring 단계부터)

```
POST   /api/members                 회원 가입 (₩10M 현금 계좌 생성)
GET    /api/stocks                  최신가를 포함한 종목 목록
GET    /api/stocks/{ticker}         종목 상세와 최근 가격
POST   /api/orders                  주문 (side, type, ticker, quantity, limitPrice)
GET    /api/orders                  내 주문 (상태, 기간, 페이징)
DELETE /api/orders/{id}             대기 주문 취소 (V2)
GET    /api/portfolio               보유, 평가액, 수익률, 합계
GET    /api/cash/ledger             현금 내역 (페이징)
POST   /api/watchlist               관심 종목 추가 (V2)
POST   /api/alerts                  가격 알림 생성 (V2)
GET    /api/leaderboard             시간가중수익률 순위 (V3)
```

리더보드 수익률은 입출금이 순위를 왜곡하지 않도록 시간가중수익률(TWR, Time-Weighted Return)로 계산한다. 각 회원의 개인 페이지에는 자신의 현금 흐름에 대한 수익률인 금액가중수익률(MWR, Money-Weighted Return)도 함께 보여 준다.

## 8. 로드맵

| 버전 | 기능 | 커리큘럼 단계 |
|---------|----------|------------------|
| Phase 0 | 엔티티 관계도(ERD, Entity-Relationship Diagram), DDL, 시드 데이터, SQL 트랜잭션으로 매수/매도, 포트폴리오 쿼리 | MySQL (DDL, 트랜잭션) — 현재 |
| Phase 1 | 데이터 접근 객체(DAO, Data Access Object) 기반 콘솔 앱: 매수, 매도, 포트폴리오 | JDBC |
| MVP | 여섯 가지 사용자 스토리의 REST API, 계층형 아키텍처, 검증, 전역 예외 처리, 각 차이를 시차·조정·조사 항목으로 기록하는 일일 대사 작업([MACRO_ETF.md §6.6](MACRO_ETF.md#66-일일-대사)과 같은 구조) | Spring Boot + JPA |
| V2 | 가격 정기 점검으로 체결되는 지정가 주문, 취소, 관심 종목, 알림, 수수료와 세금 | Spring (스케줄링) |
| V3 | 실제 가격 데이터, 리더보드(TWR), 매매 일지, 차트 | 외부 API, React |
| V4 | DART 공시 Q&A(RAG), 매매 일지 기반 트레이드 코치 에이전트, 자연어 포트폴리오 질의 | RAG, LangChain, LangGraph |
| V5 | Docker Compose (앱 + MySQL), GitHub Actions, 클라우드 배포 | Docker, CI/CD, 클라우드 |

확장 목표: 사용자끼리 거래하는 호가창 매칭 엔진.

### 매크로 ETF 트랙 (MVP 이후)

연금저축과 개인형 퇴직연금(IRP, Individual Retirement Pension)을 위한 매크로 기반 ETF 자산배분 시스템이다: 미국·한국 지표로 매주 국면 점수를 매기고, 국내 상장 ETF 15종의 목표 비중을 정하며, KIS 모의 계좌에서 Slack 승인을 거쳐 주문하고, AI 레이어를 더한다. 전체 설계: [MACRO_ETF.md](MACRO_ETF.md).

| 단계 | 구축 내용 | 커리큘럼 단계 |
|-------|--------|------------------|
| M1 | PostgreSQL, 데이터 수집기(미국 연준 경제 데이터(FRED, Federal Reserve Economic Data), 한국은행 경제통계시스템(ECOS, Economic Statistics System), KIS), 시점 기준(point-in-time) 저장, 스케줄 작업, CI | Spring Boot + JPA, 스케줄링 |
| M2 | Python 퀀트 서비스: 국면 점수와 목표 비중 | Spring과 병행하는 Python |
| M3 | 백테스트와 리스크 분석 | Spring과 병행하는 Python |
| M4 | JSON 웹 토큰(JWT, JSON Web Token) 인증, 가상 연금 계좌, 제안, Slack 승인, KIS 모의 주문 | Spring Security, 외부 API |
| M5 | React 대시보드와 차트, 공개 데모 | React |
| M6 | RAG, 국면 설명기, 주간 리포트, LangGraph 파이프라인, 자연어 Q&A | RAG, LangChain, LangGraph |
| M7 | 클라우드 배포(OCI), 배포 워크플로, 백업 | Docker, CI/CD, 클라우드 |

이 트랙을 진행하면 위의 V3–V5는 선택 백로그가 된다: 공통 인프라(KIS 클라이언트, React, Docker, CI, 클라우드)는 M1–M7에서 한 번만 구축한다. 앱은 M1에서 MySQL에서 PostgreSQL로 옮긴다.

## 9. Phase 0 체크리스트 (MySQL)

기능 번호는 아래 순서대로 `P0-01`부터 `P0-07`까지이다([CONVENTION.md](CONVENTION.md) 참고).

- [x] MVP 테이블의 ERD 스케치 (`docs/erd.*`)
- [ ] DDL 작성: 기본 키(PK, Primary Key), 외래 키(FK, Foreign Key), `NOT NULL`, `UNIQUE`, `CHECK`(예: quantity > 0) → `db/01_schema.sql`
- [ ] 시드 데이터: KOSPI 종목 5–10개(예: 삼성전자 `005930`, SK하이닉스 `000660`), 회원 1명, 초기 현금 → `db/02_seed.sql`
- [ ] "10주 매수"를 하나의 트랜잭션으로: 현금, 주문, 체결, 보유(insert 또는 update), 원장 → `COMMIT`, 실패 시 `ROLLBACK` → `db/03_buy_sell.sql`
- [ ] "매도"를 하나의 트랜잭션으로, 보유 수량보다 많이 팔지 못하게 막기
- [ ] 포트폴리오 쿼리: 보유와 최신가를 조인해 평가액과 수익률 % 계산 → `db/04_portfolio.sql`
- [ ] 대사 쿼리: 매수·매도 스크립트 실행 후 각 쿼리의 불일치가 0건이어야 한다(§5 회계 규칙 참고) → `db/05_reconciliation.sql`
  - 일부러 데이터를 깨뜨리고(예: 복사본에서 원장 금액 하나를 바꿈) 쿼리가 보고하는 내용을 시차, 조정, 조사로 분류한다(설계 질문 7)
  - 설계 질문 3에 답한 뒤, `reserved`에 대한 대사 검사를 직접 정의해 같은 파일에 추가한다

## 10. 저장소 구조

```
ant-lab/
├── README.md
├── .github/         이슈와 풀 리퀘스트 템플릿
├── docs/
│   ├── PLAN.md      이 파일
│   ├── erd.md       MVP 테이블 ERD와 엔티티 정의서
│   ├── MACRO_ETF.md 매크로 ETF 트랙 설계 문서
│   └── CONVENTION.md Git 컨벤션
└── db/              직접 작성한 DDL, 시드 데이터, 실습 쿼리
```

Spring Boot 프로젝트는 Spring 챕터를 시작할 때 저장소 루트에 추가한다. 매크로 트랙은 이후 Python 서비스(M2부터)와 React 앱(M5부터)을 각각 최상위 폴더에 추가한다.

## 11. 학습 로그

단계마다 항목 하나: 무엇을 만들었고, 무엇이 어려웠고, 무엇을 배웠는지.

| 날짜 | 단계 | 메모 |
|------|-------|-------|
| 2026-10-17 | Plan | 한국 주식 모의투자로 결정, 저장소 생성 |
| 2026-11-01 | Plan | 매크로 ETF 트랙(M1–M7) 추가, 설계는 MACRO_ETF.md |
