import type { Season } from './labels.ts';

// Pure checks on server fields only; the server still judges every request.
export function canTrade(season: Pick<Season, 'status'>): boolean {
  return season.status === 'IN_PROGRESS';
}

// Tactics change from turn 1 on; turn 0 is team building only.
export function canChangeFormation(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): boolean {
  return canTrade(season) && season.currentTurnNo >= 1;
}
