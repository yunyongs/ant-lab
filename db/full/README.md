# db/full — MVP asset pool (selection for approval)

Hand-edited pool files for the MVP history (180 assets x 360 month blocks). This folder holds the candidate list, the selection rule and, since 02-B, the selected pool (`asset.csv`) and the fetch targets. No price or return value is tracked here.

> **Status: selection for approval (02-B).** 216 candidates, 180 `SELECTED`, 34 `RESERVE`, 2 `FAILED_CHECK`. Merging this PR is the pool approval, but approval is blocked until the 28 `TO_CONFIRM` ETF listing dates are confirmed from issuer pages and the `CHECK_PENDING` row is resolved (see "Open items"). Listing dates marked `TO_CONFIRM` are best public knowledge, not verified against a primary page (see "Open items").

## Files

| File | Kind | Tracked | Content |
|------|------|---------|---------|
| `pool_proposal.csv` | hand-edited | yes | 216 candidates: KR stock 60, US stock 120, ETF 36 (KR-listed 18, US-listed 18) |
| `asset_group.csv` | hand-edited | yes | 22 groups: the 12 of `db/sample/asset_group.csv` plus 10 new (`BOND_BROAD` is used only by a reserve) |
| `asset.csv` | generated from the selection | yes | the 180 `SELECTED` rows, same 9 columns as `db/sample/asset.csv`; `proxy_index_name` is filled only for the P0-08 assets (the rest is MVP-03) |
| `fetch_targets.csv` | generated from the selection | yes | the 180 fetch targets in the batch tool's format (`tools/fetch/batch.py`), `order_no` in drip order |
| `README.md` | document | yes | rule, columns, selection result, approval flow |

## Selection rule

| Group | Rule | Places | Candidates |
|-------|------|--------|-----------|
| KR stock | Large caps of the KOSPI (KOSPI 200 members) at the snapshot date, **listed before 2007-01**, ordered by the public market-cap snapshot, one share class per company (common shares) | 50 | 60 |
| US stock | Current S&P 500 members, **IPO before 2007-01**, ordered by the public market-cap snapshot, one share class per company | 100 | 120 |
| ETF | Role coverage, not size: the 15 ETFs of `docs/MACRO_ETF.md` §3.2 (all kept), the ETFs of the P0-08 sample (`rule_rank` 1–20), then equity indexes, sectors, bonds, gold and commodities by role | 30 (both markets) | 36 |

- **Market cap orders the pool, never a position judgement.** PLAN §7 defines the pool as the market-cap top (KOSPI 200 top 50, S&P 500 top 100), so `mcap_rank_public` orders the candidates (`rule_rank`) and therefore which ones are selected. The owner decision of 2026-11-08 (PLAN §13, "no market cap in judgement") is about position judgement only: market cap is never an input to `asset_position` positions. Values are not stored, only the rank.
- `mcap_rank_public` is the row position in the public lists of `companiesmarketcap.com` (South Korea and USA, fetched 2026-11-18, `mcap_asof`). The position counts every company on the page, including ones the rule excludes. ETFs have no rank.
- A Korean company the snapshot lists by a US depositary ticker is mapped to its KRX code (`note` = `RANK_FROM_ADR_ROW`); where the snapshot shows a preferred-share code, the pool uses the common-share code (`RANK_FROM_PREFERRED_CODE_ROW`).
- All 20 P0-08 sample assets are candidates and sit inside the first places.
- Rows are sorted by `market`, `asset_type`, `rule_rank`; the ETF `rule_rank` is one sequence across both markets.

## Columns of `pool_proposal.csv` and allowed values

`market,ticker,name,asset_type,group_code,listed_on,listed_check,return_basis,rule,rule_rank,mcap_rank_public,mcap_asof,source_url,kis_excd,yf_symbol,kis_first_month,yf_first_month,has_2006_12,status,owner_decision,note`

