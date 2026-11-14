import { Link } from 'react-router';
import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import {
  openButtonLabel,
  statusLabel,
  turnText,
  type Season,
} from './labels.ts';
import styles from './Seasons.module.css';

export function SeasonCard({ season }: { season: Season }) {
  const finished = season.status === 'FINISHED';
  return (
    <li className={styles.card}>
      <div className={styles.cardTitle}>
        <h2 className={styles.cardHeading}>{`시즌 ${String(season.id)}`}</h2>
        <span className={finished ? styles.pillMuted : styles.pill}>
          {statusLabel(season)}
        </span>
      </div>
      <dl className={styles.rows}>
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
      </dl>
      <Link to={`/seasons/${String(season.id)}`} className={styles.secondary}>
        {openButtonLabel(season)}
      </Link>
    </li>
  );
}
