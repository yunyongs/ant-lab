const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

interface Parsed {
  negative: boolean;
  whole: string;
  fraction: string;
}

function parse(value: string): Parsed | null {
  const match = DECIMAL.exec(value);
  if (match === null) return null;
  const [, sign = '', whole = '', fraction = ''] = match;
  const trimmedWhole = whole.replace(/^0+(?=\d)/, '');
  const trimmedFraction = fraction.replace(/0+$/, '');
  const isZero = trimmedWhole === '0' && trimmedFraction === '';
  return {
    negative: sign === '-' && !isZero,
    whole: trimmedWhole,
    fraction: trimmedFraction,
  };
}

function compareMagnitude(a: Parsed, b: Parsed): number {
  if (a.whole.length !== b.whole.length) {
    return a.whole.length < b.whole.length ? -1 : 1;
  }
  if (a.whole !== b.whole) return a.whole < b.whole ? -1 : 1;
  const length = Math.max(a.fraction.length, b.fraction.length);
  const left = a.fraction.padEnd(length, '0');
  const right = b.fraction.padEnd(length, '0');
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

// Exact comparison of decimal strings, never through a float. Text that is
// not a plain decimal sorts after every valid value (and equal to each other).
export function compareDecimal(a: string, b: string): number {
  const left = parse(a);
  const right = parse(b);
  if (left === null || right === null) {
    if (left === null && right === null) return 0;
    return left === null ? 1 : -1;
  }
  if (left.negative !== right.negative) return left.negative ? -1 : 1;
  const magnitude = compareMagnitude(left, right);
  return left.negative ? -magnitude : magnitude;
}

export function isDecimal(value: string): boolean {
  return DECIMAL.test(value);
}
