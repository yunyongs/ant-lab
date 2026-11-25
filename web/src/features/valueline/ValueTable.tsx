import { Change } from '../format/Change.tsx';
import { decimalSign } from '../format/derive.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import type { TurnSummary } from './queries.ts';
import { DRAWDOWN_ALL_ZERO, TABLE_CAPTION } from './valueLineMessages.ts';
import styles from './ValueLine.module.css';

// The text record of the chart: every cell is formatted from the server string.
// A drawdown column of zeros is replaced by one sentence.
export function ValueTable({ items }: { items: readonly TurnSummary[] }) {
  const showDrawdown = items.some((item) => decimalSign(item.drawdown) !== 0);
  return (
    <>
      <table className={styles.table}>
        <caption className={styles.caption}>{TABLE_CAPTION}</caption>
        <thead>
          <tr>
            <th scope="col">턴</th>
            <th scope="col">총 평가액</th>
            <th scope="col">그 턴 수익률</th>
            {showDrawdown && <th scope="col">누적 낙폭</th>}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.turnNo}>
              <th scope="row">{`${String(item.turnNo)}턴`}</th>
              <td>{formatWon(item.totalValue)}</td>
              <td>
                <Change value={item.periodReturn} kind="percent" />
              </td>
              {showDrawdown && <td>{ratioToPercent(item.drawdown)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
      {!showDrawdown && <p className={styles.note}>{DRAWDOWN_ALL_ZERO}</p>}
    </>
  );
}
