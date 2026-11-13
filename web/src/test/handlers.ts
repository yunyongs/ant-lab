import { http, HttpResponse } from 'msw';

export const handlers = [
  http.get('*/api/v1/pool', () =>
    HttpResponse.json({ assets: [{ id: 1 }, { id: 2 }] }),
  ),
];
