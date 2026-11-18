# events/ — 사건 태그 표본 (P0-09)

Phase 0 표본 100개 달(2007-01–2012-04, 2023-01–2025-12 — P0-08)에 **큰 사건만 1차로 태그**한 손 편집 데이터다. DB 테이블이 아니라 파일이며, `db/11_load_events.sql`이 읽어 `month_event`에 넣고 `month_block.events_reviewed`를 켠다. 감독 모드의 사건 카드(PLAN §4.11·§4.13), 통계 보드의 사건 조건(P0-12), 복기 화면의 실제 이름, 에이전트 누출 필터의 사건 이름 사전(PLAN §4.16)이 이 파일에서 나온다.

**이 파일들에는 시세·환율에서 나온 숫자가 없다.** 반응 변화율(`reaction_change`)과 강도(`strength`)는 적재할 때 DB의 `asset_month_return`·`fx_month`에서 계산한다(아래 "반응 변화율과 강도 경계"). 사건 이름·날짜·출처는 사실의 짧은 요약이고 기사 문장이 아니다.

## 파일

| 파일 | 내용 | 행 수 |
|---|---|---|
| `types.csv` | 사건 유형 목록 — H6 검사의 기계 판독 원본 | 27 |
| `tags.csv` | 태그. 1행 = (달 × 사건) | 38 |
| `reviewed.csv` | 편집 완료 표. 표본 100개 달 전부, 결과 `TAGGED`/`NONE` | 100 |
| `README.md` | 이 문서 | — |

`types.csv`가 원본이고 이 문서의 유형 표는 그 거울이다. 둘이 다르면 `types.csv`가 맞다(erd 5.3은 "목록은 `events/README.md`"라고 적었고, 기계가 읽는 원본이 CSV다).

적재와 검증은 `db/README.md`의 (b″) 블록이다. 이 폴더의 파일은 모두 git이 추적한다 — 시세 파생값이 없고 출처가 공개 문서이기 때문이다.

## 파일 형식 (세 CSV 공통, P0-08·P0-13과 같음)

- UTF-8(BOM 없음), 줄 끝 LF(적재는 CRLF도 같은 값으로 읽지만 diff가 줄 전체로 바뀌므로 LF를 지킨다), RFC 4180. 쉼표가 든 칸은 `"…"`로 감싸고 안의 `"`는 `""`로 쓴다. 여러 줄 칸 금지. 파일 끝은 줄바꿈 하나.
- **NULL은 따옴표 없는 빈 칸**이다. 따옴표 친 빈 문자열 `""`은 적재가 거부한다. 날짜는 `YYYY-MM-DD`. `id`·`created_at` 열이 없다(erd D12 — 대리 키는 DB가 만든다).
- 첫 줄은 열 이름이고 **순서까지 적재 SQL의 스테이징 열과 같아야 한다**(`HEADER MATCH`). 열을 더하거나 순서를 바꾸면 적재가 멈춘다. 열 목록 자체가 계약이다.
- 행 순서: `tags.csv`는 `event_key` 오름차순(바이트 순서), `reviewed.csv`는 `month_start` 오름차순, `types.csv`는 `category`, `event_type` 오름차순. 어긋나면 적재가 거부한다(편집 실수를 diff에서 찾기 쉽게 한다).
- 편집은 VS Code에서 한다. 확장 "Rainbow CSV"를 설치하면 열이 색으로 구분된다. **정렬용 공백을 넣지 않는다**(공백도 값이다). 코드 칸의 공백은 거부된다.

## types.csv — 유형 목록

열: `event_type,category,name_ko,outcomes,direction_rule,reaction_refs`

| 열 | 형식 | 뜻 |
|---|---|---|
| `event_type` | `^[A-Z][A-Z_]*$`, 30자 이하, **숫자 금지** | 유형 코드. 표본 수의 단위(erd D18). DDL(`ck_month_event_event_type_format`)은 숫자를 허용하지만 연도가 코드에 들어가지 못하도록 이 목록은 더 엄격하다 |
| `category` | erd 5.4 `ck_month_event_category`의 7개 중 하나 | 유형은 범주 하나에만 속한다 |
| `name_ko` | 한글, 앞뒤 공백 금지 | 유형 이름(복기·문서용, 시즌 중 노출 아님) |
| `outcomes` | `;`로 이은 `^[A-Z][A-Z_]*$` 코드 / 빈 칸 | 허용 결과. 빈 칸이면 그 유형의 `outcome`은 NULL만 |
| `direction_rule` | `NONE` / `UP_DOWN` | `UP_DOWN`이면 `direction`이 `UP`/`DOWN` 필수, `NONE`이면 NULL만 |
| `reaction_refs` | `;`로 이은 참조 | 그 유형의 태그가 고를 수 있는 반응 참조(`tags.csv`의 `reaction_ref`) |

참조 형식은 `KR:{종목코드 6자리}`, `US:{티커}`, `FX:USD`다. `KR:`·`US:`는 `asset`에 있는 풀 종목이고 `FX:USD`는 `fx_month`의 달러 환율이다.

코드에는 나라를 `US`·`KR` 두 글자로만 쓴다(풀의 시장 코드). 상대국·정당·기관·회사·사람·병명·연도는 코드에 쓰지 않는다 — 노출 컬럼에 고유명사가 들어가면 사건 카드가 날짜 단서가 된다(erd H5). 새 유형을 더할 때도 같다.

### 유형 표 (`types.csv`의 거울)

