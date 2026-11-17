import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { apiClient } from '../../api/client.ts';
import { ProblemError, UnreachableError } from '../../api/problem.ts';
import type { components } from '../../api/schema';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { ABANDON_NOT_ACTIVE_TEXT, isStaleCode } from './abandonMessages.ts';

export type AbandonResult = components['schemas']['AbandonResultDto'];
export type AbandonSend = (expectedTurnNo: number) => Promise<AbandonResult>;

export const abandonKey = (id: number) => ['abandon', id] as const;

// Repeat only after a definite 200 that says ABANDONING and moved at least one
// turn, and never more than pathLength times. A thrown error ends the loop:
// after an unknown outcome nothing is sent again by itself.
export async function runAbandon(
  send: AbandonSend,
  expectedTurnNo: number,
  onProgress: (result: AbandonResult) => void,
): Promise<AbandonResult> {
  let result = await send(expectedTurnNo);
  let requests = 1;
  while (
    result.status === 'ABANDONING' &&
    result.advancedTurns > 0 &&
    requests < result.pathLength
  ) {
    onProgress(result);
    result = await send(result.currentTurnNo);
    requests += 1;
  }
  return result;
}

function sender(seasonId: number): AbandonSend {
  return async (expectedTurnNo) => {
    const { data } = await apiClient.POST(
      '/api/v1/seasons/{seasonId}/abandon',
      {
        params: { path: { seasonId } },
        body: { expectedTurnNo },
      },
    );
    if (data === undefined) throw new UnreachableError();
    return data;
  };
}

export interface AbandonCallbacks {
  onNotice?: (text: string | null) => void;
  // The season finished; the argument is its last turn.
  onFinished?: (turnNo: number) => void;
}

// No retries. Season state is refreshed here, not in a per-call callback:
// those are skipped once the caller has unmounted, and the page switches
// branch when the abandon ends.
export function useAbandonSeason(id: number, callbacks: AbandonCallbacks = {}) {
  const queryClient = useQueryClient();
  const { onNotice, onFinished } = callbacks;
  const [progress, setProgress] = useState<AbandonResult | null>(null);
  const mutation = useMutation({
    mutationKey: abandonKey(id),
    retry: false,
    mutationFn: (expectedTurnNo: number): Promise<AbandonResult> => {
      setProgress(null);
      return runAbandon(sender(id), expectedTurnNo, setProgress);
    },
    onSuccess: (result) => {
      if (result.status === 'FINISHED') onFinished?.(result.currentTurnNo);
      return invalidateSeasonState(queryClient, id);
    },
    onError: (error) => {
      if (isStaleCode(error) && error instanceof ProblemError) {
        onNotice?.(
          error.code === 'TURN_MISMATCH'
            ? messageFor(error.code)
            : ABANDON_NOT_ACTIVE_TEXT,
        );
      }
      // Whatever the failure, some turns may have run: read the state again.
      return invalidateSeasonState(queryClient, id);
    },
  });
  return { mutation, progress };
}
