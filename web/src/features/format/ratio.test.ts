import { describe, expect, it } from 'vitest';
import {
  ratioToCssPercent,
  ratioToPercent,
  ratioToPoints,
  ratioWithPercent,
} from './ratio.ts';

describe('ratioToPercent', () => {
  it.each([
    ['-0.20000000', '-20%'],
    ['-0.40000000', '-40%'],
    ['0.07120000', '7.12%'],
    ['1.00000000', '100%'],
    ['0.00000001', '0.000001%'],
    ['0.00000000', '0%'],
    ['-0.00000000', '0%'],
    ['0.50000001', '50.000001%'],
    ['0.5', '50%'],
    ['2', '200%'],
  ])('moves %s to %s', (input, expected) => {
    expect(ratioToPercent(input)).toBe(expected);
  });

  it.each(['abc', '', '1e-2', '.5'])('passes %j through', (input) => {
    expect(ratioToPercent(input)).toBe(input);
  });
});

describe('ratioWithPercent', () => {
  it('keeps the server string and adds the percent', () => {
    expect(ratioWithPercent('-0.20000000')).toBe('-0.20000000 (-20%)');
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
  it('prints percentage points', () => {
    expect(ratioToPoints('0.15880000')).toBe('15.88%p');
    expect(ratioToPoints('-0.03100000')).toBe('-3.1%p');
    expect(ratioToPoints('0.00000000')).toBe('0%p');
    expect(ratioToPoints('-0.00000001')).toBe('-0.000001%p');
  });

  it('passes a malformed value through', () => {
    expect(ratioToPoints('abc')).toBe('abc');
  });
});
