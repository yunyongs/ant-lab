import { describe, expect, it } from 'vitest';
import { riskGuideWon } from './riskGuide.ts';

describe('riskGuideWon', () => {
  it('is the highest total so far times one plus the limit', () => {
    expect(
      riskGuideWon(['100000000', '104820000', '97000000'], '-0.20000000'),
    ).toBe('83856000');
  });

  it('counts turn 0 (the initial capital) as a peak', () => {
    expect(riskGuideWon(['100000000', '90000000'], '-0.10000000')).toBe(
      '90000000',
    );
  });

  it('rounds half away from zero to whole won', () => {
    expect(riskGuideWon(['5'], '-0.10000000')).toBe('5');
    expect(riskGuideWon(['100000001'], '-0.20000000')).toBe('80000001');
    expect(riskGuideWon(['100000003'], '-0.30000000')).toBe('70000002');
  });

  it('stays exact above 2^53', () => {
    expect(riskGuideWon(['90071992547409930'], '-0.10000000')).toBe(
      '81064793292668937',
    );
  });

  it('reads a limit of zero and shorter fractions', () => {
    expect(riskGuideWon(['100'], '0.00000000')).toBe('100');
    expect(riskGuideWon(['100'], '-0.4')).toBe('60');
  });

  it.each([
    [['100'], '-1.00000000'],
    [['100'], '0.20000000'],
    [['100'], '-0.123456789'],
    [['100'], 'x'],
    [['100.5'], '-0.20000000'],
    [['-100'], '-0.20000000'],
    [[], '-0.20000000'],
  ])('returns null for %j with %s', (totals, limit) => {
    expect(riskGuideWon(totals, limit)).toBeNull();
  });
});
