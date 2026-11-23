import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
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

export const ADVANCE_GUARD_MS = 800;

// When the last advance of a season succeeded, per client. Kept outside the
// button because the next turn's button is a new instance under the same finger.
const advancedAt = new WeakMap<QueryClient, Map<number, number>>();

export function advanceGuardLeft(client: QueryClient, id: number): number {
  const at = advancedAt.get(client)?.get(id);
  if (at === undefined) return 0;
  return Math.max(0, at + ADVANCE_GUARD_MS - Date.now());
}

function markAdvanced(client: QueryClient, id: number) {
  let byId = advancedAt.get(client);
  if (byId === undefined) {
    byId = new Map();
    advancedAt.set(client, byId);
  }
  byId.set(id, Date.now());
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
    onSuccess: async (body) => {
      // Stamped before the page switches to the new turn and again after the
      // refetch, so the guard covers the new turn's first 800 ms on screen.
      markAdvanced(queryClient, id);
      queryClient.setQueryData(turnKey(id, body.turnNo), body);
      onAdvanced?.(body.turnNo);
      await invalidateSeasonState(queryClient, id);
      markAdvanced(queryClient, id);
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
