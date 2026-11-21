import { groupThousands, roundDecimalText } from './decimal.ts';

// Presentation only: the server's decimal string is rounded to whole won
// (half away from zero) on the text itself, then gets thousands separators and
// the won sign. Nothing is parsed into a number.
export function formatWon(value: string): string {
  const rounded = roundDecimalText(value, 0);
  if (rounded === null) return value;
  const sign = rounded.negative ? '-' : '';
  return `${sign}${groupThousands(rounded.whole)}원`;
}
