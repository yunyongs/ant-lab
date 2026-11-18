import { describe, expect, it } from 'vitest';
import {
  CATEGORY_LABEL,
  FEED_KIND_LABEL,
  PHASE_LABEL,
} from './reviewLabels.ts';

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

  it('R1: names every feed kind in the plan words', () => {
    expect(FEED_KIND_LABEL).toEqual({
      RISE: '상승 뉴스',
      FALL: '하락 뉴스',
      ECHO: '재생산',
      SPREAD: '확대',
      FADE: '소모',
      EVENT: '사건 카드',
      NUDGE: '독려 알림',
    });
  });
});
