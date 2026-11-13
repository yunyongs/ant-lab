import type { Report } from './queries.ts';
import type { GroupDef } from './reportRows.ts';
import { RECON_HINT, BENCHMARK_NOTE } from './reportMessages.ts';
import styles from './Report.module.css';

function Rows({ group, report }: { group: GroupDef; report: Report }) {
  return (
    <dl className={styles.rows}>
      {group.rows.map((row) => (
        <div key={row.label} className={styles.row}>
          <dt>
            {row.label}
            {row.hint !== undefined && (
              <span className={styles.hint}>{row.hint}</span>
            )}
          </dt>
          <dd className={row.big === true ? styles.big : undefined}>
            {row.value(report)}
          </dd>
        </div>
      ))}
    </dl>
  );
}

export function ReportGroup({
  group,
  report,
}: {
  group: GroupDef;
  report: Report;
}) {
  const headingId = `report-${group.id}`;
  if (group.summary !== undefined) {
    return (
      <section className={styles.card} aria-labelledby={headingId}>
        <details>
          <summary className={styles.summary}>
            <h2 id={headingId} className={styles.cardHeading}>
              {group.title}
            </h2>
            <span>{group.summary(report)}</span>
          </summary>
          <Rows group={group} report={report} />
          {group.id === 'recon' && <p className={styles.note}>{RECON_HINT}</p>}
        </details>
      </section>
    );
  }
  return (
    <section className={styles.card} aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.cardHeading}>
        {group.title}
      </h2>
      <Rows group={group} report={report} />
      {group.id === 'result' && <p className={styles.note}>{BENCHMARK_NOTE}</p>}
    </section>
  );
}
