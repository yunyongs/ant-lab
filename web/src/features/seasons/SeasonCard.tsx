import { useId } from 'react';
import { Link } from 'react-router';
import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import {
  abandonText,
  leagueText,
  openButtonLabel,
  statusLabel,
  turnProgressWidth,
  turnText,
  valueLabel,
  type Season,
} from './labels.ts';
import styles from './Seasons.module.css';

export function SeasonCard({ season }: { season: Season }) {
  const finished = season.status === 'FINISHED';
  const abandoned = abandonText(season);
  const league = leagueText(season);
  const turn = turnText(season);
  const progressLabelId = useId();
  return (
    <li className={styles.card}>
      <div className={styles.cardTitle}>
        <h2 className={styles.cardHeading}>{`시즌 ${String(season.id)}`}</h2>
        <div className={styles.badges}>
          {league !== null && <span className={styles.league}>{league}</span>}
          <span className={finished ? styles.pillMuted : styles.pill}>
            {statusLabel(season)}
          </span>
        </div>
      </div>
      <div className={styles.value}>
        <span className={styles.valueLabel}>{valueLabel(season)}</span>
        {season.latestTotalValue === null ? (
          <span className={styles.valueNeutral}>시작 전</span>
        ) : (
          <span className={styles.valueLine}>
            <span className={styles.valueFigure}>
              {formatWon(season.latestTotalValue)}
            </span>
            {season.totalReturn !== null && (
              <span className={styles.returnLine}>
                <span className={styles.returnLabel}>총 수익률</span>
                <Change value={season.totalReturn} kind="percent" />
              </span>
            )}
          </span>
        )}
      </div>
      <div className={styles.progress}>
        <span className={styles.progressLabel}>
          <span id={progressLabelId}>현재 턴</span>
          {/* The bar's aria-valuetext carries this text for screen readers. */}
          <span aria-hidden="true">{turn}</span>
        </span>
        <div
          className={styles.progressTrack}
          role="progressbar"
          aria-labelledby={progressLabelId}
          aria-valuemin={0}
          aria-valuemax={season.pathLength}
          aria-valuenow={season.currentTurnNo}
          aria-valuetext={turn}
        >
          <span
            className={styles.progressFill}
            style={{ width: turnProgressWidth(season) }}
          />
        </div>
      </div>
      <dl className={styles.minorRows}>
        <div className={styles.row}>
          <dt>위험 선언</dt>
          <dd>{ratioWithPercent(season.riskLimit)}</dd>
        </div>
        <div className={styles.row}>
          <dt>현금 잔액</dt>
          <dd>{formatWon(season.cashBalance)}</dd>
        </div>
      </dl>
      {abandoned !== null && <p className={styles.abandoned}>{abandoned}</p>}
      <Link to={`/seasons/${String(season.id)}`} className={styles.secondary}>
        {openButtonLabel(season)}
      </Link>
    </li>
  );
}
