import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import type { TurnSummary } from './queries.ts';
import { TABLE_CAPTION } from './valueLineMessages.ts';
import styles from './ValueLine.module.css';

// The text record of the chart: every cell is formatted from the server string.
export function ValueTable({ items }: { items: readonly TurnSummary[] }) {
  return (
    <table className={styles.table}>
      <caption className={styles.caption}>{TABLE_CAPTION}</caption>
      <thead>
        <tr>
          <th scope="col">턴</th>
          <th scope="col">총 평가액</th>
          <th scope="col">그 턴 수익률</th>
          <th scope="col">누적 낙폭</th>
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
            <td>{ratioToPercent(item.drawdown)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
