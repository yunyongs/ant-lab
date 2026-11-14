import { http, HttpResponse } from 'msw';
import { seasonFixture } from './fixtures/seasons.ts';
import { server } from './server.ts';

export function serveSeason(
  overrides: Parameters<typeof seasonFixture>[0] = {},
  id = 7,
) {
  server.use(
    http.get(`*/api/v1/seasons/${String(id)}`, () =>
      HttpResponse.json(seasonFixture({ id, ...overrides })),
    ),
  );
}
