import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from './authHandlers.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { turnResultFixture } from './fixtures/turns.ts';
import { server } from './server.ts';

type Status = components['schemas']['SeasonStatus'];

// What the next abandon request answers. The default is the whole season.
export type AbandonAnswer =
  | { kind: 'abandoning'; advanced: number }
  | { kind: 'finished' }
  // A refusal; `status` is the season state the server is left in.
  | { kind: 'refuse'; httpStatus: number; code: string; leaves?: Status }
  | { kind: 'network' };

export interface AbandonWorld {
  current: number;
  status: Status;
  abandonedTurnNo: number | null;
  notStarted: boolean;
  posts: unknown[];
  seasonGets: number;
  turnGets: number[];
  advancePosts: number;
  answers: AbandonAnswer[];
  // One gate per abandon request, consumed in order.
  postGates: Promise<void>[];
  advanceGate: Promise<void> | null;
}

// A small stateful stand-in for season 7 (plan-approved mock; values are
// invented): the abandon marks the season, runs turns, and the season lookup
// reports what the server would.
export function serveAbandonWorld(
  start: {
    current?: number;
    pathLength?: number;
    status?: Status;
    abandonedTurnNo?: number | null;
    notStarted?: boolean;
  } = {},
): AbandonWorld {
  const pathLength = start.pathLength ?? 12;
  const world: AbandonWorld = {
    current: start.current ?? 5,
    status: start.status ?? 'IN_PROGRESS',
    abandonedTurnNo: start.abandonedTurnNo ?? null,
    notStarted: start.notStarted ?? false,
    posts: [],
    seasonGets: 0,
    turnGets: [],
    advancePosts: 0,
    answers: [],
    postGates: [],
    advanceGate: null,
  };

  function result(
    advancedTurns: number,
  ): components['schemas']['AbandonResultDto'] {
    return {
      seasonId: 7,
      status: world.status,
      currentTurnNo: world.current,
      pathLength,
      abandonedTurnNo: world.abandonedTurnNo ?? world.current,
      notStarted: world.notStarted,
      advancedTurns,
      remainingTurns: pathLength - world.current,
    };
  }

  server.use(
    http.get('*/api/v1/seasons/7', () => {
      world.seasonGets += 1;
      return HttpResponse.json(
        seasonFixture({
          id: 7,
          pathLength,
          currentTurnNo: world.current,
          status: world.status,
          abandonedTurnNo: world.abandonedTurnNo,
          notStarted: world.notStarted,
        }),
      );
    }),
    http.get('*/api/v1/seasons/7/turns/:turnNo', ({ params }) => {
      const turnNo = parseWholeNumber(String(params.turnNo), 1000) ?? 0;
      world.turnGets.push(turnNo);
      return HttpResponse.json(
        turnResultFixture({ turnNo, pathLength, status: world.status }),
      );
    }),
    http.post('*/api/v1/seasons/7/turns', async () => {
      world.advancePosts += 1;
      if (world.advanceGate !== null) await world.advanceGate;
      world.current += 1;
      return HttpResponse.json(
        turnResultFixture({ turnNo: world.current, pathLength }),
        { status: 201 },
      );
    }),
    http.post('*/api/v1/seasons/7/abandon', async ({ request }) => {
      world.posts.push(await request.json());
      const gate = world.postGates.shift();
      if (gate !== undefined) await gate;
      const answer = world.answers.shift() ?? { kind: 'finished' };
      if (answer.kind === 'network') return HttpResponse.error();
      // The first request that gets through marks the season, even when the
      // turns after it fail.
      const stale =
        answer.kind === 'refuse' &&
        (answer.code === 'TURN_MISMATCH' ||
          answer.code === 'SEASON_NOT_ACTIVE');
      if (world.abandonedTurnNo === null && !stale) {
        world.abandonedTurnNo = world.current;
        world.notStarted = world.current < 3;
        world.status = 'ABANDONING';
      }
      if (answer.kind === 'refuse') {
        if (answer.leaves !== undefined) world.status = answer.leaves;
        return problem(answer.httpStatus, {
          code: answer.code,
          status: answer.httpStatus,
        });
      }
      const before = world.current;
      if (answer.kind === 'finished') {
        world.current = pathLength;
        world.status = 'FINISHED';
      } else {
        world.current = Math.min(pathLength - 1, before + answer.advanced);
        world.status = 'ABANDONING';
      }
      return HttpResponse.json(result(world.current - before));
    }),
  );
  return world;
}
