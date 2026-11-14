import { positionLabel } from '../seasons/labels.ts';
import type { Holding, PoolAsset } from './queries.ts';
import styles from './Team.module.css';

function pillClass(position: PoolAsset['systemPosition']): string {
  switch (position) {
    case 'FORWARD_TARGET':
      return `${styles.pill} ${styles.pillTarget}`;
    case 'FORWARD_BREAKOUT':
      return `${styles.pill} ${styles.pillBreakout}`;
    case 'FORWARD_DEFENSIVE':
      return `${styles.pill} ${styles.pillDefensive}`;
    default:
      return `${styles.pill}`;
  }
}

export function PoolCard({
  asset,
  holding,
  onSelect,
  disabled,
}: {
  asset: PoolAsset;
  holding: Holding | undefined;
  onSelect: (asset: PoolAsset, opener: HTMLButtonElement) => void;
  disabled: boolean;
}) {
  return (
    <li className={styles.card}>
      <div className={styles.cardTitle}>
        <h3 className={styles.cardHeading}>{asset.name}</h3>
        <span className={styles.pills}>
          <span className={styles.pill}>{asset.market}</span>
          <span className={pillClass(asset.systemPosition)}>
            {positionLabel[asset.systemPosition]}
          </span>
        </span>
      </div>
      <dl className={styles.basis}>
        <div>
          <dt>상승 참여</dt>
          <dd>{asset.upParticipation}</dd>
        </div>
        <div>
          <dt>하락 참여</dt>
          <dd>{asset.downParticipation}</dd>
        </div>
        <div>
          <dt>변동성</dt>
          <dd>{asset.volatility}</dd>
        </div>
        <div>
          <dt>최대 낙폭</dt>
          <dd>{asset.maxDrawdown}</dd>
        </div>
      </dl>
      {holding && (
        <p className={styles.held}>
          {`보유 ${String(holding.quantity)}주 · ${positionLabel[holding.declaredPosition]} 선언`}
        </p>
      )}
      <button
        type="button"
        className={styles.secondary}
        aria-label={`${asset.name} 선택`}
        disabled={disabled}
        onClick={(event) => {
          onSelect(asset, event.currentTarget);
        }}
      >
        선택
      </button>
    </li>
  );
}
