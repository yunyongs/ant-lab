import { describe, expect, it } from 'vitest';
import { signedChange } from './signedChange.ts';

describe('signedChange', () => {
  it.each([
    ['0', 'won', '0원', 'flat'],
    ['0.0000', 'won', '0원', 'flat'],
    ['-0.4', 'won', '0원', 'flat'],
    ['1234.5', 'won', '+1,235원', 'up'],
    ['-1234.5', 'won', '-1,235원', 'down'],
    ['62300000', 'won', '+62,300,000원', 'up'],
    ['0', 'percent', '0.00%', 'flat'],
    ['-0.00000001', 'percent', '0.00%', 'flat'],
    ['0.00000001', 'percent', '0.00%', 'flat'],
    ['0.0123', 'percent', '+1.23%', 'up'],
    ['-0.2', 'percent', '-20.00%', 'down'],
    ['0.1588', 'points', '+15.88%p', 'up'],
    ['-0.0001', 'points', '-0.01%p', 'down'],
    ['-0.00000001', 'points', '0.00%p', 'flat'],
  ] as const)('%s as %s', (value, kind, text, direction) => {
    expect(signedChange(value, kind)).toEqual({ text, direction });
  });

  it('passes an unreadable value through without a sign', () => {
    expect(signedChange('n/a', 'percent')).toEqual({
      text: 'n/a',
      direction: 'flat',
    });
  });
});
