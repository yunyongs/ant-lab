# Ant Lab — Macro ETF Allocation (design doc)

Status: draft · Last updated: 2026-11-01 · Owner: Yongseok Yun

> Personal learning project. Orders go to the KIS mock-trading (모의투자) account only, after human approval in Slack. No real money, not investment advice.

This doc extends [PLAN.md](PLAN.md): the stock mock-trading MVP stays as planned, and the phases below add a macro-driven ETF allocation system on top of it. Korean and finance terms are explained in the glossary (Appendix D).

---

## 1. Overview

**The problem.** Long-term pension investors in Korea (연금저축, IRP) can choose among hundreds of domestic ETFs, but most either hold a fixed mix forever or react to headlines. Well-known macro frameworks (Uragami Kunio's four market seasons, Bridgewater's All Weather, 오건영's rate and currency scenarios) describe which assets tend to work in which economic "season", but applying them takes discipline and data work that individuals rarely sustain.

**What the system does.**

1. Every week it scores 14 US, Korean and FX indicators. Ten of them estimate the probability of each of four economic seasons, defined by the direction of rates and growth; three drive a dollar-vs-won tilt, and one feeds a sentiment overlay (§2).
2. It turns those probabilities into target weights for 15 Korean-listed ETFs and places them across a 연금저축 and an IRP account under each account's legal rules (§3).
3. When the portfolio drifts or the season changes, it proposes trades in Slack. After the owner approves, it executes them on a KIS mock-trading account (§3.6, §8.4).
4. It proves the method with a point-in-time backtest against static benchmarks (§4), tracks risk with MDD, beta and stress tests (§5), and uses an AI layer to explain regime changes, write a weekly report and answer questions with charts (§8).

**What it demonstrates.**

| Skill | Where |
|-------|-------|
| Domain modeling where money must add up: ledger, holdings, order state machine | §7, Spring Boot + PostgreSQL |
| Data engineering without look-ahead: two-date storage, vintages, snapshots | §2.4, §4.2, §7.1 |
| Quantitative method with honest validation: benchmarks, a frozen period, placebo tests, disclosed biases, and a live mock record | §4 |
| Human-in-the-loop AI: LangGraph with Slack approval, RAG with citations, faithfulness checks | §8 |
| A two-service architecture with clear ownership: Java domain, Python quant and AI | §6 |
| Delivery: Docker, GitHub Actions, cloud deployment, security | §6.4–6.5 |

## 2. Investment framework: the 4-Pool regime

The system describes the economy as one of four "pools" (seasons) on two axes: the direction of rates and the direction of growth. Each week it scores a small set of US and Korean indicators, turns them into a probability for each pool, and blends the four pool allocations by those probabilities.

### 2.1 The four pools

"Rates" means the direction of policy-rate expectations and inflation momentum, not the level of yields. "Growth" means the direction of activity, not its level.

| Pool | Rates | Growth | Market character | Assets that tend to work |
|------|-------|--------|------------------|--------------------------|
| P1 Recession | ↓ | ↓ | Earnings fall, central banks cut, bonds rally, the dollar strengthens | Long Treasuries, Korean government bonds, USD, gold |
| P2 Goldilocks | ↓ | ↑ | Liquidity-driven rally before earnings fully recover | Growth equities (Nasdaq 100), S&P 500, KOSPI, long bonds |
| P3 Reflation | ↑ | ↑ | Earnings-driven rally while inflation and yields rise | Broad and dividend equities, gold, copper, short duration |
| P4 Stagflation | ↑ | ↓ | Tightening hurts stocks and bonds at the same time | Cash (CD, short-term USD bonds), gold, dividend equities |

### 2.2 Where the pools come from

The grid combines three well-known frameworks:

| Pool | Uragami Kunio, four market seasons (rates × earnings) | Bridgewater All Weather (growth × inflation vs. expectations) | 오건영 (신한은행), 『부의 시나리오』 (growth × prices) |
|------|------|------|------|
| P1 | 역실적장세: earnings and rates fall, stocks fall | Falling growth: nominal long bonds | 저성장·저물가: government bonds, dollar |
| P2 | 금융장세: rate cuts and liquidity lift stocks before earnings | Rising growth, falling inflation: equities, nominal bonds | 고성장·저물가: equities |
| P3 | 실적장세: earnings drive stocks while rates drift up | Rising growth, rising inflation: equities, commodities | 고성장·고물가: commodities, equities |
| P4 | 역금융장세: tightening while earnings still look fine, stocks fall | Rising inflation, falling growth: inflation-linked bonds, commodities, gold | 저성장·고물가: gold, commodities, dollar |

Where the frameworks disagree, the design makes these choices:

- **Direction first.** Uragami's seasons are a sequence: 금융장세 starts while growth data is still weak. Judged by levels it would look like P1 even though markets behave like P2, so indicators are scored mainly by their trend. A few whose level itself carries meaning (the Sahm rule, curve inversion, the leading index vs. 100, the rate gap) add a level term, as listed in §2.3.
- **The rates axis includes inflation.** All Weather and 오건영 frame this axis as inflation, and yields can rise without inflation (term premium, bond supply). The rates axis therefore blends yields with core-inflation momentum.
- **FX is its own axis.** 오건영 stresses the dollar, USD/KRW and the Korea–US rate gap. These do not fit the 2×2, so they become a modifier that tilts dollar versus won exposure inside a pool (§2.5).
- **US and Korea are scored separately.** The Bank of Korea can cut while the Fed holds. Each axis has a US and a Korean score, blended with fixed weights.

### 2.3 Core indicators

Fourteen indicators, chosen because each is free, available by API, released quickly, and has one role: rates (#1–4), growth (#5–9, #14), FX (#10–12) or sentiment (#13). ECOS codes were checked against the ECOS API on 2026-11-01.

| # | Indicator | Region | Axis | Transform | Frequency, lag | Source and code |
|---|-----------|--------|------|-----------|----------------|-----------------|
| 1 | 2-year Treasury yield | US | Rates | 63-day change; level vs. 126-day MA | Daily, T+1 | FRED `DGS2` |
| 2 | Core CPI | US | Rates (inflation) | 3-month annualized minus YoY (acceleration) | Monthly, ~2 weeks | FRED `CPILFESL` (ALFRED vintages) |
| 3 | 국고채 3년, and 3Y minus 기준금리 | KR | Rates | 63-day change; spread below 0 means cuts are priced | Daily | ECOS `817Y002`/`010200000`; 기준금리 `722Y001`/`0101000` |
| 4 | 소비자물가 총지수 | KR | Rates (inflation) | YoY and its 3-month change | Monthly, early next month | ECOS `901Y009`/`0` |
| 5 | Initial jobless claims | US | Growth (inverted) | 4-week MA vs. 26-week MA, z-score | Weekly, ~5 days | FRED `ICSA` (ALFRED vintages) |
| 6 | Real-time Sahm rule | US | Growth (inverted) | Level and 3-month change | Monthly | FRED `SAHMREALTIME` |
| 7 | 10Y minus 3M Treasury curve | US | Growth | Level and 3-month change | Daily | FRED `T10Y3M` |
| 8 | 선행지수 순환변동치 | KR | Growth | 3-month change; level vs. 100 | Monthly, ~T+30 | ECOS `901Y067`/`I16E` |
| 9 | 수출금액 | KR | Growth | YoY of the 3-month average | Monthly | ECOS `901Y118`/`T002` |
| 10 | USD/KRW | KR | FX | vs. 200-day MA; z-score of 63-day change | Daily | ECOS `731Y001`/`0000001` |
| 11 | Broad trade-weighted dollar | Global | FX | 63-day change, z-score | Daily, published weekly | FRED `DTWEXBGS` |
| 12 | Korea–US rate gap | KR/US | FX | 국고채 3Y − `DGS2`: level and 6-month change | Daily | Derived from #1 and #3 |
| 13 | VIX | US | Sentiment | Level vs. 50-day MA, percentile | Daily | FRED `VIXCLS` |
| 14 | Credit spreads | US, KR | Growth (inverted) | 63-day change | Daily | FRED `BAA10Y`; ECOS 회사채 AA− minus 국고채 3Y (`817Y002`/`010300000` − `010200000`) |

Dropped on purpose:

| Indicator | Why it was dropped |
|-----------|--------------------|
| ISM manufacturing and services PMI | No free official API; FRED removed ISM series in 2016 |
| Conference Board LEI, CME FedWatch | Proprietary. `DGS2` already prices the expected Fed path |
| Fed funds rate, 10-year yield alone | Fed funds lags; the 10-year is noisy from term premium (kept only inside the curve) |
| Real GDP, nonfarm payrolls | Quarterly or noisy and heavily revised; claims and the Sahm rule are faster |
| Headline CPI | Energy noise; core CPI is the cleaner signal |
| ICE BofA high-yield spread | FRED now shows only a rolling 3 years; replaced by `BAA10Y` |
| S&P 500 vs. 200-day MA, commodity prices | Prices of what the system trades; as regime inputs they cause circular signals. Used only in the overlay (§2.6) |

### 2.4 Scoring

The regime is evaluated weekly, on Saturday morning KST after the US close.

1. **Store each observation with two dates:** the period it describes and the date it became public. A value is used only from its public date onward, which prevents look-ahead in backtests.
2. **Indicator signal:** apply the transform in §2.3, z-score each resulting series against a rolling 5-year mean and standard deviation, flip the sign so that positive always means "rates up" or "growth up", and clip to ±3. Where the transform lists two series (for example a change and a level), their z-scores are averaged.
3. **Staleness:** an indicator is stale when no new value has become available (by `available_at`) for more than twice its normal release interval: 2 business days for daily series, 2 weeks for weekly series (including daily data published weekly, such as #11), 2 months for monthly series. Business days follow the source market's calendar, so holidays do not trigger it. Stale indicators are left out, flagged on the dashboard, and the remaining weights are re-normalized. If a whole region-axis group (for example R_KR) is stale, the other region's score takes its full weight; if more than 4 of the 10 rates and growth indicators are stale, the data-quality gate stops proposals (§8.4).
4. **Axis scores:** within each region and axis, indicators are equally weighted: R_US = #1, #2; R_KR = #3, #4; G_US = #5, #6, #7, #14 (US); G_KR = #8, #9, #14 (KR). Then R (rates) = 0.5 × R_US + 0.5 × R_KR and G (growth) = 0.4 × G_US + 0.6 × G_KR. All weights are fixed in advance, not tuned on the backtest.
5. **Smoothing:** an exponential moving average of R and G with a 4-week half-life (weekly α = 1 − 0.5^(1/4) ≈ 0.16).
6. **Pool probabilities:** with `p_down = σ(−k·R)` and `p_up = σ(k·G)` (σ is the logistic function, k = 2 to start), using the smoothed R and G:
   - P1 = p_down × (1 − p_up)
   - P2 = p_down × p_up
   - P3 = (1 − p_down) × p_up
   - P4 = (1 − p_down) × (1 − p_up)

   The four always sum to 1, and target weights are the probability-weighted blend of the four pool allocations (§3), so the portfolio shifts gradually instead of jumping.
7. **Pool label (with hysteresis):** the label is the quadrant of the smoothed (R, G) point, but a side changes only after R (or G) has been beyond zero on the new side by more than 0.25 (the "deadband") for two consecutive weeks, and a new label is held for at least 4 weeks (the hold applies to the label as a whole). The first label is simply the quadrant of the first smoothed point. The label is for display, explanations and the pool-change trigger (§3.6); target weights always come from the probabilities in step 6.
8. **Phase:** from the labeled pool's probability: WATCH below 0.35, ALERT 0.35–0.50, TRANSITION 0.50–0.65, CONVICTION 0.65 and above. These thresholds are starting values to be checked walk-forward.

Weekly output: the four probabilities, the pool label and phase, R, G, the FX score F, and the list of stale indicators.

### 2.5 FX modifier

F = mean z-score of three specific terms: USD/KRW vs. its 200-day MA (#10), the broad dollar's 63-day change (#11), and the negative of the Korea–US gap's 6-month change (#12). F uses only these terms, not the averaged two-series signals of §2.4 step 2. F above 0 means a strengthening dollar: inside each pool's allocation, dollar assets get a small tilt up and won assets a matching tilt down. F below 0 does the opposite. The size of the tilt is set in §3. F never changes the pool.

### 2.6 Sentiment overlay

A Fear & Greed score, computed by the system itself, sets the pace of trades but never the pool.

- **US score:** percentile ranks over a rolling 3 years of VIX vs. its 50-day MA, SPY vs. its 125-day MA, the change in `BAA10Y`, and SPY minus IEF 20-day returns, averaged.
- **KR score:** the same with VKOSPI, KOSPI vs. its 125-day MA, the Korean credit spread, the 20-day change in USD/KRW, and foreign net buying.
- **Use:** the average of the self-computed US and KR scores changes how fast increases to risky ETFs are proposed (§3.6): normally in 2 weekly steps, in one step in extreme fear (below 20), and in 3 weekly steps in extreme greed (above 80). CNN's value is displayed but not used in decisions, so the system never depends on an unofficial endpoint.
- **CNN Fear & Greed:** shown next to the system's own scores on the dashboard, with "Source: CNN" and a link to CNN's page. CNN has no official API, and its terms appear to restrict automated access, so the value is fetched at most once a day from the undocumented endpoint used by the CNN page. If that endpoint breaks or is blocked, the dashboard shows only the self-computed scores.

### 2.7 Worked example

Illustrative numbers, not real data (computed by hand, k = 2):

| Step | Value |
|------|-------|
| R_US = +0.6, R_KR = −0.2 | R = 0.5(0.6) + 0.5(−0.2) = **+0.20** |
| G_US = −0.5, G_KR = −0.3 | G = 0.4(−0.5) + 0.6(−0.3) = **−0.38** |
| p_down = σ(−0.40), p_up = σ(−0.76) | 0.401, 0.319 |
| P1, P2, P3, P4 | 0.273, 0.128, 0.191, **0.408** |
| Highest probability and phase | P4 Stagflation, ALERT |

R = +0.20 is inside the 0.25 deadband, so the label does not change this week; assuming last week's label was already P4, it stays P4. Target weights for that week would be 27% of the P1 allocation, 13% of P2, 19% of P3 and 41% of P4, whatever the label.

### 2.8 Known limitations

- **Korean data has no vintages.** ECOS publishes only the latest revised values, and the leading index (#8) is revised monthly. Backtests apply release lags, disclose the revision bias, and the system saves its own snapshot of every fetch from day one.
- **Some pools are rare.** P4 periods since 2000 are few, so per-pool statistics always show the number of months behind them.
- **Fixed parameters.** Blend weights, k, the deadband and the phase thresholds are set in advance; any tuning is walk-forward only.
- **Unverified items:** whether VKOSPI is available from the KRX Open API, and how large Korean leading-index revisions are.

## 3. ETF universe, accounts, and target weights

This section turns §2's pool probabilities into ETF weights, then places them in two virtual pension accounts and produces order proposals.

Starting assumptions for the mock accounts: 연금저축 ₩10M and IRP ₩10M, plus monthly contributions of ₩500k and ₩250k. That is ₩9M a year, the combined tax-credit limit (₩6M for 연금저축, ₩9M including IRP).

### 3.1 Selection criteria

- Listed in Korea and allowed in pension accounts (no leveraged or inverse ETFs).
- **FX-unhedged by default.** A hedged "(H)" ETF is allowed only where no unhedged, pension-eligible ETF gives the needed exposure. Today there is one exception: oil (#15).
- Net assets ≥ ₩500억, fee ≤ 0.50% for passive ETFs and ≤ 0.80% for active ones.
- A proxy series exists for backtesting before the ETF's listing date (§4).

### 3.2 Universe (15 ETFs)

Fees and net assets are from funetf as of 2026-10-30 (114260 from Samsung's 2026-09-30 factsheet). "IRP class" is how the IRP 70% rule treats the ETF. ⚠ marks a value not yet confirmed from an official source.

| # | Ticker | Name | Role | Fee | Listed | Net assets (₩억) | IRP class | Backtest proxy before listing |
|---|--------|------|------|-----|--------|------------------|-----------|-------------------------------|
| 1 | 069500 | KODEX 200 | KR equity | 0.15% | 2002-10-14 | 225,019 | Risky | KOSPI 200 index |
| 2 | 360750 | TIGER 미국S&P500 | US equity | 0.0068% | 2020-08-07 | 211,217 | Risky | SPY × USD/KRW |
| 3 | 133690 | TIGER 미국나스닥100 | US growth equity | 0.0068% | 2010-10-18 | 116,954 | Risky | QQQ × USD/KRW |
| 4 | 458730 | TIGER 미국배당다우존스 | US dividend equity | 0.01% | 2023-06-20 | 46,150 | Risky | SCHD × USD/KRW |
| 5 | 476850 | KoAct 배당성장액티브 | KR dividend growth (active) | 0.50% | 2024-02-27 | 951 | Risky | Its benchmark index ⚠ |
| 6 | 426030 | TIME 미국나스닥100액티브 | US growth (active) | 0.80% | 2022-05-11 | 23,565 | Risky | QQQ × USD/KRW + assumed tracking error |
| 7 | 0019K0 | TIME 미국나스닥100채권혼합50액티브 | Nasdaq + bonds 50/50 (active) | 0.25% | 2025-03-25 | 5,718 | Safe | ~50% QQQ + 50% short bonds ⚠ |
| 8 | 476760 | ACE 미국30년국채액티브 | US long Treasuries | 0.05% | 2024-03-12 | 4,558 | Safe | TLT × USD/KRW |
| 9 | 148070 | KIWOOM 국고채10년 | KR long bonds | 0.05% | 2011-10-20 | 3,794 | Safe | KR 10Y total return from ECOS yields |
| 10 | 114260 | KODEX 국고채3년 | KR short bonds | 0.15% | 2009-07 | ~4,980 | Safe | KR 3Y total return from ECOS yields |
| 11 | 329750 | TIGER 미국달러단기채권액티브 | USD cash | 0.30% | 2019-07-24 | 7,422 | Safe ⚠ | BIL × USD/KRW |
| 12 | 459580 | KODEX CD금리액티브(합성) | KRW cash | 0.02% | 2023-06-08 | 70,851 | Safe | CD 91-day rate (ECOS) |
| 13 | 411060 | ACE KRX금현물 | Gold | 0.19% | 2021-12-15 | 35,735 | Risky | GLD × USD/KRW |
| 14 | 160580 | TIGER 구리실물 | Copper | 0.83% | 2012-12-17 | 1,506 | Risky ⚠ | CPER × USD/KRW |
| 15 | 261220 | KODEX WTI원유선물(H) | Oil (hedged exception) | 0.35% | 2016-12-27 | 512 | Not allowed in IRP ⚠ | S&P GSCI Crude Oil ER in USD, no FX conversion |

Copper's 0.83% fee breaks the fee rule; it stays because it is the only unhedged, pension-eligible growth-sensitive commodity, and it is capped at 5%. No unhedged broad-commodity ETF exists in Korea: futures-based ones are hedged and fail IRP's derivatives limit, so P3 and P4's inflation hedge rests on gold, copper and oil. The oil ETF (#15) holds futures for the same reason, which is why it can only go in 연금저축; Mirae lists its own futures commodity ETFs as not allowed in 퇴직연금.

### 3.3 Target allocation per pool

Each pool has a full allocation (%). The week's target is the probability-weighted blend of the four (§2.4 step 6). "Neutral" is the blend when all four pools are 25%.

| # | ETF | P1 Recession | P2 Goldilocks | P3 Reflation | P4 Stagflation | Neutral |
|---|-----|-----|-----|-----|-----|-----|
| 1 | KODEX 200 | 0 | 15 | 15 | 0 | 7.5 |
| 2 | TIGER 미국S&P500 | 10 | 25 | 20 | 0 | 13.75 |
| 3 | TIGER 미국나스닥100 | 0 | 15 | 5 | 0 | 5 |
| 4 | TIGER 미국배당다우존스 | 5 | 0 | 10 | 10 | 6.25 |
| 5 | KoAct 배당성장액티브 | 0 | 0 | 5 | 5 | 2.5 |
| 6 | TIME 미국나스닥100액티브 | 0 | 10 | 0 | 0 | 2.5 |
| 7 | TIME 나스닥100채권혼합50 | 5 | 5 | 0 | 0 | 2.5 |
| 8 | ACE 미국30년국채액티브 | 25 | 10 | 0 | 0 | 8.75 |
| 9 | KIWOOM 국고채10년 | 15 | 5 | 0 | 0 | 5 |
| 10 | KODEX 국고채3년 | 5 | 0 | 10 | 10 | 6.25 |
| 11 | TIGER 미국달러단기채권 | 15 | 0 | 5 | 20 | 10 |
| 12 | KODEX CD금리액티브 | 5 | 5 | 10 | 25 | 11.25 |
| 13 | ACE KRX금현물 | 15 | 5 | 10 | 20 | 12.5 |
| 14 | TIGER 구리실물 | 0 | 5 | 5 | 0 | 2.5 |
| 15 | KODEX WTI원유선물(H) | 0 | 0 | 5 | 10 | 3.75 |
| | **Equity exposure** (0019K0 counted as half) | 17.5 | 67.5 | 55 | 15 | 38.75 |
| | **IRP-safe assets** | 70 | 25 | 25 | 55 | 43.75 |

These numbers are a first draft to be checked by the backtest (§4), not tuned to it. They respect the risk limits: equity never above 80% (the highest is P2 at 67.5%) and never below 15% (P4), active equity funds (#5–7) at most 15% in every pool, no ETF above 35%, copper at most 5%.

### 3.4 Adjustments after blending

Applied in this order:

Each ETF has a **fallback**: where its weight goes if it is too small or unavailable. #5 → #1, #6 → #3, #7 → half #3 and half #10, #14 → #13, #15 → #12. The others have none.

1. **FX tilt.** With F from §2.5 clipped to [−1, +1], move up to 5 × F percentage points in total, half within equity (KR #1, #5 → US #2–4, #6) and half within bonds and cash (KR #9, #10, #12 → US #8, #11). Within each group, the amount is taken from the won ETFs in proportion to their weights and added to the dollar ETFs in proportion to theirs; if the won ETFs hold less than the amount, only what they hold moves. F below 0 reverses the direction. Gold, commodities and #7 are not tilted.
2. **Small positions.** A weight below 2% moves to the ETF's fallback, which avoids tiny trades in costly funds. For an ETF without a fallback, a weight below 2% is rounded to 0 and goes to #12. Fallbacks are applied once, in the order #5, #6, #7, #14, #15; anything still below 2% afterwards goes to #12.
3. **Active-fund replacement.** If an active ETF is delisted or warned under KRX's rule (correlation to benchmark below 0.7 for 3 months), all of its weight moves to its fallback until the universe review (§3.7) picks a replacement.

### 3.5 Account placement

Targets are computed for both accounts combined, then placed:

1. Convert target weights to won using the total value of both accounts.
2. Put ETFs that IRP cannot hold (#15) in 연금저축.
3. Fill IRP with safe ETFs first. If the safe ETFs are worth more than IRP, the excess stays in 연금저축, starting with the most cash-like (#12, then #11, then #10), so long bonds stay in IRP.
4. If IRP still has room, fill it with risky ETFs in this order: gold (#13), S&P 500 (#2), then the rest by size. IRP's target risky share is at most 60%, which leaves a 10-point buffer under the legal 70% for price drift.
5. Everything else goes to 연금저축.
6. **Check:** total safe assets must be at least 40% of IRP's value. The lowest safe share in any pool is 25% (P2, P3), so this holds while IRP is up to 62% of the total. Beyond that, risky equity in IRP is swapped for #7 (Nasdaq exposure that still counts as safe). This slightly lowers total equity exposure, because #7 is half bonds; that cost is accepted.
7. **Drift:** between 60% and 70%, nothing is sold, and proposals put new risky buys in 연금저축 instead of IRP. Above 70% (by price moves), nothing is sold automatically, new risky buys in IRP are blocked by law, and they are redirected to 연금저축.

### 3.6 Rebalancing rules

- **Regime evaluation:** weekly (§2.4).
- **Proposal triggers:** (a) the first trading day of each month; (b) any ETF more than 5 percentage points, or 25% relative, away from its target, measured on both accounts combined; (c) a change of pool label (§2.4 step 7). Triggers are checked after the close on every trading day (§6.2); a proposal always uses the latest weekly regime.
- **Contributions first:** monthly contributions buy underweight ETFs before any sale is proposed. Sales are proposed only if a band is still breached afterwards.
- **Pacing:** decreases are proposed in one step. Increases to risky ETFs are normally split into 2 weekly steps; in one step in extreme fear (Fear & Greed below 20), and in 3 weekly steps in extreme greed (above 80). Each later step is a new proposal with trigger `TRANCHE`, created by the next week's trigger check.
- **Trade size:** whole shares only, no trade under ₩100,000; leftover cash goes to #12.
- **Approval:** every proposal goes to Slack for approval before orders are sent to the KIS mock account (§6, §8). A proposal expires at the close of the second KRX trading day after it is posted.

### 3.7 Universe maintenance

A quarterly review, plus immediate review when: an ETF announces delisting or gets a correlation warning; net assets fall below ₩300억; a fee changes; or an unhedged alternative appears for the hedged slot (#15). Universe changes go into the decision log.

### 3.8 Tax and costs

- Inside pension accounts, trades and distributions are not taxed until withdrawal, so rebalancing has no tax cost. Pension income up to ₩15M a year is taxed at 3.3–5.5% at withdrawal.
- Foreign tax withheld on US dividends inside these ETFs is now settled at withdrawal rather than inside the account, which slightly lowers the in-account return of dividend-heavy ETFs such as #4.
- Backtests charge each ETF's fee plus an assumed 0.05% per trade for commission and spread. KIS mock trades are free, so live mock results will look slightly better than real trading would.

### 3.9 Worked example

Continuing §2.7 (P1 0.273, P2 0.128, P3 0.191, P4 0.408), with F = 0 and a ₩20M total. Computed by hand; values rounded, so totals may be off by 0.1.

| ETF | Blended | After small-position rule | ₩ | Account |
|-----|---------|---------------------------|---|---------|
| KODEX 200 | 4.8 | 4.8 | 960k | 연금저축 |
| TIGER 미국S&P500 | 9.8 | 9.8 | 1.95M | 연금저축 |
| TIGER 미국나스닥100 | 2.9 | 4.2 (+ #6) | 830k | 연금저축 |
| TIGER 미국배당다우존스 | 7.4 | 7.4 | 1.47M | 연금저축 |
| KoAct 배당성장액티브 | 3.0 | 3.0 | 600k | 연금저축 |
| TIME 미국나스닥100액티브 | 1.3 | 0 | – | – |
| TIME 나스닥100채권혼합50 | 2.0 | 2.0 | 400k | IRP |
| ACE 미국30년국채액티브 | 8.1 | 8.1 | 1.62M | IRP |
| KIWOOM 국고채10년 | 4.7 | 4.7 | 950k | IRP |
| KODEX 국고채3년 | 7.4 | 7.4 | 1.47M | IRP |
| TIGER 미국달러단기채권 | 13.2 | 13.2 | 2.64M | IRP |
| KODEX CD금리액티브 | 14.1 | 14.1 | 2.82M | IRP |
| ACE KRX금현물 | 14.8 | 16.4 (+ #14) | 3.28M | 연금저축 3.18M, IRP 0.10M |
| TIGER 구리실물 | 1.6 | 0 | – | – |
| KODEX WTI원유선물(H) | 5.0 | 5.0 | 1.01M | 연금저축 |

Safe assets total 49.5% (₩9.9M), so IRP is almost entirely safe (99%) and 연금저축 holds the risky sleeve. Orders are the difference between these targets and current holdings, rounded down to whole shares.

### 3.10 Unverified items

- IRP classification of #11 (USD short bonds), #14 (copper) and #15 (oil futures).
- The composition of #7 and the benchmark of #5.
- Whether a ₩500억 floor is enough for daily liquidity at this account size (likely, but not measured).

## 4. Backtest method

The backtest answers one question: does following the regime signal beat holding the same ETFs at neutral weights, after costs and without hindsight?

### 4.1 Period and price history

- **Main window: 2007-01 to the latest month.** It includes the 2008 crisis, the 2011 euro crisis, 2015–16, the 2020 crash and the 2022 inflation shock, so every pool appears at least once.
- **Warm-up: 2002–2006.** Indicator z-scores need history before the first signal; early z-scores use an expanding window.
- **ETF returns before listing come from proxies** (the last column of §3.2). US proxies are total-return series in USD, converted to KRW with daily USD/KRW (FRED `DEXKOUS`), because the Korean ETFs are unhedged. The hedged oil ETF uses the USD index return without FX conversion, plus the hedge carry: CD 91-day rate minus the US 3-month T-bill rate (FRED `DTB3`), which is a cost when Korean rates are lower.
- **Splicing:** each ETF uses its proxy until its listing date, then its own price history.
- **Proxy data:** dividend-adjusted daily closes for the US ETFs (and SPY/IEF for the Fear & Greed score), loaded once from a free historical source (Stooq or Yahoo adjusted close) and kept current with the KIS overseas daily-price API. Korean ETF prices come from KIS.
- **Gaps:** `DTWEXBGS` starts in 2006; before that the discontinued major-currency index `DTWEXM` is spliced in. Copper before CPER (2011) and cash before BIL (2007) use FRED commodity and T-bill series.

### 4.2 No hindsight

- **Point-in-time values.** Revised US series (`CPILFESL`, `ICSA`) use ALFRED vintages: the value as first published, not today's revised value. `SAHMREALTIME` is real-time by construction. Daily market series are not revised.
- **Korean release lags.** ECOS has no vintages, so each Korean value is used only after a conservative lag: CPI +7 days, exports +15 days, leading index +35 days after the reference month ends.
- **Same timing as live.** The regime is evaluated on Saturday with data available by then; triggers are checked after each trading day's close (§6.2), and trades fill at the next trading day's close, assuming approval the same evening.
- **Rolling statistics only.** No full-sample means, standard deviations or thresholds.

### 4.3 Simulation rules

The backtest runs the same code as the live system: §2.4 scoring, §3.3–3.5 targets and placement, and §3.6 rebalancing (monthly proposals, bands, contributions first, whole shares). Every proposal is treated as approved. Costs: each ETF's fee accrued daily, plus 0.05% per trade. Contributions follow §3's ₩500k + ₩250k a month.

### 4.4 Benchmarks

| Benchmark | What it tests |
|-----------|---------------|
| Neutral static (same 15 ETFs, neutral weights, monthly rebalance) | **The main test:** the value of the regime signal itself |
| Hard switching (allocado-style threshold counting, 100% into the dominant pool) | Whether soft probabilities and hysteresis beat simple switching |
| All Weather-style static (30% equity, 55% bonds, 15% gold and commodities) | Whether a dynamic system beats a well-known static one |
| 60/40 in KRW (30% S&P 500, 30% KOSPI 200, 40% KR 10-year bonds) | A plain balanced portfolio |
| KOSPI 200 and S&P 500 in KRW | Market reference only |

### 4.5 Metrics and outputs

- **Returns and risk:** CAGR, volatility, MDD, Sharpe and Sortino (risk-free rate: CD 91-day), Calmar, annual turnover.
- **Regime behavior:** time in each pool, pool changes per year, and return by pool with the number of months behind each figure.
- **Robustness:** a sensitivity grid over k, the deadband and the blend weights, shown as a heatmap. This checks that results do not depend on one lucky setting; it is not used to pick the best setting.
- **Frozen period:** 2023-01 onward is excluded from any parameter change, and results are reported for the full period and this period separately. It is not truly unseen, because the design was written in 2026; real out-of-sample evidence is the live mock record from M4 onward.
- **Placebo:** regime labels shuffled in random blocks 1,000 times; the real signal should beat most shuffled ones.
- **Reproducibility:** each run is stored with its configuration, git commit SHA and data snapshot date.

### 4.6 Known biases (disclosed with every result)

- Korean leading-index revisions (§2.8).
- Proxy returns differ from the real ETFs' tracking.
- The universe was chosen in 2026: an ETF that failed earlier would not be in it (survivorship bias).
- The pool allocation tables (§3.3) were written by someone who knows market history, including the frozen period. The honest check is the live mock record.

## 5. Risk analytics and dataviz

All metrics are computed by the Python service, stored in `analytics` tables, and served to React through Spring.

### 5.1 Metrics

Computed per ETF, per account and for the total portfolio.

| Metric | Formula | Window |
|--------|---------|--------|
| MDD and current drawdown | min over t of [ P_t / (max of P_s for s ≤ t) ] − 1 | Full history and rolling 3 years |
| Volatility | stdev(daily log returns) × √252 | 63 and 252 days |
| Beta | cov(r_asset, r_bench) / var(r_bench) | 252 days |
| Sharpe | (mean return − risk-free) / volatility, annualized | Rolling 3 years |
| Sortino | (mean return − risk-free) / downside deviation | Rolling 3 years |
| Risk contribution | each ETF's share of portfolio volatility | 252 days |
| VaR 95% | 5th percentile of historical 1-day returns | 2 years |
| Return | time-weighted (TWR) and money-weighted (XIRR, since contributions arrive monthly) | Since start, YTD, 1 year |

- **Beta benchmarks:** Korean holdings vs. KODEX 200; US holdings vs. TIGER 미국S&P500 (a KRW series, so FX is included). Cross-market betas and correlations use weekly returns, because Korean and US trading hours do not overlap.
- **Risk-free rate:** CD 91-day (ECOS `817Y002`/`010502000`).
- **Stress tests:** replay the portfolio's actual target weights through real proxy returns in 2008-09 to 2009-02, 2020-02 to 2020-03, and 2022. This replaces allocado's "beta × index drawdown" estimate.

### 5.2 Charts

| # | Chart | What it shows | Idea from |
|---|-------|---------------|-----------|
| 1 | Regime map | R (x) vs. G (y) scatter with the four pools as quadrants; the current point plus a 12-week trail | New |
| 2 | Pool probabilities | Stacked area of P1–P4 over time, phase label, current gauges | allocado scenario page |
| 3 | Indicator panel | Small multiples of each indicator's z-score, with stale-data flags | allocado trigger matrix |
| 4 | Fear & Greed | CNN index and the self-computed US and KR scores; history vs. S&P 500 and KOSPI; returns after extreme-fear events | allocado Market Pulse |
| 5 | Accounts | 연금저축 and IRP donuts; target vs. actual bars with drift bands; IRP risky-share gauge against the 70% limit | New |
| 6 | Beta map | x = beta, volatility or \|MDD\| (toggle), y = weight, bubble size = risk contribution | allocado beta map |
| 7 | Drawdown | Underwater chart of the portfolio vs. benchmarks | allocado |
| 8 | Performance | Cumulative return vs. benchmarks (log scale) | allocado |
| 9 | Backtest | Equity curves, a pool-timeline ribbon under the curve, return by pool with n, rolling Sharpe, sensitivity heatmap | New |
| 10 | Correlation | Heatmap of the 15 ETFs, weekly returns, 2 years | New |

Charts are built with Recharts in React. Chart #1 and the backtest page are the first things a visitor sees on the public demo.

## 6. System architecture

### 6.1 Components

```
                 ┌──────────────┐      ┌───────────────────────────┐
  Browser ──────▶│ React (Vite) │─────▶│ Spring Boot API (Java 21) │──────▶ KIS Open API (mock)
 (owner, demo)   └──────────────┘ JWT  │  auth, accounts, ledger,  │──────▶ FRED, ECOS, CNN
                                       │  proposals, orders, jobs  │◀─────▶ Slack (approve / alerts)
                                       └─────┬──────────────┬──────┘
                                  internal   │              │ JPA
                                   HTTP      ▼              ▼
                           ┌──────────────────────┐   ┌──────────────────────────┐
                           │ Python service       │──▶│ PostgreSQL 16 + pgvector │
                           │ FastAPI: quant + AI  │   │ core / collected /       │
                           │ (LangChain/LangGraph)│   │ analytics / rag schemas  │
                           └──────────────────────┘   └──────────────────────────┘
                                       n8n (optional): report delivery, alert fan-out
```

| Component | Responsibility | Tech |
|-----------|----------------|------|
| Spring Boot API | System of record: users, virtual accounts, ledger, holdings, proposals, approvals, orders and fills. Data collectors, job scheduling, Slack integration, KIS client. Public REST API. | Java 21, Spring Boot 3, Spring Security + JWT, JPA, Flyway, `@Scheduled` |
| Python service | Quant: indicator transforms, regime scoring, targets, backtests, risk metrics. AI: RAG, regime explainer, weekly report, LangGraph pipeline, natural-language Q&A. Internal only. | Python 3.12, FastAPI, pandas, LangChain, LangGraph |
| PostgreSQL | Schemas, each written by one service: `core` and `collected` by Spring; `analytics`, `rag` and `agent` (LangGraph checkpoints) by Python. Both services may read everything. | PostgreSQL 16, pgvector |
| React | Dashboard, portfolio, proposals, backtest, Ask page. Public read-only demo plus owner login. | React, Vite, Recharts |
| Slack | `#antlab-proposals` (interactive approve/reject), `#antlab-alerts` (job failures, stale data), weekly report | Slack app with signed requests |
| n8n | Optional low-code flows, such as delivering the weekly report or fanning out alerts, triggered by webhooks from Spring | n8n in Docker |

**Why two services:** Java owns the transactional domain where money must add up; Python owns statistics and LLM work, where its libraries are far ahead. Only one service writes each table, so ownership stays clear. Quant logic lives only in Python, so the backtest and the live system run the same code.

### 6.2 Schedule (KST)

| When | Job | Owner |
|------|-----|-------|
| Daily 07:30 | Collect US data (FRED) after the US close | Spring |
| Weekdays 16:00 | Collect ETF closing prices (KIS) and Korean daily data (ECOS); CNN Fear & Greed once a day, stored in `collected` as indicator `CNN_FG` | Spring |
| Weekdays 16:30 | Refresh risk metrics | Python, called by Spring |
| Trading days 16:45 | Check proposal triggers (§3.6: monthly, bands, pool change); if one fires, run the proposal pipeline with the latest regime | Spring checks, Python runs the pipeline |
| Saturday 08:00 | Weekly regime evaluation, regime-change explanation, weekly report | Spring starts it, Python runs it |
| On approval | Send orders to the KIS mock account, reconcile fills, update the ledger | Spring |

Every job writes a `job_run` row (start, end, status, rows, error). A failure or stale data posts to `#antlab-alerts`.

### 6.3 KIS integration

- **Mock environment only** (`openapivts.koreainvestment.com`), selected by Spring profile. The mock environment has its own domain, keys and transaction IDs, and a lower rate limit, so requests are throttled.
- **Access tokens** are cached in the database and reused until shortly before expiry, because KIS limits how often new tokens can be issued.
- **Order flow:** approved proposal → orders for each line, split so none exceeds ₩5M → poll for fills → write fills and ledger rows in one transaction.
- **No double orders:** each order row is inserted with a unique key `{proposal_line_id}-{n}` before it is sent. A second approval click finds the rows already there and sends nothing. A failed send is retried only after the KIS order inquiry confirms the order was not received.
- **Order type and hours:** marketable limit orders (best ask for buys, best bid for sells) between 09:05 and 15:15. Approvals outside those hours are queued until the next session.
- The two virtual pension accounts map to one KIS mock general account; the ledger keeps which account owns each share (§3.5).

### 6.4 Deployment and CI

- **Local:** Docker Compose runs PostgreSQL, Spring, Python, React (dev server) and optionally n8n.
- **CI (GitHub Actions), from the first phase:** on every pull request, build and test Java (Gradle) and Python (pytest), lint, and build Docker images. On merge to `main`, push multi-architecture images to GitHub Container Registry.
- **Hosting:** one OCI Always Free Ampere (ARM) VM running Docker Compose behind Caddy for HTTPS. HTTPS is required because Slack only sends interactive button clicks to a public HTTPS URL. Deployment is a manual workflow the owner triggers. Paid and GCP options are in Appendix A.
- **Backups:** nightly `pg_dump` to object storage.

### 6.5 Security

- Secrets (KIS keys, Slack signing secret, JWT key, LLM API key) live in environment variables and GitHub Actions secrets, never in the repo.
- Every Slack request is verified with the signing secret before an approval is accepted.
- JWT: short-lived access token plus a refresh token in an httpOnly cookie, passwords hashed with BCrypt. Roles: `OWNER` (approve, run backtests) and the anonymous public demo (read-only).
- The Python service is reachable only on the internal Docker network, with a service token.

## 7. Data model and API draft

Key columns only; the DDL is written by hand during the phases. At M1 the MVP's tables move to PostgreSQL with the rest: the macro track reuses `member` as its user table, `holding` and `cash_ledger` become keyed by `account_id` (each member gets accounts), and the stock track's `orders`/`execution` stay separate from the ETF `broker_order`/`fill`. Money uses `NUMERIC(19,4)` in SQL and `BigDecimal` in Java (Python's `Decimal` where it touches money); weights use `NUMERIC(9,6)`. Statistics such as z-scores are not money and may use floating point.

### 7.1 Tables

| Schema | Table | Key columns |
|--------|-------|-------------|
| core | `member` (from the MVP) | id, email (unique), password_hash, role (added) |
| core | `account` | id, member_id, type (STOCK / PENSION_SAVINGS / IRP), name |
| core | `etf` | id, ticker (unique), name, role, fee_rate (`NUMERIC(7,6)`, e.g. 0.000068), listed_on, hedged, irp_class (SAFE / RISKY / NOT_ALLOWED), proxy_symbol, active |
| core | `etf_fallback` | etf_id, fallback_etf_id, share — one ETF can fall back to several (e.g. #7 → 50% #3, 50% #10) |
| core | `holding` | account_id, etf_id, quantity, avg_price — unique (account_id, etf_id) |
| core | `cash_ledger` | id, account_id, type, amount, balance_after, ref_id, created_at |
| core | `proposal` | id, created_at, trigger (MONTHLY / BAND / POOL_CHANGE / TRANCHE), regime_snapshot_id, status (PENDING / APPROVED / REJECTED / EXPIRED / EXECUTED), decided_at, slack_message_ts |
| core | `proposal_line` | id, proposal_id, account_id, etf_id, side, quantity, est_price, reason |
| core | `broker_order` | id, proposal_line_id, idempotency_key (unique), kis_order_no, status, submitted_at |
| core | `fill` | id, broker_order_id, price, quantity, fee, filled_at |
| core | `job_run` | id, job_name, started_at, finished_at, status, rows, error |
| collected | `indicator` | id, code, source (FRED / ECOS / CNN / KRX / DERIVED), series_id (null if derived), derived_from (input codes, e.g. #12 = KTB 3Y and `DGS2`), region, axis, transform, release_interval, normal_lag_days |
| collected | `indicator_value` | indicator_id, ref_date, value, available_at, fetched_at, vintage — unique (indicator_id, ref_date, vintage) |
| collected | `etf_price_daily` | etf_id, trade_date, close |
| collected | `proxy_price_daily` | symbol, trade_date, close, adj_close (dividend-adjusted), currency |
| analytics | `indicator_signal` | indicator_id, as_of, z, weight, stale |
| analytics | `regime_snapshot` | id, as_of, r, g, f, p1, p2, p3, p4, label, label_since, phase, stale_indicators, model_version |
| analytics | `target_weight` | regime_snapshot_id, etf_id, weight |
| analytics | `asset_metric` | etf_id, as_of, window, mdd, volatility, beta, sharpe, sortino, risk_contribution |
| analytics | `portfolio_metric` | scope (IRP / PENSION_SAVINGS / TOTAL), as_of, window, twr, xirr, mdd, volatility, var95 |
| analytics | `sentiment_score` | as_of, source (SELF_US / SELF_KR), value — CNN's raw value is in `collected` |
| analytics | `report` | id, type (WEEKLY / REGIME_EXPLANATION), regime_snapshot_id, body, sources, created_at |
| analytics | `backtest_run` | id, config, git_sha, data_as_of, metrics, created_at |
| rag | `document` | id, source, title, published_on, url, language |
| rag | `chunk` | id, document_id, text, embedding (vector) |

`indicator_value` keeps every fetch as a new vintage, which is how the Korean data gets its own point-in-time history from day one (§2.8).

### 7.2 Public API (Spring, `/api/v1`)

```
POST /auth/login | /auth/refresh | /auth/logout
GET  /regime/current                      Pool probabilities, phase, R, G, F, stale flags
GET  /regime/history?from&to
GET  /indicators                          Latest signal per indicator
GET  /indicators/{code}/series
GET  /fear-greed                          CNN plus self-computed US/KR scores
GET  /etfs | /etfs/{ticker}/metrics
GET  /accounts | /accounts/{id}/holdings | /accounts/{id}/ledger
GET  /portfolio/summary | /portfolio/performance?benchmark= | /portfolio/risk
GET  /proposals | /proposals/{id}
POST /proposals/{id}/approve | /proposals/{id}/reject      OWNER only
POST /slack/interactions                  Slack-signed button clicks
GET  /backtests | /backtests/{id}
POST /backtests                           OWNER only
POST /ask                                 { question } → { answer, charts[], sources[] }
GET  /reports/weekly/latest
```

### 7.3 Internal API (Python, called only by Spring)

```
POST /quant/regime/evaluate     POST /quant/targets       POST /quant/backtest
POST /quant/metrics/refresh     POST /ai/explain-regime   POST /ai/weekly-report
POST /ai/ask                    POST /ai/pipeline/run     POST /ai/pipeline/resume
```

`/quant/targets` takes current holdings and prices from Spring and returns target weights plus order quantities per account. Python never calls Slack or KIS; when the pipeline needs to post to Slack or execute, it calls Spring.

## 8. AI features

One rule covers every feature: **the LLM explains and answers, deterministic code decides.** Pool probabilities, target weights, quantities and risk checks come from tested code; the LLM never changes them.

### 8.1 RAG knowledge base

- **Corpus:**
  - FOMC statements and minutes (federalreserve.gov).
  - Bank of Korea 통화정책방향 decisions and minutes (bok.or.kr).
  - Bridgewater's public All Weather articles.
  - The owner's own notes on Uragami, Dalio and 오건영. Notes only: no copied book text.
- **Pipeline:**
  - Split documents by section, then into chunks of about 500 tokens.
  - Embed with a multilingual model, because the corpus is half Korean (for example bge-m3, which can run locally).
  - Store the vectors in pgvector.
  - Search with hybrid retrieval: PostgreSQL full-text search plus vector similarity.
- **Every answer cites its sources** (document title and date).

### 8.2 Regime-change explainer

- **Trigger:** the pool label or phase changes (§2.4 steps 7–8).
- **Inputs:** the indicators that moved R and G most since last week (computed deterministically), plus passages retrieved for them (for example the latest FOMC and BOK statements, and the framework notes for the new pool).
- **Output:** a short Korean explanation with numbers and citations, posted to Slack and stored with the regime snapshot. Example: "P3 → P4: core CPI accelerated while Korean exports turned down…"
- **Check:** every number in the text must appear in the tool outputs; otherwise the text is rejected and regenerated.

### 8.3 Weekly macro report

Every Saturday after the regime evaluation: regime and probabilities, what changed and why (8.2), key indicators, Fear & Greed, portfolio vs. benchmarks, and proposal status. Delivered to Slack and shown on the web.

### 8.4 LangGraph proposal pipeline

```
trigger fired ─▶ load latest weekly regime ─▶ data-quality gate ─▶ build proposal ─▶ risk checks
   ─▶ post to Slack (via Spring) ─▶ [interrupt: wait for approval] ─▶ execute (Spring) ─▶ report
```

- **Data-quality gate:** if more than 4 of the 10 rates and growth indicators are stale (§2.4 step 3), or prices are missing, the pipeline stops and alerts instead of proposing. Regime evaluation and its explanation run in the Saturday job (§6.2), not in this pipeline.
- **Risk checks** are plain code: the IRP 70% rule, maximum turnover per proposal, maximum order size, and holdings sufficient for every sell.
- **Approval:** the graph pauses with LangGraph's interrupt and saves its state in PostgreSQL (a checkpointer). Approve or Reject, clicked in Slack or on the web, calls the same Spring service, which resumes the graph. Proposals expire at the close of the second KRX trading day after posting (§3.6).
- **Before the graph exists** (M4–M5), Spring builds proposals by calling `/quant/targets` and executes approvals directly; M6 moves the same steps into the graph.
- **Who builds what:** Python computes target weights and quantities; Spring stores them as a `proposal` (it owns the `core` schema) and executes orders. Each order has a unique key derived from its proposal line (§6.3), so a double click cannot place an order twice.

### 8.5 Natural-language questions with charts

- The Ask page accepts questions such as "How did the portfolio do in P4 periods?" or "Which ETF adds the most risk?"
- **How it works:** the LLM calls a fixed set of read-only analytics tools (regime history, performance, drawdown, beta map, return by pool). Each tool returns data plus a chart spec, which React renders as a Recharts chart beside the text answer. No free-form SQL.

### 8.6 Models and evaluation

- **Models:** Claude through LangChain's Anthropic integration: a stronger model for explanations and reports, a smaller one for routing. Model IDs are pinned in config.
- **Evaluation:**
  - A golden set of about 20 questions with expected facts, run in CI with pytest.
  - Every answer is checked for faithfulness (numbers and citations) as in 8.2.

## 9. Roadmap

The macro track starts after the stock mock-trading MVP in [PLAN.md](PLAN.md) and follows the [slx-ai-campus](https://github.com/yunyongs/slx-ai-campus) curriculum (bootcamp ends 2027-03-24). The stock track's V3–V5 features become an optional backlog; the infrastructure they planned (KIS client, React, Docker, CI, cloud) is built once, in these phases. The whole app moves from MySQL to PostgreSQL at M1.

| Phase | Builds | Curriculum stage | Done when |
|-------|--------|------------------|-----------|
| M1 Data foundation | PostgreSQL in Docker Compose, Flyway, ETF master (15), FRED/ECOS/KIS collectors with point-in-time storage, history backfill, `job_run` with Slack failure alerts, CI | Spring Boot + JPA, scheduling | Daily jobs run for 2 weeks without manual fixes |
| M2 Quant engine | Python service skeleton; indicator transforms, regime scoring and targets; tests that reproduce the worked examples in §2.7 and §3.9 | Python alongside Spring | Weekly regime snapshots stored automatically |
| M3 Backtest and analytics | Backtest engine, benchmarks, robustness grid, risk metrics | Python alongside Spring | Backtest report covering §4.4–4.6 |
| M4 Accounts and approval | JWT auth, virtual accounts, ledger, placement, proposals, Slack approval, KIS mock orders and fills | Spring Security, external APIs | One monthly cycle approved in Slack and filled in the mock account |
| M5 Dashboard | React pages and the charts in §5.2, public demo, owner login | React | Demo page deployable |
| M6 AI layer | RAG, regime explainer, weekly report, LangGraph pipeline, Ask page, golden-set evaluation | RAG, LangChain, LangGraph | Proposals flow through the graph; evaluation passes in CI |
| M7 Deploy | Multi-arch images, OCI VM with Caddy, deploy workflow, backups, optional n8n | Docker, CI/CD, cloud | Public URL live; Slack approval works over HTTPS |

The backtest (M3) comes before orders (M4) on purpose: the design should earn evidence before execution is built.

### 9.1 Who writes what

The owner writes or edits the core logic: regime scoring, targets and placement, the ledger and order state machine, and the approval flow. Claude may draft scaffolding: collectors, DTOs, configuration, Docker and CI files, React components, and test skeletons. The owner reviews every file.

### 9.2 Git workflow (run by the owner)

Each phase starts by adding its feature table here (M1-01, M1-02, …). Every feature then goes issue → branch → commits → pull request → merge commit, with a self-review and (from M1) green CI before merging, and a `m<N>-done` tag at the end of the phase. The full rules are in [CONVENTION.md](CONVENTION.md).

## 10. Guardrails and non-goals

### 10.1 Guardrails

- **Mock only.** The KIS base URL comes from the Spring profile, and the app refuses to start if a real-trading URL is configured without an explicit `trading.real-money=true` flag. Turning that on requires a decision-log entry first.
- **A human approves every order** in Slack or on the web. Proposals expire at the close of the second KRX trading day after posting.
- **Limits per rebalancing proposal:** turnover at most 30% of the portfolio; no single order above ₩5M. Buys made only with new cash (initial funding and monthly contributions) are exempt from the turnover cap but still split into orders of at most ₩5M.
- **Kill switch:** `trading.enabled=false` in config, or a Slack command, stops all order sending immediately.
- **Data-quality gate:** stale or missing key data stops proposals; it never produces a guess.
- **The LLM never decides weights or orders** (§8).
- **Personal use only.** The public demo shows the mock account and the method. It is not a signal service, gives no advice, and carries a disclaimer. A public recommendation service could fall under 유사투자자문업 rules.
- **Content:** no copied book text in the RAG corpus; CNN Fear & Greed is shown with attribution and a link.

### 10.2 Non-goals

- Real-money trading (for now; see 10.3).
- Multiple users, intraday trading, leveraged or inverse ETFs, and individual stocks in this track.
- Machine-learning market prediction: the regime model is rules-based and explainable on purpose.
- Tax optimization beyond asset location between 연금저축 and IRP.

### 10.3 Future decision: real money

If real trading is ever enabled, 연금저축 orders may work through the KIS API (community reports, not officially confirmed). IRP orders reportedly do not, so IRP would stay manual: the system would post the IRP order list to Slack and the owner would place it in the KIS app.

---

## Appendix A. Paid options and scale-up

Start free; upgrade only when a concrete limit is hit. Free-tier limits and prices change, so check the provider's current pages before deciding.

### A.1 Cloud

| Option | What it adds | When to consider |
|--------|--------------|------------------|
| OCI Always Free (starting point) | Ampere ARM VM, block storage, object storage | Default for M7 |
| OCI pay-as-you-go account | Removes the risk of idle Always Free instances being reclaimed; larger shapes | If the free VM gets reclaimed or runs out of memory |
| OCI Database with PostgreSQL (managed) | Managed backups, patching, high availability | If running PostgreSQL yourself becomes the bottleneck |
| OCI Kubernetes Engine (OKE) | Kubernetes practice on the same cloud | As a learning goal only (see A.3) |
| GCP Cloud Run + Cloud SQL (PostgreSQL with pgvector) | Containers that scale to zero, managed database, Cloud Scheduler, Secret Manager | If you prefer managed services over a VM |

### A.2 Data and AI

| Option | What it adds |
|--------|--------------|
| ISM PMI license | The most-watched US growth survey (§2.3 dropped it for lack of a free API) |
| CME FedWatch data | Market-implied Fed probabilities |
| KRX data products | Official VKOSPI and Korean index history for the KR Fear & Greed score |
| LangSmith | Tracing and evaluation dashboards for the LangGraph pipeline |

### A.3 When Kafka, Airflow or Kubernetes would be justified

| Tool | Justified when | Today |
|------|----------------|-------|
| Kafka | Many real-time producers and consumers, e.g. KIS WebSocket quotes for hundreds of tickers | Overkill: a few hundred rows a day. Direct upserts suffice. |
| Airflow | Many independent pipelines with backfills, owned by a data team | Overkill: about five daily jobs. Spring `@Scheduled` + `job_run` suffices. |
| Kubernetes | Several services needing autoscaling and rolling deploys | Overkill for one user; worth it only as a deliberate learning exercise |

The earlier actstock-pipeline project used all three for a similar data volume; this plan keeps them as optional exercises.

## Appendix B. Decision log

| Date | Decision | Why |
|------|----------|-----|
| 2026-11-01 | Extend Ant Lab with new phases instead of a separate repo | One growing portfolio codebase |
| 2026-11-01 | PostgreSQL (with pgvector) instead of MySQL for these phases | One DB covers app data and RAG embeddings |
| 2026-11-01 | Python AI service called by Spring Boot | LangChain/LangGraph are Python-native |
| 2026-11-01 | KIS mock account with Slack approval; no real-money orders for now | Safety first; real orders are a later decision |
| 2026-11-01 | Korean-listed ETFs, ~15, FX-unhedged by default (exceptions below) | Simpler universe; USD/KRW exposure is part of the strategy |
| 2026-11-01 | 연금저축 and IRP modeled as virtual sub-accounts; orders run on one KIS mock general account | KIS mock trading supports only the general account (`01`), not pension accounts (`22`, `29`) |
| 2026-11-01 | Soft pool probabilities with hysteresis instead of threshold counting | Fixes allocado's flip-flopping and latest-value-only checks |
| 2026-11-01 | Fear & Greed is a pacing overlay, never a regime input | Price-based signals would make the regime flip-flop |
| 2026-11-01 | Show CNN Fear & Greed on the dashboard with attribution; self-computed US/KR scores as fallback | It is a widely followed public index; no official API, so fetch once a day and degrade gracefully |
| 2026-11-01 | Hedged (H) ETFs allowed only when no unhedged, pension-eligible ETF exists; first exception: oil (261220) | Korea has no unhedged broad-commodity or oil ETF that pension accounts can hold |
| 2026-11-01 | Each pool has a full allocation, blended by probability; targets computed for both accounts combined, then placed (IRP safe-first) | Keeps the strategy in one place and uses IRP's safe-asset rule as asset location |
| 2026-11-01 | Mock starting cash ₩10M + ₩10M, contributions ₩500k + ₩250k a month | Matches the ₩9M combined tax-credit limit |
| 2026-11-01 | Stale indicators are dropped (binary), not decayed | Exponential decay would down-weight monthly data every cycle just before each release |
| 2026-11-01 | Weekly regime, daily trigger checks; proposals expire after two trading days | A Saturday regime must be actionable on Monday; monthly and band triggers need a daily check |

## Appendix C. References

Frameworks
- Uragami Kunio's four seasons (summary): https://www.mt.co.kr/stock/2009/12/18/2009121807212118118
- Bridgewater, The All Weather Story: https://www.bridgewater.com/research-and-insights/the-all-weather-story
- 오건영, 『부의 시나리오』 (Kyobo): https://ebook-product.kyobobook.co.kr/dig/epd/ebook/E000002991075
- 오건영 on the Korea–US rate gap and USD/KRW: https://nocutnews.co.kr/news/6539529

Data
- FRED removal of ISM data (2016): https://fred.stlouisfed.org/news/2016/06/institute-for-supply-management-data-to-be-removed-from-fred
- KIS Open API official samples: https://github.com/koreainvestment/open-trading-api

Pension rules
- 국세청 연금계좌 세액공제: https://www.nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=7875
- 금융위원회, 채권혼합형 주식 편입 한도 (2023-06-01): https://www.fsc.go.kr/no010101/80098
- KB자산운용 연금 계좌 투자 상품 가이드 (2026-08-05): https://kbam.co.kr/pension/guide/pension-account-investment-products-guide
- 미래에셋 TIGER 연금 투자 가능 상품 (commodities): https://investments.miraeasset.com/tigeretf/ko/pension/commodities-investment-currencies/list.do

ETFs
- TIMEFOLIO ETF lineup: https://timeetf.co.kr/m11_list.php?cate=001
- KRX active-ETF correlation rule and the 2026 delisting: https://www.fnnews.com/news/202607051815576445
- ETF fees and net assets: funetf.co.kr product pages (as of 2026-10-30)

Prior projects
- allocado (private): the 4-Pool model, beta map and Market Pulse ideas
- actstock-pipeline (private): table naming and per-day upsert patterns

## Appendix D. Glossary

| Term | Meaning |
|------|---------|
| ₩억 | 100 million won (₩100,000,000). "Net assets ₩5,000억" = ₩500 billion |
| 연금저축 (pension savings account) | Tax-advantaged personal pension account; can hold domestic-listed ETFs, no leveraged or inverse ETFs, no risky-asset cap |
| IRP (Individual Retirement Pension) | Tax-advantaged retirement account under the 퇴직연금 (retirement pension) rules |
| IRP 70% rule | Risky assets may be at most 70% of an IRP; at least 30% must be "safe" assets (bond, cash-like and qualifying bond-mixed funds). Price drift above 70% blocks new risky buys but forces no sale |
| Safe / risky | The IRP classification of each ETF under the rule above |
| 모의투자 | Mock (paper) trading: KIS's simulated account with fake money |
| KIS | 한국투자증권 (Korea Investment & Securities), whose Open API provides quotes and orders |
| FRED / ALFRED | St. Louis Fed's economic database / its archive of past data vintages ("as first published") |
| ECOS | Bank of Korea's economic statistics system and API |
| KRX | Korea Exchange |
| VKOSPI | Korea's volatility index on KOSPI 200 options (Korean counterpart of VIX) |
| 국고채, 기준금리 | Korean government bonds; the Bank of Korea base (policy) rate |
| 선행지수 순환변동치 | Cyclical component of Korea's composite leading index; above 100 means above trend |
| 회사채 AA− | Korean corporate bonds rated AA−; its spread over government bonds measures credit stress |
| CD rate | 91-day certificate-of-deposit rate, Korea's short-term reference rate |
| 통화정책방향 | The Bank of Korea's monetary policy decision statement |
| 유사투자자문업 | Korea's regulated category for paid or public investment-signal services |
| (H), (합성) | ETF name tags: FX-hedged; synthetic (holds swaps instead of the assets) |
| Sahm rule | Recession signal: the 3-month average unemployment rate rising 0.5 points above its 12-month low |
| Term premium | Extra yield investors demand for holding long bonds instead of rolling short ones |
| ER (excess return) | A futures index return without the interest on collateral |
| Sortino, Calmar | Return per unit of downside volatility; CAGR divided by MDD |
| VaR 95% | The daily loss exceeded on only 5% of days |
| TWR, XIRR | Time-weighted return (ignores cash-flow timing); money-weighted return (includes contribution timing) |
| Deadband | The margin around zero a score must clear before the label changes (§2.4) |
| allocado, actstock-pipeline | The owner's earlier private projects: a US-focused 4-Pool portfolio app and a US ETF data pipeline |
