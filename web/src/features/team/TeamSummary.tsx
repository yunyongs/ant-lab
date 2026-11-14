import { formatWon } from '../format/money.ts';
import { ratioToCssPercent, ratioToPercent } from '../format/ratio.ts';
import type { Holdings } from './queries.ts';
import styles from './Team.module.css';

const ROWS = [
  { key: 'goalkeeper', label: '골키퍼', weight: 'goalkeeperWeight' },
  { key: 'defender', label: '수비수', weight: 'defenderWeight' },
  { key: 'midfielder', label: '미드필더', weight: 'midfielderWeight' },
  { key: 'forward', label: '공격수', weight: 'forwardWeight' },
] as const;

// Every figure is the server's string; the bar widths and the target marks
// only move the decimal point or print the formation integer.
export function TeamSummary({ holdings }: { holdings: Holdings }) {
  const formation = holdings.currentFormation;
  return (
    <section className={styles.card} aria-label="팀 요약">
      <div className={styles.cash}>
        <span className={styles.muted}>현금 잔액</span>
        <strong className={styles.cashValue}>
          {formatWon(holdings.cashBalance)}
        </strong>
      </div>
      <div className={styles.rowBetween}>
        <span className={styles.muted}>총 평가액</span>
        <span>{formatWon(holdings.totalValue)}</span>
      </div>
      <p className={styles.hint}>
        포메이션 대비 현재 비중 (검은 선 = 목표). 골키퍼에는 현금이 포함됩니다.
      </p>
      <ul className={styles.weights}>
        {ROWS.map(({ key, label, weight }) => (
          <li key={key} className={styles.weightRow}>
            <span>{label}</span>
            <span className={styles.bar} aria-hidden="true">
              <span
                className={styles.fill}
                data-testid={`fill-${key}`}
                style={{ width: ratioToCssPercent(holdings[weight]) }}
              />
              <span
                className={styles.target}
                style={{ left: `${String(formation[key])}%` }}
              />
            </span>
            <span className={styles.weightText}>
              {`${ratioToPercent(holdings[weight])} / 목표 ${String(formation[key])}%`}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
