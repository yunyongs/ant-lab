import { SignedValue } from '../format/Change.tsx';
import type { RankingRow } from './rankingRows.ts';
import styles from './Ranking.module.css';

// Rows in the server's order. Only the rank, name and the server's own
// figures are printed; no identifier reaches the markup.
export function RankingList({
  title,
  rows,
  emptyText,
}: {
  title: string;
  rows: RankingRow[];
  emptyText: string;
}) {
  return (
    <div className={styles.group}>
      <h4 className={styles.subtitle}>{title}</h4>
      {rows.length === 0 ? (
        <p className={styles.note}>{emptyText}</p>
      ) : (
        <ol className={styles.list}>
          {rows.map((row) => (
            <li key={row.rank} className={styles.row}>
              <span className={styles.rank}>{`${String(row.rank)}위`}</span>
              <span className={styles.name}>{row.name}</span>
              {row.streak !== null && (
                <span
                  className={styles.meta}
                >{`${String(row.streak)}턴 연속`}</span>
              )}
              {row.change !== null && (
                <span className={styles.meta}>
                  <SignedValue change={row.change} />
                </span>
              )}
              {row.grade !== null && (
                <span className={styles.meta}>{row.grade}</span>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
