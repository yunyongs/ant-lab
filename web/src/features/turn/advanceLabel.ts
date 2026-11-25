import type { Season } from '../seasons/labels.ts';

export function advanceLabel(
  season: Pick<Season, 'currentTurnNo' | 'pathLength'>,
): string {
  const next = season.currentTurnNo + 1;
  if (next >= season.pathLength) {
    return `마지막 턴 넘기기 (${String(season.pathLength)}턴, 시즌 끝)`;
  }
  return season.currentTurnNo === 0
    ? '구성 마치고 1턴 시작'
    : `${String(next)}턴으로 넘기기`;
}
