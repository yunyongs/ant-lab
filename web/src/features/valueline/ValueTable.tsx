import { Change } from '../format/Change.tsx';
import { decimalSign } from '../format/derive.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import type { TurnSummary } from './queries.ts';
import { DRAWDOWN_ALL_ZERO, TABLE_CAPTION } from './valueLineMessages.ts';
import styles from './ValueLine.module.css';

const WHOLE_WON = /^\d+$/;

// The text record of the chart: every cell is formatted from the server string.
// A drawdown column of zeros is replaced by one sentence.
export function ValueTable({ items }: { items: readonly TurnSummary[] }) {
  const showDrawdown = items.some((item) => decimalSign(item.drawdown) !== 0);
  const showBenchmark = items.every(
    (item) =>
      item.benchmarkValue !== null &&
      item.benchmarkValue !== undefined &&
      WHOLE_WON.test(item.benchmarkValue),
  );
  return (
    <>
      <table className={styles.table}>
        <caption className={styles.caption}>{TABLE_CAPTION}</caption>
        <thead>
          <tr>
            <th scope="col">턴</th>
            <th scope="col">총 평가액</th>
            {showBenchmark && (
              <th scope="col" className={styles.benchmarkColumn}>
                시장 기준선
              </th>
            )}
            <th scope="col">그 턴 수익률</th>
            {showDrawdown && <th scope="col">누적 낙폭</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.turnNo}>
              <th scope="row">{`${String(item.turnNo)}턴`}</th>
              <td className={styles.numberCell}>
                {formatWon(item.totalValue)}
                {showBenchmark && (
                  <span className={styles.benchmarkLine}>
                    기준선 {formatWon(item.benchmarkValue ?? '')}
                  </span>
                )}
              </td>
              {showBenchmark && (
                <td
                  className={`${styles.benchmarkColumn} ${styles.numberCell}`}
                >
                  {formatWon(item.benchmarkValue ?? '')}
                </td>
              )}
              <td className={styles.numberCell}>
                <Change value={item.periodReturn} kind="percent" />
              </td>
              {showDrawdown && (
                <td className={styles.numberCell}>
                  {ratioToPercent(item.drawdown)}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {!showDrawdown && <p className={styles.note}>{DRAWDOWN_ALL_ZERO}</p>}
    </>
  );
}
