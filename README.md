# Ant Lab (개미 연구소)

Spring Boot, MySQL, 자바 영속성 API(JPA, Java Persistence API)로 만드는 한국 주식(KOSPI/KOSDAQ) 모의투자 플랫폼이다.

사용자는 가상 현금 ₩10,000,000을 받아 시뮬레이션 가격 또는 실제 가격으로 주식을 사고팔고, 포트폴리오의 손익을 추적한다. 이후 버전에서는 지정가 주문, 가격 알림, 매매 일지, 전자공시시스템(DART, Data Analysis, Retrieval and Transfer System)의 기업 공시 기반 인공지능(AI, Artificial Intelligence) 어시스턴트를 추가한다.

최소 기능 제품(MVP, Minimum Viable Product) 이후에는 **매크로 상장지수펀드(ETF, Exchange-Traded Fund) 트랙**에서 한국 연금 계좌(연금저축, 개인형 퇴직연금(IRP, Individual Retirement Pension))를 위한 국면 기반 자산배분 시스템을 추가한다: 미국·한국 매크로 지표를 매주 점수화해 네 가지 경제 "계절"로 분류하고, 국내 상장 ETF 15종의 목표 비중을 정하며, 한국투자증권(KIS, Korea Investment & Securities) 모의투자 계좌에서 Slack 승인을 거쳐 주문하고, 백테스트, 리스크 대시보드, AI 레이어(검색 증강 생성(RAG, Retrieval-Augmented Generation), LangGraph)를 갖춘다.

> 학습 프로젝트이며 투자 조언이 아니다. 실제 돈이나 실계좌 주문은 사용하지 않는다.

- 계획, 도메인, 로드맵: [docs/PLAN.md](docs/PLAN.md)
- 매크로 ETF 트랙 설계: [docs/MACRO_ETF.md](docs/MACRO_ETF.md)
- Git 컨벤션(이슈, 브랜치, 커밋, 풀 리퀘스트(PR, Pull Request)): [docs/CONVENTION.md](docs/CONVENTION.md)
- 이 프로젝트와 함께 성장하는 학습 저장소: [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus)

## 현황

| 단계 | 상태 |
|-------|-------|
| Phase 0 — MySQL 스키마와 트랜잭션 | 진행 중 |
| Phase 1 — 자바 데이터베이스 연결(JDBC, Java Database Connectivity) 콘솔 앱 | 시작 전 |
| MVP — Spring Boot 표현 상태 전송(REST, Representational State Transfer) 애플리케이션 프로그래밍 인터페이스(API, Application Programming Interface) | 시작 전 |
| M1–M7 — 매크로 ETF 트랙 | 계획됨 ([설계](docs/MACRO_ETF.md)) |

## 기술 스택

Java 21 · Spring Boot 3 · MySQL 8 → PostgreSQL 16 + pgvector · JPA (MyBatis 선택) · Python (FastAPI, pandas) · React (이후) · RAG / LangChain / LangGraph (이후) · Docker, GitHub Actions, 오라클 클라우드 인프라(OCI, Oracle Cloud Infrastructure) (이후)
