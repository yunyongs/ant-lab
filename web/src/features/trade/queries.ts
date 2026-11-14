import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages.ts';
import { apiClient } from '../../api/client.ts';
import { ProblemError, UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { FORMATION_NOT_ACTIVE_TEXT } from './tradeMessages.ts';

export type ChangeFormationRequest =
  components['schemas']['ChangeFormationRequest'];
export type FormationChange = components['schemas']['FormationChangeDto'];

// No retries: a formation change must never be sent twice by the client.
// Season state is refreshed here, not in a per-call callback: those are
// skipped once the form that called `mutate` has unmounted. A stale screen
// reports through the page's notice, which outlives the form.
export function useChangeFormation(
  id: number,
  onNotice?: (text: string | null) => void,
) {
  const queryClient = useQueryClient();
  return useMutation({
    retry: false,
    mutationFn: async (
      body: ChangeFormationRequest,
    ): Promise<FormationChange> => {
      const { data } = await apiClient.POST(
        '/api/v1/seasons/{seasonId}/formation-changes',
        { params: { path: { seasonId: id } }, body },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
    // Returning the promise keeps the mutation pending until the season has
    // been refetched, so the result card already shows the new formation.
    onSuccess: () => invalidateSeasonState(queryClient, id),
    onError: (error) => {
      if (error instanceof ProblemError) {
        if (error.code === 'TURN_MISMATCH') {
          onNotice?.(messageFor(error.code));
        } else if (error.code === 'SEASON_NOT_ACTIVE') {
          onNotice?.(FORMATION_NOT_ACTIVE_TEXT);
        } else {
          // The server answered: a definite failure, nothing to refetch.
          return undefined;
        }
        return invalidateSeasonState(queryClient, id);
      }
      // No readable answer: the server may have committed the change. The
      // form says so itself.
      return invalidateSeasonState(queryClient, id);
    },
  });
}
