import type { components } from '../../api/schema';

type AxisDirection = components['schemas']['AxisDirection'];

export const DIRECTION_LABELS: Record<AxisDirection, string> = {
  UP: '상승',
  DOWN: '하락',
  NEUTRAL: '중립',
};

export const AXIS_ROWS = [
  { key: 'rate', label: '금리' },
  { key: 'growth', label: '성장' },
  { key: 'fx', label: '환율' },
  { key: 'sentiment', label: '심리' },
] as const;

export const REGIME_ROWS = [
  { key: 'p1', label: '금리 하락 · 성장 둔화' },
  { key: 'p2', label: '금리 하락 · 성장 확장' },
  { key: 'p3', label: '금리 상승 · 성장 확장' },
  { key: 'p4', label: '금리 상승 · 성장 둔화' },
] as const;

export const NO_AXIS_TEXT = '자료 없음';
export const NO_MACRO_TEXT = '이번 턴의 거시 환경 자료가 없습니다';

// An unknown direction has no word, so the caller shows no data.
export function directionWord(direction: string): string | undefined {
  return Object.hasOwn(DIRECTION_LABELS, direction)
    ? DIRECTION_LABELS[direction as AxisDirection]
    : undefined;
}
