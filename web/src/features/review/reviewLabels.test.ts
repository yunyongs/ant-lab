import { describe, expect, it } from 'vitest';
import { CATEGORY_LABEL, PHASE_LABEL } from './reviewLabels.ts';

describe('review labels', () => {
  it('names every event category and phase in Korean', () => {
    expect(Object.keys(CATEGORY_LABEL).sort()).toEqual(
      [
        'FINANCIAL',
        'GEOPOLITICS',
        'HEALTH',
        'POLICY',
        'POLITICS',
        'SUPPLY_SHOCK',
        'TRADE',
      ].sort(),
    );
    expect(Object.values(CATEGORY_LABEL)).toEqual([
      '지정학',
      '무역',
      '정치',
      '공급 충격',
      '금융',
      '정책',
      '보건',
    ]);
    expect(PHASE_LABEL).toEqual({ START: '시작', ONGOING: '진행', END: '끝' });
  });
});
