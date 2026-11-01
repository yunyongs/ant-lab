# Ant Lab — Project Plan

A mock trading (모의투자) platform for Korean stocks. Users get virtual cash, buy and sell KOSPI/KOSDAQ stocks at simulated or real prices, and track their portfolio. Later versions add a trading journal, price alerts, and an AI layer over corporate filings.

This is a learning project. It grows alongside the [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus) bootcamp curriculum: each version uses what the current chapter teaches. All code is written by hand through the MVP; in the macro ETF track the owner writes the core logic and reviews every file (see [MACRO_ETF.md §9.1](MACRO_ETF.md#91-who-writes-what)).

> The name "Ant Lab" (개미 연구소) is a working title and may change.

---

## 1. Goals

- Model a real domain: orders, fills, holdings, cash, with money that always adds up.
- Practice the curriculum on one growing codebase: SQL → JDBC → Spring Boot + JPA → React → RAG/LangChain → Docker/CI/CD.
- End up with a portfolio piece that shows domain modeling, consistency under concurrent requests, and an AI feature.

Non-goals: real money, real-money brokerage orders (the macro track sends orders only to a KIS mock-trading account), investment advice.

## 2. Tech stack

| Area | Choice | Notes |
|------|--------|-------|
| Language | Java 21 | |
| Backend | Spring Boot 3.x, Gradle | Generated from start.spring.io when the Spring chapter starts |
| Database | MySQL 8 → PostgreSQL 16 | MySQL for Phase 0–MVP (curriculum); PostgreSQL with pgvector from the macro track (M1) |
| Mapping | JPA (main) | MyBatis optional for reporting/statistics queries |
| Test | JUnit 5, Spring Boot Test | Concurrency tests on orders |
| Quant and AI service | Python, FastAPI, pandas | Macro track M2 |
| Frontend | React | M5 (was V3) |
| AI | RAG, LangChain, LangGraph | M6 (was V4) |
| Ops | Docker Compose, GitHub Actions, OCI | CI from M1, deployment M7 (was V5) |

## 3. Korean market rules

| Rule | Real market | MVP | Later |
|------|-------------|-----|-------|
| Trading hours | 09:00–15:30 KST, weekdays, closed on holidays | Ignored (trade any time) | Reject or queue orders outside hours |
| Tick size (호가단위) | Allowed price steps depend on the price range | Not needed (market orders only) | Validate limit prices |
| Daily price limit | ±30% from the previous close | Price generator stays inside ±30% | Reject limit orders outside the band |
| Fees and tax | Broker fee on buy/sell, transaction tax on sell | Configurable rates, default 0 | Real rates (check the current tax rate when implementing) |
| Settlement | T+2 | Instant | Optional: "available to withdraw" vs "available to trade" |
| Order unit | 1 share, no fractions | Whole-number quantity | Same |

## 4. Price data sources

| Source | Pros | Cons | When |
|--------|------|------|------|
| Simulated generator (random walk) | No API key, offline, adjustable speed | Not real prices | MVP |
| 공공데이터포털 (data.go.kr) 주식시세정보 | Free, official daily prices | One day behind | V2 |
| 한국투자증권 KIS Developers Open API | Real-time quotes (REST + WebSocket), mock-trading mode | Account and token management | V3 |
| DART Open API | Official corporate filings | Text-heavy | V4 (AI) |

Design rule: the order logic must not know where prices come from. Swapping the simulated source for a real one should not touch order code.

## 5. Domain (MVP tables)

Key columns only; the DDL is written by hand in `db/`.

| Table | Key columns |
|-------|-------------|
| `member` | id, email (unique), name, password, created_at |
| `cash_account` | member_id, balance, reserved, version |
| `stock` | id, ticker (unique, e.g. `005930`), name, market (KOSPI/KOSDAQ) |
| `stock_price` | stock_id, price, traded_at |
| `orders` | id, member_id, stock_id, side (BUY/SELL), type (MARKET/LIMIT), quantity, limit_price, status (PENDING/FILLED/CANCELED/REJECTED), created_at |
| `execution` | id, order_id, fill_price, quantity, fee, tax, executed_at |
| `holding` | member_id, stock_id, quantity, avg_price — unique (member_id, stock_id) |
| `cash_ledger` | id, member_id, type, amount, balance_after, ref_order_id, created_at |

Later: `watchlist`, `alert_rule`, `journal_entry`.

### Accounting rules

- **Cost basis:** decide how the average price changes on a buy and on a sell (design question 2) and record the rule here before writing `db/03_buy_sell.sql`.
- **Rounding:** decide the column type and decimal places of each money column (design question 1) and record them here. Fees and tax are computed at full precision, then rounded once, by one rule, in one place in the code (check the broker's exact rule when real rates are added in V2).
- **Daily P&L breakdown:** with no deposits or withdrawals, today's change in portfolio value (cash + holdings at today's close) splits into three parts that must add up to the total:
  - price effect = (today's close − yesterday's close) × yesterday's quantity,
  - trading effect = Σ over today's sells of (fill price − today's close) × quantity + Σ over today's buys of (today's close − fill price) × quantity,
  - costs = − (fees + tax).

  Realized profit is a different number: it counts the gain since purchase, much of which earlier days' valuations already showed, so it is reported on its own (user story 4) and never added to the daily breakdown. User story 5 shows this breakdown.
