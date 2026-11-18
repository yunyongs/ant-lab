import { describe, expect, it } from 'vitest';
import { scoreFixture } from '../../test/fixtures/score.ts';
import { EXCLUSION_TEXT, ITEM_LABEL, ITEM_ORDER } from './scoreLabels.ts';

describe('score labels', () => {
  it('names every item of the DTO exactly once, in PLAN order', () => {
    expect([...ITEM_ORDER].sort()).toEqual(
      Object.keys(scoreFixture().items).sort(),
    );
    expect(ITEM_ORDER).toHaveLength(9);
    expect(ITEM_ORDER.map((key) => ITEM_LABEL[key])).toEqual([
      '위험 준수',
      '매매 규율',
      '분산',
      '회전율',
      '일관성',
      '포메이션 준수',
      '역할 수행',
      '소음 반응',
      '독려 반응',
    ]);
    expect(new Set(Object.values(ITEM_LABEL)).size).toBe(9);
  });

  it('has a sentence for each exclusion reason', () => {
    expect(Object.keys(EXCLUSION_TEXT).sort()).toEqual([
      'NOT_STARTED',
      'NO_SCORE',
    ]);
  });
});
