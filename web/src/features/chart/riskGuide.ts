const WHOLE_WON = /^\d+$/;
const LIMIT = /^-0\.(\d{1,8})$|^-?0(?:\.0{1,8})?$/;
const SCALE = 100_000_000n;

// The value at which the server's drawdown rule (total / peak - 1, peak =
// highest total so far, turn 0 = initial capital) reaches the risk limit:
// peak x (1 + limit), rounded half away from zero to whole won. Exact BigInt
// arithmetic on the server strings; null when any input is malformed.
export function riskGuideWon(
  totals: readonly string[],
  riskLimit: string,
): string | null {
  const match = LIMIT.exec(riskLimit);
  if (match === null || totals.length === 0) return null;
  const limit = -BigInt((match[1] ?? '0').padEnd(8, '0'));
  let peak = 0n;
  for (const total of totals) {
    if (!WHOLE_WON.test(total)) return null;
    const value = BigInt(total);
    if (value > peak) peak = value;
  }
  const scaled = peak * (SCALE + limit);
  const whole = scaled / SCALE;
  const rest = scaled % SCALE;
  return (rest * 2n >= SCALE ? whole + 1n : whole).toString();
}
