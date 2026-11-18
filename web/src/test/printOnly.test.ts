import { describe, expect, it } from 'vitest';

// The score and rating screens print server fields. This scan catches an
// arithmetic operator or an aggregate next to one of those fields.
const MEMBERS =
  'score|resultScore|processScore|luckPercentile|sharpeDiffPercentile|rating|processItems|countedSeasons|seasonsUntilPublic|notStartedCount|noScoreCount|pendingSeasons|seqNo';
const BEFORE = new RegExp(`\\.(${MEMBERS})\\s*[-+*/%]`);
const AFTER = new RegExp(`[-+*/%]\\s*[\\w.]*\\.(${MEMBERS})\\b`);
const BANNED = [/\.reduce\(/, /\.sort\(/, /\.toSorted\(/, /Math\./];

const RAW = import.meta.glob<string>(
  [
    '../features/score/*.{ts,tsx}',
    '../features/rating/*.{ts,tsx}',
    '!../features/**/*.test.*',
  ],
  { query: '?raw', import: 'default', eager: true },
);

function sources(): { file: string; text: string }[] {
  return Object.entries(RAW).map(([file, raw]) => ({
    file,
    // Template literals and comments are not code: strip them first.
    text: raw.replace(/\/\/.*$/gm, '').replace(/`[^`]*`/g, '``'),
  }));
}

describe('score and rating folders only print', () => {
  it('finds the files it scans', () => {
    expect(sources().length).toBeGreaterThanOrEqual(7);
  });

  it('has no arithmetic next to a server field and no aggregate or sort', () => {
    for (const { file, text } of sources()) {
      expect(text, `${file} operator before`).not.toMatch(BEFORE);
      expect(text, `${file} operator after`).not.toMatch(AFTER);
      for (const pattern of BANNED) {
        expect(text, `${file} ${String(pattern)}`).not.toMatch(pattern);
      }
    }
  });

  it('would catch a recomputed value (the scan itself)', () => {
    expect('x.score + y.resultScore').toMatch(BEFORE);
    expect('1 - p.luckPercentile').toMatch(AFTER);
    expect('rows.reduce((a, b) => a)').toMatch(BANNED[0] ?? /^$/);
  });
});
