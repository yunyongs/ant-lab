import { describe, expect, it } from 'vitest';
import { formatWon } from './money.ts';

describe('formatWon', () => {
  it.each([
    ['0', '0원'],
    ['999', '999원'],
    ['1000', '1,000원'],
    ['62300000', '62,300,000원'],
    ['-1234.5000', '-1,234.5000원'],
    ['123456789012.5', '123,456,789,012.5원'],
    ['100000000', '100,000,000원'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatWon(input)).toBe(expected);
  });

  it.each(['abc', '', '1e5', '1,000', ' 5', '-', '1.'])(
    'returns %j unchanged',
    (input) => {
      expect(formatWon(input)).toBe(input);
    },
  );
});
