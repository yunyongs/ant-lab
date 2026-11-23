import { describe, expect, it } from 'vitest';
import { compareDecimal } from './compareDecimal.ts';

describe('compareDecimal', () => {
  it.each([
    ['0.1', '0.2', -1],
    ['0.2', '0.1', 1],
    ['0.10000000', '0.1', 0],
    ['-0.0', '0', 0],
    ['-0.00000001', '0', -1],
    ['-0.5', '-0.25', -1],
    ['-0.25', '-0.5', 1],
    ['-1', '1', -1],
    ['10', '9.99999999', 1],
    ['007', '7', 0],
    ['0.12345678', '0.12345679', -1],
    ['123456789012345678.1', '123456789012345678.2', -1],
    ['9007199254740993', '9007199254740992', 1],
  ])('compares %s with %s as %i', (a, b, expected) => {
    expect(compareDecimal(a, b)).toBe(expected);
  });

  it('sorts text that is not a decimal after every valid value', () => {
    expect(compareDecimal('abc', '5')).toBe(1);
    expect(compareDecimal('5', '')).toBe(-1);
    expect(compareDecimal('', 'x')).toBe(0);
    const sorted = ['x', '2', '-1', '1.5'].sort(compareDecimal);
    expect(sorted).toEqual(['-1', '1.5', '2', 'x']);
  });
});
