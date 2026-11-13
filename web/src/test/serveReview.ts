import { http, HttpResponse } from 'msw';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from './authHandlers.ts';
import { reviewFixture } from './fixtures/review.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { turnResultFixture } from './fixtures/turns.ts';
import { server } from './server.ts';

export interface ReviewWorld {
  reviewGets: number;
  seasonGets: number;
  // Every `GET /turns/n`, in arrival order.
  turnGets: number[];
  // Turns that answer 500 until cleared.
  failingTurns: Set<number>;
}

// Season `id` with the given overrides, its review, and its turns. A turn `n`
// answers a total value `100000000 + n` so rows can be told apart.
export function serveReview(
  options: {
    id?: number;
    season?: Parameters<typeof seasonFixture>[0];
    review?: ReturnType<typeof reviewFixture> | (() => Response);
  } = {},
): ReviewWorld {
  const id = options.id ?? 7;
  const world: ReviewWorld = {
    reviewGets: 0,
    seasonGets: 0,
    turnGets: [],
    failingTurns: new Set(),
  };
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
    http.get(`*/api/v1/seasons/${String(id)}/review`, () => {
      world.reviewGets += 1;
      const review = options.review ?? reviewFixture();
      return typeof review === 'function'
        ? review()
        : HttpResponse.json(review);
    }),
    http.get(`*/api/v1/seasons/${String(id)}/turns/:turnNo`, ({ params }) => {
      const turnNo = parseWholeNumber(String(params.turnNo), 1000) ?? 0;
      world.turnGets.push(turnNo);
      if (world.failingTurns.has(turnNo)) {
        return problem(500, { code: 'INTERNAL_ERROR', status: 500 });
      }
      return HttpResponse.json(
        turnResultFixture({
          seasonId: id,
          turnNo,
          status: 'FINISHED',
          totalValue: String(100000000 + turnNo),
          periodReturn: '0.01000000',
        }),
      );
    }),
  );
  return world;
}

export const reviewNotFinished409 = () =>
  problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 });
