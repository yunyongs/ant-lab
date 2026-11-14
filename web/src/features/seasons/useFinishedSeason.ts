import { isFinished } from './seasonGate.ts';
import type { Season } from './labels.ts';
import { useSeason } from './queries.ts';

export type FinishedGate =
  | { state: 'loading' }
  | { state: 'error'; error: unknown; retry: () => void }
  | { state: 'pending'; season: Season }
  | { state: 'finished'; season: Season };

// Reads the shared season query (one request with the header line). Callers
// enable their report/review query only on `finished`.
export function useFinishedSeason(id: number | null): FinishedGate {
  const query = useSeason(id);
  if (query.data !== undefined) {
    return isFinished(query.data)
      ? { state: 'finished', season: query.data }
      : { state: 'pending', season: query.data };
  }
  if (query.isError) {
    return {
      state: 'error',
      error: query.error,
      retry: () => {
        void query.refetch();
      },
    };
  }
  return { state: 'loading' };
}