| `event_type` | 범주 | 이름 | `outcomes` | 방향 | `reaction_refs` |
|---|---|---|---|---|---|
| `BANK_FAILURE` | FINANCIAL | 금융기관 파산·구제 | `FAILED;RESCUED` | NONE | `US:SPY;KR:055550` |
| `CORPORATE_DISTRESS` | FINANCIAL | 대기업 부실·부도(회계 부정 포함) | `FAILED;RESCUED` | NONE | `US:SPY;KR:069500;KR:055550` |
| `CREDIT_CRUNCH` | FINANCIAL | 신용 경색 | (빈 칸) | NONE | `US:SPY;KR:055550` |
| `CREDIT_RATING_DOWNGRADE` | FINANCIAL | 국가 신용등급 강등 | (빈 칸) | NONE | `US:TLT;US:SPY;KR:069500` |
| `CURRENCY_SHOCK` | FINANCIAL | 통화 급락·평가절하(통화 위기) | `DEVALUED;RESCUED` | NONE | `FX:USD;KR:069500;US:SPY` |
| `MARKET_CRASH` | FINANCIAL | 증시 폭락·거래 중단 | `HALTED` | NONE | `US:SPY;US:QQQ;KR:069500` |
| `SOVEREIGN_DEBT` | FINANCIAL | 국가 부채 위기 | `ESCALATED;RESCUED;DEFAULTED` | NONE | `US:SPY;KR:069500` |
| `ARMED_CONFLICT` | GEOPOLITICS | 무력 분쟁 | `ESCALATED;CEASEFIRE` | NONE | `US:XOM;US:SPY;KR:069500` |
| `MILITARY_PROVOCATION` | GEOPOLITICS | 군사 도발·교전 | (빈 칸) | NONE | `KR:069500;FX:USD` |
| `PEACE_TALKS` | GEOPOLITICS | 정상회담·평화 협상 | `AGREED;COLLAPSED` | NONE | `KR:069500;FX:USD` |
| `TERROR_ATTACK` | GEOPOLITICS | 대형 테러 공격 | (빈 칸) | NONE | `US:SPY;KR:069500` |
| `EPIDEMIC` | HEALTH | 감염병 유행(대유행 아님) | (빈 칸) | NONE | `KR:069500;US:SPY` |
| `PANDEMIC` | HEALTH | 감염병 대유행 | (빈 칸) | NONE | `US:SPY;KR:069500` |
| `FISCAL_STANDOFF` | POLICY | 예산·부채 한도 대치와 정부 폐쇄 | `SHUTDOWN;RESOLVED` | NONE | `US:SPY;US:TLT` |
| `FISCAL_STIMULUS` | POLICY | 대규모 재정 부양·구제 법안 | (빈 칸) | NONE | `US:SPY;KR:069500` |
| `MONETARY_EASING` | POLICY | 통화 완화(인하·자산 매입) | `RATE_CUT;ASSET_PURCHASE;EMERGENCY_CUT` | NONE | `US:TLT;US:SHY;US:SPY;KR:069500` |
| `MONETARY_TIGHTENING` | POLICY | 통화 긴축(인상·자산 매입 축소) | `RATE_HIKE;ASSET_PURCHASE_CUT;BALANCE_SHEET_CUT;SIGNALED` | NONE | `US:TLT;US:SHY;US:SPY;KR:069500` |
| `KR_LEGISLATIVE_ELECTION` | POLITICS | 한국 총선 | `MAJORITY_GAINED;MAJORITY_KEPT;MAJORITY_LOST;MINORITY_KEPT` | NONE | `KR:069500` |
| `KR_PRESIDENTIAL_ELECTION` | POLITICS | 한국 대선 | `CHANGE;KEEP` | NONE | `KR:069500` |
| `POLITICAL_CRISIS` | POLITICS | 국내 정치 위기(헌정 위기·탄핵) | (빈 칸) | NONE | `KR:069500;FX:USD` |
| `REFERENDUM` | POLITICS | 해외 국민투표 | `CHANGE;KEEP` | NONE | `US:SPY;KR:069500;FX:USD` |
| `US_MIDTERM` | POLITICS | 미국 중간선거 | `MAJORITY_LOST;MAJORITY_KEPT;MINORITY_KEPT` | NONE | `US:SPY` |
| `US_PRESIDENTIAL_ELECTION` | POLITICS | 미국 대선 | `CHANGE;KEEP` | NONE | `US:SPY` |
| `NATURAL_DISASTER` | SUPPLY_SHOCK | 대형 재해(공급망) | (빈 칸) | NONE | `KR:069500;US:SPY` |
| `OIL_SHOCK` | SUPPLY_SHOCK | 유가 급등락 | (빈 칸) | UP_DOWN | `US:XOM` |
| `SANCTION` | TRADE | 수출 통제·경제 제재 | `IMPOSED;RETALIATED;PAUSED;LIFTED` | NONE | `KR:069500;US:SPY;KR:005930` |
| `TARIFF` | TRADE | 관세 부과·보복·합의 | `IMPOSED;RETALIATED;PAUSED;AGREED` | NONE | `KR:069500;US:SPY` |

긴축(`MONETARY_TIGHTENING`)은 1차(P0-09)에서 뺐고 MVP-07 07-A에서 더했다(확장 구간과 2012–2022의 인상 주기 시작·자산 매입 축소가 큰 사건이기 때문). "마지막 인상"은 그 달에는 마지막인지 몰랐으므로 `hindsight`에만 쓴다(시점 기준 T2). 07-A에서 더하거나 바꾼 유형과 이유는 아래 "MVP 확장 편집 규칙"에 있다.

### 결과 코드의 뜻

