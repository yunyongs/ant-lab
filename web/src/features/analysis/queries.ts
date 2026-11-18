import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type Analysis = components['schemas']['SeasonAnalysisDto'];

export const analysisKey = (id: number) => ['analysis', id] as const;

// The finished-season gate is where a block is mounted (only ReportView and
// ReviewView, which render for a finished season); do not mount one elsewhere.
// Shared by the report and the review page: one request per season and
// session. Enabled only for a finished season; its inputs no longer change.
export function useAnalysis(id: number, enabled: boolean) {
  return useQuery({
    queryKey: analysisKey(id),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<Analysis> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/report/analysis',
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
