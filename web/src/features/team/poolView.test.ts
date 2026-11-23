import { describe, expect, it } from 'vitest';
import { poolAssetFixture } from '../../test/fixtures/pool.ts';
import {
  applyPoolView,
  filterPool,
  groupOptions,
  isFiltered,
  parseFilters,
  sortPool,
} from './poolView.ts';

const assets = [
  poolAssetFixture({
    assetId: 1,
    name: '가나다',
    ticker: 'AAA',
    volatility: '0.10000000',
    maxDrawdown: '-0.30000000',
    upParticipation: '1.00000000',
    downParticipation: '0.90000000',
  }),
  poolAssetFixture({
    assetId: 2,
    name: 'Beta Corp',
    ticker: 'BBB',
    market: 'US',
    groupId: 2,
    groupName: '기술',
    systemPosition: 'FORWARD_TARGET',
    volatility: '0.02000000',
    maxDrawdown: '-0.05000000',
    upParticipation: '1.20000000',
    downParticipation: '0.20000000',
  }),
  poolAssetFixture({
    assetId: 3,
    name: '채권 ETF',
    ticker: 'CCC',
    assetType: 'ETF',
    groupId: 3,
    groupName: '채권',
    systemPosition: 'DEFENDER',
    volatility: '0.02000000',
    maxDrawdown: '-0.0500000',
    upParticipation: '0.20000000',
    downParticipation: '0.05000000',
  }),
];

describe('parseFilters', () => {
  it('reads known values and ignores unknown ones', () => {
    const filters = parseFilters(
      new URLSearchParams(
        'q=a&market=US&type=BOND&pos=DEFENDER&group=x1&sort=vol-asc',
      ),
    );
    expect(filters).toEqual({
      query: 'a',
      market: 'US',
      assetType: null,
      position: 'DEFENDER',
      groupId: null,
      sort: 'vol-asc',
    });
  });

  it('treats an empty address as no filter', () => {
    expect(isFiltered(parseFilters(new URLSearchParams()))).toBe(false);
  });
});

describe('filterPool', () => {
  const none = parseFilters(new URLSearchParams());
  it('searches name and ticker case-insensitively', () => {
    expect(
      filterPool(assets, { ...none, query: ' beta ' }).map((a) => a.assetId),
    ).toEqual([2]);
    expect(
      filterPool(assets, { ...none, query: 'ccc' }).map((a) => a.assetId),
    ).toEqual([3]);
  });

  it('combines market, type, position and group', () => {
    expect(
      filterPool(assets, { ...none, market: 'KR', assetType: 'ETF' }).map(
        (a) => a.assetId,
      ),
    ).toEqual([3]);
    expect(
      filterPool(assets, { ...none, position: 'FORWARD_TARGET' }).map(
        (a) => a.assetId,
      ),
    ).toEqual([2]);
    expect(
      filterPool(assets, { ...none, groupId: '2' }).map((a) => a.assetId),
    ).toEqual([2]);
    expect(filterPool(assets, { ...none, market: 'US', groupId: '3' })).toEqual(
      [],
    );
  });
});

describe('sortPool', () => {
  it('compares decimal strings exactly and keeps server order on ties', () => {
    expect(sortPool(assets, 'vol-asc').map((a) => a.assetId)).toEqual([
      2, 3, 1,
    ]);
    expect(sortPool(assets, 'vol-desc').map((a) => a.assetId)).toEqual([
      1, 2, 3,
    ]);
  });

  it('puts the smaller drawdown first, treating -0.05 and -0.0500000 as equal', () => {
    expect(sortPool(assets, 'mdd-small').map((a) => a.assetId)).toEqual([
      2, 3, 1,
    ]);
    expect(sortPool(assets, 'mdd-large').map((a) => a.assetId)).toEqual([
      1, 2, 3,
    ]);
  });

  it('sorts by participation', () => {
    expect(sortPool(assets, 'up-desc').map((a) => a.assetId)).toEqual([
      2, 1, 3,
    ]);
    expect(sortPool(assets, 'down-asc').map((a) => a.assetId)).toEqual([
      3, 2, 1,
    ]);
  });

  it('does not reorder or copy without a sort', () => {
    expect(sortPool(assets, null)).toBe(assets);
  });
});

describe('applyPoolView and groupOptions', () => {
  it('filters then sorts', () => {
    const filters = parseFilters(
      new URLSearchParams('market=KR&sort=vol-desc'),
    );
    expect(applyPoolView(assets, filters).map((a) => a.assetId)).toEqual([
      1, 3,
    ]);
  });

  it('lists each group once, sorted by name', () => {
    expect(groupOptions(assets).map((g) => g.name)).toEqual(
      ['시험 업종', '기술', '채권'].sort((a, b) => a.localeCompare(b, 'ko')),
    );
  });
});

describe('sort with malformed values', () => {
  it('keeps malformed values last in both directions', () => {
    const bad = poolAssetFixture({ assetId: 9, volatility: 'n/a' });
    const list = [bad, ...assets];
    expect(sortPool(list, 'vol-asc').map((a) => a.assetId)).toEqual([
      2, 3, 1, 9,
    ]);
    expect(sortPool(list, 'vol-desc').map((a) => a.assetId)).toEqual([
      1, 2, 3, 9,
    ]);
  });
});
