# Issue management and work plan (이슈 관리와 작업 계획)

Status as of 2026-11-17. Issue numbers link to the GitHub issues. Git conventions stay in `docs/CONVENTION.md`.
2026-11-17 기준. 이슈 번호는 GitHub 이슈다. git 규칙은 `docs/CONVENTION.md`를 따른다.

## 1. Rules (규칙)

- **Close on the last PR.** A feature split into several PRs uses `Refs #N` in the earlier PRs and `Closed #N` in the last one, so the issue closes by itself. On 2026-11-17 twenty-seven issues stayed open after their PRs merged because every PR used `Refs`; they were closed by hand.
  여러 PR로 나눈 기능은 앞 PR에 `Refs #N`, **마지막 PR에 `Closed #N`**을 쓴다. 2026-11-17에 모든 PR이 `Refs`만 써서 PR이 머지돼도 이슈 27건이 열려 있었고 손으로 닫았다.
- **Follow-ups become issues.** When review or audit leaves something for later, open an issue and link it from the PR; do not leave it only in a work report.
  리뷰·감사가 남긴 후속 일은 보고서에만 두지 말고 이슈로 만들어 PR에서 연결한다.
- **One label for the plan state** (new labels, 2026-11-17):
  계획 상태 라벨 하나를 붙인다(2026-11-17 새로 만듦).

| Label | Meaning (뜻) |
|---|---|
| `⚡ Quick` | Small and safe to do in the same day (오늘 바로 처리 가능한 작은 일) |
| `🌙 Night` | Complex or needs build/Docker/QA cycles; planned for the night run (복잡하거나 구현·QA·감사 순환이 필요해 밤 작업으로 계획) |
| `⏳ Blocked` | Waiting for an external release or an owner decision/action (외부 릴리스나 소유자 결정·조치 대기) |

- **Weekly sweep:** list `gh issue list --state open`, close what has a merged PR, check blocked ones against their condition, and move finished plans out of this table.
  주기 점검: 열린 이슈 목록을 보고 머지된 PR이 있는 것은 닫고, 대기 이슈는 조건을 다시 확인하고, 끝난 계획은 이 표에서 뺀다.

## 2. Triage on 2026-11-17 (2026-11-17 분류)

- 34 issues were open. **30 closed**: 29 had merged PRs (27 by their own PR #263–#338, and #169 and #171 by the runtime smokes in #295), and #188 was fixed today by PR #341 (`eslint` 10.15 and `typescript-eslint` 8.73 pull in the patched `minimatch`; `npm audit` shows 0, `npm run verify` passes). 4 stay open (#29, #85, #186, #187).
  열린 이슈 34건 중 **30건 종료**: 29건은 머지된 PR이 있었고(27건은 각자의 PR #263–#338, #169·#171은 #295 런타임 스모크), #188은 오늘 PR #341로 해소했다(`eslint` 10.15·`typescript-eslint` 8.73이 패치된 `minimatch`를 쓰며 `npm audit` 0건, `npm run verify` 통과). 4건(#29, #85, #186, #187)은 계속 열려 있다.
- 7 new follow-up issues were opened (#342–#348) so nothing lives only in work reports.
  보고서에만 있던 후속 일 7건을 새 이슈(#342–#348)로 등록했다.

## 3. Open issues and plan (열린 이슈와 계획)

### ⚡ Quick — none today (오늘 처리할 것 없음)

Every small issue was handled today (#188, and the 29 already-implemented ones were only closed). The remaining items either wait on something outside the repo or need build/QA cycles.
작은 일은 오늘 모두 처리했다(#188 해소, 이미 구현된 29건 종료). 남은 것은 저장소 밖 조건을 기다리거나 구현·QA 순환이 필요하다.

### 🌙 Night — planned for tonight, in this order (오늘 밤 작업 순서)

| # | Issue | Why night / size | Next step (다음 단계) |
|---|---|---|---|
| 1 | #342 M2-06 PR B engine path source + PR A follow-ups | Spring + engine contract, Docker tests; M | Restart from the preserved patch (in the local work folder, unverified), then QA and audit. 보존 패치에서 이어서 시작 |
| 2 | #344 MVP-11 follow-ups (T19, image jar check) | Small code, needs CI/Docker check; S | One worker, then PR. 워커 하나 + PR |
| 3 | #343 M2-06 PR C/D (projection table, parity) | Depends on #342; L | After #342 merges. #342 머지 뒤 |
| 4 | #345 AG-02 runner follow-ups | Several small PRs; M | Split by item; no live model calls. 항목별 PR, 라이브 호출 없음 |
| 5 | #346 M2-08 regime labels adapter | New feature; planning first; M | Plan (Opus), then implement. 계획부터 |
| 6 | #85 `compose.prod.yaml` | Needs isolated compose checks (secrets mount, SCRAM, memory); S–M | Verify in an isolated project. 격리 프로젝트에서 확인 |
| 7 | #29 P0-08 follow-up | Part needs an owner choice (market-cap rank is out of position judgement per PLAN §13) | Do the third-source date check and synthetic sample; ask the owner before the rest. 일부 소유자 확인 필요 |

Next feature candidates from the v1 table (not yet issues): MVP-12 ranking board and alerts, MVP-13 order sources, MVP-14 season abandon, M1-10 scheduler. Each opens an issue when its plan is approved.
v1 기능 표의 다음 후보(아직 이슈 아님): MVP-12, MVP-13, MVP-14, M1-10. 계획 승인 때 이슈를 만든다.

### ⏳ Blocked (대기)

| # | Issue | Waiting for (대기 조건) | Check (확인 방법) |
|---|---|---|---|
| 1 | #347 KIS live smoke | Owner approval (up to 3 requests) 소유자 승인 | Comment "approved" on the issue 이슈에 승인 코멘트 |
| 2 | #348 AG-01 detection run / runner live calls | Owner decision on `data_collection=deny` (options a/b/c) 소유자 결정 | Comment the chosen option 선택지를 코멘트 |
| 3 | #187 TypeScript 7 | `typescript-eslint` peer range (latest 8.73.0 supports `<6.1.0`) | `npm view typescript-eslint peerDependencies` |
| 4 | #186 jsx-a11y | `eslint-plugin-jsx-a11y` supports ESLint 10 (latest 6.10.2 does not) | `npm view eslint-plugin-jsx-a11y peerDependencies` |

### Owner actions outside issues (이슈 밖 소유자 조치)

- On an existing local DB volume, run the role script once as the superuser (`db/roles/antlab_owner_app.sql`, see `db/README.md`). Merging did not change your local DB.
  기존 로컬 DB 볼륨에는 슈퍼유저로 역할 스크립트를 한 번 실행한다(`db/README.md`). 머지만으로 로컬 DB는 바뀌지 않았다.
- Review the 36 new feed templates (`feed/templates.csv`, `feed/notices.csv`).
  새 피드 템플릿 36개와 고지 문장을 검토한다.
- Open questions in `owner_questions_1117_0700.md` (agent model per profile, Tomcat log policy) — defaults are in use.
  `owner_questions_1117_0700.md`의 열린 질문(프로필당 모델 규칙, Tomcat 로그 정책)은 기본값으로 진행 중이다.
