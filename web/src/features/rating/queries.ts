import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type Rating = components['schemas']['RatingDto'];

export const ratingKey = ['rating'] as const;

// The rating request can make the server score every unscored finished
// season, which may wait on the engine; so no automatic retry and no refetch
// until something marks it stale (a new score or a season change).
export function useRating(enabled: boolean) {
  return useQuery({
    queryKey: ratingKey,
    staleTime: Infinity,
    retry: false,
    // An errored query is read again only by the retry button.
    retryOnMount: false,
    refetchOnReconnect: false,
    enabled,
    // No abort signal: an unmount must not cancel a request the server is
    // still computing, or the next view would start a second one.
    queryFn: async (): Promise<Rating> => {
      const { data } = await apiClient.GET('/api/v1/members/me/rating');
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}
