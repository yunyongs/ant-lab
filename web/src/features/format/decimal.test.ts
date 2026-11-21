import { describe, expect, it } from 'vitest';
import { formatDecimal, roundDecimalText } from './decimal.ts';

describe('formatDecimal', () => {
  it.each([
    ['1.5', '1.5'],
    ['2.000', '2'],
    ['0.005', '0.01'],
    ['-0.005', '-0.01'],
    ['0.004', '0'],
    ['-0.004', '0'],
    ['0.000000001', '0'],
    ['1.999', '2'],
    ['9.995', '10'],
    ['1234567.126', '1,234,567.13'],
    ['62.85714286', '62.86'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatDecimal(input)).toBe(expected);
  });

  it.each(['abc', '', '1e-9', 'NaN'])('returns %j unchanged', (input) => {
    expect(formatDecimal(input)).toBe(input);
  });
});

describe('roundDecimalText', () => {
  it('rounds half away from zero and drops the sign of zero', () => {
    expect(roundDecimalText('-0.5', 0)).toEqual({
      negative: true,
      whole: '1',
      fraction: '',
    });
    expect(roundDecimalText('-0.4', 0)?.negative).toBe(false);
  });
});
