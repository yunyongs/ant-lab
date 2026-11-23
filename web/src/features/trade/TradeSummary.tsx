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

// Live values at this turn's prices, all from the server.
export function TradeSummary({ holdings }: { holdings: Holdings }) {
  return (
    <section className={styles.card} aria-label="매매 요약">
      <div className={styles.cash}>
        <span className={styles.muted}>현금 잔액</span>
        <strong className={styles.cashValue}>
          {formatWon(holdings.cashBalance)}
        </strong>
      </div>
      <div className={styles.rowBetween}>
        <span className={styles.muted}>
          {`총 평가액 (${String(holdings.turnNo)}턴 가격 기준)`}
        </span>
        <span>{formatWon(holdings.totalValue)}</span>
      </div>
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
      <WeightBars rows={weightRowsOf(holdings, holdings.currentFormation)} />
    </section>
  );
}
