# db/full — MVP asset pool (candidate draft)

Hand-edited pool files for the MVP history (180 assets x 360 month blocks). This folder holds the candidate list and the selection rule. The final pool (`asset.csv`) and the fetch targets arrive in the second data PR (02-B), after the KIS monthly sweep has filled the availability columns. No price or return value is tracked here.

> **Status: candidate draft (02-A).** 216 candidates for 180 places. Availability columns are empty on purpose. Listing dates marked `TO_CONFIRM` are best public knowledge, not verified; they are review-only for stocks and must be `CONFIRMED` from issuer pages for ETFs before the pool is approved.

## Files

| File | Kind | Tracked | Content |
|------|------|---------|---------|
| `pool_proposal.csv` | hand-edited | yes | 216 candidates: KR stock 60, US stock 120, ETF 36 (KR-listed 18, US-listed 18) |
| `asset_group.csv` | hand-edited | yes | 22 groups: the 12 of `db/sample/asset_group.csv` plus 10 new |
| `README.md` | document | yes | rule, columns, approval flow |

`asset.csv` (same 9 columns as `db/sample/asset.csv`) and `fetch_targets.csv` are produced by 02-B from the rows that pass every check.

## Selection rule

| Group | Rule | Places | Candidates |
|-------|------|--------|-----------|
| KR stock | Large caps of the KOSPI (KOSPI 200 members) at the snapshot date, **listed before 2007-01**, ordered by the public market-cap snapshot, one share class per company (common shares) | 50 | 60 |
| US stock | Current S&P 500 members, **IPO before 2007-01**, ordered by the public market-cap snapshot, one share class per company | 100 | 120 |
| ETF | Role coverage, not size: the 15 ETFs of `docs/MACRO_ETF.md` §3.2 (all kept), the ETFs of the P0-08 sample (`rule_rank` 1–20), then equity indexes, sectors, bonds, gold and commodities by role | 30 (both markets) | 36 |

- **Market cap is a reference column only.** It orders the candidates (`rule_rank`) and appears as `mcap_rank_public`; it is never an input to a position judgement (PLAN §13, "no market cap in judgement"). Values are not stored, only the rank.
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
| `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` | KIS exchange code; `YYYY-MM`; `Y`/`N` | **empty here; filled by the KIS monthly sweep** |
| `status` | `SELECTED`, `RESERVE`, `FAILED_CHECK` | 02-B |
| `owner_decision` | blank, `DROP`, `KEEP` | owner, in review of 02-B |
| `note` | flags: `DATE_APPROX`, `SHARE_CLASS`, `TICKER_OR_STRUCTURE_CHANGE` (check that history is continuous), `RANK_FROM_*`, ETF role | 02-A |

## How `listed_check` works

- `TO_CONFIRM`: the date is a best public value that no one has checked against a primary page. For a stock it is review-only. The binding test for "listed before 2007-01" is measurable: a month-end close for 2006-12 in the canonical source (`has_2006_12 = Y`), written by the sweep (PLAN §13-7).
- `CONFIRMED`: checked against a public page and recorded. An ETF's `listed_on` drives the proxy months of the history (H7), so every pool ETF must be `CONFIRMED` from its issuer page before approval.

## Availability and approval flow

1. **02-A (this draft):** candidates, rule, groups. Availability columns stay empty.
2. **Sweep:** the KIS monthly sweep fills `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` for all 216 candidates (no price is stored).
3. **02-B:** `status` is set. A candidate that fails a check (no 2006-12 close, a gap in the main period from 2007-01, a rejected symbol) is `FAILED_CHECK` and the next candidate by `rule_rank` takes its place. The first 50 / 100 / 30 rows that pass are `SELECTED`; the rest are `RESERVE`. `asset.csv`, `asset_group.csv` and `fetch_targets.csv` are generated from the selection.
4. **Owner approval:** the owner reads the 02-B PR. A row-level change is made through `owner_decision` (`DROP` / `KEEP`) or a review comment. **Merging the 02-B PR is the pool approval.**

## Check

`python3 -m pytest tools/fetch/tests/test_pool_proposal.py` validates columns, key uniqueness, allowed values, date format, source URL presence, counts per group, rank order, the P0-08 rows and the §3.2 ETFs. It needs no network and no keys.

---

# db/full — MVP 종목 풀 (후보 초안)

MVP 이력(종목 180 × 월 블록 360)을 위한 손 편집 풀 파일이다. 이 폴더에는 후보 목록과 선정 규칙이 있다. 최종 풀(`asset.csv`)과 수집 대상 파일은 KIS 월봉 훑기가 가용성 열을 채운 뒤 두 번째 데이터 PR(02-B)에서 만든다. 가격이나 수익률 값은 추적하지 않는다.

