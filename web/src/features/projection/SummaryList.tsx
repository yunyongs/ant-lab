import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import type { Projection } from './phases.ts';
import styles from './Projection.module.css';

type Summary = Projection['summaries'][number];

// Always listed lowest percentile first, so the median never stands alone.
const END_ROWS = [
  ['p05', '하위 5%'],
  ['p25', '하위 25%'],
  ['p50', '중앙'],
  ['p75', '상위 25%'],
  ['p95', '상위 5%'],
] as const;

const MDD_ROWS = [
  ['p05', '하위 5%'],
  ['p10', '10% 확률로 이보다 나쁨'],
  ['p25', '하위 25%'],
  ['p50', '중앙'],
  ['p75', '상위 25%'],
  ['p95', '상위 5%'],
] as const;

// Server-counted horizons carry a years hint where it is exact.
function turnsHeading(turn: number): string {
  const years = turn === 60 ? ' (5년)' : turn === 120 ? ' (10년)' : '';
  return `${String(turn)}턴 뒤${years}`;
}

function SummaryBlock({
  summary,
  riskLimit,
}: {
  summary: Summary;
  riskLimit: string;
}) {
  return (
    <section className={styles.block}>
      <h2 className={styles.blockHeading}>{turnsHeading(summary.turn)}</h2>
      <h3 className={styles.subHeading}>끝 가치</h3>
      <dl className={styles.rows}>
        {END_ROWS.map(([key, label]) => (
          <div key={key} className={styles.row}>
            <dt>{label}</dt>
            <dd>{formatWon(summary.end[key])}</dd>
          </div>
        ))}
      </dl>
      <h3 className={styles.subHeading}>최대 낙폭</h3>
      <dl className={styles.rows}>
        {MDD_ROWS.map(([key, label]) => (
          <div key={key} className={styles.row}>
            <dt>{label}</dt>
            <dd>{ratioToPercent(summary.mdd[key])}</dd>
          </div>
        ))}
      </dl>
      <p className={styles.breach}>
        {`위험 선언(${ratioToPercent(riskLimit)})을 넘길 확률 ${ratioToPercent(summary.breachProbability)}`}
      </p>
    </section>
  );
}

export function SummaryList({ projection }: { projection: Projection }) {
  return (
    <>
      {projection.summaries.map((summary) => (
        <SummaryBlock
          key={summary.turn}
          summary={summary}
          riskLimit={projection.riskLimit}
        />
      ))}
    </>
  );
}
