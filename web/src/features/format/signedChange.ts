import { formatWon } from './money.ts';
import { ratioToPercent, ratioToPoints } from './ratio.ts';

export type Direction = 'up' | 'down' | 'flat';
export type ChangeKind = 'won' | 'percent' | 'points';

export interface SignedText {
  text: string;
  direction: Direction;
}

const FORMATTERS: Record<ChangeKind, (value: string) => string> = {
  won: formatWon,
  percent: ratioToPercent,
  points: ratioToPoints,
};

// The sign and direction come from the text that is displayed, so a value that
// rounds to zero ("-0.00000001" -> "0.00%") is flat and unsigned.
export function signedChange(value: string, kind: ChangeKind): SignedText {
  const formatted = FORMATTERS[kind](value);
  if (formatted === value || !/[1-9]/.test(formatted)) {
    return { text: formatted, direction: 'flat' };
  }
  if (formatted.startsWith('-')) return { text: formatted, direction: 'down' };
  return { text: `+${formatted}`, direction: 'up' };
}
