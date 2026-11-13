import { http, HttpResponse } from 'msw';
import { authHandlers } from './authHandlers.ts';

export const handlers = [
  http.get('*/api/v1/pool', () =>
    HttpResponse.json({ assets: [{ id: 1 }, { id: 2 }] }),
  ),
  ...authHandlers,
];