| Column | Allowed values / format | Filled by |
|--------|-------------------------|-----------|
| `market` | `KR`, `US` | 02-A |
| `ticker` | KR: 6-character code; US: ticker, dot kept (`BRK.B`) | 02-A |
| `name`, `group_code` | display name; a code of `asset_group.csv` | 02-A |
| `asset_type` | `STOCK`, `ETF` | 02-A |
| `listed_on` | `YYYY-MM-DD`; if the day is unknown, the first of the month or year and `note` has `DATE_APPROX` | 02-A |
| `listed_check` | `CONFIRMED`, `TO_CONFIRM` | 02-A, updated in 02-B |
| `return_basis` | `PRICE` (KR), `TOTAL` (US) | 02-A |
| `rule`, `rule_rank` | rule code; 1..N within (market, asset type), one sequence for ETFs | 02-A |
| `mcap_rank_public`, `mcap_asof` | integer and `YYYY-MM-DD`; stocks only | 02-A |
| `source_url` | public `https://` page to check the row (not a stored price) | 02-A |
| `yf_symbol` | yfinance symbol for the cross-check (dash for dotted tickers) | 02-A |
| `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` | KIS exchange code; `YYYY-MM`; `Y`/`N` | `kis_excd` 02-A; the other three 02-B from the sweep markers and the yfinance price files (blank = not measured yet) |
| `status` | `SELECTED`, `RESERVE`, `FAILED_CHECK` | 02-B |
| `owner_decision` | blank, `DROP`, `KEEP` | owner, in review of 02-B |
| `note` | flags: `DATE_APPROX`, `SHARE_CLASS`, `TICKER_OR_STRUCTURE_CHANGE` (check that history is continuous), `RANK_FROM_*`, ETF role | 02-A |

## How `listed_check` works

- `TO_CONFIRM`: the date is a best public value that no one has checked against a primary page. For a stock it is review-only. The binding test for "listed before 2007-01" is measurable: a month-end close for 2006-12 in the canonical source (`has_2006_12 = Y`), written by the sweep (PLAN §13-7).
- `CONFIRMED`: checked against a public page and recorded. An ETF's `listed_on` drives the proxy months of the history (H7), so every pool ETF must be `CONFIRMED` from its issuer page before approval.

## Availability and approval flow

1. **02-A:** candidates, rule, groups. Availability columns stay empty.
2. **Sweep:** the KIS monthly sweep fills `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` for all 216 candidates (no price is stored).
3. **02-B (this PR):** `status` is set. A candidate that fails a check (no 2006-12 close, a gap in the main period from 2007-01, a rejected symbol) is `FAILED_CHECK` and the next candidate by `rule_rank` takes its place. The first 50 / 100 / 30 rows that pass are `SELECTED`; the rest are `RESERVE`. `asset.csv`, `asset_group.csv` and `fetch_targets.csv` are generated from the selection.
4. **Owner approval:** the owner reads the 02-B PR. A row-level change is made through `owner_decision` (`DROP` / `KEEP`) or a review comment. **Merging the 02-B PR is the pool approval.**

## Selection result (02-B)

| Group | Candidates | SELECTED | RESERVE | FAILED_CHECK |
|-------|-----------|----------|---------|--------------|
| KR stock | 60 | 50 | 8 | 2 |
| US stock | 120 | 100 | 20 | 0 |
| ETF | 36 | 30 (KR-listed 16, US-listed 14) | 6 | 0 |

