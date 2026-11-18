import { http, HttpResponse } from 'msw';
import { ratingFixture } from './fixtures/rating.ts';
import { scoreFixture } from './fixtures/score.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { server } from './server.ts';

export interface ScoreWorld {
  scoreGets: number;
  ratingGets: number;
  seasonGets: number;
}

type Body<T> = T | (() => Response);

const answer = <T extends object>(body: Body<T>) =>
  typeof body === 'function' ? body() : HttpResponse.json(body);

// Season `id` (finished unless overridden), its score and the rating, with a
// counter for each request. A score or rating may be a DTO or a function
// returning any Response (a Problem, a network error).
export function serveScore(
  options: {
    id?: number;
    season?: Parameters<typeof seasonFixture>[0];
    score?: Body<ReturnType<typeof scoreFixture>>;
    rating?: Body<ReturnType<typeof ratingFixture>>;
  } = {},
): ScoreWorld {
  const id = options.id ?? 7;
  const world: ScoreWorld = { scoreGets: 0, ratingGets: 0, seasonGets: 0 };
  server.use(
    http.get(`*/api/v1/seasons/${String(id)}`, () => {
      world.seasonGets += 1;
      return HttpResponse.json(
        seasonFixture({
          id,
          status: 'FINISHED',
          currentTurnNo: 12,
          ...options.season,
        }),
      );
    }),
    http.get(`*/api/v1/seasons/${String(id)}/score`, () => {
      world.scoreGets += 1;
      return answer(options.score ?? scoreFixture());
    }),
    http.get('*/api/v1/members/me/rating', () => {
      world.ratingGets += 1;
      return answer(options.rating ?? ratingFixture());
    }),
  );
  return world;
}
