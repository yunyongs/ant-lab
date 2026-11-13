# web — one-season web app / 한 판 웹 앱

TypeScript React app built with Vite. It has the app shell, router, API status line and dev proxy (WEB-01), the typed API client (WEB-02), signup, login and session handling (WEB-03), the season list and season start (WEB-04), team building at turn 0 (WEB-05), turn advance with the turn result (WEB-06), between-turn trading and formation change (WEB-07), the report card of a finished season (WEB-08), the season review (WEB-09), and the real-browser end-to-end play (WEB-10).
Vite로 빌드하는 TypeScript React 앱이다. 앱 틀·라우터·API 상태 한 줄·개발 프록시(WEB-01), 타입 있는 API 클라이언트(WEB-02), 가입·로그인·세션 처리(WEB-03), 시즌 목록·시즌 시작(WEB-04), 0턴 팀 구성(WEB-05), 턴 넘기기와 턴 결과(WEB-06), 턴 사이 매매·전술 변경(WEB-07), 끝난 시즌의 성적표(WEB-08), 시즌 복기(WEB-09), 실제 브라우저 종단 플레이(WEB-10)가 들어 있다.

## Prerequisites / 준비

- Node from `.nvmrc` (major 26) and the npm bundled with it. `engine-strict=true`, so another major fails `npm ci`.
- `.nvmrc`의 Node(주 판 26)와 함께 오는 npm. `engine-strict=true`라서 다른 주 판이면 `npm ci`가 실패한다.
- VS Code extensions: ESLint (`dbaeumer.vscode-eslint`), Prettier (`esbenp.prettier-vscode`), EditorConfig (`EditorConfig.EditorConfig`), Vitest (`vitest.explorer`).
- VS Code 확장: ESLint, Prettier, EditorConfig, Vitest(위 ID).

## Run / 실행

Start the API first (see the root README), then:
먼저 API를 띄운 뒤(루트 README 참고) 실행한다.

```bash
cd web
npm ci
npm run dev
```

Open <http://localhost:5173>. Always use `localhost`, not `127.0.0.1`: cookies are stored per host, so mixing the two loses the session later. In development React StrictMode runs effects twice, so the API log shows two `/api/v1/pool` calls; that is expected.
<http://localhost:5173>을 연다. 항상 `localhost`로 열고 `127.0.0.1`은 쓰지 않는다. 쿠키가 호스트별로 저장되어 섞어 쓰면 나중에 로그인이 사라진다. 개발 모드에서는 React StrictMode가 효과를 두 번 실행하므로 API 로그에 `/api/v1/pool` 호출이 두 번 보이는 것이 정상이다.

## Scripts / 스크립트

| Script                    | What it does / 하는 일                                                                                                                                                                                                                                        |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dev` / `preview`         | Vite dev server / serve the built app, both with the API proxy                                                                                                                                                                                                |
| `build`                   | `tsc -b && vite build` into `dist/`                                                                                                                                                                                                                           |
| `lint`                    | `eslint .`                                                                                                                                                                                                                                                    |
| `format` / `format:check` | Prettier write / check                                                                                                                                                                                                                                        |
| `typecheck`               | `tsc -b`                                                                                                                                                                                                                                                      |
| `test` / `test:watch`     | `vitest run` / `vitest`                                                                                                                                                                                                                                       |
| `verify`                  | lint → format:check → typecheck → test → build, stops on first failure                                                                                                                                                                                        |
| `gen:api`                 | regenerates `src/api/schema.d.ts` from `../docs/api/openapi.yaml` (see below)                                                                                                                                                                                 |
| `e2e`                     | real-browser play of one season at 375×812 and 1280×800 against a running API (`ANTLAB_API_PORT` required); see «End-to-end check» / 실행 중인 API를 상대로 375×812와 1280×800에서 실제 브라우저로 한 시즌을 둔다(`ANTLAB_API_PORT` 필수). 「종단 확인」 참고 |

`cd web && npm ci && npm run verify` needs no backend, no secrets and no fixed ports.
`cd web && npm ci && npm run verify`는 백엔드·비밀값·고정 포트가 필요 없다.

`gen:api` — regenerates `src/api/schema.d.ts` from `../docs/api/openapi.yaml`; commit the result. `npm test` fails when it is stale.
`gen:api` — `../docs/api/openapi.yaml`에서 `src/api/schema.d.ts`를 다시 만든다. 결과를 커밋한다. 낡으면 `npm test`가 실패한다.

## Proxy / 프록시

Only `/api` is forwarded, unchanged, to `http://127.0.0.1:8080`. For an API on another port: `ANTLAB_API_PORT=18080 npm run dev` (an integer from 1 to 65535, otherwise the server refuses to start). The value is read by the Node-side Vite config only; it never reaches the browser bundle.
`/api`만 그대로 `http://127.0.0.1:8080`으로 넘긴다. 다른 포트의 API는 `ANTLAB_API_PORT=18080 npm run dev`로 지정한다(1–65535 정수, 아니면 서버가 시작하지 않는다). 값은 Node 쪽 Vite 설정만 읽고 브라우저 번들에는 들어가지 않는다.

