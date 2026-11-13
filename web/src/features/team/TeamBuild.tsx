import { useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import { invalidateSeasonState } from '../seasons/queries.ts';
import type { Season } from '../seasons/labels.ts';
import { NextStepCard } from './NextStepCard.tsx';
import { PoolList } from './PoolList.tsx';
import { usePool, useHoldings, usePrices } from './queries.ts';
import { TeamEmpty } from './TeamEmpty.tsx';
import { TeamSummary } from './TeamSummary.tsx';
import styles from './Team.module.css';

export function TeamBuild({
  season,
  onNotice,
}: {
  season: Season;
  onNotice: (text: string) => void;
}) {
  const queryClient = useQueryClient();
  const pool = usePool();
  const holdings = useHoldings(season.id);
  const prices = usePrices(season.id, season.currentTurnNo);

  const poolAssets = pool.data ?? [];
  const poolReady = pool.isSuccess && poolAssets.length > 0;

  return (
    <div className={styles.team}>
      <h2 className={styles.heading}>0턴 팀 구성</h2>

      {holdings.isPending && (
        <p role="status" className={styles.muted}>
          팀을 불러오는 중입니다…
        </p>
      )}
      {holdings.isError && (
        <QueryError
          error={holdings.error}
          onRetry={() => {
            void holdings.refetch();
          }}
        />
      )}
      {holdings.data && <TeamSummary holdings={holdings.data} />}

      {pool.isPending && (
        <p role="status" className={styles.muted}>
          종목 풀을 불러오는 중입니다…
        </p>
      )}
      {(pool.isError || (pool.isSuccess && poolAssets.length === 0)) && (
        <TeamEmpty
          error={pool.error}
          onRetry={() => {
            void pool.refetch();
          }}
        />
      )}
      {poolReady && holdings.data && (
        <PoolList
          assets={poolAssets}
          holdings={holdings.data.holdings}
          prices={prices.data}
          seasonId={season.id}
          turnNo={season.currentTurnNo}
          cashBalance={holdings.data.cashBalance}
          onStale={(code) => {
            onNotice(messageFor(code));
            void invalidateSeasonState(queryClient, season.id);
          }}
        />
      )}
      {holdings.data && (
        <NextStepCard holdingCount={holdings.data.holdings.length} />
      )}
    </div>
  );
}
