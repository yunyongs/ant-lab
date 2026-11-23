import { http, HttpResponse } from 'msw';
import { parseWholeNumber } from '../features/format/integer.ts';
import { authHandlers } from './authHandlers.ts';
import { analysisFixture } from './fixtures/analysis.ts';
import { contributionFixture } from './fixtures/contribution.ts';
import { holdingsFixture } from './fixtures/holdings.ts';
import { pricesFixture } from './fixtures/prices.ts';
import { ratingFixture } from './fixtures/rating.ts';
import { scoreFixture } from './fixtures/score.ts';
import { projectionFixture } from './fixtures/projection.ts';
import { poolFixture } from './fixtures/pool.ts';
import { emptyBoard } from './fixtures/ranking.ts';
import { seasonList } from './fixtures/seasons.ts';
import { turnResultFixture } from './fixtures/turns.ts';

// POST handlers are never defaults: each test installs its own.
export const handlers = [
  http.get('*/api/v1/pool', () => HttpResponse.json(poolFixture)),
  http.get('*/api/v1/seasons', () => HttpResponse.json({ items: seasonList })),
  http.get('*/api/v1/seasons/:seasonId/prices', ({ request }) => {
    const text = new URL(request.url).searchParams.get('turnNo') ?? '0';
    return HttpResponse.json(pricesFixture(parseWholeNumber(text, 1000) ?? 0));
  }),
  http.get('*/api/v1/seasons/:seasonId/holdings', () =>
    HttpResponse.json(holdingsFixture()),
  ),
  http.get('*/api/v1/seasons/:seasonId/turns/:turnNo', ({ params }) =>
    HttpResponse.json(
      turnResultFixture({
        turnNo: parseWholeNumber(String(params.turnNo), 1000) ?? 0,
      }),
    ),
  ),
  http.get('*/api/v1/seasons/:seasonId/turns/:turnNo/ranking', ({ params }) =>
    HttpResponse.json(
      emptyBoard(parseWholeNumber(String(params.turnNo), 1000) ?? 0),
    ),
  ),
  http.get(
    '*/api/v1/seasons/:seasonId/turns/:turnNo/contribution',
    ({ params }) =>
      HttpResponse.json(
        contributionFixture({
          turnNo: parseWholeNumber(String(params.turnNo), 1000) ?? 0,
        }),
      ),
  ),
  http.get('*/api/v1/seasons/:seasonId/report/analysis', () =>
    HttpResponse.json(analysisFixture()),
  ),
  http.get('*/api/v1/seasons/:seasonId/projection', ({ request }) => {
    const phase = new URL(request.url).searchParams.get('phase');
    return HttpResponse.json(
      projectionFixture({
        phase: phase === 'START' || phase === 'END' ? phase : 'CURRENT',
      }),
    );
  }),
  http.get('*/api/v1/seasons/:seasonId/score', () =>
    HttpResponse.json(scoreFixture()),
  ),
  http.get('*/api/v1/members/me/rating', () =>
    HttpResponse.json(ratingFixture()),
  ),
  ...authHandlers,
];
