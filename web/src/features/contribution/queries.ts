import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type TurnContribution = components['schemas']['TurnContributionDto'];
export type HoldingContribution =
  components['schemas']['HoldingContributionDto'];
export type PositionContribution =
  components['schemas']['PositionContributionDto'];

export const contributionKey = (id: number, turnNo: number) =>
  ['contribution', id, turnNo] as const;

// A played turn's contribution never changes, so it is fetched once. `enabled`
// keeps the request inside 1..current turn.
export function useTurnContribution(
  id: number,
  turnNo: number,
  enabled: boolean,
) {
  return useQuery({
    queryKey: contributionKey(id, turnNo),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<TurnContribution> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/turns/{turnNo}/contribution',
        { params: { path: { seasonId: id, turnNo } }, signal },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}