| 코드 | 쓰는 유형 | 뜻 (그 달 말에 확정된 것만) |
|---|---|---|
| `CHANGE` / `KEEP` | 대선, 해외 국민투표 | 대선: 직전 대선 당선자의 당선 당시 소속 정당과 새 당선자의 정당이 다름 / 같음(현직이 탈당했거나 자리가 비어도 이 기준. 정당이 이름을 바꾸거나 합당했으면 이어지는 정당으로 본다). 국민투표: 현 상태를 바꾸는 쪽이 이김 / 현 상태 유지 |
| `MAJORITY_GAINED` / `MAJORITY_KEPT` / `MAJORITY_LOST` / `MINORITY_KEPT` | 한국 총선 | 집권 여당이 선거 전 과반이 아니었다가 과반을 얻음 / 과반이었고 유지 / 과반이었다가 잃음 / 선거 전후 모두 과반이 아님 |
| `MAJORITY_LOST` / `MAJORITY_KEPT` / `MINORITY_KEPT` | 미국 중간선거 | 대통령 소속 정당이 하원 과반을 잃음 / 유지 / 선거 전후 모두 과반이 아님 |
| `FAILED` / `RESCUED` | 은행·금융기관, 대기업 부실 | 파산·폐쇄·부도로 정리 / 인수·구제·채권단 관리로 정리 (국가 부채 위기와 통화 위기에서 `RESCUED`는 구제금융 합의) |
| `DEFAULTED` | 국가 부채 위기 | 국가가 채무 불이행(지급 중단·모라토리엄)을 선언했거나 지급일을 넘김 |
| `DEVALUED` | 통화 위기 | 당국이 통화 가치를 공식 절하했거나 고정·관리 환율을 포기함 |
| `HALTED` | 증시 폭락 | 한국 또는 미국 시장 전체의 거래가 멈춤(서킷 브레이커 발동, 거래소 휴장) |
| `ESCALATED` / `CEASEFIRE` | 무력 분쟁, 국가 부채 위기(`ESCALATED`만) | 확전·확산 / 휴전 |
| `AGREED` / `COLLAPSED` | 정상회담·평화 협상 | 공동 선언·합의 서명 / 합의 없이 결렬 |
| `RATE_CUT` / `EMERGENCY_CUT` / `ASSET_PURCHASE` | 통화 완화 | 정례 인하 / 정례 회의 밖 긴급 인하 / 자산 매입 계획 발표 |
| `RATE_HIKE` / `ASSET_PURCHASE_CUT` / `BALANCE_SHEET_CUT` / `SIGNALED` | 통화 긴축 | 정책금리 인상 / 자산 매입 규모 축소 결정 / 보유 자산 축소 시작 결정 / 축소·인상 예고(결정은 아직 없음) |
| `SHUTDOWN` / `RESOLVED` | 예산·부채 한도 대치 | 정부 업무 정지가 시작됨 / 예산·부채 한도 법안 서명으로 해소 |
| `IMPOSED` / `RETALIATED` / `PAUSED` / `AGREED` | 관세 | 부과 / 맞대응 / 유예 / 합의 |
| `IMPOSED` / `RETALIATED` / `PAUSED` / `LIFTED` | 수출 통제·경제 제재 | 부과 / 맞대응 / 유예 / 해제 |

## tags.csv — 태그

열(순서 고정): `event_key,month_start,category,event_type,phase,outcome,direction,reaction_ref,title,hindsight,source,checked_on`

| 열 | DB 컬럼 | 형식·규칙 |
|---|---|---|
| `event_key` | `event_key` | `^ev[0-9]{4}\.[0-9]{2}$`(예: `ev0007.02`). 앞 6글자 = **에피소드**(한 사건), 뒤 2자리 = 그 에피소드 안의 달 순번. **바꾸지 않는다**(`turn_feed`가 id로 가리킨다). 번호는 편집 순서다. 1차 편집은 거의 연대순으로 번호를 매겼으므로 `event_key`와 `month_event.id`는 시즌 중 어떤 화면·API·에이전트 입력에도 내지 않는다(erd [내부] — 번호 순서가 날짜 순서를 드러낸다). 날짜·이름을 넣지 않는다 |
| `month_start` | `month_block_id`(자연 키로 조인) | `YYYY-MM-01`, `month_block`에 있는 달 |
| `category` | `category` | `types.csv`의 그 유형 범주와 같아야 한다 |
| `event_type` | `event_type` | `types.csv`에 있어야 한다 |
| `phase` | `phase` | `START`/`ONGOING`/`END` — **당시 인식 기준**(시점 기준 T2) |
| `outcome` | `outcome` | 그 유형의 `outcomes` 안 또는 빈 칸. 그 달 말에 확정된 결과만(T3) |
| `direction` | `direction` | 유형의 `direction_rule`대로 |
| `reaction_ref` | (적재 안 함) | 유형의 `reaction_refs` 중 하나. 반응 변화율을 어디서 가져올지 가리킨다 |
| `title` | `title` | 한글 위주 사실 이름, 1–100자, 앞뒤 공백 금지, 따옴표류(`"` `“` `”` `'` `‘` `’` `「` `」`) 금지. 그 달 말에 쓰이던 이름(T4) |
| `hindsight` | `hindsight` | 빈 칸 또는 1–300자, 앞뒤 공백 금지, 따옴표류 금지. 나중에 붙은 이름·사후 평가(T4) |
| `source` | `source` | `https://`로 시작하는 URL 1–3개를 ` ; `(공백 세미콜론 공백)로 잇는다. 500자 이하. `utm_` 같은 추적 매개변수 금지 |
| `checked_on` | (적재 안 함) | 출처를 확인한 날짜 |

- 적재가 계산하는 컬럼(파일에 없음): `reaction_change`, `strength`.
- `title`·`hindsight`·`source`는 erd `[복기 전용]`이다. 시즌 중 화면·API·에이전트 입력으로 내보내지 않는다.
- **에피소드 규칙**(적재와 검증이 모두 본다): 같은 에피소드의 행은 `category`·`event_type`이 같고, 달 순번이 01부터 빈틈없이 늘며 `month_start`도 같은 순서로 엄격히 늘고, `START`는 있으면 순번 01, `END`는 있으면 마지막 행이다. 달이 이어지지 않아도 된다(큰 달만 태그한다).
- 한 달의 태그는 3개 이하다(적재가 거부하고 검증이 다시 본다).

### 에피소드를 나누는 규칙

같은 유형이라도 **원인·주체가 다르면 다른 에피소드**다. 판단이 어려우면 **나누지 않는다** — 에피소드가 적을수록 유형의 표본 수 n이 작게 세어져 범주를 숨기는 쪽(안전한 쪽)으로 어긋난다.

## reviewed.csv — 편집 완료 표

열: `month_start,result`

| 값 | 뜻 | DB |
|---|---|---|
| 그 달 행이 파일에 없음 | **아직 안 봄** | `events_reviewed = FALSE` (적재가 건드리지 않는다) |
| `result = NONE` | 봤고 **사건 없음** | `TRUE` + 태그 0건 |
| `result = TAGGED` | 봤고 태그 있음 | `TRUE` + 태그 1건 이상 |

`NONE`/`TAGGED`를 따로 적는 이유: 태그 행이 실수로 지워져도 그 달이 조용히 "사건 없음"이 되지 않는다(파일 안에서 한 번, DB와 파일 사이에서 한 번 교차 확인한다). 태그가 있는 달은 모두 이 표에 있어야 한다.

