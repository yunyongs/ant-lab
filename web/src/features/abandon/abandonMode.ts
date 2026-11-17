import type { Season } from '../seasons/labels.ts';

// A plain status check on a server field; the server still judges the call.
export function abandonMode(
  season: Pick<Season, 'status'>,
): 'start' | 'resume' | null {
  if (season.status === 'IN_PROGRESS') return 'start';
  if (season.status === 'ABANDONING') return 'resume';
  return null;
}