- **How the checks were read.** the cached raw files were read month by month (distinct months of the KIS monthly pages in `data/raw/mvp-04/kis` and of the yfinance price files in `data/raw/mvp-04/yf`; bar counts are not used). `has_2006_12 = Y` only if the canonical source has an observation in 2006-12: KIS for KR, yfinance for US (the KIS US floor is 2007-08). Continuity is checked for the main period per canonical segment: KR stock and KR ETF, KIS from the first month (from 2006-12 for stocks) to 2025-12; US, yfinance from 2006-12 to the cut month `yf_canonical_to` and KIS from the cut month to 2025-12. Any missing month fails a stock or an ETF as `FAILED_CHECK` (reason in `note`); none was found. ETFs need no 2006-12 close (their early months are proxy months, H7), and the yfinance segment of a US ETF is not measured yet, so its early coverage is not claimed.
- **`FAILED_CHECK` (2):** `028260` (first KIS month 2014-12, surviving entity of a 2015 merger) and `034730` (first KIS month 2009-11). The next ranks (51, 52) took their places. The reason is in `note` (`FAILED_CHECK: ...`).
- **`RESERVE` (34):** KR stock ranks 53–60, US stock ranks 101–120, ETF ranks 31–36 (`091170`, `102110`, `XLU`, `DIA`, `LQD`, `AGG`). The 20 US reserves have no yfinance measurement yet (`CHECK_PENDING: YF_PENDING` in `note`).
- **ETF split.** All 15 ETFs of `docs/MACRO_ETF.md` §3.2 and the 20 P0-08 assets are `SELECTED`; ETF ranks 1–30 pass the checks that can be run now (KIS segment continuity); the listing dates are still open (see below). The planning estimate was KR 18 + US 12; the real split by role coverage is KR 16 + US 14.
- **`fetch_targets.csv`.** Order: `069500`, `SPY`, `QQQ`, `GLD` (proxy bases known today), the other P0-08 assets, KR stocks, KR ETFs, US stocks, US ETFs (each by `rule_rank`). `kis_basis` is `RAW` for KR ETFs and `ADJUSTED` otherwise; `kis_from` is the first month the sweep measured; `yf_canonical_to` for a US asset is December of `max(2007, year of kis_first_month)` (decision Q4: yfinance is canonical through 2007-12; an asset with a later KIS start keeps yfinance up to the December of its start year).
- The sweep measured the listing month of every KR ETF equal to the month of its first KIS bar.

### Open items for the owner

- **`CHECK_PENDING` rows (all of them).** Selected: `BRK.B` (US stock rank 7), `SELECTED` but not yet passed: `has_2006_12` is blank and `note` has `CHECK_PENDING: YF_PENDING` because its yfinance file (`BRK-B`) is not fetched; the KIS symbol form works (first KIS month 2012-09, continuous). The test requires every selected stock to be `has_2006_12 = Y` or explicitly `CHECK_PENDING`. Resolve it before or right after merge: if the yfinance run shows no 2006-12 close or a gap, the first US reserve (`PWR`, rank 101, also pending) takes its place. Reserves: `PWR`, `CMCSA`, `PNC`, `WAT`, `CTAS`, `EMR`, `SPG`, `MNST`, `MSI`, `INTU`, `AMT`, `ECL`, `MDLZ`, `WMB`, `CDNS`, `MCO`, `SNPS`, `SHW`, `RCL`, `TRV`. 20 US reserves and all 18 US ETFs have no `yf_first_month` yet; 78 KR rows have no yfinance measurement (cross-check only).
- **`TO_CONFIRM` listing dates — blocks approval for ETFs.** 28 of the 30 selected ETFs are still `TO_CONFIRM` (only `069500` and `411060` are `CONFIRMED`), and 150 selected stocks (review-only). No network was used in 02-B, so no date was checked against an issuer page; the issuer confirmation required before approval (see "How `listed_check` works") is still to be done. A wrong listing month moves the ACTUAL/proxy boundary. The sweep only shows that each KR ETF's first KIS bar falls in the listed month. ETFs to confirm, because `listed_on` sets their proxy months: `360750` (sources differ by one day), `133690`, `458730`, `476850`, `426030`, `0019K0`, `476760`, `148070`, `114260` (`DATE_APPROX`), `329750`, `459580`, `160580`, `261220`, `091160` and the 14 US ETFs.
- **`LISTED_ON_CONFLICT` (6 KR stocks):** `009540`, `024110`, `003550`, `021240`, `071050`, `006800` have KIS bars from a month before their approximate `listed_on`. The measured month is the one that counts for the history; the `listed_on` of these rows is wrong or approximate.
- The `BRK.B` `note` still says "KIS symbol form to be measured (M9)"; it is measured now.