- Target `127.0.0.1`, not `localhost`: the API binds IPv4 loopback and `localhost` may resolve to `::1` first.
- 대상은 `localhost`가 아니라 `127.0.0.1`이다. API가 IPv4 루프백에 붙고 `localhost`는 `::1`로 먼저 풀릴 수 있다.
- `changeOrigin: false`: the browser `Host` is kept, so a `Location` header the API builds from the request stays same-origin; `Set-Cookie` passes through unchanged.
- `changeOrigin: false`: 브라우저의 `Host`가 유지되어 API가 요청 `Host`로 만드는 `Location`이 같은 출처로 남고, `Set-Cookie`는 그대로 전달된다.
- The dev server binds `localhost:5173` only (`strictPort`); `/readyz` and Actuator are not proxied.
- 개발 서버는 `localhost:5173`에만 붙고(`strictPort`) `/readyz`와 Actuator는 넘기지 않는다.

## Login and session / 로그인과 세션

- Sign up at `/signup` (the app logs you in right after) or log in at `/login`. Every other page needs a login.
- `/signup`에서 가입하거나(가입하면 바로 로그인된다) `/login`에서 로그인한다. 다른 화면은 모두 로그인이 필요하다.
- The access token lives only in memory. The refresh token is an HttpOnly cookie that the API sets for `/api/v1/auth`; scripts cannot read it. A reload asks `/api/v1/auth/refresh` once for a new access token.
- 접근 토큰은 메모리에만 있다. 갱신 토큰은 API가 `/api/v1/auth` 경로로 설정하는 HttpOnly 쿠키라 스크립트가 읽을 수 없다. 새로 고치면 `/api/v1/auth/refresh`에 한 번 요청해 새 접근 토큰을 받는다.
- When a call answers 401, the client refreshes once and repeats the call; a second 401 goes back to the login screen. Refresh, login and logout are serialised across tabs with the Web Locks API, because the server treats a second use of a rotated refresh token as theft and ends the login.
- 호출이 401이면 클라이언트가 한 번 갱신하고 같은 호출을 다시 보낸다. 다시 401이면 로그인 화면으로 간다. 서버는 회전된 갱신 토큰을 두 번 쓰면 도난으로 보고 로그인을 끝내므로, 갱신·로그인·로그아웃은 Web Locks API로 탭 사이에서도 한 번에 하나씩 실행한다.
- Logging out in one tab logs out every open tab.
- 한 탭에서 로그아웃하면 열린 탭이 모두 로그아웃된다.
- Until the API checks bearer tokens (SEC-04) and uses the logged-in member (SEC-05), season calls work with or without a login and act as the fixed member.
- API가 Bearer 토큰을 검사하고(SEC-04) 로그인한 회원을 쓰기(SEC-05) 전까지 시즌 호출은 로그인과 상관없이 동작하며 고정 회원으로 처리된다.

## Play / 플레이

