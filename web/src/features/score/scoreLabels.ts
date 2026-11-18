import type { components } from '../../api/schema';

type Items = components['schemas']['SeasonScoreItemsDto'];
type RatingExclusion = components['schemas']['RatingExclusion'];

// Order of PLAN §6.3.
export const ITEM_ORDER = [
  'risk',
  'discipline',
  'diversification',
  'turnover',
  'consistency',
  'formation',
  'role',
  'noise',
  'nudge',
] as const satisfies readonly (keyof Items)[];

export const ITEM_LABEL = {
  risk: '위험 준수',
  discipline: '매매 규율',
  diversification: '분산',
  turnover: '회전율',
  consistency: '일관성',
  formation: '포메이션 준수',
  role: '역할 수행',
  noise: '소음 반응',
  nudge: '독려 반응',
} as const satisfies Record<keyof Items, string>;

export const EXCLUSION_TEXT = {
  NOT_STARTED: '3턴 전에 포기해 레이팅에서 빠집니다(미시작).',
  NO_SCORE: '경로 분포를 만들 수 없어 레이팅에서 빠집니다.',
} as const satisfies Record<RatingExclusion, string>;