## Check

`python3 -m pytest tools/fetch/tests/test_pool_proposal.py` validates columns, key uniqueness, allowed values, date format, source URL presence, counts per group, rank order, the P0-08 rows and the §3.2 ETFs, plus the status counts (50 / 100 / 30), `asset.csv` against the selection and the sample contract, and `fetch_targets.csv` through the batch tool's own reader. `python3 tools/fetch/fetch_batch.py status --targets db/full/fetch_targets.csv` reads the targets and sends nothing. No network and no keys are needed.

---

# db/full — MVP 종목 풀 (승인용 선정 결과)

MVP 이력(종목 180 × 월 블록 360)을 위한 손 편집 풀 파일이다. 이 폴더에는 후보 목록, 선정 규칙, 그리고 02-B부터 선정된 풀(`asset.csv`)과 수집 대상 파일이 있다. 가격이나 수익률 값은 추적하지 않는다.

> **상태: 승인용 선정 결과(02-B).** 후보 216종 중 `SELECTED` 180, `RESERVE` 34, `FAILED_CHECK` 2. 이 PR의 머지가 풀 승인이지만, `TO_CONFIRM`인 ETF 상장일 28종을 운용사 페이지로 확인하고 `CHECK_PENDING` 행을 해소하기 전에는 승인할 수 없다("남은 확인" 참고). `TO_CONFIRM`인 상장일은 공개 자료에 따른 최선의 값이며 1차 페이지로 확인하지 않았다("남은 확인" 참고).

## 파일

| 파일 | 종류 | 추적 | 내용 |
|------|------|------|------|
| `pool_proposal.csv` | 손 편집 | 예 | 후보 216종: 국내 주식 60, 미국 주식 120, ETF 36(국내 상장 18, 미국 상장 18) |
| `asset_group.csv` | 손 편집 | 예 | 그룹 22개: `db/sample/asset_group.csv`의 12개 + 새 10개(`BOND_BROAD`는 예비 후보만 쓴다) |
| `asset.csv` | 선정에서 생성 | 예 | `SELECTED` 180행, `db/sample/asset.csv`와 같은 9열. `proxy_index_name`은 P0-08 종목만 채우고 나머지는 MVP-03 |
| `fetch_targets.csv` | 선정에서 생성 | 예 | 수집 대상 180개, 배치 도구 형식(`tools/fetch/batch.py`), `order_no`는 드립 순서 |
| `README.md` | 문서 | 예 | 규칙, 열, 선정 결과, 승인 흐름 |

## 선정 규칙

| 구분 | 규칙 | 자리 | 후보 |
|------|------|------|------|
| 국내 주식 | 기준 시점의 KOSPI 대형주(KOSPI 200 구성) 중 **2007-01 이전 상장**, 공개 시가총액 스냅샷 순, 회사당 한 종류(보통주) | 50 | 60 |
| 미국 주식 | 현재 S&P 500 구성 종목 중 **2007-01 이전 IPO**, 공개 시가총액 스냅샷 순, 회사당 한 종류 | 100 | 120 |
| ETF | 크기가 아니라 역할 포괄: `docs/MACRO_ETF.md` §3.2의 15종(모두 유지), P0-08 표본의 ETF(`rule_rank` 1–20), 그다음 주식 지수·섹터·채권·금·원자재를 역할별로 | 30(두 시장 합) | 36 |

