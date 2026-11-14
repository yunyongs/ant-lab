import { useIsMutating } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { OrderSheet } from '../team/OrderSheet.tsx';
import type { Holding } from '../team/queries.ts';
import { advanceKey } from '../turn/queries.ts';
import { HoldingCard, UNKNOWN_ASSET_NAME } from './HoldingCard.tsx';
import styles from './Trade.module.css';

interface Selection {
  side: 'BUY' | 'SELL';
  assetId: number;
}

// Names come only from the pool join; the asset id is never printed.
export function HoldingList({
  holdings,
  names,
  prices,
  pricesFailed,
  seasonId,
  turnNo,
  cashBalance,
  onStale,
}: {
  holdings: Holding[];
  names: Map<number, string>;
  prices: Map<number, string> | undefined;
  pricesFailed: boolean;
  seasonId: number;
  turnNo: number;
  cashBalance: string;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const advancing = useIsMutating({ mutationKey: advanceKey(seasonId) }) > 0;
  const [selected, setSelected] = useState<Selection | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const heading = useRef<HTMLHeadingElement | null>(null);
  const nameOf = (assetId: number) => names.get(assetId) ?? UNKNOWN_ASSET_NAME;

  return (
    <section aria-label="보유 종목" className={styles.section}>
      <h2 ref={heading} tabIndex={-1} className={styles.sectionTitle}>
        {`보유 종목 (${String(holdings.length)})`}
      </h2>
      {holdings.length === 0 ? (
        <div className={styles.empty}>
          <p className={styles.emptyTitle}>보유 종목이 없습니다</p>
          <p>
            전부 현금(골키퍼)입니다. 아래 종목 풀에서 사면 여기에 나타납니다.
          </p>
        </div>
      ) : (
        <ul className={styles.list}>
          {holdings.map((holding) => (
            <HoldingCard
              key={holding.assetId}
              holding={holding}
              name={nameOf(holding.assetId)}
              disabled={advancing}
              onTrade={(side, picked, button) => {
                opener.current = button;
                setSelected({ side, assetId: picked.assetId });
              }}
            />
          ))}
        </ul>
      )}
      {selected && (
        <OrderSheet
          key={`${selected.side}-${String(selected.assetId)}`}
          side={selected.side}
          asset={{
            assetId: selected.assetId,
            name: nameOf(selected.assetId),
          }}
          holding={holdings.find((h) => h.assetId === selected.assetId)}
          price={prices?.get(selected.assetId)}
          pricesFailed={pricesFailed}
          seasonId={seasonId}
          turnNo={turnNo}
          cashBalance={cashBalance}
          onClose={() => {
            setSelected(null);
            // A fully sold holding's card is gone: fall back to the heading.
            if (opener.current?.isConnected) opener.current.focus();
            else heading.current?.focus();
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