- **Reconciliation:** the books must agree with themselves (Phase 0 item `P0-07`, then a daily job in the MVP): the sum of `cash_ledger.amount` equals `cash_account.balance`, the latest `balance_after` equals the balance, and each holding's quantity equals its buy fills minus its sell fills. Every difference is classified as timing, adjustment or investigate (design question 7), and an error is corrected by adding a new ledger row, never by editing an old one.

### Design questions to answer before writing the DDL

1. Prices in won are whole numbers, but the average price usually is not. Which MySQL type fits each money column, and why is `DOUBLE` dangerous?
2. You hold 10 shares at ₩70,000 and buy 5 more at ₩76,000. What is the new average price? What happens to it when you sell 3?
3. You have ₩1,000,000 and a pending limit buy for ₩800,000. Can you place a ₩500,000 market buy now? What is `reserved` for?
4. What is the difference between realized and unrealized profit, and which tables does each come from?
5. Two sell orders for the same 10 shares arrive at the same moment. How do you stop both from succeeding?
6. Why keep `balance_after` on every ledger row when `cash_account.balance` already exists?
7. Your reconciliation finds that the ledger total and `cash_account.balance` differ by ₩1. List three possible causes, and for each say whether it is a timing difference (clears by itself), an adjustment (needs a correcting entry) or something to investigate.

## 6. MVP user stories

Feature numbers `MVP-01` to `MVP-06`, in the order below (see [CONVENTION.md](CONVENTION.md)).

1. I sign up and get ₩10,000,000 in virtual cash.
2. I see a list of stocks with current prices that change over time (simulated).
3. I place a market buy: cash goes down, I get the shares, and a fill is recorded — all or nothing.
4. I place a market sell: I cannot sell shares I do not own, and the realized profit is recorded.
5. I see my portfolio: quantity, average price, current value, and return % per holding, plus totals, and what drove today's change (price effect, trading effect, costs).
6. I see my order history and cash history.

## 7. API draft (from the Spring phase)

```
POST   /api/members                 Sign up (creates cash account with ₩10M)
GET    /api/stocks                  Stock list with latest price
GET    /api/stocks/{ticker}         Stock detail and recent prices
POST   /api/orders                  Place order (side, type, ticker, quantity, limitPrice)
GET    /api/orders                  My orders (status, period, paging)
DELETE /api/orders/{id}             Cancel a pending order (V2)
GET    /api/portfolio               Holdings, values, returns, totals
GET    /api/cash/ledger             Cash history (paging)
POST   /api/watchlist               Add to watchlist (V2)
POST   /api/alerts                  Create price alert (V2)
GET    /api/leaderboard             Ranking by time-weighted return (V3)
```

Leaderboard returns are time-weighted (TWR) so that deposits and withdrawals do not distort the ranking; each member's own page also shows the money-weighted return (MWR), the return on their own cash flows.

## 8. Roadmap

