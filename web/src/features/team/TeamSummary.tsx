import { formatWon } from '../format/money.ts';
import { ratioToCssPercent, ratioToPercent } from '../format/ratio.ts';
import type { Holdings } from './queries.ts';
import { buyHint, slicesOf } from './teamHint.ts';
import styles from './TeamSummary.module.css';

export function TeamSummary({ holdings }: { holdings: Holdings }) {
  const slices = slicesOf(holdings);
  const hint = buyHint(holdings);
  return (
    <section className={styles.card} aria-label="팀 요약">
      <div className={styles.cash}>
        <span className={styles.muted}>남은 현금</span>
        <strong className={styles.cashValue}>
          {formatWon(holdings.cashBalance)}
        </strong>
      </div>
      <div className={styles.bars}>
        <span className={styles.barLabel}>목표 포메이션</span>
        <div className={styles.bar} aria-hidden="true">
          {slices.map((slice) => (
            <span
              key={slice.key}
              className={`${styles.segment} ${styles[slice.key]}`}
              style={{ width: `${String(slice.target)}%` }}
            />
          ))}
        </div>
        <span className={styles.barLabel}>지금</span>
        <div className={styles.bar} aria-hidden="true">
          {slices.map((slice) => (
            <span
              key={slice.key}
              className={`${styles.segment} ${styles[slice.key]}`}
              data-testid={`fill-${slice.key}`}
              style={{ width: ratioToCssPercent(slice.weight) }}
            />
          ))}
        </div>
      </div>
      <ul className={styles.legend}>
        {slices.map((slice) => (
          <li key={slice.key} className={styles.legendItem}>
            <span
              aria-hidden="true"
              className={`${styles.swatch} ${styles[slice.key]}`}
            />
            {`${slice.label} ${ratioToPercent(slice.weight)} → ${String(slice.target)}%`}
          </li>
        ))}
      </ul>
      <p className={styles.hint}>
        골키퍼에는 현금이 포함됩니다.
        {hint !== null && ` ${hint}`}
      </p>
    </section>
  );
}
