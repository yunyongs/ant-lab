import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages.ts';
import { apiClient } from '../../api/client.ts';
import { ProblemError, UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { invalidateSeasonState } from '../seasons/queries.ts';
import {
  ADVANCE_NOT_ACTIVE_TEXT,
  UNKNOWN_OUTCOME_TEXT,
} from './turnMessages.ts';

export type TurnResult = components['schemas']['TurnResultDto'];

export const turnKey = (id: number, turnNo: number) =>
  ['turn', id, turnNo] as const;
export const advanceKey = (id: number) => ['advance', id] as const;

// A stored turn row never changes after its advance, so it is fetched once.
// Shared by `useTurn` and the review's per-turn value lookups (one key).
export function turnQueryOptions(id: number, turnNo: number) {
  return {
    queryKey: turnKey(id, turnNo),
    staleTime: Infinity,
    queryFn: async ({
      signal,
    }: {
      signal: AbortSignal;
    }): Promise<TurnResult> => {
      const { data } = await apiClient.GET(
        '/api/v1/seasons/{seasonId}/turns/{turnNo}',
        { params: { path: { seasonId: id, turnNo } }, signal },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
  };
}

// `enabled` lets a caller skip the request for a turn it knows is out of range.
export function useTurn(id: number, turnNo: number, enabled = true) {
  return useQuery({ ...turnQueryOptions(id, turnNo), enabled });
}

// No retries: a turn advance must never be sent twice by the client.
// Season state is refreshed here, not in a per-call callback: those are
// skipped once the component that called `mutate` has unmounted, and the page
// switches branch when the advance succeeds.
export interface AdvanceCallbacks {
  onAdvanced?: (turnNo: number) => void;
  onNotice?: (text: string | null) => void;
}

export function useAdvanceTurn(id: number, callbacks: AdvanceCallbacks = {}) {
  const queryClient = useQueryClient();
  const { onAdvanced, onNotice } = callbacks;
  return useMutation({
    mutationKey: advanceKey(id),
    retry: false,
    mutationFn: async (expectedTurnNo: number): Promise<TurnResult> => {
      const { data } = await apiClient.POST(
        '/api/v1/seasons/{seasonId}/turns',
        {
          params: { path: { seasonId: id } },
          body: { expectedTurnNo },
        },
      );
      if (data === undefined) throw new UnreachableError();
      return data;
    },
    // Returning the promise keeps the mutation pending until the season has
    // been refetched, so the page switches branch once.
    onSuccess: (body) => {
      queryClient.setQueryData(turnKey(id, body.turnNo), body);
      onAdvanced?.(body.turnNo);
      return invalidateSeasonState(queryClient, id);
    },
    // Page-level notices go through the page's callback: this screen may be
    // gone by the time the refetch has finished.
    onError: (error) => {
      if (error instanceof ProblemError) {
        if (error.code === 'TURN_MISMATCH') {
          onNotice?.(messageFor(error.code));
        } else if (error.code === 'SEASON_NOT_ACTIVE') {
          onNotice?.(ADVANCE_NOT_ACTIVE_TEXT);
        } else {
          return undefined;
        }
        return invalidateSeasonState(queryClient, id);
      }
      // No readable answer: the server may have committed the advance.
      onNotice?.(UNKNOWN_OUTCOME_TEXT);
      return invalidateSeasonState(queryClient, id);
    },
  });
}