**`NONE`의 범위:** 이 1차 편집은 정기 선거와 큰 사건 목록을 훑은 것이다. `NONE`은 "1차 편집 기준(아래 "1차 편집 범위")으로 태그할 사건이 없다"이지, 그 달에 아무 일도 없었다는 뜻이 아니다. 세부는 EXT에서 다시 본다(PLAN §4.11).

## 시점 기준 (사후 정보 배제, PLAN §4.5·§4.11)

사건 카드는 그 턴에 플레이어가 알 수 있던 것만 말해야 한다. 편집 규칙으로 옮기면 다음과 같다.

- **T1 달 배정:** 사건은 그것이 알려진 달에 붙인다. 그 달 마지막 거래일 장 마감 뒤에 알려진 사건은 다음 달에 붙인다(월말 종가 기준, 반응이 다음 달 수익률에 나오기 때문 — PLAN §4.6). 시각은 사건이 일어난 곳의 현지 시각이다.
- **T2 단계:** `START` = 처음 1면에 오른 달. `END` = **그 달에** 끝났다고 발표·인식된 달(휴전 합의, 파면 결정). 나중에 돌아보니 끝이었던 달(예: "마지막 금리 인상")은 `END`가 아니라 `hindsight`에 쓴다.
- **T3 결과:** 그 달 말에 확정된 결과만 `outcome`에 쓴다. 확정되지 않았으면 빈 칸으로 두고, 확정된 달에 `ONGOING` + 결과 행을 더한다.
- **T4 이름:** `title`은 그 달 말에 쓰이던 사실 이름이다. 나중에 붙은 이름과 평가("위기의 정점이었다")는 `hindsight`에만 쓴다. 유형(`event_type`)도 그 달 말에 알려진 성격으로 고른다. 성격이 나중에 확정됐으면 그 사실을 `hindsight`에 쓴다.

## 1차 편집 범위

**선정 규칙**

1. **정기 선거는 결과와 무관하게 늘 태그한다:** 미국 대선·중간선거, 한국 대선·총선. 선거가 있던 달에 `START` 한 행이고 `outcome`은 그 달 말에 확정된 결과다.
2. **그 밖의 사건은 그 달 말 주요 뉴스의 첫머리에 오른 범주 대표 사건**(은행 파산, 팬데믹, 무력 분쟁 발발, 관세 부과, 중앙은행의 큰 결정, 신용등급 강등, 재해, 대형 정치 위기)이다. 진행 중인 사건의 지속 달(`ONGOING`)은 결과가 바뀐 달(확전·휴전·구제·유예)만 태그한다. 시장 반응이 작아도 태그하며(강도 1), 반응의 크기는 `strength`로만 나타난다. 이번 1차는 사건 목록에서 출발했고, 시장 반응이 큰 달에서 거꾸로 사건을 찾는 점검은 하지 않았다 — 2차 편집 과제다.
3. 한 달의 태그는 3개 이하다(카드 수 자체가 위기 달의 표식이 되지 않게).
4. 표본 밖에서 시작한 사건이 표본 첫 달에 이어지면 그 행은 `ONGOING`이다(`START`를 지어내지 않는다).

**범위 밖**

- 확장 구간(1997-01–2006-12)과 본 구간 중 표본에 없는 2012-05–2022-12의 편집 — MVP 데이터 준비 몫이다(PLAN §4.7·§4.11). 그 달들의 `month_block` 행이 아직 없다. 편집 규칙은 아래 "MVP 확장 편집 규칙"이다.
- 세부 편집(작은 사건) — EXT.
- 사건 카드 문장의 유형·결과별 템플릿 — 소음 짝이 함께 필요해 P0-18 또는 EXT에서 새 id로 더한다.

## MVP extension drafting rules (MVP-07, 07-B..E) / MVP 확장 편집 규칙

### English

These rules add to everything above (formats, T1–T4, selection rules 1–4, episode rules, sources). They apply to the four drafting PRs: 07-B 1997-01–2001-12, 07-C 2002-01–2006-12, 07-D 2012-05–2017-12, 07-E 2018-01–2022-12.

1. **Types are fixed for drafting.** Use only `types.csv`. If an event fits no type, put it in the hold list with the reason; a new type needs its own reviewed change to `types.csv` (07-A rules: no year, no proper noun, two-letter tokens only `US`/`KR`, reusable for three or more episodes across 1997–2025).
2. **Every month of the era gets a `reviewed.csv` row** (`TAGGED` or `NONE`). Regular elections are always tagged (rule 1): US presidential elections and midterms (November), KR presidential elections (1997-12, 2002-12, 2012-12, 2017-05, 2022-03) and KR legislative elections (2000-04, 2004-04, 2016-04, 2020-04). The `outcome` is blank when the result was not settled at month end (a decisive recount or court case still open); add an `ONGOING` row with the outcome in the month it was settled (T3).
3. **Type choice.**
   - Official devaluation or a dropped peg → `CURRENCY_SHOCK` `DEVALUED`; an IMF or bilateral rescue of a currency crisis → `CURRENCY_SHOCK` `RESCUED`. A sovereign default → `SOVEREIGN_DEBT` `DEFAULTED`. A default and a devaluation of the same country in the same month are one tag (`SOVEREIGN_DEBT`).
   - A bank, broker or hedge fund failing or rescued → `BANK_FAILURE`. A non-financial company or conglomerate in default, workout or an accounting fraud that headlines → `CORPORATE_DISTRESS` (no outcome in the month the fraud is revealed; `FAILED` on a bankruptcy filing or default, `RESCUED` on a creditor workout or bailout). A frozen consumer or corporate credit market → `CREDIT_CRUNCH`.
   - `MARKET_CRASH` only when the fall itself is the headline and no other tag of that month is its reported cause; `HALTED` only for a market-wide circuit breaker or an exchange closure in KR or US. "Bubble" and "bust" are later names: they go to `hindsight`, never to the type.
   - `PANDEMIC` when, at month end, the WHO has declared a pandemic, or a PHEIC for a new virus already spreading across continents (precedent `ev0010`); any other outbreak is `EPIDEMIC`. An episode keeps the type of its first row.
   - `MONETARY_TIGHTENING`: `START` = first hike of a cycle or the first headline signal (`SIGNALED`); `ONGOING` only for an outsized hike, a purchase cut or the start of balance-sheet runoff. One episode per central bank and cycle; the Fed and the Bank of Korea are separate episodes. An easing episode ends when the same bank starts tightening; the next easing is a new episode.
   - `FISCAL_STANDOFF` (`SHUTDOWN` / `RESOLVED`), `REFERENDUM` (`CHANGE` = the side changing the status quo won), `PEACE_TALKS` (`AGREED` / `COLLAPSED`), `SANCTION` (export controls, embargoes, financial sanctions; tariffs stay `TARIFF`), `TERROR_ATTACK` (mass-casualty attack leading world news). A referendum inside a debt crisis is tagged as `SOVEREIGN_DEBT`.
   - Wars and invasions → `ARMED_CONFLICT`; tests, launches and clashes around the Korean peninsula → `MILITARY_PROVOCATION`.
