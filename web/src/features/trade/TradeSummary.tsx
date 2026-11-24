import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioWithPoints } from '../format/ratio.ts';
import { formationText } from '../seasons/labels.ts';
import { WeightBars } from '../seasons/WeightBars.tsx';
import { weightRowsOf } from '../seasons/weightRows.ts';
import type { Holdings } from '../team/queries.ts';
import styles from './Trade.module.css';

export const PNL_BASIS_NOTE =
  '평가손익은 수수료·환전 비용·세금을 빼기 전 값입니다. 종목별 합과 몇 원 다를 수 있습니다.';

// Live values at this turn's prices, all from the server. Two figures stay in
// view; formation, deviation, weights and the P&L basis sit behind a
// disclosure.
export function TradeSummary({ holdings }: { holdings: Holdings }) {
  return (
    <section className={styles.summary} aria-label="매매 요약">
      <dl className={styles.figures}>
        <div className={styles.figure}>
          <dt>총 평가액</dt>
          <dd>{formatWon(holdings.totalValue)}</dd>
        </div>
        <div className={styles.figure}>
          <dt>살 수 있는 현금</dt>
          <dd>{formatWon(holdings.cashBalance)}</dd>
        </div>
      </dl>
      <p className={styles.hint}>체결은 이번 턴 가격</p>
      <details className={styles.more}>
        <summary>포메이션·비중</summary>
        <div className={styles.moreBody}>
          {holdings.holdings.length > 0 && (
            <>
              <div className={styles.rowBetween}>
                <span className={styles.muted}>평가손익 (보유 합계)</span>
                <Change value={holdings.unrealizedPnl} kind="won" />
              </div>
              <p className={styles.hint}>{PNL_BASIS_NOTE}</p>
            </>
          )}
          <div className={styles.rowBetween}>
            <span className={styles.muted}>현재 포메이션</span>
            <span>{formationText(holdings.currentFormation)}</span>
          </div>
          <div className={styles.rowBetween}>
            <span className={styles.muted}>포메이션 이탈</span>
            <span>{ratioWithPoints(holdings.formationDeviation)}</span>
          </div>
          <WeightBars
            rows={weightRowsOf(holdings, holdings.currentFormation)}
          />
        </div>
      </details>
    </section>
  );
}
