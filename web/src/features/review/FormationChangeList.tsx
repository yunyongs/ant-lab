import type { components } from '../../api/schema';
import { formationText } from '../seasons/labels.ts';
import { NO_CHANGES_TEXT, NO_REASON_TEXT } from './reviewMessages.ts';
import styles from './Review.module.css';

type FormationChange = components['schemas']['FormationChangeDto'];

export function FormationChangeList({
  changes,
}: {
  changes: FormationChange[];
}) {
  return (
    <section className={styles.card} aria-labelledby="review-changes">
      <h2 id="review-changes" className={styles.cardHeading}>
        전술 변경 기록
      </h2>
      {changes.length === 0 ? (
        <p className={styles.note}>{NO_CHANGES_TEXT}</p>
      ) : (
        <ul className={styles.changes}>
          {changes.map((change, index) => (
            <li key={`${String(change.turnNo)}-${String(index)}`}>
              <span
                className={styles.strong}
              >{`${String(change.turnNo)}턴`}</span>
              <span>{` ${formationText(change.before)} → ${formationText(change.after)}`}</span>
              <span className={styles.reason}>
                {change.reason === null
                  ? NO_REASON_TEXT
                  : `사유: ${change.reason}`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
