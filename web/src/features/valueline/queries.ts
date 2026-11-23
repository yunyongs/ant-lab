import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type TurnSummary = components['schemas']['TurnSummaryDto'];

// Played turns never change, so the list is fixed for a given current turn;
// a turn advance moves to a new key instead of invalidating this one.
export const turnSummariesKey = (id: number, currentTurnNo: number) =>
  ['turnSummaries', id, currentTurnNo] as const;

export function useTurnSummaries(
  id: number,
  currentTurnNo: number,
  enabled: boolean,
) {
  return useQuery({
    queryKey: turnSummariesKey(id, currentTurnNo),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<TurnSummary[]> => {
      const { data } = await apiClient.GET('/api/v1/seasons/{seasonId}/turns', {
        params: { path: { seasonId: id } },
        signal,
      });
      if (data === undefined) throw new UnreachableError();
      return data.items;
    },
  });
}
