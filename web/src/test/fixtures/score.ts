import type { components } from '../../api/schema';

type SeasonScoreDto = components['schemas']['SeasonScoreDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented
// and distinct so a test can tell the rows apart. `score` is deliberately not
// the mean of the other two.
export function scoreFixture(
  overrides: Partial<SeasonScoreDto> = {},
): SeasonScoreDto {
  return {
    scoreVersion: 's63-v1',
    score: '58.30',
    resultScore: '63.10',
    processScore: '51.45',
    luckPercentile: '0.63125000',
    sharpeDiffPercentile: '0.41250000',
    items: {
      risk: '71.10',
      discipline: '48.20',
      diversification: '55.30',
      turnover: '39.40',
      consistency: '62.50',
      formation: '44.60',
      role: '66.70',
      noise: '33.80',
      nudge: '41.90',
    },
    processItems: 9,
    countedInRating: true,
    ratingExclusion: null,
    ...overrides,
  };
}

// The server may send null for an item whose input does not exist yet.
export const scoreWithNullItems = scoreFixture({
  processItems: 7,
  items: {
    ...scoreFixture().items,
    consistency: null,
    noise: null,
  },
});

// No path distribution: no score, result or percentiles; excluded.
export const scoreWithoutDistribution = scoreFixture({
  score: null,
  resultScore: null,
  luckPercentile: null,
  sharpeDiffPercentile: null,
  countedInRating: false,
  ratingExclusion: 'NO_SCORE',
});
