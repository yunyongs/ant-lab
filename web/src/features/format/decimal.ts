const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

export interface RoundedDecimal {
  negative: boolean;
  whole: string;
  fraction: string;
}

// Display rounding on the decimal text, never on a float: half away from zero.
// A result that rounds to zero loses its minus sign.
export function roundDecimalText(
  value: string,
  places: number,
): RoundedDecimal | null {
  const match = DECIMAL.exec(value);
  if (match === null) return null;
  const [, sign = '', whole = '', fraction = ''] = match;
  const padded = fraction.padEnd(places, '0');
  let digits = `${whole}${padded.slice(0, places)}`;
  if (padded.charCodeAt(places) >= 53) digits = increment(digits);
  digits = digits.padStart(places + 1, '0');
  const cut = digits.length - places;
  const roundedWhole = digits.slice(0, cut).replace(/^0+(?=\d)/, '');
  const roundedFraction = digits.slice(cut);
  const isZero = /^0*$/.test(roundedWhole) && /^0*$/.test(roundedFraction);
  return {
    negative: sign === '-' && !isZero,
    whole: roundedWhole,
    fraction: roundedFraction,
  };
}

const NEXT_DIGIT: Record<string, string> = {
  '0': '1',
  '1': '2',
  '2': '3',
  '3': '4',
  '4': '5',
  '5': '6',
  '6': '7',
  '7': '8',
  '8': '9',
};

// Adds one to a string of digits.
function increment(digits: string): string {
  const chars = digits.split('');
  for (let i = chars.length - 1; i >= 0; i -= 1) {
    if (chars[i] === '9') {
      chars[i] = '0';
    } else {
      chars[i] = NEXT_DIGIT[chars[i] ?? '0'] ?? '0';
      return chars.join('');
    }
  }
  return `1${chars.join('')}`;
}

export function groupThousands(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

// At most two decimals for a multiple, rating or fractional quantity.
// Trailing zeros are trimmed ("1.50" -> "1.5", "2.00" -> "2").
export function formatDecimal(value: string): string {
  const rounded = roundDecimalText(value, 2);
  if (rounded === null) return value;
  const fraction = rounded.fraction.replace(/0+$/, '');
  const sign = rounded.negative ? '-' : '';
  const whole = groupThousands(rounded.whole);
  return fraction === '' ? `${sign}${whole}` : `${sign}${whole}.${fraction}`;
}

export function formatDecimalFixed(value: string, places: number): string {
  const rounded = roundDecimalText(value, places);
  if (rounded === null) return value;
  const sign = rounded.negative ? '-' : '';
  return `${sign}${groupThousands(rounded.whole)}.${rounded.fraction}`;
}
