# Ant Lab — Project Plan

A mock trading (모의투자) platform for Korean stocks. Users get virtual cash, buy and sell KOSPI/KOSDAQ stocks at simulated or real prices, and track their portfolio. Later versions add a trading journal, price alerts, and an AI layer over corporate filings.

This is a learning project. It grows alongside the [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus) bootcamp curriculum: each version uses what the current chapter teaches. All code is written by hand.

> The name "Ant Lab" (개미 연구소) is a working title and may change.

---

## 1. Goals

- Model a real domain: orders, fills, holdings, cash, with money that always adds up.
- Practice the curriculum on one growing codebase: SQL → JDBC → Spring Boot + JPA → React → RAG/LangChain → Docker/CI/CD.
- End up with a portfolio piece that shows domain modeling, consistency under concurrent requests, and an AI feature.

Non-goals: real money, real brokerage orders, investment advice.

## 2. Tech stack

| Area | Choice | Notes |
|------|--------|-------|
| Language | Java 21 | |
| Backend | Spring Boot 3.x, Gradle | Generated from start.spring.io when the Spring chapter starts |
| Database | MySQL 8 | PostgreSQL can be tried later in Docker |
| Mapping | JPA (main) | MyBatis optional for reporting/statistics queries |
| Test | JUnit 5, Spring Boot Test | Concurrency tests on orders |
| Frontend | React | V3 |
| AI | RAG, LangChain, LangGraph | V4 |
| Ops | Docker Compose, GitHub Actions, cloud | V5 |

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

### Design questions to answer before writing the DDL

1. Prices in won are whole numbers, but the average price usually is not. Which MySQL type fits each money column, and why is `DOUBLE` dangerous?
2. You hold 10 shares at ₩70,000 and buy 5 more at ₩76,000. What is the new average price? What happens to it when you sell 3?
3. You have ₩1,000,000 and a pending limit buy for ₩800,000. Can you place a ₩500,000 market buy now? What is `reserved` for?
4. What is the difference between realized and unrealized profit, and which tables does each come from?
5. Two sell orders for the same 10 shares arrive at the same moment. How do you stop both from succeeding?
6. Why keep `balance_after` on every ledger row when `cash_account.balance` already exists?

## 6. MVP user stories

1. I sign up and get ₩10,000,000 in virtual cash.
2. I see a list of stocks with current prices that change over time (simulated).
3. I place a market buy: cash goes down, I get the shares, and a fill is recorded — all or nothing.
4. I place a market sell: I cannot sell shares I do not own, and the realized profit is recorded.
5. I see my portfolio: quantity, average price, current value, and return % per holding, plus totals.
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
GET    /api/leaderboard             Ranking by return % (V3)
```

## 8. Roadmap

| Version | Features | Curriculum stage |
|---------|----------|------------------|
| Phase 0 | ERD, DDL, seed data, buy/sell as SQL transactions, portfolio query | MySQL (DDL, transactions) — now |
| Phase 1 | Console app with DAOs: buy, sell, portfolio | JDBC |
| MVP | REST API for the six user stories, layered architecture, validation, global exception handling | Spring Boot + JPA |
| V2 | Limit orders filled by a scheduled price check, cancel, watchlist, alerts, fees and tax | Spring (scheduling) |
| V3 | Real price data, leaderboard, trading journal, charts | External APIs, React |
| V4 | DART filing Q&A (RAG), trade-coach agent over the journal, natural-language portfolio questions | RAG, LangChain, LangGraph |
| V5 | Docker Compose (app + MySQL), GitHub Actions, cloud deployment | Docker, CI/CD, cloud |

Stretch goal: an order-book matching engine where users trade with each other.

## 9. Phase 0 checklist (MySQL)

- [ ] Sketch the ERD for the MVP tables (`docs/erd.*`)
- [ ] Write the DDL: PKs, FKs, `NOT NULL`, `UNIQUE`, `CHECK` (e.g. quantity > 0) → `db/01_schema.sql`
- [ ] Seed data: 5–10 KOSPI tickers (e.g. Samsung Electronics `005930`, SK hynix `000660`), one member, starting cash → `db/02_seed.sql`
- [ ] "Buy 10 shares" as one transaction: cash, order, execution, holding (insert or update), ledger → `COMMIT`, or `ROLLBACK` on failure → `db/03_buy_sell.sql`
- [ ] "Sell" as one transaction, refusing to sell more than held
- [ ] Portfolio query: holdings joined with latest prices, value and return % → `db/04_portfolio.sql`

## 10. Repo layout

```
ant-lab/
├── README.md
├── CLAUDE.md        Rules for Claude in this repo (tutoring, no solution code)
├── docs/
│   └── PLAN.md      This file
└── db/              Hand-written DDL, seed data, practice queries
```

The Spring Boot project is added at the repo root when the Spring chapter starts.

## 11. Learning log

One entry per phase: what was built, what was hard, what was learned.

| Date | Phase | Notes |
|------|-------|-------|
| 2026-10-17 | Plan | Chose Korean-stock mock trading; repo created |
