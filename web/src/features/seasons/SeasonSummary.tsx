import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import { formationText, statusLabel, turnText, type Season } from './labels.ts';
import styles from './Seasons.module.css';

export function SeasonSummary({ season }: { season: Season }) {
  return (
    <section className={styles.card} aria-label="시즌 요약">
      <dl className={styles.rows}>
        <div className={styles.row}>
          <dt>상태</dt>
          <dd>{statusLabel(season)}</dd>
        </div>
        <div className={styles.row}>
          <dt>현재 턴</dt>
          <dd>{turnText(season)}</dd>
        </div>
        <div className={styles.row}>
          <dt>위험 선언</dt>
          <dd>{ratioWithPercent(season.riskLimit)}</dd>
        </div>
        <div className={styles.row}>
          <dt>현금 잔액</dt>
          <dd>{formatWon(season.cashBalance)}</dd>
        </div>
        <div className={styles.row}>
          <dt>포메이션</dt>
          <dd>{formationText(season.currentFormation)}</dd>
        </div>
      </dl>
    </section>
  );
}
