import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import type { TurnResult } from './queries.ts';
import styles from './TurnSummary.module.css';

// The first lines of the turn screen: server figures only, no new number.
export function TurnSummary({ turn }: { turn: TurnResult }) {
  return (
    <section
      className={styles.summary}
      aria-label="요약"
      data-breached={turn.riskBreached ? 'true' : 'false'}
    >
      <div className={styles.head}>
        <strong className={styles.total}>{formatWon(turn.totalValue)}</strong>
        <Change value={turn.periodReturn} kind="percent" />
      </div>
      <p className={styles.line}>
        <span>누적 낙폭 {ratioWithPercent(turn.drawdown)}</span>
        <span>위험 선언 {ratioWithPercent(turn.riskLimit)}</span>
        <span className={turn.riskBreached ? styles.over : styles.ok}>
          {turn.riskBreached ? '초과' : '선언 안'}
        </span>
      </p>
    </section>
  );
}
