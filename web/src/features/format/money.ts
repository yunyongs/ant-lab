const DECIMAL = /^(-?)(\d+)(\.\d+)?$/;

// Presentation only: the server's decimal string gets thousands separators and
// the won sign. Nothing is parsed into a number, added or rounded.
export function formatWon(value: string): string {
  const match = DECIMAL.exec(value);
  if (match === null) return value;
  const [, sign = '', whole = '', fraction = ''] = match;
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${grouped}${fraction}원`;
}
