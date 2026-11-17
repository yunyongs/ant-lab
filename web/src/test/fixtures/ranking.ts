import type { components } from '../../api/schema';

type RankingBoardDto = components['schemas']['RankingBoardDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function emptyBoard(turnNo = 5): RankingBoardDto {
  return {
    turnNo,
    gainers: [],
    losers: [],
    attention: [],
    risingStreaks: [],
    fallingStreaks: [],
    groupGainers: [],
    groupLosers: [],
  };
}

// Ids are those of the pool fixture (101..103, groups 1 and 2 by name).
export function rankingFixture(
  turnNo = 5,
  overrides: Partial<RankingBoardDto> = {},
): RankingBoardDto {
  return {
    turnNo,
    gainers: [
      { rank: 1, assetId: 102, changeRate: '0.12340000' },
      { rank: 2, assetId: 101, changeRate: '0.05000000' },
    ],
    losers: [{ rank: 1, assetId: 103, changeRate: '-0.07250000' }],
    attention: [
      { rank: 1, assetId: 103, grade: 3 },
      { rank: 2, assetId: 101, grade: 2 },
      { rank: 3, assetId: 102, grade: 1 },
    ],
    risingStreaks: [
      { rank: 1, assetId: 101, streak: 4, changeRate: '0.05000000' },
    ],
    fallingStreaks: [],
    groupGainers: [{ rank: 1, groupId: 1, changeRate: '0.08000000' }],
    groupLosers: [{ rank: 1, groupId: 2, changeRate: '-0.03000000' }],
    ...overrides,
  };
}
