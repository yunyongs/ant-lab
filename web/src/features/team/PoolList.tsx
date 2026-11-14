import { useIsMutating } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { advanceKey } from '../turn/queries.ts';
import { OrderSheet } from './OrderSheet.tsx';
import { PoolCard } from './PoolCard.tsx';
import type { Holding, PoolAsset } from './queries.ts';
import styles from './Team.module.css';

export function PoolList({
  assets,
  holdings,
  prices,
  pricesFailed,
  seasonId,
  turnNo,
  cashBalance,
  onStale,
}: {
  assets: PoolAsset[];
  holdings: Holding[];
  prices: Map<number, string> | undefined;
  pricesFailed: boolean;
  seasonId: number;
  turnNo: number;
  cashBalance: string;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const advancing = useIsMutating({ mutationKey: advanceKey(seasonId) }) > 0;
  const [selected, setSelected] = useState<PoolAsset | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  return (
    <section aria-label="종목 풀" className={styles.section}>
      <h3 className={styles.sectionTitle}>종목 풀</h3>
      <p className={styles.hint}>
        참여율은 시장 대비 배수, 변동성·최대 낙폭은 월 기준 비율입니다.
      </p>
      <ul className={styles.list}>
        {assets.map((asset) => (
          <PoolCard
            key={asset.assetId}
            asset={asset}
            holding={holdings.find((h) => h.assetId === asset.assetId)}
            disabled={advancing}
            onSelect={(picked, button) => {
              opener.current = button;
              setSelected(picked);
            }}
          />
        ))}
      </ul>
      {selected && (
        <OrderSheet
          key={selected.assetId}
          side="BUY"
          asset={selected}
          holding={holdings.find((h) => h.assetId === selected.assetId)}
          price={prices?.get(selected.assetId)}
          pricesFailed={pricesFailed}
          seasonId={seasonId}
          turnNo={turnNo}
          cashBalance={cashBalance}
          onClose={() => {
            setSelected(null);
            opener.current?.focus();
          }}
          onStale={(code) => {
            setSelected(null);
            onStale(code);
          }}
        />
      )}
    </section>
  );
}
