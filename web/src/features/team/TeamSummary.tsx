import { formatWon } from '../format/money.ts';
import { WeightBars } from '../seasons/WeightBars.tsx';
import { weightRowsOf } from '../seasons/weightRows.ts';
import type { Holdings } from './queries.ts';
import styles from './Team.module.css';

export function TeamSummary({ holdings }: { holdings: Holdings }) {
  const formation = holdings.currentFormation;
  return (
    <section className={styles.card} aria-label="팀 요약">
      <div className={styles.cash}>
        <span className={styles.muted}>현금 잔액</span>
        <strong className={styles.cashValue}>
          {formatWon(holdings.cashBalance)}
        </strong>
      </div>
      <div className={styles.rowBetween}>
        <span className={styles.muted}>총 평가액</span>
        <span>{formatWon(holdings.totalValue)}</span>
      </div>
      <p className={styles.hint}>
        포메이션 대비 현재 비중 (검은 선 = 목표). 골키퍼에는 현금이 포함됩니다.
      </p>
      <WeightBars
        rows={weightRowsOf(
          {
            goalkeeperWeight: holdings.goalkeeperWeight,
            defenderWeight: holdings.defenderWeight,
            midfielderWeight: holdings.midfielderWeight,
            forwardWeight: holdings.forwardWeight,
          },
          formation,
        )}
      />
    </section>
  );
}
