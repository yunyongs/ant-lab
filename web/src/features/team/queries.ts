import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { invalidateSeasonState } from '../seasons/queries.ts';

export type PoolAsset = components['schemas']['PoolAssetDto'];
export type Holdings = components['schemas']['HoldingsDto'];
export type Holding = components['schemas']['HoldingDto'];
export type OrderRequest = components['schemas']['PlaceOrderRequest'];
export type OrderResult = components['schemas']['OrderResultDto'];

export const poolKey = ['pool'] as const;
export const pricesKey = (id: number, turnNo: number) =>
  ['prices', id, turnNo] as const;
export const holdingsKey = (id: number) => ['holdings', id] as const;

// The pool is fixed for the reference date, so it is fetched once.
export function usePool() {
  return useQuery({
    queryKey: poolKey,
    staleTime: Infinity,
    queryFn: async ({ signal }): Promise<PoolAsset[]> => {
      const { data } = await apiClient.GET('/api/v1/pool', { signal });
      if (!Array.isArray(data?.assets)) throw new UnreachableError();
      return data.assets;
    },
  });
}

export function useHoldings(id: number) {
  return useQuery({
    queryKey: holdingsKey(id),
    queryFn: async ({ signal }): Promise<Holdings> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/holdings',
        {
          params: { path: { seasonId: id } },
          signal,
        },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}

// The turn is always passed explicitly so the key is exact.
export function usePrices(id: number, turnNo: number) {
  return useQuery({
    queryKey: pricesKey(id, turnNo),
    queryFn: async ({ signal }): Promise<Map<number, string>> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/prices',
        {
          params: { path: { seasonId: id }, query: { turnNo } },
          signal,
        },
      );
      if (!Array.isArray(data?.prices)) throw new UnreachableError();
      return new Map(data.prices.map((p) => [p.assetId, p.price]));
    },
  });
}

// No retries: an order must never be sent twice by the client.
// Season state is refreshed here, not in a per-call callback: those are
// skipped once the sheet that called `mutate` has unmounted.
export function usePlaceOrder(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    retry: false,
    mutationFn: async (body: OrderRequest): Promise<OrderResult> => {
      const { data } = await apiClient.POST(
        '/api/v1/seasons/{seasonId}/orders',
        {
          params: { path: { seasonId: id } },
          body,
        },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
    onSuccess: () => {
      void invalidateSeasonState(queryClient, id);
    },
  });
}
