import { formatDecimal } from '../format/decimal.ts';

// At most two decimals, trailing zeros trimmed (rounded on the text).
export function strengthText(strength: string): string {
  return `강도 ${formatDecimal(strength)}`;
}
