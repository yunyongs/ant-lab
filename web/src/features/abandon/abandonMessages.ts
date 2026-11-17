import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { UNKNOWN_OUTCOME_TEXT } from '../turn/turnMessages.ts';

export const ABANDON_BUTTON = '시즌 포기';
export const RESUME_BUTTON = '이어서 정리';
export const KEEP_BUTTON = '포기하지 않기';
export const CONFIRM_BUTTON = '포기하고 끝까지 진행';
export const DIALOG_TITLE = '시즌을 포기할까요?';

export const ABANDON_NOT_ACTIVE_TEXT =
  '진행 중인 시즌이 아니어서 포기할 수 없습니다.';
export const RECONCILIATION_TEXT =
  '남은 턴을 정리하던 중 계좌 검산이 맞지 않아 멈췄습니다. 포기 표시와 그때까지 진행한 턴은 남아 있습니다. 이어서 정리해 주세요.';
export const WORKING_TEXT =
  '시즌을 정리하는 중입니다. 남은 턴을 매매 없이 진행합니다.';

export function rulesText(remainingTurns: number): string[] {
  return [
    `남은 ${String(remainingTurns)}턴을 매매 없이 자동으로 진행합니다.`,
    '포기한 시즌도 성적표를 받고 레이팅에 들어갑니다.',
    "3턴이 되기 전에 포기하면 '미시작'으로 기록되어 레이팅에서 빠지고 횟수가 남습니다.",
    '되돌릴 수 없습니다.',
  ];
}

export function progressText(
  currentTurnNo: number,
  pathLength: number,
  remainingTurns: number,
): string {
  return `${String(currentTurnNo)}/${String(pathLength)}턴까지 정리했습니다. 남은 ${String(remainingTurns)}턴을 이어서 진행합니다.`;
}

export function leftoverText(remainingTurns: number): string {
  return `아직 ${String(remainingTurns)}턴이 남았습니다. 이어서 정리해 주세요.`;
}

// Stale-screen refusals go to the page notice instead (see the hook).
export function isStaleCode(error: unknown): boolean {
  return (
    error instanceof ProblemError &&
    (error.code === 'TURN_MISMATCH' || error.code === 'SEASON_NOT_ACTIVE')
  );
}

// The sentence for a failed abandon; null where nothing is shown here.
export function failureText(error: unknown): string | null {
  if (error === null || error === undefined) return null;
  if (!(error instanceof ProblemError)) return UNKNOWN_OUTCOME_TEXT;
  if (error.code === 'UNAUTHENTICATED' || isStaleCode(error)) return null;
  if (error.code === 'RECONCILIATION_FAILED') return RECONCILIATION_TEXT;
  return messageFor(error.code);
}
