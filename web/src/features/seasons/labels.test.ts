import { describe, expect, it } from 'vitest';
import { POSITIONS, positionLabel } from './labels.ts';

describe('positionLabel', () => {
  it('has a distinct Korean label for each of the six positions', () => {
    expect(POSITIONS).toHaveLength(6);
    const texts = POSITIONS.map((p) => positionLabel[p]);
    expect(new Set(texts).size).toBe(6);
    expect(texts).toContain('공격수·타겟형');
    expect(texts).toContain('공격수·돌파형');
    expect(texts).toContain('공격수·수비적');
  });
});
