import { describe, expect, it } from 'vitest';
import {
  addDecimal,
  decimalSign,
  floorToManWon,
  percentOf,
  positionGap,
  quantityShare,
  shareWidth,
  subtractDecimal,
  weightAfterOrder,
} from './derive.ts';
import { formatWon } from './money.ts';

describe('addDecimal and subtractDecimal', () => {
  it.each([
    ['100000000', '18451663.4', '118451663.4'],
    ['0.1', '0.2', '0.3'],
    ['-5', '5', '0'],
    ['-1234567890', '-1', '-1234567891'],
    ['123456789012345678901234567890', '1', '123456789012345678901234567891'],
  ])('adds %s and %s', (a, b, expected) => {
    expect(addDecimal(a, b)).toBe(expected);
  });

  it.each([
    ['118451663', '100000000', '18451663'],
    ['99999999', '100000000', '-1'],
    ['0', '0', '0'],
    ['-999999999', '1', '-1000000000'],
    ['1.50', '1.25', '0.25'],
  ])('subtracts %s - %s', (a, b, expected) => {
    expect(subtractDecimal(a, b)).toBe(expected);
  });

  it('returns null for malformed input', () => {
    expect(addDecimal('abc', '1')).toBeNull();
    expect(subtractDecimal('1', '')).toBeNull();
    expect(subtractDecimal('1e5', '1')).toBeNull();
    expect(subtractDecimal('1.', '1')).toBeNull();
  });

  it('feeds the won formatter with one rounding step', () => {
    const gain = subtractDecimal('118451663.5', '100000000');
    expect(gain).toBe('18451663.5');
    expect(formatWon(gain ?? '')).toBe('18,451,664원');
    const loss = subtractDecimal('99999999.5', '100000000');
    expect(formatWon(loss ?? '')).toBe('-1원');
  });
});

describe('decimalSign', () => {
  it.each([
    ['5', 1],
    ['-5', -1],
    ['0', 0],
    ['-0.000', 0],
    ['0.001', 1],
  ])('signs %s as %s', (input, expected) => {
    expect(decimalSign(input)).toBe(expected);
  });

  it('is null when malformed', () => {
    expect(decimalSign('x')).toBeNull();
  });
});

describe('percentOf', () => {
  it.each([
    ['18451663', '100000000', '18.45%'],
    ['-1', '100000000', '0.00%'],
    ['1', '100000000', '0.00%'],
    ['-500000', '100000000', '-0.50%'],
    ['5', '10000', '0.05%'],
    ['-5', '10000', '-0.05%'],
    ['4', '100000', '0.00%'],
    ['-5', '100000', '-0.01%'],
    ['1', '3', '33.33%'],
    ['2', '3', '66.67%'],
    ['-2', '3', '-66.67%'],
    ['100', '100', '100.00%'],
    ['12345678', '1', '1,234,567,800.00%'],
    ['0', '100', '0.00%'],
    ['1.5', '3', '50.00%'],
    ['-999999999', '1000000000', '-100.00%'],
    ['-900000000', '100000000', '-900.00%'],
  ])('shares %s of %s as %s', (numerator, denominator, expected) => {
    expect(percentOf(numerator, denominator)).toBe(expected);
  });

  it('keeps a negative denominator sign consistent', () => {
    expect(percentOf('1', '-4')).toBe('-25.00%');
    expect(percentOf('-1', '-4')).toBe('25.00%');
  });

  it('rounds -1 won against a 9-digit base to 0.00% without a minus sign', () => {
    expect(percentOf('-1', '123456789')).toBe('0.00%');
  });

  it('is null for a zero total or malformed input', () => {
    expect(percentOf('5', '0')).toBeNull();
    expect(percentOf('5', '0.000')).toBeNull();
    expect(percentOf('x', '10')).toBeNull();
    expect(percentOf('5', '')).toBeNull();
  });
});

describe('shareWidth', () => {
  it.each([
    ['1', '4', '25.00%'],
    ['-1', '4', '0.00%'],
    ['5', '4', '100.00%'],
    ['0', '4', '0.00%'],
    ['1', '3', '33.33%'],
    ['16', '20', '80.00%'],
  ])('clamps %s of %s to %s', (numerator, denominator, expected) => {
    expect(shareWidth(numerator, denominator)).toBe(expected);
  });

  it('is null for a zero denominator', () => {
    expect(shareWidth('1', '0')).toBeNull();
  });
});

