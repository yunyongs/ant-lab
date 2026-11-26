import { describe, expect, it } from 'vitest';
import {
  formatAxisTurn,
  formatAxisWon,
  valueChartData,
  wonToChartNumber,
} from './adapter.ts';
import type { ValuePointText } from './adapter.ts';

const rows = (...totals: string[]) =>
  totals.map((totalValue, turnNo) => ({
    turnNo,
    totalValue,
    benchmarkValue: String(100000000 + turnNo * 1000000),
  }));

describe('wonToChartNumber', () => {
  it('converts whole won exactly', () => {
    expect(wonToChartNumber('104820000')).toBe(104820000);
    expect(wonToChartNumber('0')).toBe(0);
    expect(wonToChartNumber('9007199254740991')).toBe(9007199254740991);
  });

  it.each(['9007199254740992', '1.5', '-1', '', '1e3', ' 1'])(
    'refuses %j',
    (text) => {
      expect(wonToChartNumber(text)).toBeNull();
    },
  );
});

describe('valueChartData', () => {
  it('maps turns to points with turn 0 as the base and an exact guide', () => {
    const data = valueChartData(
      rows('100000000', '104820000', '97000000'),
      '-0.20000000',
    );
    expect(data).toEqual({
      points: [
        { time: 0, value: 100000000 },
        { time: 1, value: 104820000 },
        { time: 2, value: 97000000 },
      ],
      benchmark: [
        { time: 0, value: 100000000 },
        { time: 1, value: 101000000 },
        { time: 2, value: 102000000 },
      ],
      base: 100000000,
      guide: 83856000,
    });
  });

  it('draws no chart for fewer than two points', () => {
    expect(valueChartData(rows('100000000'), '-0.20000000')).toBeNull();
    expect(valueChartData([], '-0.20000000')).toBeNull();
  });

  it('draws no chart when a value cannot convert exactly', () => {
    expect(
      valueChartData(rows('100000000', '9007199254740992'), '-0.20000000'),
    ).toBeNull();
    expect(valueChartData(rows('100000000', '1.5'), '-0.20000000')).toBeNull();
  });

  it('draws the benchmark only when every shown value converts', () => {
    const valid: [ValuePointText, ValuePointText] = [
      { turnNo: 0, totalValue: '100', benchmarkValue: '100' },
      { turnNo: 1, totalValue: '110', benchmarkValue: '105' },
    ];
    const expected = valueChartData(valid, 'x');
    expect(expected?.benchmark).toEqual([
      { time: 0, value: 100 },
      { time: 1, value: 105 },
    ]);
    const cases: [string, ValuePointText][] = [
      ['null', { ...valid[1], benchmarkValue: null }],
      ['malformed', { ...valid[1], benchmarkValue: '1.5' }],
      ['absent', { turnNo: 1, totalValue: '110' }],
    ] as const;
    for (const [name, second] of cases) {
      const data = valueChartData([valid[0], second], 'x');
      expect(data?.benchmark, name).toBeNull();
      expect(data?.points, name).toEqual(expected?.points);
      expect(data?.base, name).toBe(expected?.base);
      expect(data?.guide, name).toBe(expected?.guide);
    }
  });

  it('cuts the benchmark at the shown turn', () => {
    const data = valueChartData(
      rows('100000000', '104820000', '97000000').filter(
        (item) => item.turnNo <= 1,
      ),
      '-0.20000000',
    );
    expect(data?.benchmark).toEqual([
      { time: 0, value: 100000000 },
      { time: 1, value: 101000000 },
    ]);
  });

  it('draws no chart when turns are not strictly ascending', () => {
    expect(
      valueChartData(
        [
          { turnNo: 1, totalValue: '1' },
          { turnNo: 1, totalValue: '2' },
        ],
        '-0.20000000',
      ),
    ).toBeNull();
  });

  it('keeps the line without a guide when the limit is malformed', () => {
    expect(valueChartData(rows('100', '110'), 'x')?.guide).toBeNull();
  });
});

describe('axis labels', () => {
  it('rounds a chart position to whole won', () => {
    expect(formatAxisWon(104820000)).toBe('104,820,000원');
    expect(formatAxisWon(100000000.5)).toBe('100,000,001원');
    expect(formatAxisWon(99999999.4)).toBe('99,999,999원');
    expect(formatAxisWon(Number.NaN)).toBe('');
  });

  it('shows turn numbers, never a date', () => {
    expect(formatAxisTurn(3)).toBe('3턴');
    expect(formatAxisTurn(2.5)).toBe('');
  });
});