- **시가총액은 풀을 정할 뿐 포지션 판정에는 쓰이지 않는다.** PLAN §7이 풀을 시가총액 상위(KOSPI 200 상위 50, S&P 500 상위 100)로 정하므로 `mcap_rank_public`이 후보 순서(`rule_rank`)와 선정 결과를 정한다. 2026-11-08 소유자 결정(PLAN §13 "판정에서 시가총액 제외")은 포지션 판정에 한정된다: 시가총액은 `asset_position`의 포지션을 정하는 입력이 아니다. 값은 저장하지 않고 순위만 둔다.
- `mcap_rank_public`은 `companiesmarketcap.com`의 한국·미국 공개 목록(2026-11-18 수집, `mcap_asof`)의 행 순서다. 규칙이 제외하는 회사도 순서에 센다. ETF는 순위가 없다.
- 스냅샷이 미국 예탁 증서 티커로 보여 주는 한국 회사는 KRX 코드로 바꾸고(`note` = `RANK_FROM_ADR_ROW`), 우선주 코드로 보여 주는 회사는 풀에 보통주 코드를 쓴다(`RANK_FROM_PREFERRED_CODE_ROW`).
- P0-08 표본 20종은 모두 후보이며 앞 자리 안에 있다.
- 행은 `market`, `asset_type`, `rule_rank` 순이고 ETF의 `rule_rank`는 두 시장에 걸친 하나의 순서다.

## `pool_proposal.csv` 열과 허용 값

`market,ticker,name,asset_type,group_code,listed_on,listed_check,return_basis,rule,rule_rank,mcap_rank_public,mcap_asof,source_url,kis_excd,yf_symbol,kis_first_month,yf_first_month,has_2006_12,status,owner_decision,note`

| 열 | 허용 값 / 형식 | 채우는 쪽 |
|----|----------------|-----------|
| `market` | `KR`, `US` | 02-A |
| `ticker` | 국내: 6자 코드, 미국: 티커(점 유지, `BRK.B`) | 02-A |
| `name`, `group_code` | 표시 이름, `asset_group.csv`의 코드 | 02-A |
| `asset_type` | `STOCK`, `ETF` | 02-A |
| `listed_on` | `YYYY-MM-DD`. 일을 모르면 그 달 또는 그 해의 1일이고 `note`에 `DATE_APPROX` | 02-A |
| `listed_check` | `CONFIRMED`, `TO_CONFIRM` | 02-A, 02-B에서 갱신 |
| `return_basis` | `PRICE`(국내), `TOTAL`(미국) | 02-A |
| `rule`, `rule_rank` | 규칙 코드, (시장, 유형)별 1..N(ETF는 하나의 순서) | 02-A |
| `mcap_rank_public`, `mcap_asof` | 정수, `YYYY-MM-DD`. 주식만 | 02-A |
| `source_url` | 행을 확인하는 공개 `https://` 페이지(저장한 가격이 아님) | 02-A |
| `yf_symbol` | 교차 확인용 yfinance 심볼(점이 있는 티커는 대시) | 02-A |
| `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` | KIS 거래소 코드, `YYYY-MM`, `Y`/`N` | `kis_excd`는 02-A, 나머지 셋은 02-B가 훑기 표지와 yfinance 시세 파일에서 채움(빈 칸 = 아직 측정 안 함) |
| `status` | `SELECTED`, `RESERVE`, `FAILED_CHECK` | 02-B |
| `owner_decision` | 빈 칸, `DROP`, `KEEP` | 소유자(02-B 검토 때) |
| `note` | 표시: `DATE_APPROX`, `SHARE_CLASS`, `TICKER_OR_STRUCTURE_CHANGE`(이력이 이어지는지 확인), `RANK_FROM_*`, ETF 역할 | 02-A |

## `listed_check`의 뜻

- `TO_CONFIRM`: 아직 1차 페이지로 확인하지 않은 공개 값이다. 주식에서는 검토용이다. "2007-01 이전 상장"의 구속력 있는 검사는 측정 가능한 형태로 훑기가 적는다: 정본 출처의 2006-12 월말 종가(`has_2006_12 = Y`, PLAN §13-7).
- `CONFIRMED`: 공개 페이지로 확인하고 기록했다. ETF의 `listed_on`은 이력의 대체 달(H7)을 정하므로, 풀의 모든 ETF는 승인 전에 운용사 페이지로 `CONFIRMED`가 돼야 한다.

