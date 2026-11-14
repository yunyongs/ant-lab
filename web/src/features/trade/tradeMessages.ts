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
