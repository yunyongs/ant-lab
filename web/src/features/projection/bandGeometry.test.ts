import { describe, expect, it } from 'vitest';
import { DATE_PATTERN } from '../../test/datePattern.ts';
import { bandGeometry } from './bandGeometry.ts';
import type { Projection } from './phases.ts';

type Band = Projection['bands'][number];

const flat = (turn: number, value: string): Band => ({
  turn,
  p05: value,
  p25: value,
  p50: value,
  p75: value,
  p95: value,
});

const THREE: Band[] = [
  flat(0, '100'),
  { turn: 1, p05: '80', p25: '90', p50: '100', p75: '110', p95: '120' },
  { turn: 2, p05: '60', p25: '80', p50: '100', p75: '120', p95: '140' },
];

// Above 2^53 (9007199254740992) a double cannot tell these values apart.
const BIG: Band[] = [
  flat(0, '9007199254740995'),
  {
    turn: 1,
    p05: '9007199254740993',
    p25: '9007199254740994',
    p50: '9007199254740995',
    p75: '9007199254740996',
    p95: '9007199254740997',
  },
];

function long(): Band[] {
  return Array.from({ length: 121 }, (_, i) => ({
    turn: i,
    p05: String(1_000_000 - i * 500),
    p25: String(1_000_000 - i * 200),
    p50: String(1_000_000 + i * 100),
    p75: String(1_000_000 + i * 400),
    p95: String(1_000_000 + i * 900),
  }));
}

describe('bandGeometry', () => {
  it('G1: gives exact point strings for a three-band example', () => {
    expect(bandGeometry(THREE, '100')).toEqual({
      outer: '0,250 500,125 1000,0 1000,500 500,375 0,250',
      inner: '0,250 500,187 1000,125 1000,375 500,312 0,250',
      median: '0,250 500,250 1000,250',
      nowY: '250',
      min: '60',
      max: '140',
    });
  });

  it('G2: keeps values above 2^53 apart', () => {
    expect(bandGeometry(BIG, '9007199254740995')).toEqual({
      outer: '0,250 1000,0 1000,500 0,250',
      inner: '0,250 1000,125 1000,375 0,250',
      median: '0,250 1000,250',
      nowY: '250',
      min: '9007199254740993',
      max: '9007199254740997',
    });
  });

  it('G3: spreads 121 bands over x 0..1000, strictly increasing, inside the box', () => {
    const geometry = bandGeometry(long(), '1000000');
    expect(geometry).not.toBeNull();
    const points = (geometry?.median ?? '').split(' ');
    expect(points).toHaveLength(121);
    const xs = points.map((point) =>
      Number.parseInt(point.split(',')[0] ?? ''),
    );
    expect(xs[0]).toBe(0);
    expect(xs[120]).toBe(1000);
    for (let i = 1; i < xs.length; i += 1) {
      expect(xs[i] ?? -1).toBeGreaterThan(xs[i - 1] ?? 0);
    }
    for (const list of [geometry?.outer, geometry?.inner, geometry?.median]) {
      for (const point of (list ?? '').split(' ')) {
        const [x = '', y = ''] = point.split(',');
        expect(Number.parseInt(x)).toBeLessThanOrEqual(1000);
        expect(Number.parseInt(y)).toBeGreaterThanOrEqual(0);
        expect(Number.parseInt(y)).toBeLessThanOrEqual(500);
      }
    }
  });

  it.each([
    ['a fraction', [flat(0, '100'), flat(1, '12.5')]],
    ['a negative', [flat(0, '100'), flat(1, '-1')]],
    ['an empty value', [flat(0, '100'), flat(1, '')]],
    ['an exponent', [flat(0, '100'), flat(1, '1e6')]],
    ['a single band', [flat(0, '100')]],
    ['unsorted turns', [flat(2, '100'), flat(1, '100')]],
    ['repeated turns', [flat(1, '100'), flat(1, '100')]],
    ['no bands', []],
  ])('G4: refuses %s', (_name, bands) => {
    expect(bandGeometry(bands, '100')).toBeNull();
  });

  it('G4: refuses a malformed total value', () => {
    expect(bandGeometry(THREE, '1e2')).toBeNull();
    expect(bandGeometry(THREE, '')).toBeNull();
  });

  it('G5: draws a flat line when every value is equal', () => {
    expect(bandGeometry([flat(0, '100'), flat(1, '100')], '100')).toEqual({
      outer: '0,250 1000,250 1000,250 0,250',
      inner: '0,250 1000,250 1000,250 0,250',
      median: '0,250 1000,250',
      nowY: '250',
      min: '100',
      max: '100',
    });
  });

  it('G6: no coordinate can read as a year-month', () => {
    for (const geometry of [
      bandGeometry(THREE, '100'),
      bandGeometry(BIG, '9007199254740995'),
      bandGeometry(long(), '1000000'),
    ]) {
      expect(geometry).not.toBeNull();
      const text = [geometry?.outer, geometry?.inner, geometry?.median].join(
        ' ',
      );
      expect(text).not.toMatch(DATE_PATTERN);
    }
  });
});