4. **Episode keys.** Each PR uses its own block so parallel drafts never collide: 07-B `ev0100`–`ev0199`, 07-C `ev0200`–`ev0299`, 07-D `ev0300`–`ev0399`, 07-E `ev0400`–`ev0499`. The era of the `START` row owns the episode; a later era adds `ONGOING` rows to that key (merge in era order). An episode of the sample (`ev0001`–`ev0021`) continues with its next sequence number only if the new month is after its last row; otherwise the candidate goes to the hold list (a new episode would raise n).
5. **`reaction_ref`.** Choose from the type's list; for a type that is likely n < 3 in the replay range, pick `KR:069500` or `US:SPY` first. Before listing (`KR:069500` 2002-10, `KR:055550` 2001-09, `US:TLT`/`US:SHY` 2002-07, `US:QQQ` 1999-03) the value is the proxy-index return; keep it, because it is the same number the turn screen shows for that asset.
6. **Sources.** Open every URL on the day of drafting and write that date in `checked_on`. Primary sources first (central banks, election commissions, WHO, IMF, governments, exchanges); encyclopedia pages for dates only. Nothing from memory without an opened source. Titles are short facts in Korean with the name used at month end; later names and judgements ("tantrum", "bubble", "the last hike", "the peak of the crisis") go to `hindsight`.
7. **Checks before review.** `tools/fetch/tests/test_events_files.py` checks format, order, H5 and H6 without a database (`tools/fetch/.venv/bin/python -m pytest -q tools/fetch/tests/test_events_files.py`). The loader and `db/11_load_events_check.sql` run only on a database whose `month_block` holds the new months (full load, plan run R-L).

### 한국어

위의 모든 규칙(형식, T1–T4, 선정 규칙 1–4, 에피소드 규칙, 출처)에 더하는 규칙이다. 네 편집 PR에 적용한다: 07-B 1997-01–2001-12, 07-C 2002-01–2006-12, 07-D 2012-05–2017-12, 07-E 2018-01–2022-12.

1. **편집 중 유형은 고정이다.** `types.csv`의 유형만 쓴다. 맞는 유형이 없으면 이유와 함께 보류 목록에 둔다. 새 유형은 `types.csv`를 따로 리뷰받아 더한다(07-A 규칙: 연도·고유명사 금지, 두 글자 토큰은 `US`/`KR`만, 1997–2025에서 에피소드 3개 이상에 다시 쓸 수 있을 것).
2. **그 구간의 모든 달에 `reviewed.csv` 행을 둔다**(`TAGGED` 또는 `NONE`). 정기 선거는 늘 태그한다(규칙 1): 미국 대선·중간선거(11월), 한국 대선(1997-12, 2002-12, 2012-12, 2017-05, 2022-03), 한국 총선(2000-04, 2004-04, 2016-04, 2020-04). 그 달 말에 결과가 확정되지 않았으면(결과를 좌우하는 재검표·소송이 진행 중) `outcome`을 비우고, 확정된 달에 결과가 든 `ONGOING` 행을 더한다(T3).
3. **유형 고르기.**
   - 공식 평가절하나 고정 환율 포기 → `CURRENCY_SHOCK` `DEVALUED`. 통화 위기에 대한 국제통화기금·양자 구제 → `CURRENCY_SHOCK` `RESCUED`. 국가 채무 불이행 → `SOVEREIGN_DEBT` `DEFAULTED`. 같은 나라의 채무 불이행과 평가절하가 같은 달이면 태그 하나(`SOVEREIGN_DEBT`)다.
   - 은행·증권사·헤지펀드의 파산·구제 → `BANK_FAILURE`. 금융사가 아닌 회사·재벌의 부도·채권단 관리·1면에 오른 회계 부정 → `CORPORATE_DISTRESS`(부정이 드러난 달은 결과 없음, 파산 신청·부도는 `FAILED`, 채권단 관리·구제는 `RESCUED`). 소비자·기업 신용 시장이 얼어붙음 → `CREDIT_CRUNCH`.
   - `MARKET_CRASH`는 하락 자체가 1면이고 그 달 다른 태그가 원인으로 보도되지 않았을 때만 쓴다. `HALTED`는 한국·미국 시장 전체의 서킷 브레이커나 휴장일 때만이다. "거품"과 "붕괴"는 나중에 붙은 이름이라 `hindsight`에만 쓰고 유형으로 고르지 않는다.
   - `PANDEMIC`은 그 달 말에 세계보건기구가 대유행을 선언했거나, 이미 여러 대륙으로 퍼진 새 바이러스에 국제 공중보건 비상사태(PHEIC)를 선포했을 때다(선례 `ev0010`). 그 밖의 유행은 `EPIDEMIC`이다. 에피소드는 첫 행의 유형을 유지한다.
   - `MONETARY_TIGHTENING`: `START` = 인상 주기의 첫 인상이나 1면에 오른 첫 예고(`SIGNALED`). `ONGOING`은 큰 폭 인상, 자산 매입 축소, 보유 자산 축소 시작만이다. 에피소드는 중앙은행·주기마다 하나이고 미국 연준과 한국은행은 다른 에피소드다. 완화 에피소드는 같은 은행이 긴축을 시작하면 끝나고, 다음 완화는 새 에피소드다.
   - `FISCAL_STANDOFF`(`SHUTDOWN` / `RESOLVED`), `REFERENDUM`(`CHANGE` = 현 상태를 바꾸는 쪽이 이김), `PEACE_TALKS`(`AGREED` / `COLLAPSED`), `SANCTION`(수출 통제·금수·금융 제재, 관세는 `TARIFF` 그대로), `TERROR_ATTACK`(세계 뉴스 첫머리에 오른 대량 인명 피해 공격). 부채 위기 안의 국민투표는 `SOVEREIGN_DEBT`로 태그한다.
   - 전쟁·침공 → `ARMED_CONFLICT`. 한반도 주변의 핵실험·미사일 발사·교전 → `MILITARY_PROVOCATION`.
