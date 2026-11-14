import { describe, expect, it } from 'vitest';
import {
  checkFormation,
  sameFormation,
  type ShareTexts,
} from './formationCheck.ts';

const base: ShareTexts = {
  goalkeeper: '25',
  defender: '25',
  midfielder: '25',
  forward: '25',
};

describe('checkFormation', () => {
  it('accepts a valid formation and returns integers', () => {
    expect(
      checkFormation({
        goalkeeper: '40',
        defender: '30',
        midfielder: '20',
        forward: '10',
      }),
    ).toEqual({
      formation: { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 },
      sum: 100,
      problem: null,
      invalid: [],
    });
  });

  it('rejects a sum other than 100', () => {
    const result = checkFormation({ ...base, forward: '22' });
    expect(result.problem).toBe('sum');
    expect(result.sum).toBe(97);
  });

  it('rejects a goalkeeper of 0 even when the sum is 100', () => {
    const result = checkFormation({
      goalkeeper: '0',
      defender: '40',
      midfielder: '30',
      forward: '30',
    });
    expect(result.problem).toBe('goalkeeper');
    expect(result.invalid).toEqual(['goalkeeper']);
  });

  it.each(['1.5', '', '101', '-1', '1e1'])(
    'rejects %j as not a whole number 0–100',
    (text) => {
      const result = checkFormation({ ...base, defender: text });
      expect(result.problem).toBe('whole');
      expect(result.invalid).toEqual(['defender']);
      expect(result.sum).toBeNull();
    },
  );
});

describe('sameFormation', () => {
  const now = { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 };

  it('is true only when all four shares match', () => {
    expect(sameFormation(now, { ...now })).toBe(true);
    expect(sameFormation(now, { ...now, forward: 11 })).toBe(false);
    expect(sameFormation(now, { ...now, goalkeeper: 39 })).toBe(false);
  });
});
