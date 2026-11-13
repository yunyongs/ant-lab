import { http, HttpResponse } from 'msw';
import { authHandlers } from './authHandlers.ts';
import { seasonList } from './fixtures/seasons.ts';

export const handlers = [
  http.get('*/api/v1/pool', () =>
    HttpResponse.json({ assets: [{ id: 1 }, { id: 2 }] }),
  ),
  http.get('*/api/v1/seasons', () => HttpResponse.json({ items: seasonList })),
  ...authHandlers,
];