describe('quantityShare', () => {
  it.each([
    ['0', 10, '0'],
    ['0', 100, '0'],
    ['1', 10, '0'],
    ['1', 100, '1'],
    ['1', 50, '0'],
    ['59', 10, '5'],
    ['59', 25, '14'],
    ['59', 50, '29'],
    ['59', 100, '59'],
    ['9007199254740993', 50, '4503599627370496'],
  ])('takes %s x %s%% as %s', (quantity, percent, expected) => {
    expect(quantityShare(quantity, percent)).toBe(expected);
  });

  it('rejects fractions, signs and out-of-range shares', () => {
    expect(quantityShare('1.5', 50)).toBeNull();
    expect(quantityShare('-3', 50)).toBeNull();
    expect(quantityShare('10', 101)).toBeNull();
    expect(quantityShare('10', 12.5)).toBeNull();
  });
});

describe('floorToManWon', () => {
  it.each([
    ['25000000', '25000000'],
    ['25009999.99', '25000000'],
    ['9999', '0'],
    ['10000', '10000'],
    ['123456789', '123450000'],
  ])('floors %s to %s', (input, expected) => {
    expect(floorToManWon(input)).toBe(expected);
  });

  it('is null for zero, negative or malformed gaps', () => {
    expect(floorToManWon('0')).toBeNull();
    expect(floorToManWon('-5000')).toBeNull();
    expect(floorToManWon('abc')).toBeNull();
  });
});

describe('positionGap', () => {
  it.each([
    ['100000000', 25, '0', '25000000'],
    ['100000000', 25, '25000000', '0'],
    ['100000000', 25, '30000000', '-5000000'],
    ['100000000', 0, '0', '0'],
    ['100000000.5', 25, '0', '25000000.125'],
    ['0', 25, '0', '0'],
    ['999999999', 100, '1', '999999998'],
  ])(
    'gap of %s at %s%% with %s held is %s',
    (total, target, held, expected) => {
      expect(positionGap(total, target, held)).toBe(expected);
    },
  );

  it('is null for malformed input or an invalid target', () => {
    expect(positionGap('x', 25, '0')).toBeNull();
    expect(positionGap('100', 25.5, '0')).toBeNull();
    expect(positionGap('100', 101, '0')).toBeNull();
  });
});

describe('weightAfterOrder', () => {
  it('adds a buy to the holding and takes the fee off the total', () => {
    expect(weightAfterOrder('0', '500000', '100000000', '1000', 'BUY')).toBe(
      '0.50%',
    );
    expect(
      weightAfterOrder('9500000', '500000', '100000000', '1000', 'BUY'),
    ).toBe('10.00%');
  });

  it('takes a sell off the holding', () => {
    expect(
      weightAfterOrder('10000000', '2500000', '100000000', '1000', 'SELL'),
    ).toBe('7.50%');
  });

  it('shows an oversold position as negative instead of clamping', () => {
    expect(weightAfterOrder('100', '300000', '100000000', '0', 'SELL')).toBe(
      '-0.30%',
    );
  });

  it('is null for a zero total or malformed input', () => {
    expect(weightAfterOrder('0', '100', '1000', '1000', 'BUY')).toBeNull();
    expect(weightAfterOrder('0', 'x', '1000', '0', 'BUY')).toBeNull();
    expect(weightAfterOrder('0', '-5', '1000', '0', 'SELL')).toBeNull();
  });
});

describe('edge cases from the audit', () => {
  it('shows the fee in the weight after an order', () => {
    expect(
      weightAfterOrder('9500000', '500000', '100000000', '100000', 'BUY'),
    ).toBe('10.01%');
    expect(
      weightAfterOrder('10000000', '500000', '100000000', '100000', 'SELL'),
    ).toBe('9.51%');
  });

  it('keeps notional + fee equal to the cash taken, rounding once', () => {
    const paid = addDecimal('100.49', '0.49');
    const taken = subtractDecimal('1000', '899.02');
    expect(paid).toBe('100.98');
    expect(taken).toBe(paid);
    expect(formatWon(paid!)).toBe(formatWon('101'));
  });

  it.each([
    ['0.0005', '0.1', '0.50%'],
    ['9007199254740993', '200', '4,503,599,627,370,496.50%'],
    ['4.9999', '100000', '0.00%'],
    ['-4.9999', '100000', '0.00%'],
  ])('percentOf(%s, %s) is %s', (numerator, denominator, expected) => {
    expect(percentOf(numerator, denominator)).toBe(expected);
  });
});
