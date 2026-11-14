import { formatWon } from '../format/money.ts';
import {
  ratioToPoints,
  ratioWithPercent,
  ratioWithPoints,
} from '../format/ratio.ts';
import { WeightBars } from '../seasons/WeightBars.tsx';
import { weightRowsOf } from '../seasons/weightRows.ts';
import type { TurnResult } from './queries.ts';
import {
  BREACH_BODY,
  BREACH_BODY_FINISHED,
  BREACH_TITLE,
} from './turnMessages.ts';
import styles from './Turn.module.css';

// Whitelisted fields only: the DTO is never spread into the page. Every figure
// is the server's string; only the presentation is changed.
export function TurnResultCard({ turn }: { turn: TurnResult }) {
  const breached = turn.riskBreached;
  return (
    <>
      {breached && (
        <div className={styles.breach}>
          <p className={styles.strong}>{BREACH_TITLE}</p>
          <p>
            {turn.status === 'FINISHED' ? BREACH_BODY_FINISHED : BREACH_BODY}
          </p>
        </div>
      )}
      <section className={styles.card} aria-label="턴 결과">
        <div className={styles.total}>
          <span className={styles.muted}>총 평가액</span>
          <strong className={styles.totalValue}>
            {formatWon(turn.totalValue)}
          </strong>
        </div>
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>직전 턴 대비</dt>
            <dd>{formatWon(turn.valueChange)}</dd>
          </div>
          <div className={styles.row}>
            <dt>턴 수익률</dt>
            <dd>{ratioWithPercent(turn.periodReturn)}</dd>
          </div>
          <div className={styles.row}>
            <dt>현금</dt>
            <dd>{formatWon(turn.cashBalance)}</dd>
          </div>
          <div className={styles.row}>
            <dt>보유 평가액</dt>
            <dd>{formatWon(turn.holdingsValue)}</dd>
          </div>
        </dl>
      </section>
      <section
        className={`${styles.card} ${breached ? styles.overLimit : ''}`}
        aria-label="위험 한도"
        data-breached={breached ? 'true' : 'false'}
      >
        <div className={styles.cardTitle}>
          <h3 className={styles.cardHeading}>위험 한도</h3>
          <span
            className={`${styles.badge} ${breached ? styles.badgeOver : styles.badgeOk}`}
          >
            {breached ? '초과' : '선언 안'}
          </span>
        </div>
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>누적 낙폭</dt>
            <dd>{ratioWithPercent(turn.drawdown)}</dd>
          </div>
          <div className={styles.row}>
            <dt>위험 선언</dt>
            <dd>{ratioWithPercent(turn.riskLimit)}</dd>
          </div>
          <div className={styles.row}>
            <dt>한도까지 여유</dt>
            <dd>{`${turn.riskHeadroom} (${ratioToPoints(turn.riskHeadroom)})`}</dd>
          </div>
        </dl>
      </section>
      <section className={styles.card} aria-label="포지션 비중">
        <h3 className={styles.cardHeading}>포지션 비중 (검은 선 = 목표)</h3>
        <WeightBars rows={weightRowsOf(turn, turn.targetFormation)} />
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>포메이션 이탈</dt>
            <dd>{ratioWithPoints(turn.formationDeviation)}</dd>
          </div>
        </dl>
      </section>
      <p className={styles.hint}>
        이 턴의 결과는 턴을 넘긴 시점의 값입니다. 이번 턴에 사고판 결과는 매매
        화면의 평가액에 바로 반영됩니다.
      </p>
    </>
  );
}
