const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

interface Shifted {
  negative: boolean;
  whole: string;
  fraction: string;
}

// Moves the decimal point two places to the right as text (ratio -> percent).
function shiftTwo(value: string): Shifted | null {
  const match = DECIMAL.exec(value);
  if (match === null) return null;
  const [, sign = '', whole = '', fraction = ''] = match;
  const padded = fraction.padEnd(2, '0');
  const shiftedWhole = `${whole}${padded.slice(0, 2)}`.replace(/^0+(?=\d)/, '');
  return {
    negative: sign === '-',
    whole: shiftedWhole,
    fraction: padded.slice(2),
  };
}

function join(shifted: Shifted, fraction: string): string {
  const isZero = /^0*$/.test(shifted.whole) && /^0*$/.test(fraction);
  const sign = shifted.negative && !isZero ? '-' : '';
  return fraction === ''
    ? `${sign}${shifted.whole}`
    : `${sign}${shifted.whole}.${fraction}`;
}

// "-0.20000000" -> "-20%"; text only, trailing zeros trimmed.
export function ratioToPercent(value: string): string {
  const shifted = shiftTwo(value);
  if (shifted === null) return value;
  return `${join(shifted, shifted.fraction.replace(/0+$/, ''))}%`;
}

// "-0.20000000" -> "-0.20000000 (-20%)".
export function ratioWithPercent(value: string): string {
  const percent = ratioToPercent(value);
  return percent === value ? value : `${value} (${percent})`;
}

// "0.62000000" -> "62.000000%", for a CSS width (no trimming needed).
export function ratioToCssPercent(value: string): string {
  const shifted = shiftTwo(value);
  if (shifted === null) return value;
  return `${join(shifted, shifted.fraction)}%`;
}

// "0.15880000" -> "15.88%p": a gap or headroom in percentage points.
export function ratioToPoints(value: string): string {
  const percent = ratioToPercent(value);
  return percent === value ? value : `${percent.slice(0, -1)}%p`;
}

// "0.05120000" -> "0.05120000 (5.12%p)": the same text trick, labelled as a gap.
export function ratioWithPoints(value: string): string {
  const points = ratioToPoints(value);
  return points === value ? value : `${value} (${points})`;
}
