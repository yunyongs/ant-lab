// The server's decimal string with trailing zeros cut as text; no rounding.
export function strengthText(strength: string): string {
  const shown = /^-?\d+\.\d+$/.test(strength)
    ? strength.replace(/\.?0+$/, '')
    : strength;
  return `강도 ${shown}`;
}
