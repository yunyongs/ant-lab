import type { components } from '../../api/schema';

type RatingDto = components['schemas']['RatingDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function ratingFixture(overrides: Partial<RatingDto> = {}): RatingDto {
  return {
    pillar: 'MANAGER',
    rating: null,
    countedSeasons: 0,
    seasonsUntilPublic: 3,
    notStartedCount: 0,
    noScoreCount: 0,
    pendingSeasons: 0,
    history: [],
    ...overrides,
  };
}

export const ratingWithHistory = ratingFixture({
  rating: '61.25',
  countedSeasons: 4,
  seasonsUntilPublic: 0,
  history: [
    { seqNo: 4, seasonId: 2012, score: '64.10', rating: '61.25' },
    { seqNo: 3, seasonId: 1999, score: '58.00', rating: '57.80' },
    { seqNo: 2, seasonId: 7, score: '52.30', rating: null },
    { seqNo: 1, seasonId: 4, score: '40.70', rating: null },
  ],
});