## 가용성과 승인 흐름

1. **02-A:** 후보, 규칙, 그룹. 가용성 열은 비어 있다.
2. **훑기:** KIS 월봉 훑기가 후보 216종의 `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12`를 채운다(가격은 저장하지 않는다).
3. **02-B(이 PR):** `status`를 정한다. 검사에 떨어진 후보(2006-12 종가 없음, 2007-01 이후 본 구간 결측, 거부된 심볼)는 `FAILED_CHECK`가 되고 `rule_rank` 다음 후보가 자리를 채운다. 통과한 앞 50 / 100 / 30행이 `SELECTED`, 나머지는 `RESERVE`다. `asset.csv`, `asset_group.csv`, `fetch_targets.csv`는 이 선택에서 만든다.
4. **소유자 승인:** 소유자가 02-B PR을 읽는다. 행 단위 변경은 `owner_decision`(`DROP` / `KEEP`)이나 리뷰 코멘트로 한다. **02-B PR의 머지가 풀 승인이다.**

## 선정 결과 (02-B)

| 구분 | 후보 | SELECTED | RESERVE | FAILED_CHECK |
|------|------|----------|---------|--------------|
| 국내 주식 | 60 | 50 | 8 | 2 |
| 미국 주식 | 120 | 100 | 20 | 0 |
| ETF | 36 | 30(국내 상장 16, 미국 상장 14) | 6 | 0 |

- **검사를 읽은 방법.** 캐시한 원본 파일을 달마다 읽었다(`data/raw/mvp-04/kis`의 KIS 월봉 페이지와 `data/raw/mvp-04/yf`의 yfinance 시세 파일에서 중복 없는 달을 센다. 봉 개수는 쓰지 않는다). 정본 출처에 2006-12 관측이 있을 때만 `has_2006_12 = Y`: 국내는 KIS, 미국은 yfinance(KIS 미국 하한이 2007-08). 연속성은 정본 구간마다 본 구간(2007-01 이후)에서 본다: 국내 주식·ETF는 KIS 첫 달(주식은 2006-12)부터 2025-12까지, 미국은 yfinance 2006-12부터 전환 달 `yf_canonical_to`까지와 KIS 전환 달부터 2025-12까지. 빠진 달이 있으면 주식·ETF 모두 `FAILED_CHECK`(사유는 `note`)이며, 빠진 달은 없었다. ETF는 2006-12 종가가 필요 없고(초기 달은 대체 달, H7) 미국 ETF의 yfinance 구간은 아직 측정하지 않아 초기 커버리지를 주장하지 않는다.
- **`FAILED_CHECK`(2):** `028260`(KIS 첫 달 2014-12, 2015년 합병 존속 법인), `034730`(KIS 첫 달 2009-11). 다음 순위(51, 52)가 자리를 채웠다. 사유는 `note`(`FAILED_CHECK: ...`)에 있다.
- **`RESERVE`(34):** 국내 주식 53–60위, 미국 주식 101–120위, ETF 31–36위(`091170`, `102110`, `XLU`, `DIA`, `LQD`, `AGG`). 미국 예비 20종은 아직 yfinance 측정이 없다(`note`의 `CHECK_PENDING: YF_PENDING`).
- **ETF 구성.** `docs/MACRO_ETF.md` §3.2의 15종과 P0-08 20종이 모두 `SELECTED`이고 ETF 1–30위는 지금 할 수 있는 검사(KIS 구간 연속성)를 통과했고, 상장일 확인은 아직 남았다(아래). 계획 단계 추정은 국내 18 + 미국 12였고, 역할 포괄로 정한 실제 구성은 국내 16 + 미국 14다.
- **`fetch_targets.csv`.** 순서: `069500`, `SPY`, `QQQ`, `GLD`(지금 알려진 대체 기초), 나머지 P0-08 종목, 국내 주식, 국내 ETF, 미국 주식, 미국 ETF(각각 `rule_rank` 순). `kis_basis`는 국내 ETF `RAW`, 나머지 `ADJUSTED`. `kis_from`은 훑기가 잰 첫 달. 미국 종목의 `yf_canonical_to`는 `max(2007, kis_first_month의 연도)`의 12월(결정 Q4: yfinance 정본은 2007-12까지, KIS 시작이 더 늦은 종목은 시작 연도 12월까지).
- 훑기가 잰 국내 ETF의 상장 달은 모두 첫 KIS 봉의 달과 같다.

