import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import type { Analysis } from './queries.ts';
import { NOT_SCORED_NOTE, NUDGED_SHARE_NONE } from './analysisMessages.ts';
import { Rows } from './Rows.tsx';
import { SOURCE_LABEL } from './sourceLabels.ts';
import styles from './Analysis.module.css';

// A source the web does not know yet is shown as sent, never as a blank cell.
function labelOf(source: string): string {
  const labels: Record<string, string> = SOURCE_LABEL;
  return labels[source] ?? source;
}

export function PlatformBlock({
  platform,
}: {
  platform: Analysis['platform'];
}) {
  return (
    <section className={styles.card} aria-labelledby="analysis-platform">
      <h2 id="analysis-platform" className={styles.cardHeading}>
        플랫폼의 수익
      </h2>
      <p className={styles.note}>{NOT_SCORED_NOTE}</p>
      <p className={styles.note}>
        {`이 시즌에 낸 수수료 ${formatWon(platform.totalFee)} — 플랫폼에는 수익입니다.`}
      </p>
      <Rows
        rows={[
          { label: '총 수수료', value: formatWon(platform.totalFee) },
          { label: '0턴 팀 구성 수수료', value: formatWon(platform.teamFee) },
        ]}
      />
      <div
        className={styles.scroll}
        tabIndex={0}
        role="region"
        aria-label="출발점별 주문 표"
      >
        <table className={styles.table}>
          <caption>출발점별 주문 (1턴부터)</caption>
          <thead>
            <tr>
              <th scope="col">출발점</th>
              <th scope="col">주문</th>
              <th scope="col">매매 대금</th>
              <th scope="col">수수료</th>
            </tr>
          </thead>
          <tbody>
            {platform.bySource.map((row, index) => (
              <tr key={index}>
                <th scope="row">{labelOf(row.source)}</th>
                <td>{`${String(row.orders)}건`}</td>
                <td>{formatWon(row.tradeNotional)}</td>
                <td>{formatWon(row.fee)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Rows
        rows={[
          {
            label: '랭킹·알림에서 시작된 수수료',
            value: formatWon(platform.nudgedFee),
          },
          {
            label: '그 몫의 비중',
            value:
              platform.nudgedFeeShare === null
                ? NUDGED_SHARE_NONE
                : ratioWithPercent(platform.nudgedFeeShare),
          },
        ]}
      />
    </section>
  );
}
