import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { isNotFinishedProblem } from '../seasons/notFinished.ts';
import { seasonKey } from '../seasons/queries.ts';
import { useAnalysis } from './queries.ts';

// A 409 means the cached season said FINISHED too early: refetch the season
// and show nothing here (the page's own gate then takes over).
export function useAnalysisState(seasonId: number) {
  const queryClient = useQueryClient();
  const analysis = useAnalysis(seasonId, true);
  const stale = isNotFinishedProblem(analysis.error);
  useEffect(() => {
    if (stale) {
      void queryClient.invalidateQueries({ queryKey: seasonKey(seasonId) });
    }
  }, [stale, seasonId, queryClient]);
  return { analysis, stale };
}
