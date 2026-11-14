import { http, HttpResponse } from 'msw';
import { parseWholeNumber } from '../features/format/integer.ts';
import { authHandlers } from './authHandlers.ts';
import { holdingsFixture } from './fixtures/holdings.ts';
import { pricesFixture } from './fixtures/prices.ts';
import { poolFixture } from './fixtures/pool.ts';
import { seasonList } from './fixtures/seasons.ts';

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
  ...authHandlers,
];
