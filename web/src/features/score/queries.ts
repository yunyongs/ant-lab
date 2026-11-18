import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { ratingKey } from '../rating/queries.ts';

export type SeasonScore = components['schemas']['SeasonScoreDto'];

export const scoreKey = (id: number) => ['score', id] as const;

// The first request computes and stores the score on the server (an engine
// call), so a failure is not retried on its own: one more try is the user's
// click. A stored score never changes, so the answer is kept for the session.
export function useScore(id: number, enabled: boolean) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: scoreKey(id),
    staleTime: Infinity,
    retry: false,
    // An errored query is read again only by the retry button.
    retryOnMount: false,
    refetchOnReconnect: false,
    enabled,
    // No abort signal: an unmount must not cancel a request the server is
    // still computing, or the next view would start a second one.
    queryFn: async (): Promise<SeasonScore> => {
      const { data } = await apiClient.GET('/api/v1/seasons/{seasonId}/score', {
        params: { path: { seasonId: id } },
      });
      if (data === undefined) throw new UnreachableError();
      // A new stored score can change the rating; the rating query is only
      // marked stale and is read again when the season list is opened.
      void queryClient.invalidateQueries({ queryKey: ratingKey });
      return data;
    },
  });
}
