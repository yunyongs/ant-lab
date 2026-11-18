// Texts of the formation change that differ from the shared error catalog.
export const FORMATION_NOT_ACTIVE_TEXT =
  '진행 중인 시즌이 아니어서 전술을 바꿀 수 없습니다.';

export const SAME_FORMATION_TEXT = '지금과 같은 포메이션입니다';
export const REASON_TOO_LONG_TEXT = '사유는 200자까지 적을 수 있습니다';
export const TURN_ZERO_TEXT =
  '전술은 1턴부터 바꿀 수 있습니다. 0턴에는 팀 구성만 합니다.';

// The server counts Unicode characters; so does the browser.
export const REASON_MAX = 200;

export function reasonLength(text: string): number {
  return [...text].length;
}

// Texts of the position-only change that differ from the shared error catalog.
export const POSITION_ALREADY_TEXT = '이미 그 포지션입니다';
export const POSITION_NOT_HELD_TEXT =
  '이 종목은 지금 보유하고 있지 않아 포지션을 바꿀 수 없습니다.';
export const POSITION_RECORD_TEXT =
  '보유와 라인업 기록이 맞지 않아 포지션 변경을 멈췄습니다.';
export const POSITION_NOT_ACTIVE_TEXT =
  '진행 중인 시즌이 아니어서 포지션을 바꿀 수 없습니다.';
