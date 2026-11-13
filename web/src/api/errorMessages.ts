import type { ErrorCode } from './problem.ts';

export const errorMessages = {
  INVALID_ARGUMENT: '입력값을 다시 확인해 주세요.',
  NOT_FOUND: '찾는 항목이 없습니다.',
  METHOD_NOT_ALLOWED: '지원하지 않는 요청입니다.',
  NOT_ACCEPTABLE: '지원하지 않는 응답 형식입니다.',
  UNSUPPORTED_MEDIA_TYPE: '지원하지 않는 요청 형식입니다.',
  INTERNAL_ERROR: '서버에 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.',
  POOL_NOT_LOADED: '종목 풀이 아직 준비되지 않았습니다.',
  POOL_NOT_JUDGED: '종목 풀의 포지션 판정이 끝나지 않았습니다.',
  PATH_TOO_LONG:
    '이 길이의 시즌은 만들 수 없습니다. 더 짧은 시즌을 골라 주세요.',
  SEASON_LIMIT: '진행 중인 시즌은 3개까지입니다. 하나를 끝낸 뒤 시작해 주세요.',
  TURN_NOT_REACHED: '아직 진행하지 않은 턴입니다.',
  SEASON_NOT_FINISHED: '시즌을 끝내야 성적표를 볼 수 있습니다.',
  SEASON_NOT_ACTIVE: '진행 중인 시즌이 아니어서 주문할 수 없습니다.',
  TURN_MISMATCH: '화면의 턴이 지난 턴입니다. 새로 고친 뒤 다시 시도해 주세요.',
  LEDGER_MISMATCH: '계좌 기록이 맞지 않아 주문을 멈췄습니다.',
  LINEUP_MISMATCH: '보유와 라인업 기록이 맞지 않아 주문을 멈췄습니다.',
  ASSET_NOT_IN_POOL: '이번 턴에 거래할 수 없는 종목입니다.',
  INSUFFICIENT_CASH: '현금이 모자랍니다(대금과 수수료).',
  INSUFFICIENT_HOLDING: '보유 수량보다 많이 팔 수 없습니다.',
  POSITION_CONFLICT: '이미 보유 중인 종목의 포지션과 다릅니다.',
  POSITION_REQUIRED: '새로 사는 종목은 포지션을 정해 주세요.',
  INVALID_FORMATION: '포메이션 기록이 맞지 않아 변경을 멈췄습니다.',
  EVENT_TIME_CONFLICT: '앞선 요청과 시각이 겹칩니다. 다시 시도해 주세요.',
  MEMBER_UNAVAILABLE: '로그인 정보를 확인할 수 없습니다.',
  RECONCILIATION_FAILED:
    '턴을 넘기는 중 계좌 검산이 맞지 않아 아무것도 바꾸지 않았습니다. 지금 턴 그대로입니다.',
  FORBIDDEN: '이 기능을 쓸 권한이 없습니다.',
  EMAIL_TAKEN:
    '이미 가입한 이메일입니다. 로그인하거나 다른 이메일을 써 주세요.',
  UNAUTHENTICATED: '로그인이 끝났습니다. 다시 로그인해 주세요.',
  INVALID_CREDENTIALS:
    '이메일이나 비밀번호가 맞지 않습니다. 다시 확인해 주세요.',
} as const satisfies Record<ErrorCode, string>;

export const UNREACHABLE_TEXT =
  'API에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.';

const UNKNOWN_MESSAGE = '알 수 없는 오류가 생겼습니다.';

export function messageFor(code: ErrorCode | 'UNKNOWN'): string {
  return code === 'UNKNOWN' ? UNKNOWN_MESSAGE : errorMessages[code];
}
