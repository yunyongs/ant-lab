import { formatWon } from '../format/money.ts';
import { riskGuideWon } from './riskGuide.ts';

// The one place where server decimal strings become JavaScript numbers. The
// chart library only draws numbers, and these numbers only place pixels: every
// value a player reads is still formatted from the server string (the table)
// or from a whole won that converts exactly. Only whole won up to
// Number.MAX_SAFE_INTEGER is converted, where the conversion has no rounding;
// anything else draws no chart and the table stays the record.

const WHOLE_WON = /^\d+$/;

export function wonToChartNumber(text: string): number | null {
  if (!WHOLE_WON.test(text)) return null;
  if (BigInt(text) > BigInt(Number.MAX_SAFE_INTEGER)) return null;
  return Number(text);
}

export interface ChartPoint {
  time: number;
  value: number;
}

export interface ValueChartData {
  points: ChartPoint[];
  benchmark: ChartPoint[] | null;
  // Turn 0's total (the initial capital): above it is up, below it is down.
  base: number;
  // Where the drawdown reaches the risk limit from the highest total so far.
  guide: number | null;
}

export interface ValuePointText {
  turnNo: number;
  totalValue: string;
  benchmarkValue?: string | null;
}

export function valueChartData(
  items: readonly ValuePointText[],
  riskLimit: string,
): ValueChartData | null {
  if (items.length < 2) return null;
  const points: ChartPoint[] = [];
  let previous = -1;
  for (const item of items) {
    if (!Number.isInteger(item.turnNo) || item.turnNo <= previous) return null;
    previous = item.turnNo;
    const value = wonToChartNumber(item.totalValue);
    if (value === null) return null;
    points.push({ time: item.turnNo, value });
  }
  const base = points[0]?.value;
  if (base === undefined) return null;
  const benchmark: ChartPoint[] = [];
  for (const item of items) {
    const value =
      item.benchmarkValue === undefined || item.benchmarkValue === null
        ? null
        : wonToChartNumber(item.benchmarkValue);
    if (value === null) {
      benchmark.length = 0;
      break;
    }
    benchmark.push({ time: item.turnNo, value });
  }
  const guideText = riskGuideWon(
    items.map((item) => item.totalValue),
    riskLimit,
  );
  const guide = guideText === null ? null : wonToChartNumber(guideText);
  return {
    points,
    benchmark: benchmark.length === items.length ? benchmark : null,
    base,
    guide,
  };
}

// Axis ticks and the crosshair label are positions the chart picked, not
// server values: rounded to whole won for display.
export function formatAxisWon(value: number): string {
  if (!Number.isFinite(value)) return '';
  return formatWon(Math.round(value).toString());
}

export function formatAxisTurn(value: number): string {
  return Number.isInteger(value) ? `${String(value)}턴` : '';
}
