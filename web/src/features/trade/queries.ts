import { useMutation, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages.ts';
import { apiClient } from '../../api/client.ts';
import { ProblemError, UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { holdingsKey } from '../team/queries.ts';
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

export type ChangeLineupRequest = components['schemas']['ChangeLineupRequest'];
export type LineupChange = components['schemas']['LineupChangeDto'];

// No retries: a position change must never be sent twice by the client.
// Holdings are refreshed here so the card shows the server's position; a
// stale screen reports through the sheet's `onStale`.
export function useChangeLineup(id: number) {
  const queryClient = useQueryClient();
  return useMutation({
    retry: false,
    mutationFn: async (body: ChangeLineupRequest): Promise<LineupChange> => {
      const { data } = await apiClient.POST(
        '/api/v1/seasons/{seasonId}/lineup-changes',
        { params: { path: { seasonId: id } }, body },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: holdingsKey(id) }),
    // Refreshes on failure are fired, not awaited: while the mutation is
    // pending the sheet cannot be closed, and an offline refetch would keep
    // it pending. A stale screen (TURN_MISMATCH, SEASON_NOT_ACTIVE) is
    // refreshed by the page's `onStale`.
    onError: (error) => {
      if (error instanceof ProblemError) {
        if (error.code === 'INSUFFICIENT_HOLDING') {
          // The list the player acted on was stale.
          void queryClient.invalidateQueries({ queryKey: holdingsKey(id) });
        }
        return;
      }
      // No readable answer: the server may have committed the change.
      void invalidateSeasonState(queryClient, id);
    },
  });
}
