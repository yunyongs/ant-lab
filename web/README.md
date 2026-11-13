# web — one-season web app / 한 판 웹 앱

TypeScript React app built with Vite. This is the WEB-01 skeleton: app shell, router, one API status line and the dev proxy.
Vite로 빌드하는 TypeScript React 앱이다. WEB-01 골격으로, 앱 틀·라우터·API 상태 한 줄·개발 프록시가 들어 있다.

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

| Script                    | What it does / 하는 일                                                 |
| ------------------------- | ---------------------------------------------------------------------- |
| `dev` / `preview`         | Vite dev server / serve the built app, both with the API proxy         |
| `build`                   | `tsc -b && vite build` into `dist/`                                    |
| `lint`                    | `eslint .`                                                             |
| `format` / `format:check` | Prettier write / check                                                 |
| `typecheck`               | `tsc -b`                                                               |
| `test` / `test:watch`     | `vitest run` / `vitest`                                                |
| `verify`                  | lint → format:check → typecheck → test → build, stops on first failure |

`cd web && npm ci && npm run verify` needs no backend, no secrets and no fixed ports.
`cd web && npm ci && npm run verify`는 백엔드·비밀값·고정 포트가 필요 없다.

## Proxy / 프록시

Only `/api` is forwarded, unchanged, to `http://127.0.0.1:8080`. For an API on another port: `ANTLAB_API_PORT=18080 npm run dev` (an integer from 1 to 65535, otherwise the server refuses to start). The value is read by the Node-side Vite config only; it never reaches the browser bundle.
`/api`만 그대로 `http://127.0.0.1:8080`으로 넘긴다. 다른 포트의 API는 `ANTLAB_API_PORT=18080 npm run dev`로 지정한다(1–65535 정수, 아니면 서버가 시작하지 않는다). 값은 Node 쪽 Vite 설정만 읽고 브라우저 번들에는 들어가지 않는다.

- Target `127.0.0.1`, not `localhost`: the API binds IPv4 loopback and `localhost` may resolve to `::1` first.
- 대상은 `localhost`가 아니라 `127.0.0.1`이다. API가 IPv4 루프백에 붙고 `localhost`는 `::1`로 먼저 풀릴 수 있다.
- `changeOrigin: false`: the browser `Host` is kept, so a `Location` header the API builds from the request stays same-origin; `Set-Cookie` passes through unchanged.
- `changeOrigin: false`: 브라우저의 `Host`가 유지되어 API가 요청 `Host`로 만드는 `Location`이 같은 출처로 남고, `Set-Cookie`는 그대로 전달된다.
- The dev server binds `localhost:5173` only (`strictPort`); `/readyz` and Actuator are not proxied.
- 개발 서버는 `localhost:5173`에만 붙고(`strictPort`) `/readyz`와 Actuator는 넘기지 않는다.

## Folder layout / 폴더 구조

```text
src/app/       providers, router, shell, queryClient / 공급자·라우터·틀
src/pages/     route views / 경로별 화면
src/features/  feature components, hooks, small fetchers / 기능별 코드 (health: 임시 상태 조회)
src/styles/    tokens.css (design tokens), reset, global / 디자인 토큰·초기화
src/test/      test setup, request mocks, render helpers / 시험 설정·요청 모의·렌더 도우미
src/api/       reserved for WEB-02 (generated types, typed client) / WEB-02용 예약
```

## Rules / 규칙

- Show server values as they are. Money is a decimal string: no `Number(…)`, `parseFloat` (lint error). 서버 값을 그대로 보여 준다. 금액은 십진 문자열이라 `Number(…)`·`parseFloat`을 쓰면 린트 오류다.
- Access tokens live in memory only: no `localStorage`, `sessionStorage` or `document.cookie` (lint error). 접근 토큰은 메모리에만 두며 위 저장소 접근은 린트 오류다.
- No secrets in `web/` and no `VITE_*` values: everything there ships in the bundle. `web/`에 비밀값과 `VITE_*` 값을 두지 않는다. 번들에 그대로 실린다.
- The root `.gitignore` ignores these names at any depth, so never create folders `env`, `bin`, `build`, `out`, `logs`, `tmp`, `temp`, `dist` (except Vite output), `secrets`, `.cache` or files `token_*.json`, `*.key`, `*.log`. Design tokens are `tokens.css`. 루트 `.gitignore`가 위 이름을 어느 깊이에서든 무시하므로 만들지 않는다. 디자인 토큰은 `tokens.css`다.
- The app name is one constant in `src/app/appName.ts`. 앱 이름은 `src/app/appName.ts`의 상수 하나다.

## Tests / 시험

- jsdom (Vitest, Testing Library, MSW): shell, navigation, not-found view, status line states. Requests without a handler fail the test. jsdom 시험: 앱 틀·이동·없는 경로·상태 줄. 핸들러 없는 요청은 시험을 실패시킨다.
- Node environment (`// @vitest-environment node`): proxy defaults and invalid ports, dev binding, a real Vite proxy against a stub HTTP backend on free ports, the lint restrictions, exact dependency pins. Node 환경 시험: 프록시 기본값·잘못된 포트, 개발 서버 바인딩, 빈 포트의 가짜 백엔드를 상대로 한 실제 Vite 프록시, 린트 금지 규칙, 의존성 정확 고정.
- Real-browser end-to-end tests arrive in WEB-10. 실제 브라우저 종단 시험은 WEB-10에서 더한다.