### 소유자가 볼 남은 확인

- **`CHECK_PENDING` 행 전부.** 선정: `BRK.B`(미국 주식 7위)는 `SELECTED`이지만 아직 통과하지 않았다. yfinance 파일(`BRK-B`)을 받지 않아 `has_2006_12`가 비어 있고 `note`에 `CHECK_PENDING: YF_PENDING`이 있다. KIS 심볼 형식은 통하고 첫 KIS 달 2012-09부터 끊김이 없다. 테스트는 선정 주식이 `has_2006_12 = Y`이거나 명시적 `CHECK_PENDING`일 것을 요구한다. 머지 전후에 해소하라: yfinance 실행에서 2006-12 종가가 없거나 빠진 달이 있으면 미국 예비 1순위(`PWR`, 101위, 이것도 보류)가 자리를 채운다. 예비: `PWR`, `CMCSA`, `PNC`, `WAT`, `CTAS`, `EMR`, `SPG`, `MNST`, `MSI`, `INTU`, `AMT`, `ECL`, `MDLZ`, `WMB`, `CDNS`, `MCO`, `SNPS`, `SHW`, `RCL`, `TRV`. 미국 예비 20종과 미국 ETF 18종 전부 `yf_first_month`가 아직 없고, 국내 78행은 yfinance 측정이 없다(교차 확인용일 뿐).
- **`TO_CONFIRM` 상장일 — ETF는 승인을 막는다.** 선정 ETF 30종 중 28종이 아직 `TO_CONFIRM`이고(`069500`, `411060`만 `CONFIRMED`) 선정 주식 150종도 그렇다(주식은 검토용). 02-B는 네트워크를 쓰지 않아 운용사 페이지로 확인한 날짜가 없고, 승인 전에 필요한 운용사 확인("`listed_check`의 뜻" 참고)은 아직 남았다. 상장 달이 틀리면 ACTUAL/대체 경계가 움직인다. 훑기는 국내 ETF의 첫 KIS 봉이 상장 달에 있음을 보일 뿐이다. `listed_on`이 ETF의 대체 달을 정하므로 확인할 ETF: `360750`(출처마다 하루 차이), `133690`, `458730`, `476850`, `426030`, `0019K0`, `476760`, `148070`, `114260`(`DATE_APPROX`), `329750`, `459580`, `160580`, `261220`, `091160`, 미국 ETF 14종.
- **`LISTED_ON_CONFLICT`(국내 주식 6종):** `009540`, `024110`, `003550`, `021240`, `071050`, `006800`은 KIS 봉이 근사 `listed_on`보다 앞선 달에 시작한다. 이력에는 측정한 달이 쓰이며, 이 행들의 `listed_on`은 틀렸거나 근사값이다.
- `BRK.B`의 `note`에 "KIS symbol form to be measured (M9)"가 남아 있다. 지금은 측정되었다.

## 검사

`python3 -m pytest tools/fetch/tests/test_pool_proposal.py`가 열, 키 중복, 허용 값, 날짜 형식, 출처 URL 유무, 그룹별 후보 수, 순위 순서, P0-08 행, §3.2 ETF에 더해 상태 개수(50 / 100 / 30), 선정과 `asset.csv`의 일치와 표본 계약, 배치 도구 자체 리더로 읽은 `fetch_targets.csv`를 검사한다. `python3 tools/fetch/fetch_batch.py status --targets db/full/fetch_targets.csv`는 대상을 읽기만 하고 아무것도 보내지 않는다. 네트워크와 키가 필요 없다.
