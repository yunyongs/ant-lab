import type { components } from '../../api/schema';

export type Season = components['schemas']['SeasonDto'];
export type Formation = components['schemas']['FormationDto'];

export function statusLabel(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): string {
  if (season.status === 'FINISHED') return '끝남';
  if (season.status === 'ABANDONING') return '정리 중';
  if (season.status === 'HALTED') return '점검 중';
  return season.currentTurnNo === 0 ? '진행 중 · 팀 구성' : '진행 중';
}

export function turnText(
  season: Pick<Season, 'currentTurnNo' | 'pathLength'>,
): string {
  return `${String(season.currentTurnNo)} / ${String(season.pathLength)}턴`;
}

// Integers only (turns, not money); clamped so a bad pair never overflows the bar.
export function turnProgressWidth(
  season: Pick<Season, 'currentTurnNo' | 'pathLength'>,
): string {
  if (season.pathLength <= 0) return '0%';
  const share = Math.min(
    100,
    Math.max(0, (season.currentTurnNo * 100) / season.pathLength),
  );
  return `${String(share)}%`;
}

export function valueLabel(season: Pick<Season, 'status'>): string {
  return season.status === 'FINISHED' ? '최종 평가액' : '평가액';
}

export function leagueText(season: Pick<Season, 'leagueId'>): string | null {
  return season.leagueId === null ? null : `리그 ${String(season.leagueId)}`;
}

export function headerSeasonLine(season: Season): string {
  return `시즌 ${String(season.id)} · ${String(season.currentTurnNo)}/${String(season.pathLength)}턴 · ${statusLabel(season)}`;
}

// Server fields only: the web never works out "not started" itself.
export function abandonText(
  season: Pick<Season, 'abandonedTurnNo' | 'notStarted'>,
): string | null {
  if (season.abandonedTurnNo === null) return null;
  const base = `${String(season.abandonedTurnNo)}턴에서 포기`;
  return season.notStarted ? `${base} · 미시작` : base;
}

export function formationText(formation: Formation): string {
  return `골키퍼 ${String(formation.goalkeeper)} · 수비수 ${String(formation.defender)} · 미드필더 ${String(formation.midfielder)} · 공격수 ${String(formation.forward)}`;
}

export function openButtonLabel(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): string {
  if (season.status === 'FINISHED') return '결과 보기';
  if (season.status === 'ABANDONING' || season.status === 'HALTED')
    return '시즌 보기';
  return season.currentTurnNo === 0
    ? '팀 구성 이어하기'
    : `${String(season.currentTurnNo + 1)}턴으로 이어하기`;
}

export type Position = components['schemas']['Position'];

export const positionLabel = {
  GOALKEEPER: '골키퍼',
  DEFENDER: '수비수',
  MIDFIELDER: '미드필더',
  FORWARD_TARGET: '공격수·타겟형',
  FORWARD_BREAKOUT: '공격수·돌파형',
  FORWARD_DEFENSIVE: '공격수·수비적',
} as const satisfies Record<Position, string>;

export const POSITIONS = Object.keys(positionLabel) as Position[];
