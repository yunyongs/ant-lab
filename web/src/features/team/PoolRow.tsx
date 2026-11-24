import { useId, useState } from 'react';
import { formatDecimal } from '../format/decimal.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import { positionLabel } from '../seasons/labels.ts';
import styles from './PoolRow.module.css';
import type { Holding, PoolAsset } from './queries.ts';

export function PoolRow({
  asset,
  holding,
  price,
  onSelect,
  disabled,
}: {
  asset: PoolAsset;
  holding: Holding | undefined;
  price: string | undefined;
  onSelect: (asset: PoolAsset, opener: HTMLButtonElement) => void;
  disabled: boolean;
}) {
  const [open, setOpen] = useState(false);
  const detailId = useId();
  return (
    <li className={styles.row}>
      <div className={styles.line}>
        <div className={styles.id}>
          <h3 className={styles.name} title={asset.name}>
            {asset.name}
          </h3>
          <span className={styles.position}>
            {positionLabel[asset.systemPosition]}
          </span>
        </div>
        <span className={styles.price}>
          {price === undefined ? '–' : formatWon(price)}
        </span>
        <button
          type="button"
          className={styles.buy}
          aria-label={`${asset.name} 사기`}
          disabled={disabled}
          onClick={(event) => {
            onSelect(asset, event.currentTarget);
          }}
        >
          사기
        </button>
        <button
          type="button"
          className={styles.toggle}
          aria-label={`${asset.name} 지표`}
          aria-expanded={open}
          aria-controls={detailId}
          onClick={() => {
            setOpen((value) => !value);
          }}
        >
          {open ? '▴' : '▾'}
        </button>
      </div>
      {holding && (
        <p className={styles.held}>
          {`보유 ${String(holding.quantity)}주 · ${positionLabel[holding.declaredPosition]} 선언`}
        </p>
      )}
      <div className={styles.detail} id={detailId} hidden={!open}>
        <dl className={styles.stats}>
          <div className={styles.fullName}>
            <dt>이름</dt>
            <dd>{asset.name}</dd>
          </div>
          <div>
            <dt>시장 · 코드</dt>
            <dd>{`${asset.market} · ${asset.ticker}`}</dd>
          </div>
          <div>
            <dt>상승 참여</dt>
            <dd>{formatDecimal(asset.upParticipation)}</dd>
          </div>
          <div>
            <dt>하락 참여</dt>
            <dd>{formatDecimal(asset.downParticipation)}</dd>
          </div>
          <div>
            <dt>변동성</dt>
            <dd>{ratioToPercent(asset.volatility)}</dd>
          </div>
          <div>
            <dt>최대 낙폭</dt>
            <dd>{ratioToPercent(asset.maxDrawdown)}</dd>
          </div>
        </dl>
        <p className={styles.hint}>
          참여율은 시장 대비 배수, 변동성·최대 낙폭은 월 기준 비율입니다.
        </p>
      </div>
    </li>
  );
}
