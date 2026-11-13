import type { components } from '../../api/schema';

export type Season = components['schemas']['SeasonDto'];
export type Formation = components['schemas']['FormationDto'];

export function statusLabel(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): string {
  if (season.status === 'FINISHED') return '끝남';
  if (season.status === 'ABANDONING') return '정리 중';
  return season.currentTurnNo === 0 ? '진행 중 · 팀 구성' : '진행 중';
}

export function turnText(
  season: Pick<Season, 'currentTurnNo' | 'pathLength'>,
): string {
  return `${String(season.currentTurnNo)} / ${String(season.pathLength)}턴`;
}

export function headerSeasonLine(season: Season): string {
  return `시즌 ${String(season.id)} · ${String(season.currentTurnNo)}/${String(season.pathLength)}턴 · ${statusLabel(season)}`;
}

export function formationText(formation: Formation): string {
  return `골키퍼 ${String(formation.goalkeeper)} · 수비수 ${String(formation.defender)} · 미드필더 ${String(formation.midfielder)} · 공격수 ${String(formation.forward)}`;
}

export function openButtonLabel(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): string {
  if (season.status === 'FINISHED') return '결과 보기';
  if (season.status === 'ABANDONING') return '시즌 보기';
  return season.currentTurnNo === 0 ? '팀 구성하러 가기' : '이어하기';
}
