import { useId } from 'react';
import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioToCssPercent, ratioToPercent } from '../format/ratio.ts';
import { positionLabel } from '../seasons/labels.ts';
import type { Holding } from '../team/queries.ts';
import styles from './Trade.module.css';

export const UNKNOWN_ASSET_NAME = '이름을 찾지 못한 종목';

// One holding as a disclosure: the toggle row shows value, weight and P&L;
// the open part has the facts and the three actions.
export function HoldingCard({
  holding,
  name,
  open,
  onToggle,
  disabled,
  canChangePosition,
  onTrade,
  onChangePosition,
}: {
  holding: Holding;
  name: string;
  open: boolean;
  onToggle: () => void;
  disabled: boolean;
  canChangePosition: boolean;
  onTrade: (
    side: 'BUY' | 'SELL',
    holding: Holding,
    opener: HTMLButtonElement,
  ) => void;
  onChangePosition: (holding: Holding, opener: HTMLButtonElement) => void;
}) {
  // Never derived from the asset id: ids are not shown anywhere.
  const panelId = useId();
  return (
    <li className={styles.row}>
      <button
        type="button"
        className={styles.rowToggle}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={onToggle}
      >
        <span className={styles.rowLine}>
          <span className={styles.rowName}>
            {name}
            <span className={styles.rowPosition}>
              {` · ${positionLabel[holding.declaredPosition]}`}
            </span>
          </span>
          <span>{formatWon(holding.value)}</span>
        </span>
        <span className={styles.rowLine}>
          <span className={styles.muted}>
            {`비중 ${ratioToPercent(holding.weight)}`}
          </span>
          <span className={styles.rowPnl}>
            <Change value={holding.unrealizedPnl} kind="won" />
            <Change value={holding.unrealizedReturn} kind="percent" />
          </span>
        </span>
        <span className={styles.weightTrack} aria-hidden="true">
          <span
            className={styles.weightFill}
            style={{ width: ratioToCssPercent(holding.weight) }}
          />
        </span>
      </button>
      {open && (
        <div id={panelId} className={styles.rowPanel}>
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
          </dl>
          <div className={styles.actions}>
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
            {canChangePosition && (
              <button
                type="button"
                className={styles.positionAction}
                aria-label={`${name} 포지션 변경`}
                disabled={disabled}
                onClick={(event) => {
                  onChangePosition(holding, event.currentTarget);
                }}
              >
                포지션
              </button>
            )}
          </div>
        </div>
      )}
    </li>
  );
}
