import { useQueries, useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { turnQueryOptions, type TurnResult } from '../turn/queries.ts';

export type Review = components['schemas']['SeasonReviewDto'];

export const reviewKey = (id: number) => ['review', id] as const;

// Enabled only for a finished season: an unfinished one never sends the
// request. A finished season no longer changes, so the answer is kept.
export function useReview(id: number, enabled: boolean) {
  return useQuery({
    queryKey: reviewKey(id),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<Review> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/review',
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

export type TurnValue =
  | { state: 'loading' }
  | { state: 'error' }
  | { state: 'ready'; turn: TurnResult };

export interface TurnValues {
  // Index 0 is turn 1.
  values: TurnValue[];
  failedCount: number;
  retryFailed: () => void;
}

// One request per played turn (1..lastTurnNo) through the shared turn key:
// turns already cached are not fetched again, and each is fetched once.
export function useTurnValues(
  id: number,
  lastTurnNo: number,
  enabled: boolean,
): TurnValues {
  const queries = useQueries({
    queries: Array.from({ length: Math.max(lastTurnNo, 0) }, (_, index) => ({
      ...turnQueryOptions(id, index + 1),
      enabled,
    })),
  });
  const values: TurnValue[] = queries.map((query) => {
    if (query.data !== undefined) return { state: 'ready', turn: query.data };
    if (query.isError) return { state: 'error' };
    return { state: 'loading' };
  });
  const failed = queries.filter((query) => query.isError);
  return {
    values,
    failedCount: failed.length,
    retryFailed: () => {
      for (const query of failed) void query.refetch();
    },
  };
}
