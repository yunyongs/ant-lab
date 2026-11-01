# Ant Lab (개미 연구소)

A mock trading platform for Korean stocks (KOSPI/KOSDAQ), built with Spring Boot, MySQL and JPA.

Users get ₩10,000,000 in virtual cash, buy and sell stocks at simulated or real prices, and track their portfolio's profit and loss. Later versions add limit orders, price alerts, a trading journal, and an AI assistant over corporate filings (DART).

After the MVP, a **macro ETF track** adds a regime-based allocation system for Korean pension accounts (연금저축, IRP): weekly scoring of US and Korean macro indicators into four economic "seasons", target weights for 15 Korean-listed ETFs, Slack-approved orders on a KIS mock-trading account, backtests, risk dashboards, and an AI layer (RAG, LangGraph).

> Learning project, not investment advice. No real money or brokerage orders are involved.

- Plan, domain, and roadmap: [docs/PLAN.md](docs/PLAN.md)
- Macro ETF track design: [docs/MACRO_ETF.md](docs/MACRO_ETF.md)
- Git conventions (issues, branches, commits, PRs): [docs/CONVENTION.md](docs/CONVENTION.md)
- Learning repo this project grows with: [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus)

## Status

| Phase | State |
|-------|-------|
| Phase 0 — MySQL schema and transactions | In progress |
| Phase 1 — JDBC console app | Not started |
| MVP — Spring Boot REST API | Not started |
| M1–M7 — Macro ETF track | Planned ([design](docs/MACRO_ETF.md)) |

## Stack

Java 21 · Spring Boot 3 · MySQL 8 → PostgreSQL 16 + pgvector · JPA (MyBatis optional) · Python (FastAPI, pandas) · React (later) · RAG / LangChain / LangGraph (later) · Docker, GitHub Actions, OCI (later)
