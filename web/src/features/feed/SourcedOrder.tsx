import { useIsMutating, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type ReactNode } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { canTrade } from '../seasons/seasonGate.ts';
import { OrderSheet } from '../team/OrderSheet.tsx';
import {
  useHoldings,
  usePool,
  usePrices,
  type Holding,
  type PoolAsset,
} from '../team/queries.ts';
import { advanceKey } from '../turn/queries.ts';
import type { ItemContext } from './itemActions.ts';
import type { OrderSource } from './orderSource.ts';

export interface OrderStarter {
  context: ItemContext;
  nameOf: (assetId: number) => string | null;
  start: (
    side: 'BUY' | 'SELL',
    assetId: number,
    source: OrderSource,
    opener: HTMLButtonElement,
  ) => void;
}

interface Selection {
  side: 'BUY' | 'SELL';
  assetId: number;
  source: OrderSource;
}

// Opens the order sheet in place for an item the player tapped and sends that
// item's source with the order. Data is read for the season's current turn
// only, also on a past-turn page. While it is not ready there are no buttons.
export function SourcedOrder({
  season,
  onNotice,
  children,
}: {
  season: Season;
  onNotice: (text: string | null) => void;
  children: (order: OrderStarter) => ReactNode;
}) {
  const queryClient = useQueryClient();
  const holdings = useHoldings(season.id);
  const pool = usePool();
  const prices = usePrices(season.id, season.currentTurnNo);
  const advancing = useIsMutating({ mutationKey: advanceKey(season.id) }) > 0;
  const [selected, setSelected] = useState<Selection | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  // A sheet opened for one turn must not come back on its own for the next.
  const [seenTurn, setSeenTurn] = useState(season.currentTurnNo);
  if (seenTurn !== season.currentTurnNo) {
    setSeenTurn(season.currentTurnNo);
    setSelected(null);
  }

  // A cached answer from an earlier turn is stale; wait for the refetch.
  const ready =
    holdings.data !== undefined &&
    holdings.data.turnNo === season.currentTurnNo &&
    pool.data !== undefined;
  const assets = new Map<number, PoolAsset>(
    (ready ? pool.data : []).map((a) => [a.assetId, a]),
  );
  const held = new Map<number, Holding>(
    (ready ? holdings.data.holdings : []).map((h) => [h.assetId, h]),
  );

  const context: ItemContext = {
    tradable: canTrade(season),
    advancing,
    inPool: (assetId) => assets.has(assetId),
    held: (assetId) => held.has(assetId),
  };
  const nameOf = (assetId: number) => assets.get(assetId)?.name ?? null;
  const asset = selected === null ? undefined : assets.get(selected.assetId);

  return (
    <>
      {children({
        context,
        nameOf,
        start: (side, assetId, source, button) => {
          opener.current = button;
          onNotice(null);
          setSelected({ side, assetId, source });
        },
      })}
      {!ready && (holdings.isError || pool.isError) && (
        <QueryError
          error={holdings.error ?? pool.error}
          onRetry={() => {
            void holdings.refetch();
            void pool.refetch();
          }}
        />
      )}
      {ready && selected !== null && asset !== undefined && (
        <OrderSheet
          key={`${selected.side}-${String(selected.assetId)}`}
          side={selected.side}
          asset={asset}
          holding={held.get(selected.assetId)}
          price={prices.data?.get(selected.assetId)}
          pricesFailed={prices.isError}
          seasonId={season.id}
          turnNo={season.currentTurnNo}
          cashBalance={holdings.data.cashBalance}
          source={selected.source}
          onClose={() => {
            setSelected(null);
            if (opener.current?.isConnected) opener.current.focus();
          }}
          onStale={(code) => {
            setSelected(null);
            onNotice(messageFor(code));
            void invalidateSeasonState(queryClient, season.id);
          }}
        />
      )}
    </>
  );
}
