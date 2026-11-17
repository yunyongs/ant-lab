import type { Projection } from './phases.ts';

export interface BandGeometry {
  // Polygon point lists ("x,y x,y ..."): 5-95 % band, 25-75 % band, and the
  // median as a polyline.
  outer: string;
  inner: string;
  median: string;
  // Height of the team's value now (horizontal reference line).
  nowY: string;
  // Axis extremes as whole-won text, for the legend.
  min: string;
  max: string;
}

export const VIEW_WIDTH = 1000;
export const VIEW_HEIGHT = 500;

const WHOLE_WON = /^\d+$/;

function parseWon(text: string): bigint | null {
  return WHOLE_WON.test(text) ? BigInt(text) : null;
}

interface Row {
  p05: bigint;
  p25: bigint;
  p50: bigint;
  p75: bigint;
  p95: bigint;
}

// Pure and exact: the server's whole-won strings are scaled with BigInt, so a
// value above 2^53 keeps its place. Anything malformed draws no chart (null).
// The numbers produced here are pixels, never printed as values.
export function bandGeometry(
  bands: Projection['bands'],
  totalValue: string,
): BandGeometry | null {
  // One x step per band must stay a distinct whole pixel.
  if (bands.length < 2 || bands.length > VIEW_WIDTH + 1) return null;
  const now = parseWon(totalValue);
  if (now === null) return null;
  const rows: Row[] = [];
  let previousTurn = -Infinity;
  for (const band of bands) {
    if (!Number.isInteger(band.turn) || band.turn <= previousTurn) return null;
    previousTurn = band.turn;
    const p05 = parseWon(band.p05);
    const p25 = parseWon(band.p25);
    const p50 = parseWon(band.p50);
    const p75 = parseWon(band.p75);
    const p95 = parseWon(band.p95);
    if (
      p05 === null ||
      p25 === null ||
      p50 === null ||
      p75 === null ||
      p95 === null
    ) {
      return null;
    }
    rows.push({ p05, p25, p50, p75, p95 });
  }
  let max = now;
  let min = now;
  for (const row of rows) {
    if (row.p95 > max) max = row.p95;
    if (row.p05 < min) min = row.p05;
  }
  const span = max - min;
  const height = BigInt(VIEW_HEIGHT);
  // Flat data: one line through the middle (no division by zero).
  const y = (value: bigint): string =>
    span === 0n
      ? String(VIEW_HEIGHT / 2)
      : (((max - value) * height) / span).toString();
  const last = rows.length - 1;
  const x = (index: number): string =>
    String(Math.round((index * VIEW_WIDTH) / last));
  const upper = (pick: (row: Row) => bigint) =>
    rows.map((row, i) => `${x(i)},${y(pick(row))}`);
  const lower = (pick: (row: Row) => bigint) =>
    rows.map((row, i) => `${x(i)},${y(pick(row))}`).reverse();
  return {
    outer: [...upper((r) => r.p95), ...lower((r) => r.p05)].join(' '),
    inner: [...upper((r) => r.p75), ...lower((r) => r.p25)].join(' '),
    median: upper((r) => r.p50).join(' '),
    nowY: y(now),
    min: min.toString(),
    max: max.toString(),
  };
}