- The home page lists your seasons with their status and current turn. **새 시즌 시작** opens the start form: pick a risk limit (−10 to −40 %), set the four formation shares (whole numbers, sum 100, goalkeeper at least 1) and a length of 12, 24 or 60 turns.
- 홈 화면에 내 시즌이 상태·현재 턴과 함께 나온다. **새 시즌 시작**을 누르면 시작 폼이 열린다. 위험 선언(−10~−40%)을 고르고, 포메이션 네 칸(정수, 합 100, 골키퍼 1 이상)과 길이(12·24·60턴)를 정한다.
- Money and ratios are shown exactly as the API sends them (decimal strings); the app only adds thousands separators and percent signs.
- 금액과 비율은 API가 보낸 문자열 그대로 보여 준다. 앱은 천 단위 구분 기호와 퍼센트 표시만 더한다.
- At turn 0 the season page shows the asset pool with each asset's system position and its basis. Tap **선택** to buy: choose a declared position for a new holding and a whole-number quantity. After each buy the page shows the server's fill (price, amount, fee, cash) and the refreshed weights against your formation; nothing is estimated in the browser.
- 0턴의 시즌 화면에는 종목 풀이 종목마다 시스템 판정 포지션과 그 근거와 함께 나온다. **선택**을 눌러 산다. 새로 사는 종목은 선언 포지션을 정하고 수량은 정수로 적는다. 살 때마다 서버의 체결 결과(가격·대금·수수료·현금)와 다시 받은 포메이션 대비 비중을 보여 준다. 브라우저에서 미리 계산하는 값은 없다.
- **다음 턴** sends the current turn number once; the server applies the next month and checks the books before it moves on. The result card shows total value, change, turn return, drawdown and the room left to your risk limit (over the limit is marked, and the season goes on), plus weights against the formation. If the books do not match, nothing changes and you can try again. Earlier turns open from **지난 턴 보기**. After the last turn the season is finished; report and review come next.
- **다음 턴**은 지금 턴 번호를 한 번만 보낸다. 서버가 다음 달을 적용하고 장부를 맞춰 본 뒤에 턴을 넘긴다. 결과 카드에는 총 평가액·변화·턴 수익률·낙폭·위험 선언까지 남은 여유(넘으면 표시되고 시즌은 계속된다)와 포메이션 대비 비중이 나온다. 장부가 맞지 않으면 아무것도 바뀌지 않고 다시 시도할 수 있다. 지난 턴은 **지난 턴 보기**로 연다. 마지막 턴을 넘기면 시즌이 끝나고 성적표·복기로 이어진다.
- **매매** shows your live holdings and cash at this turn's prices. Buy more or sell from each holding (never more than you hold), or buy a new asset from the pool. From turn 1 on, **전술 변경** sets a new formation (whole numbers, sum 100, goalkeeper at least 1) with an optional one-line reason; orders placed later in the same turn are tied to that change and show it in their result. A sell shows the realized profit or loss, and selling everything closes that holding. Rebalancing is up to you: the app never places orders for you.
- **매매**에는 이번 턴 가격 기준의 보유와 현금이 바로 나온다. 보유 종목마다 더 사거나 팔 수 있고(보유한 수량까지만), 종목 풀에서 새 종목을 살 수 있다. 1턴부터는 **전술 변경**으로 새 포메이션(정수, 합 100, 골키퍼 1 이상)과 선택 사유 한 줄을 정한다. 같은 턴에 이어서 낸 주문은 그 변경에 묶이고 체결 결과에 그렇게 표시된다. 매도 결과에는 실현 손익이 나오고, 전량을 팔면 그 종목의 보유가 닫힌다. 비중 맞추기는 직접 한다. 앱이 대신 주문하지 않는다.
- **성적표** opens after the last turn: results against the benchmark, risk compliance, process (turnover, formation deviation, role fit, encouraged trades, formation changes), costs and the reconciliation record, all as the server computed them. A cell that cannot be computed says why. Before the season ends the screen only says how far the season is.
- **성적표**는 마지막 턴 뒤에 열린다. 기준선 대비 결과, 위험 준수, 과정(회전율·포메이션 이탈·역할 수행·독려 매매·전술 변경), 비용, 대사 기록을 서버가 계산한 값 그대로 보여 준다. 계산할 수 없는 칸에는 이유가 나온다. 시즌이 끝나기 전에는 시즌이 어디까지 왔는지만 알려 준다.
- **복기** opens after the last turn and is the only screen with real dates: the year-month behind each turn, that month's summary and events with sources, the value at each turn, your formation changes with their reasons, and the seed, engine version and data date that reproduce the path. Before the season ends it stays closed, because knowing the months would change your decisions.
- **복기**는 마지막 턴 뒤에 열리며 실제 날짜가 나오는 유일한 화면이다. 각 턴의 실제 연월, 그 달의 요약과 사건(출처 포함), 턴마다의 평가액, 전술 변경과 그 사유, 경로를 다시 만드는 시드·엔진 버전·데이터 기준일이 나온다. 달을 알면 판단이 달라지므로 시즌이 끝나기 전에는 닫혀 있다.

