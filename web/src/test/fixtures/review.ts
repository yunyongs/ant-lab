import type { components } from '../../api/schema';

type SeasonReviewDto = components['schemas']['SeasonReviewDto'];
type ReviewTurnDto = components['schemas']['ReviewTurnDto'];
type ReviewEventDto = components['schemas']['ReviewEventDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function reviewEvent(
  overrides: Partial<ReviewEventDto> = {},
): ReviewEventDto {
  return {
    category: 'FINANCIAL',
    eventType: 'EVENT_TYPE_CODE_X',
    phase: 'START',
    title: 'Invented bank run',
    hindsight: null,
    source: 'https://example.test/a',
    ...overrides,
  };
}

// Year-month `2001-01` for turn 1, one month later per turn (invented).
export function reviewTurn(
  turnNo: number,
  overrides: Partial<ReviewTurnDto> = {},
): ReviewTurnDto {
  const month = ((turnNo - 1) % 12) + 1;
  const year = 2001 + Math.floor((turnNo - 1) / 12);
  return {
    turnNo,
    yearMonth: `${String(year)}-${String(month).padStart(2, '0')}`,
    eventSummary: `Invented summary ${String(turnNo)}`,
    events: [],
    ...overrides,
  };
}

export function reviewFixture(
  overrides: Partial<SeasonReviewDto> = {},
): SeasonReviewDto {
  const pathLength = overrides.pathLength ?? 12;
  return {
    seasonStatus: 'FINISHED',
    pathLength,
    abandonedTurnNo: null,
    seed: '-8123456789012345678',
    engineVersion: 'engine-9.9.9',
    dataSnapshotDate: '2026-11-12',
    turns: Array.from({ length: pathLength }, (_, i) =>
      reviewTurn(i + 1, i === 0 ? { events: [reviewEvent()] } : {}),
    ),
    formationChanges: [],
    ...overrides,
  };
}

// Strings that must never reach a screen before the season ends.
export const poisonedReviewStrings = [
  '2001-01',
  'Invented summary 1',
  'Invented bank run',
  '-8123456789012345678',
  'engine-9.9.9',
] as const;
