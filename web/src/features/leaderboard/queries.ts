import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type Leaderboard = components['schemas']['ManagerLeaderboardDto'];
export type LeaderboardKind = components['schemas']['LeaderboardKind'];

export const leaderboardKey = (kind: LeaderboardKind) =>
  ['leaderboard', 'manager', kind] as const;

export function useManagerLeaderboard(kind: LeaderboardKind) {
  return useQuery({
    queryKey: leaderboardKey(kind),
    queryFn: async ({ signal }): Promise<Leaderboard> => {
      const { data } = await apiClient.GET('/api/v1/leaderboards/manager', {
        params: { query: { kind } },
        signal,
      });
      if (data === undefined) throw new UnreachableError();
      return data;
    },
    placeholderData: keepPreviousData,
  });
}