## End-to-end check / 종단 확인

`scripts/web_e2e.sh` (from the repository root) starts a throwaway database and API container in the project `antlab-e2e` (ports 55476 and 18096) with a random one-run signing key, loads the sample (`ANTLAB_GOLDEN_SAMPLE_DIR`, default `db/sample`), installs Chromium for the test runner if needed, and runs `npm run e2e`: sign up, start a 12-turn season, build the team, change the formation and sell once at turn 2, play to the end, open the report and the review, log out — once at phone width and once at desktop width. It fails on any console error, any critical accessibility violation, a date on screen before the season ends, horizontal scrolling, or a token in browser storage, and it removes the project afterwards (also on failure; `--keep` leaves it after a pass). Screenshots of every step go to `ANTLAB_E2E_OUT_DIR` (default `web/test-results/`, ignored by git). Needs Docker, `psql` 16 or later, Python 3 and the sample CSVs, so it runs locally, not in CI. To run it against an API you already started, set `ANTLAB_API_PORT` and run `npm run e2e` in `web/`; this adds a test member and a finished season to that database. A failed run also keeps a Playwright trace in `artifacts/` of that folder; it contains the run's short-lived tokens and the test member's password, so never attach it to an issue or PR (it is ignored by git). To build the API from another checkout, set `ANTLAB_E2E_API_DIR` to its root.
`scripts/web_e2e.sh`(저장소 루트에서)는 프로젝트 `antlab-e2e`(포트 55476, 18096)에 일회용 DB와 API 컨테이너를, 실행 한 번에만 쓰는 임의 서명 키로 띄우고, 표본(`ANTLAB_GOLDEN_SAMPLE_DIR`, 기본 `db/sample`)을 적재하고, 필요하면 시험용 Chromium을 설치한 뒤 `npm run e2e`를 실행한다: 가입, 12턴 시즌 시작, 팀 구성, 2턴에 전술 변경과 매도 한 번, 끝까지 진행, 성적표와 복기 열기, 로그아웃 — 휴대폰 폭과 데스크톱 폭에서 한 번씩. 콘솔 오류, 치명적 접근성 위반, 시즌이 끝나기 전 화면의 날짜, 가로 스크롤, 브라우저 저장소의 토큰 중 하나라도 있으면 실패하고, 끝나면(실패해도) 프로젝트를 지운다(`--keep`이면 통과 뒤 남긴다). 단계마다 찍은 화면은 `ANTLAB_E2E_OUT_DIR`(기본 `web/test-results/`, git 무시)에 저장된다. Docker, `psql` 16 이상, Python 3, 표본 CSV가 필요해서 CI가 아니라 로컬에서만 돈다. 이미 띄운 API를 상대로 돌리려면 `ANTLAB_API_PORT`를 정하고 `web/`에서 `npm run e2e`를 실행한다. 그 DB에 시험용 회원과 끝난 시즌이 하나씩 더해진다. 실패한 실행은 그 폴더의 `artifacts/`에 Playwright trace도 남긴다. 그 실행의 짧은 수명 토큰과 시험 회원 비밀번호가 들어 있으므로 이슈나 PR에 첨부하지 않는다(git 무시). 다른 체크아웃의 API를 빌드하려면 `ANTLAB_E2E_API_DIR`에 그 루트를 준다.

