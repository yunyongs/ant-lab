import type { components } from '../../api/schema';

type SeasonReviewDto = components['schemas']['SeasonReviewDto'];
type ReviewTurnDto = components['schemas']['ReviewTurnDto'];
type ReviewEventDto = components['schemas']['ReviewEventDto'];
type ReviewFeedItemDto = components['schemas']['ReviewFeedItemDto'];

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

export function reviewFeedItem(
  overrides: Partial<ReviewFeedItemDto> = {},
): ReviewFeedItemDto {
  return {
    seq: 1,
    kind: 'RISE',
    signal: false,
    assetId: null,
    groupId: null,
    body: 'Invented feed sentence',
    event: null,
    ordersFromItem: 0,
    ...overrides,
  };
}

// One item per kind in display order; NUDGE comes last as on the server.
export function feedOfReviewTurn(): ReviewFeedItemDto[] {
  return [
    reviewFeedItem({ seq: 1, kind: 'RISE', body: 'Invented rise body' }),
    reviewFeedItem({ seq: 2, kind: 'ECHO', body: 'Invented echo body' }),
    reviewFeedItem({
      seq: 3,
      kind: 'SPREAD',
      groupId: 246813579,
      body: 'Invented spread body',
    }),
    reviewFeedItem({
      seq: 4,
      kind: 'EVENT',
      signal: true,
      body: 'Invented event body',
      event: reviewEvent({ title: 'Invented feed event' }),
    }),
    reviewFeedItem({
      seq: 5,
      kind: 'NUDGE',
      assetId: 987654321,
      body: 'Invented nudge body',
    }),
  ];
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
    feed: [],
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

// Feed strings (bodies, the event inside the feed, the reveal badge) that
// must stay on the review screen.
export const poisonedFeedStrings = [
  'Invented rise body',
  'Invented nudge body',
  'Invented feed event',
  '실제 사건(신호)',
  '생성된 소음',
  '독려 알림',
] as const;