4. **에피소드 키.** 병행 편집이 부딪히지 않도록 PR마다 번호 구간을 쓴다: 07-B `ev0100`–`ev0199`, 07-C `ev0200`–`ev0299`, 07-D `ev0300`–`ev0399`, 07-E `ev0400`–`ev0499`. `START` 행이 있는 구간의 PR이 에피소드를 갖고, 뒤 구간은 그 키에 `ONGOING` 행을 더한다(구간 순서로 머지). 표본의 에피소드(`ev0001`–`ev0021`)는 새 달이 그 에피소드의 마지막 행보다 뒤일 때만 다음 순번으로 잇는다. 아니면 보류 목록에 둔다(새 에피소드로 만들면 n이 늘어난다).
5. **`reaction_ref`.** 유형의 허용 목록에서 고르고, 재생 구간에서 n < 3이 될 것 같은 유형은 `KR:069500`이나 `US:SPY`를 먼저 고른다. 상장 전 달(`KR:069500` 2002-10, `KR:055550` 2001-09, `US:TLT`/`US:SHY` 2002-07, `US:QQQ` 1999-03)의 값은 대리 지수 수익률이다. 턴 화면이 그 종목에 보여 주는 숫자와 같으므로 그대로 쓴다.
6. **출처.** 편집한 날 모든 URL을 직접 열고 그 날짜를 `checked_on`에 적는다. 1차 자료(중앙은행, 선거관리기관, 세계보건기구, 국제통화기금, 정부, 거래소)를 먼저 쓰고 백과사전은 날짜 확인용으로만 쓴다. 열어 본 출처 없이 기억으로 쓰지 않는다. `title`은 그 달 말에 쓰이던 이름으로 쓴 한국어 짧은 사실이다. 나중 이름과 평가("발작", "거품", "마지막 인상", "위기의 정점")는 `hindsight`에 쓴다.
7. **리뷰 전 검사.** `tools/fetch/tests/test_events_files.py`가 DB 없이 형식·순서·H5·H6을 본다(`tools/fetch/.venv/bin/python -m pytest -q tools/fetch/tests/test_events_files.py`). 적재와 `db/11_load_events_check.sql`은 새 달이 `month_block`에 들어간 DB(전체 적재, 계획의 R-L)에서만 돌릴 수 있다.

## 표본 수 n — 정의 N1과 정본 쿼리

> **유형의 표본 수 n = 재생 표본에 속한 달에 태그가 하나 이상 있는 에피소드의 수** (같은 `event_type` 안에서 `left(event_key, 6)`의 개수).

n < 3인 유형은 시즌에서 **범주를 말하지 않는 카드**로 나간다(PLAN §4.11 고유 사건 예외). PLAN의 "3회"는 사건의 횟수이고, 달 수로 세면 6개월 이어진 사건 하나가 n = 6이 되어 숨겨야 할 유형이 드러난다. 에피소드로 세면 한 사건을 띄엄띄엄 태그해도 n이 늘지 않는다. 통계 보드의 n < 5 숨김·5 ≤ n < 10 "일화" 규칙(PLAN §4.11)은 그 조건에 실제로 들어간 에피소드 수로 센다(START가 표본 안에 있는 에피소드 — N1 이하라 숨김이 늘어나는 쪽).

재생 표본은 Phase 0에서 `month_block` 전체(100개 달)다. MVP부터는 erd 6.5 S1의 재생 범위 조건을 `WHERE`에 더한다.

```sql
SELECT e.event_type, count(DISTINCT left(e.event_key, 6)) AS n, count(*) AS tag_rows,
       count(DISTINCT left(e.event_key, 6)) < 3 AS category_hidden
FROM month_event e JOIN month_block m ON m.id = e.month_block_id
-- WHERE <재생 표본 조건 — Phase 0은 없음, MVP는 S1>
GROUP BY e.event_type ORDER BY e.event_type COLLATE "C";
```

적재의 보고와 검증 SQL 6절이 같은 식을 쓴다. 표본이 100개 달뿐이라 **대부분의 유형이 n < 3**이다. 이것은 표본 크기 때문이며 MVP 전체 표본에서 다시 센다. `turn_feed.category_hidden`이 판정 결과를 저장하므로(erd D27) 표본이 바뀌어도 지난 시즌에 "보인 그대로"는 유지된다.

## 반응 변화율과 강도 경계

- `reaction_change` = `reaction_ref`가 가리키는 대상의 **그 달** 값이다. `KR:`·`US:`는 `asset_month_return.local_return`(현지 통화, 그 종목의 `return_basis`), `FX:USD`는 `fx_month.fx_change`다. 반올림 없이 그대로 복사한다(`NUMERIC(12,8)`, erd D20). 참조 달의 `return_kind`가 `PROXY_INDEX`면 반응값도 대리 지수 값이다(Phase 0 표본에는 없다).
- `strength`는 |`reaction_change`|가 **0.05 미만이면 1, 0.05 이상 0.10 미만이면 2, 0.10 이상이면 3**이다(경계값은 위 단계에 포함: 0.05 → 2, 0.10 → 3). 고정 상수라 표본 전체의 통계를 쓰지 않는다(PLAN §4.5 "이동 통계만").
- 왜 풀 종목에서 구하나: 카드의 숫자가 **그 턴 화면에 이미 있는 숫자와 같은 종류**(종목의 그 달 수익률)라 새 정보를 더하지 않고, 손으로 숫자를 옮기지 않아 재현되며 검증이 정확히 비교하고, 추적 파일에 시세 파생값이 남지 않는다. 대가: "유가 +28%"처럼 풀 밖 대상은 말할 수 없고 `OIL_SHOCK`은 에너지 종목(`US:XOM`)의 수익률이 대신한다. 따라서 `OIL_SHOCK`의 `direction`(사건의 방향)과 반응 숫자의 부호가 다를 수 있다.
- 숨긴 카드의 숫자가 특정 종목의 그 달 수익률과 같으면 범주가 샌다. 그래서 n < 3 유형의 태그는 시장 대표 종목(`KR:069500`·`US:SPY`)을 참조로 고른다. 예외는 `OIL_SHOCK`이다 — 허용 참조가 에너지 종목(`US:XOM`) 하나뿐이라 숨긴 카드의 숫자가 그 종목의 수익률과 같다. 정수 % 렌더는 일치를 흐릴 뿐 막지 못하며, 화면 숫자와 일부러 맞춰 봐야 보이는 단서라 받아들인다(PLAN §13-5 "우연히만 막는다"). `types.csv`의 허용 목록에는 대표 종목이 아닌 참조(`US:TLT`·`US:SHY`·`KR:055550`·`US:XOM`)도 있으니 n < 3 유형에 새 태그를 달 때는 대표 종목을 먼저 고른다.
- 반응값이 P0-08 무시 CSV에서 온 DB 값이므로 KIS 키가 없어 표본을 못 만든 사람은 적재·검증을 할 수 없다(P0-08과 같은 한계).

