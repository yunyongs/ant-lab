import type { Review } from './queries.ts';
import { REPRO_HINT } from './reviewMessages.ts';
import styles from './Review.module.css';

export function ReproCard({ review }: { review: Review }) {
  return (
    <section className={styles.card} aria-labelledby="review-repro">
      <h2 id="review-repro" className={styles.cardHeading}>
        재현 정보
      </h2>
      <dl className={styles.rows}>
        <div className={styles.row}>
          <dt>경로 시드</dt>
          <dd className={styles.mono}>{review.seed}</dd>
        </div>
        <div className={styles.row}>
          <dt>엔진 버전</dt>
          <dd>{review.engineVersion}</dd>
        </div>
        <div className={styles.row}>
          <dt>데이터 기준일</dt>
          <dd>{review.dataSnapshotDate}</dd>
        </div>
      </dl>
      <p className={styles.note}>{REPRO_HINT}</p>
    </section>
  );
}
