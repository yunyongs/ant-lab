import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client.ts';
import { UnreachableError } from '../../api/problem.ts';
import type { Projection, ProjectionPhase } from './phases.ts';

// The current team's answer depends on the turn; START and END are stored once
// by the server, so their key carries no turn.
export const projectionKey = (
  id: number,
  phase: ProjectionPhase,
  currentTurnNo: number,
) =>
  [
    'projection',
    id,
    phase,
    phase === 'CURRENT' ? currentTurnNo : null,
  ] as const;

// One engine call can take many seconds, so no automatic retry (the default
// single retry would double the wait); the page offers a retry button.
export function useProjection(
  id: number,
  phase: ProjectionPhase,
  currentTurnNo: number,
  enabled: boolean,
) {
  return useQuery({
    queryKey: projectionKey(id, phase, currentTurnNo),
    staleTime: Infinity,
    retry: false,
    enabled,
    queryFn: async ({ signal }): Promise<Projection> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/projection',
        { params: { path: { seasonId: id }, query: { phase } }, signal },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  });
}
