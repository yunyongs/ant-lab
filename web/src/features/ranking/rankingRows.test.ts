import { describe, expect, it } from 'vitest';
import { poolFixture } from '../../test/fixtures/pool.ts';
import { rankingFixture } from '../../test/fixtures/ranking.ts';
import { UNKNOWN_ASSET_NAME } from '../trade/HoldingCard.tsx';
import { gradeLabel, UNKNOWN_GROUP_NAME } from './rankingLabels.ts';
import { rankingRows } from './rankingRows.ts';

describe('rankingRows', () => {
  const rows = rankingRows(rankingFixture(), poolFixture.assets);

  it('joins asset and group names from the pool and keeps the server order', () => {
    expect(rows.gainers.map((r) => [r.rank, r.name])).toEqual([
      [1, '종목 나'],
      [2, '종목 가'],
    ]);
    expect(rows.losers.map((r) => r.name)).toEqual(['채권 ETF 다']);
    expect(rows.groupGainers.map((r) => r.name)).toEqual(['시험 업종']);
    expect(rows.groupLosers.map((r) => r.name)).toEqual(['시험 채권']);
    expect(rows.gainers.every((r) => r.known)).toBe(true);
  });

  it('turns server ratios into percent text only', () => {
    expect(rows.gainers.map((r) => r.change)).toEqual(['12.34%', '5%']);
    expect(rows.losers.map((r) => r.change)).toEqual(['-7.25%']);
    expect(rows.risingStreaks[0]).toMatchObject({ streak: 4, change: '5%' });
  });

  it('prints attention as a grade label with no change figure', () => {
    expect(rows.attention.map((r) => [r.grade, r.change, r.streak])).toEqual([
      ['매우 높음', null, null],
      ['높음', null, null],
      ['보통', null, null],
    ]);
  });

  it('gives an id missing from the pool a placeholder and marks it unknown', () => {
    const board = rankingFixture(5, {
      gainers: [{ rank: 1, assetId: 987654, changeRate: '0.01000000' }],
      groupGainers: [{ rank: 1, groupId: 987655, changeRate: '0.01000000' }],
    });
    const out = rankingRows(board, poolFixture.assets);
    expect(out.gainers[0]).toMatchObject({
      name: UNKNOWN_ASSET_NAME,
      known: false,
    });
    expect(out.groupGainers[0]).toMatchObject({
      name: UNKNOWN_GROUP_NAME,
      known: false,
    });
  });
});

describe('gradeLabel', () => {
  it.each([
    [3, '매우 높음'],
    [2, '높음'],
    [1, '보통'],
  ])('labels grade %i as %s', (grade, label) => {
    expect(gradeLabel(grade)).toBe(label);
  });

  it.each([0, 4, -1])('does not invent a label for grade %i', (grade) => {
    expect(gradeLabel(grade)).toBe('알 수 없음');
  });
});
