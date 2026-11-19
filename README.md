# my Fund Manager

[![api](https://github.com/yunyongs/ant-lab/actions/workflows/api.yml/badge.svg)](https://github.com/yunyongs/ant-lab/actions/workflows/api.yml)
[![engine](https://github.com/yunyongs/ant-lab/actions/workflows/engine.yml/badge.svg)](https://github.com/yunyongs/ant-lab/actions/workflows/engine.yml)
[![web](https://github.com/yunyongs/ant-lab/actions/workflows/web.yml/badge.svg)](https://github.com/yunyongs/ant-lab/actions/workflows/web.yml)

A portfolio-manager training game. You act as the manager of a virtual team of real stocks and ETFs, step through one month at a time over recombined real market history, and are scored on risk-adjusted results and process, not on raw returns.
포트폴리오 매니저 훈련 게임. 실제 종목(국내·미국 주식과 ETF)으로 가상의 팀을 짜고, 실제 과거 달을 재조합한 시간을 한 달씩 넘기며 운용한다. 성적은 수익률이 아니라 위험 조정 성과와 과정 점수로 매긴다.

> A learning project, not investment advice. The game never uses real money or real brokerage orders. 학습 프로젝트이며 투자 조언이 아니다. 실제 돈이나 실계좌 주문은 쓰지 않는다.

## Features / 특징

- **Season play**: formations and positions, event cards, news-like noise, rankings and alerts, report cards. 시즌 한 판: 포메이션·포지션, 사건 카드, 소음 피드, 순위·알림, 성적표.
- **Scenario engine** (Python): month-block bootstrap with calendar alignment, projection bands, regime scoring. 시나리오 엔진: 달 블록 부트스트랩, 예상 분포 밴드, 국면 채점.
- **Agent managers**: LLM agents play under the same rules through an internal runner API (simulation only). 에이전트 매니저: LLM 에이전트가 같은 규칙으로 참가(모의 안에서만).
- **Operations**: JWT auth, Prometheus and Grafana dashboards, Flyway migrations, CI with runtime smoke tests. 운영: JWT 인증, 모니터링, 마이그레이션, CI.

## Stack / 기술 스택

| Part | Tech |
|---|---|
| API | Java 21, Spring Boot 4.1, JPA, Flyway, PostgreSQL 17 |
| Engine | Python 3.13, FastAPI, NumPy |
| Agent runner | Python (`ai/`), OpenRouter / Ollama adapters |
| Web | React, TypeScript, Vite |
| Ops | Docker Compose, Prometheus, Grafana, GitHub Actions |

## Quick start / 빠른 시작

Requirements: Docker, JDK 21; Node 26 for the web app, [uv](https://docs.astral.sh/uv/) for Python. 필요: Docker, JDK 21, (웹) Node 26, (Python) uv.

```bash
git clone https://github.com/yunyongs/ant-lab.git && cd ant-lab

# 1. Secrets stay in a git-ignored .env (see .env.example for all names)
echo "ANTLAB_JWT_SECRET=$(openssl rand -base64 32)" > .env

# 2. Database + API
docker compose --profile api up -d --build --wait db api
curl -fsS http://127.0.0.1:8080/readyz          # {"status":"UP"}

# 3. Web app (another terminal)
cd web && npm ci && npm run dev                  # http://localhost:5173
```

The API container uses an empty database created by Flyway. Sign up in the web app, start a season, and advance turns. No API key is needed; real market data and the agent runner are optional extras. 빈 DB는 Flyway가 만든다. 웹에서 가입하고 시즌을 시작해 턴을 넘기면 된다. API 키는 필요 없다.

Optional: `docker compose --profile monitoring up -d --wait` starts Prometheus and Grafana (needs `GF_SECURITY_ADMIN_PASSWORD` in `.env`); `--profile engine` starts the Python engine (needs `ANTLAB_ENGINE_TOKEN`). 선택: 모니터링·엔진 프로필.

## Tests / 시험

```bash
./gradlew test                       # Java (Testcontainers; some golden tests skip without sample data)
cd engine && uv run --frozen pytest  # Python engine
cd ai && uv run --frozen pytest      # agent runner
cd web && npm run verify             # lint, types, tests, build
```

## Project layout / 구조

| Path | Contents |
|---|---|
| `src/` | Spring Boot API |
| `engine/` | Python scenario engine |
| `ai/` | Agent runner and model adapters |
| `web/` | React web app |
| `db/` | Schema notes, sample SQL |
| `monitoring/` | Prometheus and Grafana config |
| `docs/` | Plan, design, ERD, API contracts (`docs/api/`) |

## Documentation / 문서

[Plan](docs/PLAN.md) · [Spring and JPA design](docs/SPRING_JPA.md) · [ERD](docs/erd.md) · [Deployment](docs/DEPLOY.md) · [Git conventions](docs/CONVENTION.md) · [Issue plan](docs/ISSUES.md) · [Engine methodology](docs/MACRO_ETF.md)

## Status / 현황

| Area | State |
|---|---|
| One-season API, JWT auth, web app, Grafana monitoring | Done |
| Observability (Grafana: service, AI usage, agents) | In progress |
| Python engine (history reader, paths, projections, regime scoring) | In progress |
| Agent runner (server side and Python client) | In progress |
| Data collection (KIS prices), full history load, scoring and ratings | In progress / planned |
| Desktop and mobile apps, real-money operation | Planned |

Details and roadmap: [docs/PLAN.md](docs/PLAN.md#11-로드맵커리큘럼-순서-유지). 자세한 로드맵은 PLAN을 본다.

## License / 라이선스

Not chosen yet. 아직 정하지 않았다.
