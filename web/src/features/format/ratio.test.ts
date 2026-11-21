import { describe, expect, it } from 'vitest';
import {
  ratioToCssPercent,
  ratioToPercent,
  ratioToPoints,
  ratioWithPercent,
  ratioWithPoints,
} from './ratio.ts';

describe('ratioToPercent', () => {
  it.each([
    ['-0.20000000', '-20.00%'],
    ['-0.40000000', '-40.00%'],
    ['0.07120000', '7.12%'],
    ['0.03100000', '3.10%'],
    ['1.00000000', '100.00%'],
    ['0.00000001', '0.00%'],
    ['0.00000000', '0.00%'],
    ['-0.00000000', '0.00%'],
    ['-0.00000001', '0.00%'],
    ['0.50000001', '50.00%'],
    ['0.00005', '0.01%'],
    ['-0.00005', '-0.01%'],
    ['0.00004', '0.00%'],
    ['0.99999', '100.00%'],
    ['0.5', '50.00%'],
    ['2', '200.00%'],
    ['1234.5678', '123,456.78%'],
  ])('moves %s to %s', (input, expected) => {
    expect(ratioToPercent(input)).toBe(expected);
  });

  it.each(['abc', '', '1e-2', '.5', 'NaN'])('passes %j through', (input) => {
    expect(ratioToPercent(input)).toBe(input);
  });
});

describe('ratioWithPercent', () => {
  it('shows only the rounded percent', () => {
    expect(ratioWithPercent('-0.20000000')).toBe('-20.00%');
  });

  it('passes a malformed value through', () => {
    expect(ratioWithPercent('n/a')).toBe('n/a');
  });
});

describe('ratioToCssPercent', () => {
  it('keeps every digit for a CSS width', () => {
    expect(ratioToCssPercent('0.62000000')).toBe('62.000000%');
    expect(ratioToCssPercent('1.00000000')).toBe('100.000000%');
    expect(ratioToCssPercent('0.00000000')).toBe('0.000000%');
  });

  it('passes a malformed value through', () => {
    expect(ratioToCssPercent('x')).toBe('x');
  });
});

describe('ratioToPoints', () => {
  it('prints percentage points with two decimals', () => {
    expect(ratioToPoints('0.15880000')).toBe('15.88%p');
    expect(ratioToPoints('-0.03100000')).toBe('-3.10%p');
    expect(ratioToPoints('0.00000000')).toBe('0.00%p');
    expect(ratioToPoints('-0.00000001')).toBe('0.00%p');
  });

  it('passes a malformed value through', () => {
    expect(ratioToPoints('abc')).toBe('abc');
  });
});

describe('ratioWithPoints', () => {
  it.each([
    ['0.05120000', '5.12%p'],
    ['-0.02000000', '-2.00%p'],
    ['0.00000000', '0.00%p'],
    ['0.59864000', '59.86%p'],
  ])('shows %s as %s', (input, expected) => {
    expect(ratioWithPoints(input)).toBe(expected);
  });

  it('passes a malformed value through unchanged', () => {
    expect(ratioWithPoints('abc')).toBe('abc');
  });
});
