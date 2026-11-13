import { formatWon } from '../format/money.ts';
import { formationText } from '../seasons/labels.ts';
import { WeightBars } from '../seasons/WeightBars.tsx';
import { weightRowsOf } from '../seasons/weightRows.ts';
import type { Holdings } from '../team/queries.ts';
import styles from './Trade.module.css';

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
      <div className={styles.rowBetween}>
        <span className={styles.muted}>현재 포메이션</span>
        <span>{formationText(holdings.currentFormation)}</span>
      </div>
      <div className={styles.rowBetween}>
        <span className={styles.muted}>포메이션 이탈</span>
        <span>{holdings.formationDeviation}</span>
      </div>
      <WeightBars rows={weightRowsOf(holdings, holdings.currentFormation)} />
    </section>
  );
}
