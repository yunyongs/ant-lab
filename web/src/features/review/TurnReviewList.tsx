import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import type { Review, TurnValues } from './queries.ts';
import { EventTag } from './EventTag.tsx';
import {
  NO_SUMMARY_TEXT,
  NOT_PLAYED_TEXT,
  RETRY_FAILED_TURNS_TEXT,
  VALUE_FAILED_TEXT,
  VALUE_LOADING_TEXT,
} from './reviewMessages.ts';
import styles from './Review.module.css';

function ValueLine({
  turnNo,
  lastTurnNo,
  values,
}: {
  turnNo: number;
  lastTurnNo: number;
  values: TurnValues;
}) {
  if (turnNo > lastTurnNo)
    return <p className={styles.value}>{NOT_PLAYED_TEXT}</p>;
  const value = values.values[turnNo - 1];
  if (value === undefined || value.state === 'loading') {
    return <p className={styles.value}>{VALUE_LOADING_TEXT}</p>;
  }
  if (value.state === 'error') {
    return <p className={styles.value}>{VALUE_FAILED_TEXT}</p>;
  }
  return (
    <p className={styles.value}>
      {`총 평가액 ${formatWon(value.turn.totalValue)} · 턴 수익률 ${ratioWithPercent(value.turn.periodReturn)}`}
    </p>
  );
}

export function TurnReviewList({
  review,
  lastTurnNo,
  values,
}: {
  review: Review;
  lastTurnNo: number;
  values: TurnValues;
}) {
  return (
    <section className={styles.card} aria-labelledby="review-turns">
      <h2 id="review-turns" className={styles.cardHeading}>
        턴별 복기
      </h2>
      <ol className={styles.turns}>
        {review.turns.map((turn) => (
          <li key={turn.turnNo} className={styles.turn}>
            <span className={styles.turnNo}>{`${String(turn.turnNo)}턴`}</span>
            <div className={styles.turnBody}>
              <p className={styles.month}>{turn.yearMonth}</p>
              <ValueLine
                turnNo={turn.turnNo}
                lastTurnNo={lastTurnNo}
                values={values}
              />
              <p className={styles.summary}>
                {turn.eventSummary ?? NO_SUMMARY_TEXT}
              </p>
              {turn.events.length > 0 && (
                <ul className={styles.events}>
                  {turn.events.map((event, index) => (
                    <EventTag
                      key={`${event.title}-${String(index)}`}
                      event={event}
                    />
                  ))}
                </ul>
              )}
            </div>
          </li>
        ))}
      </ol>
      {values.failedCount > 0 && (
        <button
          type="button"
          className={styles.secondary}
          onClick={values.retryFailed}
        >
          {RETRY_FAILED_TURNS_TEXT}
        </button>
      )}
    </section>
  );
}
