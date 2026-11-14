import type { Season } from '../seasons/labels.ts';

export function advanceLabel(
  season: Pick<Season, 'currentTurnNo' | 'pathLength'>,
): string {
  const next = season.currentTurnNo + 1;
  return next >= season.pathLength
    ? `마지막 턴 넘기기 (${String(season.pathLength)}턴, 시즌 끝)`
    : `다음 턴 (${String(next)}턴으로)`;
}
