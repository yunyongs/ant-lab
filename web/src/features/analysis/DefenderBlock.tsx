import { signedChange } from '../format/signedChange.ts';
import type { Analysis } from './queries.ts';
import { NOT_SCORED_NOTE } from './analysisMessages.ts';
import { Rows } from './Rows.tsx';
import styles from './Analysis.module.css';

export function DefenderBlock({
  defender,
}: {
  defender: Analysis['defenderGoals'];
}) {
  return (
    <section className={styles.card} aria-labelledby="analysis-defender">
      <h3 id="analysis-defender" className={styles.cardHeading}>
        수비수 득점
      </h3>
      <p className={styles.note}>
        {`시장이 내린 턴에 수비수·골키퍼로 선언한 보유 종목이 낸 이익입니다. ${NOT_SCORED_NOTE}`}
      </p>
      <Rows
        rows={[
          {
            label: '시장이 내린 턴',
            value: `${String(defender.downMarketTurns)}턴`,
          },
          { label: '득점', value: `${String(defender.goals)}회` },
          { label: '득점 금액', value: signedChange(defender.goalWon, 'won') },
          {
            label: '득점 수익률',
            value: signedChange(defender.goalReturn, 'percent'),
          },
        ]}
      />
    </section>
  );
}