## Folder layout / 폴더 구조

```text
src/app/       providers, router, shell, queryClient / 공급자·라우터·틀
src/pages/     route views / 경로별 화면
src/features/  feature components, hooks, small fetchers / 기능별 코드 (health: 임시 상태 조회, auth: 세션·갱신·로그인)
src/styles/    tokens.css (design tokens), reset, global / 디자인 토큰·초기화
src/test/      test setup, request mocks, render helpers / 시험 설정·요청 모의·렌더 도우미
src/api/       generated API types, typed client, error catalog / 생성된 API 타입·클라이언트·오류 목록
```

## Rules / 규칙

- Show server values as they are. Money is a decimal string: no `Number(…)`, `parseFloat` (lint error). 서버 값을 그대로 보여 준다. 금액은 십진 문자열이라 `Number(…)`·`parseFloat`을 쓰면 린트 오류다.
- API types and the client come from `src/api/` (generated from the contract); do not hand-write DTO types. Error codes map to Korean text in `src/api/errorMessages.ts`; a missing code fails `npm run typecheck` (part of `verify`; `npm test` alone does not type-check). API 타입과 클라이언트는 `src/api/`(계약에서 생성)를 쓴다. DTO 타입을 손으로 쓰지 않는다. 오류 코드는 `src/api/errorMessages.ts`에서 한국어 문장으로 바뀌고, 빠진 코드는 `npm run typecheck`(`verify`에 포함, `npm test`만으로는 타입 검사를 하지 않는다)에서 실패한다.
- Access tokens live in memory only: no `localStorage`, `sessionStorage` or `document.cookie` (lint error). 접근 토큰은 메모리에만 두며 위 저장소 접근은 린트 오류다.
- No secrets in `web/` and no `VITE_*` values: everything there ships in the bundle. `web/`에 비밀값과 `VITE_*` 값을 두지 않는다. 번들에 그대로 실린다.
- The root `.gitignore` ignores these names at any depth, so never create folders `env`, `bin`, `build`, `out`, `logs`, `tmp`, `temp`, `dist` (except Vite output), `secrets`, `.cache` or files `token_*.json`, `*.key`, `*.log`. Design tokens are `tokens.css`. 루트 `.gitignore`가 위 이름을 어느 깊이에서든 무시하므로 만들지 않는다. 디자인 토큰은 `tokens.css`다.
- The app name is one constant in `src/app/appName.ts`. 앱 이름은 `src/app/appName.ts`의 상수 하나다.

## Tests / 시험

- jsdom (Vitest, Testing Library, MSW): shell, navigation, not-found view, status line states. Requests without a handler fail the test. Auth tests cover login, signup, restore, refresh-once-and-retry, tab serialisation, logout, and that no token reaches browser storage. jsdom 시험: 앱 틀·이동·없는 경로·상태 줄. 핸들러 없는 요청은 시험을 실패시킨다. 인증 시험은 로그인·가입·복원·한 번 갱신 후 재시도·탭 직렬화·로그아웃과 토큰이 브라우저 저장소에 들어가지 않음을 확인한다.
- Node environment (`// @vitest-environment node`): proxy defaults and invalid ports, dev binding, a real Vite proxy against a stub HTTP backend on free ports, the lint restrictions, exact dependency pins. Node 환경 시험: 프록시 기본값·잘못된 포트, 개발 서버 바인딩, 빈 포트의 가짜 백엔드를 상대로 한 실제 Vite 프록시, 린트 금지 규칙, 의존성 정확 고정.
- Real browser (Playwright, Chromium): the season play of «End-to-end check», local only. 실제 브라우저(Playwright, Chromium): 「종단 확인」의 한 시즌 플레이, 로컬 전용.
