import type { components } from '../../api/schema';

type PoolAssetDto = components['schemas']['PoolAssetDto'];
type PoolDto = components['schemas']['PoolDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function poolAssetFixture(
  overrides: Partial<PoolAssetDto> = {},
): PoolAssetDto {
  return {
    assetId: 101,
    market: 'KR',
    ticker: 'FIX-AAA',
    name: '종목 가',
    assetType: 'STOCK',
    returnBasis: 'PRICE',
    proxyIndexName: null,
    groupId: 1,
    groupName: '시험 업종',
    groupKind: 'SECTOR',
    systemPosition: 'MIDFIELDER',
    upParticipation: '0.91000000',
    downParticipation: '0.87000000',
    volatility: '0.06200000',
    maxDrawdown: '-0.31000000',
    ...overrides,
  };
}

export const poolFixture: PoolDto = {
  assets: [
    poolAssetFixture(),
    poolAssetFixture({
      assetId: 102,
      market: 'US',
      ticker: 'FIX-BBB',
      name: '종목 나',
      systemPosition: 'FORWARD_BREAKOUT',
      upParticipation: '1.42000000',
      downParticipation: '1.18000000',
      volatility: '0.09800000',
      maxDrawdown: '-0.52000000',
    }),
    poolAssetFixture({
      assetId: 103,
      ticker: 'FIX-CCC',
      name: '채권 ETF 다',
      assetType: 'ETF',
      returnBasis: 'TOTAL',
      proxyIndexName: '시험 추종 지수',
      groupKind: 'ASSET_CLASS',
      groupName: '시험 채권',
      systemPosition: 'DEFENDER',
      upParticipation: '0.12000000',
      downParticipation: '0.05000000',
      volatility: '0.01300000',
      maxDrawdown: '-0.08000000',
    }),
  ],
};
