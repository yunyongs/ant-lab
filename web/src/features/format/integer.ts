const WHOLE = /^(0|[1-9][0-9]*)$/;

// The only place that turns typed text into a JSON integer (formation shares,
// quantities, route ids). Money never goes through here.
export function parseWholeNumber(text: string, max: number): number | null {
  if (!WHOLE.test(text)) return null;
  // Integers only, after the regex check; never money.
  const value = Number.parseInt(text, 10);
  return value <= max ? value : null;
}

// Largest route id accepted from a URL (ids are JSON integers).
export const MAX_ROUTE_ID = Number.MAX_SAFE_INTEGER;
