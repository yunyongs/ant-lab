import { formatWon } from '../format/money.ts';
import {
  ratioToCssPercent,
  ratioToPercent,
  ratioToPoints,
} from '../format/ratio.ts';
import type { WeightRow } from './weightRows.ts';
import styles from './WeightBars.module.css';

// Every figure is the server's string; the bar widths and the target marks
// only move the decimal point or print the formation integer.
export function WeightBars({ rows }: { rows: readonly WeightRow[] }) {
  return (
    <ul className={styles.weights}>
      {rows.map(({ key, label, weight, target, gap, value }) => (
        <li key={key} className={styles.weightRow}>
          <span>{label}</span>
          <span className={styles.bar} aria-hidden="true">
            <span
              className={styles.fill}
              data-testid={`fill-${key}`}
              style={{ width: ratioToCssPercent(weight) }}
            />
            <span
              className={styles.target}
              style={{ left: `${String(target)}%` }}
            />
          </span>
          <span className={styles.weightText}>
            {`${ratioToPercent(weight)} / 목표 ${String(target)}%`}
            {gap !== undefined && ` (${ratioToPoints(gap)})`}
            {value !== undefined && ` · ${formatWon(value)}`}
          </span>
        </li>
      ))}
    </ul>
  );
}
