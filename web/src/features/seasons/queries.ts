import { useMutation, useQuery, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import type { Season } from './labels.ts';

export type StartSeasonRequest = components['schemas']['StartSeasonRequest'];

export const seasonsKey = ['seasons'] as const;
export const seasonKey = (id: number | null) => ['season', id] as const;

async function fetchSeasons(signal: AbortSignal): Promise<Season[]> {
  const { data } = await apiClient.GET('/api/v1/seasons', { signal });
  if (!Array.isArray(data?.items)) throw new UnreachableError();
  return data.items;
}

async function fetchSeason(id: number, signal: AbortSignal): Promise<Season> {
  const { data } = await apiClient.GET('/api/v1/seasons/{seasonId}', {
    params: { path: { seasonId: id } },
    signal,
  });
  if (data === undefined) throw new UnreachableError();
  return data;
}

export function useSeasons() {
  return useQuery({
    queryKey: seasonsKey,
    queryFn: ({ signal }) => fetchSeasons(signal),
  });
}

// One query per season: the page and the header line share it (one request).
export function useSeason(id: number | null) {
  return useQuery({
    queryKey: seasonKey(id),
    queryFn: ({ signal }) => fetchSeason(id ?? 0, signal),
    enabled: id !== null,
  });
}

export function useStartSeason() {
  return useMutation({
    mutationFn: async (body: StartSeasonRequest): Promise<Season> => {
      const { data } = await apiClient.POST('/api/v1/seasons', { body });
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}

// Used by later features after a fill, a turn advance or a TURN_MISMATCH.
export async function invalidateSeasonState(
  queryClient: QueryClient,
  id: number,
): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: seasonKey(id) }),
    queryClient.invalidateQueries({ queryKey: ['holdings', id] }),
    queryClient.invalidateQueries({ queryKey: ['prices', id] }),
    queryClient.invalidateQueries({ queryKey: seasonsKey }),
  ]);
}
