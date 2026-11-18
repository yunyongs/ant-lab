import styles from './Analysis.module.css';

export interface RowDef {
  label: string;
  value: string;
}

export function Rows({ rows }: { rows: RowDef[] }) {
  return (
    <dl className={styles.rows}>
      {rows.map((row) => (
        <div key={row.label} className={styles.row}>
          <dt>{row.label}</dt>
          <dd>{row.value}</dd>
        </div>
      ))}
    </dl>
  );
}
