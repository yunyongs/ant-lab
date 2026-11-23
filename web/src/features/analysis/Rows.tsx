import { SignedValue } from '../format/Change.tsx';
import type { SignedText } from '../format/signedChange.ts';
import styles from './Analysis.module.css';

export interface RowDef {
  label: string;
  // A SignedText is a gain or loss: sign, arrow and up/down colour.
  value: string | SignedText;
}

export function Rows({ rows }: { rows: RowDef[] }) {
  return (
    <dl className={styles.rows}>
      {rows.map((row) => (
        <div key={row.label} className={styles.row}>
          <dt>{row.label}</dt>
          <dd>
            {typeof row.value === 'string' ? (
              row.value
            ) : (
              <SignedValue change={row.value} />
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}