## 출처·인용·저작권

- **사실만, 문장 복제 금지:** `title`·`hindsight`는 작성자의 짧은 사실 요약이다. 기사·백과사전 문장을 옮기지 않고 인용문(따옴표)을 쓰지 않는다 — 따옴표류 문자는 적재가 거부한다. 사실(날짜·사건·결과) 자체는 저작물이 아니라고 보되 법률 판단은 아니다(보수 적용, PLAN §4.12).
- **출처 우선순위:** (1) 1차 공식 자료 — 중앙은행·정부 보도자료, 선거관리기관 결과, 국제기구 발표, 신용평가사 발표, 감독기관 공지. (2) 공개 백과사전·공공 통계(날짜 확인용). (3) 주요 언론 기사(로그인·유료 벽이 없는 것만). 로그인이 필요한 페이지는 쓰지 않는다. URL은 추적 매개변수를 빼고 적는다.
- 행마다 `checked_on`(확인한 날짜)을 적는다. 확인하지 못한 후보는 넣지 않고 아래 "보류 목록"에 남긴다. 출처끼리 달이 다르면 T1 규칙으로 정하고 "판단 기록"에 한 줄 적는다.
- 이번 1차(확인일 2026-11-07)에서 읽은 것은 중앙은행 공식 보도자료 6건(미국 연준)과 공개 백과사전 문서다. 주요 언론 기사는 열어 보지 않았다. 백과사전 문서만 출처인 행은 날짜·결과를 공식 자료로 다시 확인하면 좋다(2차 편집 과제).
- 저장소는 공개이므로 편집자의 이름·연락처를 쓰지 않는다.

## 누출 주의 (편집할 때)

| 경로 | 이 폴더의 대응 |
|---|---|
| 노출 코드에 연도·이름 | 숫자 금지, 두 글자 토큰은 `US`/`KR`만, 금지 토큰 목록 — 적재가 숫자·두 글자 토큰을, 검증 SQL이 금지 토큰까지 막는다 |
| 희소 유형의 카드 | n < 3 목록을 적재 보고와 검증이 보여 준다. 시즌에서는 범주를 말하지 않는 카드로 나간다 |
| 유형 + 결과 + 달력 월의 조합 | 받아들인다 — 통계 보드에서 찾아보는 것이 의도된 사용이다(PLAN §13-5 "우연히만 막는다") |
| 한 턴에 카드 여러 장 | 한 달 3개 상한. 하류가 카드 수와 소음 수를 섞는다 |
| 반응 숫자가 화면 종목 수익률과 같음 | n < 3 유형의 태그는 시장 대표 종목을 참조한다(예외 `OIL_SHOCK` — "반응 변화율과 강도 경계"). 하류는 정수 %로만 렌더 |
| 에이전트가 복기에서 실명을 받음 | `title`·`source`·`hindsight`는 복기 전용 — 에이전트 입력 금지(PLAN §4.16). 누출 필터의 사건 이름 사전은 `title` ∪ `hindsight` |
| 키에 날짜·이름 | 키는 의미 없는 번호(`ev####.##`) |

## 실행 (저장소 루트에서)

적재와 검증은 `db/README.md`의 (b″)와 같다. 작업 DB(`antlab`)에 01 → 02 → 10을 먼저 실행해 두고:

```bash
psql -X -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events.sql
psql -X -q -v ON_ERROR_STOP=1 -d antlab -f db/11_load_events_check.sql
```

- 적재의 두 번째 실행에는 새 행도 갱신도 없다(멱등). 파일과 DB의 값이 다르면 **오류로 멈추고 전부 되돌린다** — 값 정정은 하지 않는다(시즌이 참조한 뒤의 정정 정책은 MVP). Phase 0에서 이미 적재한 값을 고쳐야 하면 `db/README.md`의 (b′)로 DB를 다시 만든다.
- 검증의 마지막 줄이 `p0_09_check | PASS`이고 종료 코드가 0이면 통과다.

## 편집 방법

- 새 태그: 새 에피소드면 가장 큰 번호 다음 번호로 `ev####.01`을 만든다(기존 번호를 밀지 않는다. MVP 확장 편집은 "MVP 확장 편집 규칙" 4의 번호 구간 안에서 다음 번호를 쓴다). 같은 에피소드의 다음 달이면 `.02`처럼 순번을 잇는다. `tags.csv`를 `event_key` 순서로 정렬하고, 태그를 단 달이 `reviewed.csv`에 `TAGGED`로 있는지 확인한다.
- 새 유형: `types.csv`에 행을 더하고(정렬 유지) 이 문서의 유형 표를 같이 고친다. 코드 규칙은 위와 같다.
- 확장 구간(MVP)은 같은 형식으로 행을 더한다. 그 달들이 `month_block`에 들어온 뒤에 한다.

## 판단 기록

