import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';

export type Report = components['schemas']['SeasonReportDto'];

export const reportKey = (id: number) => ['report', id] as const;

// Enabled only for a finished season: an unfinished one never sends the
// request (the server would answer 409 anyway). A finished season's inputs no
// longer change, so the answer is kept for the session.
export function useReport(id: number, enabled: boolean) {
  return useQuery({
    queryKey: reportKey(id),
    staleTime: Infinity,
    enabled,
    queryFn: async ({ signal }): Promise<Report> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/report',
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
