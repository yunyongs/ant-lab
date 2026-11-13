import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import { positionLabel } from '../seasons/labels.ts';
import type { Holding } from '../team/queries.ts';
import styles from './Trade.module.css';

export const UNKNOWN_ASSET_NAME = '이름을 찾지 못한 종목';

export function HoldingCard({
  holding,
  name,
  disabled,
  onTrade,
}: {
  holding: Holding;
  name: string;
  disabled: boolean;
  onTrade: (
    side: 'BUY' | 'SELL',
    holding: Holding,
    opener: HTMLButtonElement,
  ) => void;
}) {
  return (
    <li className={styles.card}>
      <div className={styles.cardTitle}>
        <h3 className={styles.cardHeading}>{name}</h3>
        <span className={styles.pill}>
          {positionLabel[holding.declaredPosition]}
        </span>
      </div>
      <dl className={styles.facts}>
        <div>
          <dt>수량</dt>
          <dd>{`${String(holding.quantity)}주`}</dd>
        </div>
        <div>
          <dt>평균단가</dt>
          <dd>{formatWon(holding.avgPrice)}</dd>
        </div>
        <div>
          <dt>현재가</dt>
          <dd>{formatWon(holding.price)}</dd>
        </div>
        <div>
          <dt>평가액</dt>
          <dd>{formatWon(holding.value)}</dd>
        </div>
        <div>
          <dt>비중</dt>
          <dd>{ratioToPercent(holding.weight)}</dd>
        </div>
      </dl>
      <div className={styles.buttons}>
        <button
          type="button"
          className={styles.primary}
          aria-label={`${name} 매수`}
          disabled={disabled}
          onClick={(event) => {
            onTrade('BUY', holding, event.currentTarget);
          }}
        >
          매수
        </button>
        <button
          type="button"
          className={styles.sell}
          aria-label={`${name} 매도`}
          disabled={disabled}
          onClick={(event) => {
            onTrade('SELL', holding, event.currentTarget);
          }}
        >
          매도
        </button>
      </div>
    </li>
  );
}
