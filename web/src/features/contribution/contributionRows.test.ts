import { describe, expect, it } from 'vitest';
import { holdingContributionFixture } from '../../test/fixtures/contribution.ts';
import { poolAssetFixture } from '../../test/fixtures/pool.ts';
import { UNKNOWN_ASSET_NAME } from '../trade/HoldingCard.tsx';
import { contributionLists } from './contributionRows.ts';

// Holdings in the server's order: contribution desc, ties by assetId.
function holdings(rows: readonly (readonly [number, string])[]) {
  return rows.map(([assetId, contribution]) =>
    holdingContributionFixture({ assetId, contribution }),
  );
}

const pool = [1, 2, 3, 4, 5, 6, 7, 8].map((assetId) =>
  poolAssetFixture({ assetId, name: `종목 ${String(assetId)}` }),
);

const ids = (rows: readonly { assetId: number }[]) =>
  rows.map((row) => row.assetId);

describe('contributionLists', () => {
  it('shows all of exactly six non-zero holdings with nothing left over', () => {
    const lists = contributionLists(
      holdings([
        [1, '300'],
        [2, '200'],
        [3, '100'],
        [4, '-100'],
        [5, '-200'],
        [6, '-300'],
      ]),
      pool,
    );
    expect(ids(lists.up)).toEqual([1, 2, 3]);
    expect(ids(lists.down)).toEqual([6, 5, 4]);
    expect(lists.hasMore).toBe(false);
  });

  it('leaves the down list empty when every holding gained', () => {
    const lists = contributionLists(
      holdings([
        [1, '500'],
        [2, '0.5'],
      ]),
      pool,
    );
    expect(ids(lists.up)).toEqual([1, 2]);
    expect(lists.down).toEqual([]);
    expect(lists.hasMore).toBe(false);
  });

  it('cuts a tie at the third place by the server order and keeps the fourth for 전체 보기', () => {
    const lists = contributionLists(
      holdings([
        [1, '900'],
        [2, '400'],
        [3, '400'],
        [4, '400'],
      ]),
      pool,
    );
    expect(ids(lists.up)).toEqual([1, 2, 3]);
    expect(lists.hasMore).toBe(true);
    expect(ids(lists.all)).toEqual([1, 2, 3, 4]);
  });

  it('keeps tied losses in assetId order, largest loss first', () => {
    const lists = contributionLists(
      holdings([
        [1, '-100'],
        [2, '-700'],
        [3, '-700'],
        [4, '-700'],
        [5, '-900'],
      ]),
      pool,
    );
    expect(ids(lists.down)).toEqual([5, 2, 3]);
    expect(lists.hasMore).toBe(true);
  });

  it('compares long decimal strings exactly and puts zero in neither list', () => {
    const lists = contributionLists(
      holdings([
        [1, '12345678901234567890'],
        [2, '0'],
        [3, '-12345678901234567890'],
        [4, '-12345678901234567891'],
      ]),
      pool,
    );
    expect(ids(lists.up)).toEqual([1]);
    expect(ids(lists.down)).toEqual([4, 3]);
    expect(lists.hasMore).toBe(true);
  });

  it('names holdings from the pool and marks an unknown asset', () => {
    const lists = contributionLists(
      holdings([
        [1, '10'],
        [99, '-10'],
      ]),
      pool,
    );
    expect(lists.all.map((row) => row.name)).toEqual([
      '종목 1',
      UNKNOWN_ASSET_NAME,
    ]);
    expect(lists.all[0]?.position).toBe('미드필더');
  });
});
