import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type RankingBoard = components['schemas']['RankingBoardDto'];

export const rankingKey = (id: number, turnNo: number) =>
  ['ranking', id, turnNo] as const;

// A played turn's board never changes, so it is fetched once. `enabled` keeps
// the request inside 1..current turn: the board of a later turn must not be
// asked for.
export function useRanking(id: number, turnNo: number, enabled: boolean) {
  return useQuery({
    queryKey: rankingKey(id, turnNo),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<RankingBoard> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/turns/{turnNo}/ranking',
        { params: { path: { seasonId: id, turnNo } }, signal },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}
