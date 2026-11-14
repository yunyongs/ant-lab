import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { PoolList } from '../team/PoolList.tsx';
import { useHoldings, usePool, usePrices } from '../team/queries.ts';
import { FormationEntry } from './FormationEntry.tsx';
import { HoldingList } from './HoldingList.tsx';
import { TradeSummary } from './TradeSummary.tsx';
import styles from './Trade.module.css';

// Live holdings and the pool for buying something new. Rendered only while
// the season is in progress, so no other state requests these.
export function TradeBody({
  season,
  onNotice,
}: {
  season: Season;
  onNotice: (text: string | null) => void;
}) {
  const queryClient = useQueryClient();
  const holdings = useHoldings(season.id);
  const pool = usePool();
  const prices = usePrices(season.id, season.currentTurnNo);
  // Open from the start only when there is nothing held (read once).
  const [poolOpen, setPoolOpen] = useState<boolean | null>(null);
  const held = holdings.data?.holdings ?? [];
  const open = poolOpen ?? held.length === 0;

  const onStale = (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => {
    onNotice(messageFor(code));
    void invalidateSeasonState(queryClient, season.id);
  };

  const retry = () => {
    void holdings.refetch();
    void pool.refetch();
  };
  const failed = holdings.isError || pool.isError;
  const error = holdings.error ?? pool.error;
  // Without data the whole view is the error; with data (a failed refetch)
  // keep the list and any open sheet, so a fill result is not lost.
  if (failed && (holdings.data === undefined || pool.data === undefined)) {
    return <QueryError error={error} onRetry={retry} />;
  }
  // A cached answer from an earlier turn is stale; wait for the refetch.
  if (
    holdings.data === undefined ||
    pool.data === undefined ||
    holdings.data.turnNo !== season.currentTurnNo
  ) {
    return (
      <p role="status" className={styles.muted}>
        보유 종목을 불러오는 중입니다…
      </p>
    );
  }
  const names = new Map(pool.data.map((a) => [a.assetId, a.name]));
  const common = {
    prices: prices.data,
    pricesFailed: prices.isError,
    seasonId: season.id,
    turnNo: season.currentTurnNo,
    cashBalance: holdings.data.cashBalance,
    onStale,
  };
  return (
    <>
      {failed && <QueryError error={error} onRetry={retry} />}
      <TradeSummary holdings={holdings.data} />
      <FormationEntry season={season} />
      <HoldingList holdings={held} names={names} {...common} />
      <details
        className={styles.pool}
        open={open}
        onToggle={(event) => {
          setPoolOpen(event.currentTarget.open);
        }}
      >
        <summary>종목 풀에서 새로 사기</summary>
        <PoolList assets={pool.data} holdings={held} {...common} />
      </details>
    </>
  );
}
