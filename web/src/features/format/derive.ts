import { groupThousands } from './decimal.ts';

// Exact arithmetic on the API's decimal strings. Every value is read into a
// BigInt with a decimal scale, combined without loss, and rounded once, at the
// end, half away from zero. Malformed input gives null so a screen can hide the
// element instead of showing a guessed number. Nothing here is sent back.

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;
const WHOLE = /^\d+$/;
const TEN = 10n;
const MAN_WON = 10_000n;

interface Scaled {
  // value = units / 10^scale
  units: bigint;
  scale: number;
}

function parse(text: string): Scaled | null {
  const match = DECIMAL.exec(text);
  if (match === null) return null;
  const [, sign = '', whole = '', fraction = ''] = match;
  const units = BigInt(`${whole}${fraction}`);
  return { units: sign === '-' ? -units : units, scale: fraction.length };
}

function pow10(exponent: number): bigint {
  return TEN ** BigInt(exponent);
}

function align(a: Scaled, b: Scaled): [bigint, bigint, number] {
  const scale = Math.max(a.scale, b.scale);
  return [
    a.units * pow10(scale - a.scale),
    b.units * pow10(scale - b.scale),
    scale,
  ];
}

function abs(value: bigint): bigint {
  return value < 0n ? -value : value;
}

// units / 10^scale as plain decimal text without trailing fraction zeros.
function toText(units: bigint, scale: number): string {
  const negative = units < 0n;
  const digits = abs(units)
    .toString()
    .padStart(scale + 1, '0');
  const cut = digits.length - scale;
  const whole = digits.slice(0, cut);
  const fraction = digits.slice(cut).replace(/0+$/, '');
  const body = fraction === '' ? whole : `${whole}.${fraction}`;
  return negative ? `-${body}` : body;
}

// Integer division rounding half away from zero (denominator > 0).
function divideRounded(numerator: bigint, denominator: bigint): bigint {
  const quotient = (2n * abs(numerator) + denominator) / (2n * denominator);
  return numerator < 0n ? -quotient : quotient;
}

// Integer division rounding toward negative infinity (denominator > 0).
function divideFloor(numerator: bigint, denominator: bigint): bigint {
  const quotient = numerator / denominator;
  return numerator % denominator < 0n ? quotient - 1n : quotient;
}

export function addDecimal(a: string, b: string): string | null {
  const left = parse(a);
  const right = parse(b);
  if (left === null || right === null) return null;
  const [x, y, scale] = align(left, right);
  return toText(x + y, scale);
}

export function subtractDecimal(a: string, b: string): string | null {
  const left = parse(a);
  const right = parse(b);
  if (left === null || right === null) return null;
  const [x, y, scale] = align(left, right);
  return toText(x - y, scale);
}

// Sign of an exact decimal: -1, 0 or 1; null when malformed.
export function decimalSign(value: string): -1 | 0 | 1 | null {
  const parsed = parse(value);
  if (parsed === null) return null;
  if (parsed.units === 0n) return 0;
  return parsed.units < 0n ? -1 : 1;
}

// numerator / denominator x 100 rounded to `places` decimals, as scaled units
// (percent x 10^places). Null for malformed input or a zero denominator.
function percentUnits(
  numerator: string,
  denominator: string,
  places: number,
): bigint | null {
  const top = parse(numerator);
  const bottom = parse(denominator);
  if (top === null || bottom === null || bottom.units === 0n) return null;
  let scaledTop = top.units * pow10(bottom.scale) * 100n * pow10(places);
  let scaledBottom = bottom.units * pow10(top.scale);
  if (scaledBottom < 0n) {
    scaledTop = -scaledTop;
    scaledBottom = -scaledBottom;
  }
  return divideRounded(scaledTop, scaledBottom);
}

// "12.34%": the exact share, rounded once to two places. A result that rounds
// to zero has no minus sign ("-1 won of 100,000,000" is "0.00%").
export function percentOf(
  numerator: string,
  denominator: string,
): string | null {
  const units = percentUnits(numerator, denominator, 2);
  if (units === null) return null;
  const digits = abs(units).toString().padStart(3, '0');
  const whole = groupThousands(digits.slice(0, -2));
  const sign = units < 0n ? '-' : '';
  return `${sign}${whole}.${digits.slice(-2)}%`;
}

// A bar width: the share clamped to 0..100, as a CSS percent with two
// decimals. A width is not a figure, so the clamp is not a data change.
export function shareWidth(
  numerator: string,
  denominator: string,
): string | null {
  const units = percentUnits(numerator, denominator, 2);
  if (units === null) return null;
  const clamped = units < 0n ? 0n : units > 10_000n ? 10_000n : units;
  const digits = clamped.toString().padStart(3, '0');
  return `${digits.slice(0, -2)}.${digits.slice(-2)}%`;
}

// Quantity for a ratio button: floor(quantity x percent / 100). The quantity
// is the server's whole-share maximum or the held quantity.
export function quantityShare(
  quantity: string,
  percent: number,
): string | null {
  if (!WHOLE.test(quantity) || !Number.isInteger(percent)) return null;
  if (percent < 0 || percent > 100) return null;
  return ((BigInt(quantity) * BigInt(percent)) / 100n).toString();
}

// Exact decimal text floored to a multiple of 10,000 won (for "약 N만 원").
// Negative or malformed input gives null: only a positive gap is advised.
export function floorToManWon(won: string): string | null {
  const parsed = parse(won);
  if (parsed === null || parsed.units <= 0n) return null;
  const whole = divideFloor(parsed.units, pow10(parsed.scale));
  return ((whole / MAN_WON) * MAN_WON).toString();
}

// Gap to a formation target: total x target% / 100 - position value, exact.
// Positive = buy this much; zero or negative = nothing to buy.
export function positionGap(
  totalValue: string,
  targetPercent: number,
  positionValue: string,
): string | null {
  const total = parse(totalValue);
  const position = parse(positionValue);
  if (total === null || position === null) return null;
  if (!Number.isInteger(targetPercent)) return null;
  if (targetPercent < 0 || targetPercent > 100) return null;
  const target: Scaled = {
    units: total.units * BigInt(targetPercent),
    scale: total.scale + 2,
  };
  const [x, y, scale] = align(target, position);
  return toText(x - y, scale);
}

// Weight of one holding after an order fills, as a 2-place percent. A buy adds
// the notional to the holding, a sell removes it; the fee leaves the total.
// A negative result (selling more than is held) is shown as is, not clamped.
export function weightAfterOrder(
  holdingValue: string,
  notional: string,
  totalValue: string,
  fee: string,
  side: 'BUY' | 'SELL',
): string | null {
  const change = side === 'BUY' ? notional : `-${notional}`;
  const next = addDecimal(holdingValue, change);
  const total = subtractDecimal(totalValue, fee);
  if (next === null || total === null) return null;
  return percentOf(next, total);
}
