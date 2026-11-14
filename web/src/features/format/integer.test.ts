import { describe, expect, it } from 'vitest';
import { parseWholeNumber } from './integer.ts';

describe('parseWholeNumber', () => {
  it.each([
    ['0', 0],
    ['7', 7],
    ['100', 100],
  ])('accepts %j', (text, expected) => {
    expect(parseWholeNumber(text, 100)).toBe(expected);
  });

  it.each(['', ' 7', '07', '-1', '1.5', '1e2', '101', '7 ', 'abc'])(
    'rejects %j',
    (text) => {
      expect(parseWholeNumber(text, 100)).toBeNull();
    },
  );
});
