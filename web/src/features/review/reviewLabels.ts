import type { components } from '../../api/schema';

type EventCategory = components['schemas']['EventCategory'];
type EventPhase = components['schemas']['EventPhase'];

// `satisfies Record<…>`: a new enum value after `gen:api` is a compile error.
export const CATEGORY_LABEL = {
  GEOPOLITICS: '지정학',
  TRADE: '무역',
  POLITICS: '정치',
  SUPPLY_SHOCK: '공급 충격',
  FINANCIAL: '금융',
  POLICY: '정책',
  HEALTH: '보건',
} as const satisfies Record<EventCategory, string>;

export const PHASE_LABEL = {
  START: '시작',
  ONGOING: '진행',
  END: '끝',
} as const satisfies Record<EventPhase, string>;