| Version | Features | Curriculum stage |
|---------|----------|------------------|
| Phase 0 | ERD, DDL, seed data, buy/sell as SQL transactions, portfolio query | MySQL (DDL, transactions) — now |
| Phase 1 | Console app with DAOs: buy, sell, portfolio | JDBC |
| MVP | REST API for the six user stories, layered architecture, validation, global exception handling, daily reconciliation job that records each difference as a timing, adjustment or investigate item (same structure as [MACRO_ETF.md §6.6](MACRO_ETF.md#66-daily-reconciliation)) | Spring Boot + JPA |
| V2 | Limit orders filled by a scheduled price check, cancel, watchlist, alerts, fees and tax | Spring (scheduling) |
| V3 | Real price data, leaderboard (TWR), trading journal, charts | External APIs, React |
| V4 | DART filing Q&A (RAG), trade-coach agent over the journal, natural-language portfolio questions | RAG, LangChain, LangGraph |
| V5 | Docker Compose (app + MySQL), GitHub Actions, cloud deployment | Docker, CI/CD, cloud |

Stretch goal: an order-book matching engine where users trade with each other.

### Macro ETF track (after the MVP)

A macro-driven ETF allocation system for 연금저축 and IRP: weekly regime scoring from US and Korean indicators, target weights for 15 Korean-listed ETFs, Slack-approved orders on a KIS mock account, and an AI layer. Full design: [MACRO_ETF.md](MACRO_ETF.md).

| Phase | Builds | Curriculum stage |
|-------|--------|------------------|
| M1 | PostgreSQL, data collectors (FRED, ECOS, KIS), point-in-time storage, scheduled jobs, CI | Spring Boot + JPA, scheduling |
| M2 | Python quant service: regime scoring and targets | Python alongside Spring |
| M3 | Backtest and risk analytics | Python alongside Spring |
| M4 | JWT auth, virtual pension accounts, proposals, Slack approval, KIS mock orders | Spring Security, external APIs |
| M5 | React dashboard and charts, public demo | React |
| M6 | RAG, regime explainer, weekly report, LangGraph pipeline, natural-language Q&A | RAG, LangChain, LangGraph |
| M7 | Cloud deployment (OCI), deploy workflow, backups | Docker, CI/CD, cloud |

With this track, V3–V5 above become an optional backlog: their shared infrastructure (KIS client, React, Docker, CI, cloud) is built once in M1–M7. The app moves from MySQL to PostgreSQL at M1.

## 9. Phase 0 checklist (MySQL)

Feature numbers `P0-01` to `P0-07`, in the order below (see [CONVENTION.md](CONVENTION.md)).

- [ ] Sketch the ERD for the MVP tables (`docs/erd.*`)
- [ ] Write the DDL: PKs, FKs, `NOT NULL`, `UNIQUE`, `CHECK` (e.g. quantity > 0) → `db/01_schema.sql`
- [ ] Seed data: 5–10 KOSPI tickers (e.g. Samsung Electronics `005930`, SK hynix `000660`), one member, starting cash → `db/02_seed.sql`
- [ ] "Buy 10 shares" as one transaction: cash, order, execution, holding (insert or update), ledger → `COMMIT`, or `ROLLBACK` on failure → `db/03_buy_sell.sql`
- [ ] "Sell" as one transaction, refusing to sell more than held
- [ ] Portfolio query: holdings joined with latest prices, value and return % → `db/04_portfolio.sql`
- [ ] Reconciliation queries: each must return zero mismatches after the buy and sell scripts (see Accounting rules in §5) → `db/05_reconciliation.sql`
  - Break the data on purpose (for example change one ledger amount in a copy) and classify what the queries report: timing, adjustment or investigate (design question 7)
  - After answering design question 3, define a reconciliation check for `reserved` yourself and add it to the same file

## 10. Repo layout

```
ant-lab/
├── README.md
├── .github/         Issue and pull-request templates
├── docs/
│   ├── PLAN.md      This file
│   ├── MACRO_ETF.md Design doc for the macro ETF track
│   └── CONVENTION.md Git conventions
└── db/              Hand-written DDL, seed data, practice queries
```

The Spring Boot project is added at the repo root when the Spring chapter starts. The macro track later adds a Python service (from M2) and a React app (from M5) in their own top-level folders.

## 11. Learning log

One entry per phase: what was built, what was hard, what was learned.

| Date | Phase | Notes |
|------|-------|-------|
| 2026-10-17 | Plan | Chose Korean-stock mock trading; repo created |
| 2026-11-01 | Plan | Added the macro ETF track (M1–M7), design in MACRO_ETF.md |