> **상태: 후보 초안(02-A).** 180자리에 후보 216종. 가용성 열은 일부러 비워 둔다. `TO_CONFIRM`인 상장일은 공개 자료에 따른 최선의 값이며 검증하지 않았다. 주식은 검토용이고, ETF는 풀 승인 전에 운용사 페이지로 `CONFIRMED`가 돼야 한다.

## 파일

| 파일 | 종류 | 추적 | 내용 |
|------|------|------|------|
| `pool_proposal.csv` | 손 편집 | 예 | 후보 216종: 국내 주식 60, 미국 주식 120, ETF 36(국내 상장 18, 미국 상장 18) |
| `asset_group.csv` | 손 편집 | 예 | 그룹 22개: `db/sample/asset_group.csv`의 12개 + 새 10개 |
| `README.md` | 문서 | 예 | 규칙, 열, 승인 흐름 |

`asset.csv`(`db/sample/asset.csv`와 같은 9열)와 `fetch_targets.csv`는 모든 검사를 통과한 행으로 02-B가 만든다.

## 선정 규칙

| 구분 | 규칙 | 자리 | 후보 |
|------|------|------|------|
| 국내 주식 | 기준 시점의 KOSPI 대형주(KOSPI 200 구성) 중 **2007-01 이전 상장**, 공개 시가총액 스냅샷 순, 회사당 한 종류(보통주) | 50 | 60 |
| 미국 주식 | 현재 S&P 500 구성 종목 중 **2007-01 이전 IPO**, 공개 시가총액 스냅샷 순, 회사당 한 종류 | 100 | 120 |
| ETF | 크기가 아니라 역할 포괄: `docs/MACRO_ETF.md` §3.2의 15종(모두 유지), P0-08 표본의 ETF(`rule_rank` 1–20), 그다음 주식 지수·섹터·채권·금·원자재를 역할별로 | 30(두 시장 합) | 36 |

- **시가총액은 참고 열일 뿐이다.** 후보 순서(`rule_rank`)를 정하고 `mcap_rank_public`에 보이지만 포지션 판정의 입력이 아니다(PLAN §13 "판정에서 시가총액 제외"). 값은 저장하지 않고 순위만 둔다.
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
| `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12` | KIS 거래소 코드, `YYYY-MM`, `Y`/`N` | **여기서는 비어 있음. KIS 월봉 훑기가 채움** |
| `status` | `SELECTED`, `RESERVE`, `FAILED_CHECK` | 02-B |
| `owner_decision` | 빈 칸, `DROP`, `KEEP` | 소유자(02-B 검토 때) |
| `note` | 표시: `DATE_APPROX`, `SHARE_CLASS`, `TICKER_OR_STRUCTURE_CHANGE`(이력이 이어지는지 확인), `RANK_FROM_*`, ETF 역할 | 02-A |

## `listed_check`의 뜻

- `TO_CONFIRM`: 아직 1차 페이지로 확인하지 않은 공개 값이다. 주식에서는 검토용이다. "2007-01 이전 상장"의 구속력 있는 검사는 측정 가능한 형태로 훑기가 적는다: 정본 출처의 2006-12 월말 종가(`has_2006_12 = Y`, PLAN §13-7).
- `CONFIRMED`: 공개 페이지로 확인하고 기록했다. ETF의 `listed_on`은 이력의 대체 달(H7)을 정하므로, 풀의 모든 ETF는 승인 전에 운용사 페이지로 `CONFIRMED`가 돼야 한다.

## 가용성과 승인 흐름

1. **02-A(이 초안):** 후보, 규칙, 그룹. 가용성 열은 비어 있다.
2. **훑기:** KIS 월봉 훑기가 후보 216종의 `kis_excd`, `kis_first_month`, `yf_first_month`, `has_2006_12`를 채운다(가격은 저장하지 않는다).
3. **02-B:** `status`를 정한다. 검사에 떨어진 후보(2006-12 종가 없음, 2007-01 이후 본 구간 결측, 거부된 심볼)는 `FAILED_CHECK`가 되고 `rule_rank` 다음 후보가 자리를 채운다. 통과한 앞 50 / 100 / 30행이 `SELECTED`, 나머지는 `RESERVE`다. `asset.csv`, `asset_group.csv`, `fetch_targets.csv`는 이 선택에서 만든다.
4. **소유자 승인:** 소유자가 02-B PR을 읽는다. 행 단위 변경은 `owner_decision`(`DROP` / `KEEP`)이나 리뷰 코멘트로 한다. **02-B PR의 머지가 풀 승인이다.**

## 검사

`python3 -m pytest tools/fetch/tests/test_pool_proposal.py`가 열, 키 중복, 허용 값, 날짜 형식, 출처 URL 유무, 그룹별 후보 수, 순위 순서, P0-08 행, §3.2 ETF를 검사한다. 네트워크와 키가 필요 없다.