- 은행 사건: 2008-03과 2008-09는 원인·주체가 달라 다른 에피소드다. 2023-03과 2023-05의 지역은행 폐쇄는 같은 에피소드다. 2008-09에는 같은 달 구제 결정도 있었으나 태그 하나만 둔다(한 달 같은 유형 둘이면 나누지 않는다).
- 통화 완화: 2010-11의 자산 매입 발표는 새 에피소드로 나누지 않고 2007-09에 시작한 완화 에피소드의 `ONGOING`으로 둔다(판단이 어려우면 나누지 않는다).
- 무력 분쟁: 2025-06의 이스라엘·이란 직접 교전과 휴전은 2023-10에 시작한 중동 무력 분쟁 에피소드의 `ONGOING` + `CEASEFIRE`로 둔다(같은 이유). 2011-02의 리비아는 다른 에피소드다.
- 유가: 정점이 확인된 2008-07을 `START`(상승)로, 급락은 7월 고점 대비 연저점이 확인된 2008-12를 `ONGOING`(하락)으로 둔다. 상승 국면이 그 전부터 이어졌으므로 `START`는 엄밀히 "처음 1면에 오른 달"이 아니라 "확인한 첫 달"이다.
- 팬데믹: 2009-04는 국제 공중보건 비상사태 선포 달을 `START`로, 2009-06은 대유행 선언 달을 `ONGOING`으로 둔다.
- 국가 부채 위기: 위기는 2009년 말부터였으나 확인한 것은 2010-05의 구제금융 합의다. `START`를 그 달에 둔다(앞선 달은 보류 목록).
- 관세(2025): 2025-04는 그 달 말 확정 결과인 유예(`PAUSED`)로 쓴다. 같은 달의 맞대응은 태그하지 않았다(한 행에 결과 하나). 중국은 유예 대상에서 빠졌다.
- 대선(한국): 옛 정의(현직 대통령 소속 정당)는 2007-12와 2025-06(파면 뒤 권한대행 체제)에서 판정이 모호해 기준을 직전 당선자의 당시 정당으로 바꿨고, 두 달 모두 `CHANGE`다.
- 군사 도발(2010-03): 그 달 말에는 원인이 발표되지 않았다. 범주는 그 달에도 맞고 유형은 표본 수가 적어 시즌 중 나가지 않으므로 유형을 그대로 두고, 확정된 원인은 `hindsight`에 쓴다. 표본이 커져 유형이 공개되면(MVP) 다시 본다.
- 정치 위기: 2024-12(비상계엄 선포와 해제, 탄핵소추안 가결)와 2025-04(탄핵 인용 결정)를 하나의 에피소드로 둔다. 이름·정당 없이 사실만 쓰고 평가를 쓰지 않는다.

## 보류 목록

출처로 날짜·결과를 확인하지 못했거나 이번에 넣지 않기로 한 후보다. 추측으로 채우지 않았다.

| 후보 | 이유 |
|---|---|
| 2011-08 국가 부채 위기 확산(`SOVEREIGN_DEBT`, `ONGOING`, `ESCALATED`) | 열어 본 문서에서 그 달의 확산 사건을 확인하지 못했다 |
| 2011년 초 유가 급등(`OIL_SHOCK`, `UP`) | 문서는 "2011년 초"까지만 말하고 달을 정하지 못했다(2월/3월) |
| 2025-05 관세 일시 합의(`TARIFF`, `ONGOING`, `AGREED`) | 열어 본 문서에서 합의 날짜를 확인하지 못했다 |
| 2008-10 유가 급락 | 월을 확인하지 못해 연저점이 확인된 2008-12로 대신했다 |
| 국가 부채 위기의 `START` 앞선 달(2009-12–2010-04) | 그리스 재정 적자 공개·등급 강등 등을 확인하지 못했다 |
| 2008-09 보험사 구제 결정 | 같은 달 은행 파산과 한 에피소드로 보아 따로 태그하지 않았다(결정이 아니라 선택) |
| 2차 편집 과제 | 시장 반응이 큰 달에서 거꾸로 사건 찾기, 공식 자료로 출처 보강 |

## 하류 약속

- **P0-12:** 사건 조건의 단위는 `event_type`, 카드의 n은 N1, 통계 보드의 n은 그 조건에 들어간 에피소드 수. "사건 뒤 k개월"의 기준 달은 에피소드의 `START` 행 달이다. 표본 안에 `START`가 없는 에피소드는 기준 달이 없으므로 그 조건에서 뺀다. 표본이 끊긴 구간(2012-04 ↔ 2023-01)을 넘는 "k개월 뒤"는 없는 달로 본다.
- **P0-18:** 사건 있는 달의 카드 후보는 그 블록의 `month_event` 행이다. `category_hidden` = 생성 시점의 N1 n < 3. 숨긴 카드의 반응 숫자는 정수 %로만 그린다. 카드 수와 소음 수를 섞어 카드 수가 위기 달의 표식이 되지 않게 한다. 숨긴 `OIL_SHOCK` 카드의 숫자는 에너지 종목 수익률과 같다 — 숨긴 카드에 숫자를 낼지, 낸다면 어떤 반올림인지 P0-18이 정한다.
- **MVP:** DTO는 `title`·`source`·`hindsight`를 시즌 중 내지 않고, n < 3 유형의 범주·유형·결과·단계·방향도 내지 않는다. `reaction_change`는 화면과 같은 반올림으로만 낸다. `event_key`·`month_event.id`를 시즌 DTO·URL에 넣지 않는다(카드는 `turn_feed` id로 가리킨다). 확장 구간에서 대리 지수 값인 반응을 그대로 쓸지 참조를 바꿀지 정한다. 재생 표본 조건을 N1 쿼리에 넣는다. 검증 SQL의 `month_block` 100행·`REVIEWED_ALL`은 재생 표본 조건(erd 6.5 S1)으로 한정하며 "태그 달만 TRUE"로 완화하지 않는다. 역사 행 정정 정책과 `month_block.event_summary` 채우기를 정한다. CI 합성 표본에 사건 표본을 포함한다.
- **AG-01·PLAN §4.16:** 탐지 시험의 표본은 P0-08의 100개 달이고, 사건 카드는 사람 화면과 같은 가림을 거친 값만 넣는다. 메모리 누출 필터의 사건 이름 사전은 `tags.csv`의 `title` ∪ `hindsight`다.
