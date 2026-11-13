import { http, HttpResponse } from 'msw';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from './authHandlers.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { turnResultFixture } from './fixtures/turns.ts';
import { server } from './server.ts';

export interface TurnWorld {
  current: number;
  status: 'IN_PROGRESS' | 'FINISHED';
  posts: unknown[];
  seasonGets: number;
  turnGets: number[];
  // When set, the next advance answers with it instead of applying the turn.
  failWith: (() => Response) | null;
  // Awaited by the advance handler / the season handler when set.
  postGate: Promise<void> | null;
  seasonGate: Promise<void> | null;
}

// A tiny stateful stand-in for season 7: the advance moves the turn, the
// season lookup reports it. Values are invented (plan-approved mock).
export function serveTurnWorld(
  start: { current: number; pathLength?: number } = { current: 4 },
): TurnWorld {
  const pathLength = start.pathLength ?? 12;
  const world: TurnWorld = {
    current: start.current,
    status: 'IN_PROGRESS',
    posts: [],
    seasonGets: 0,
    turnGets: [],
    failWith: null,
    postGate: null,
    seasonGate: null,
  };
  server.use(
    http.get('*/api/v1/seasons/7', async () => {
      world.seasonGets += 1;
      if (world.seasonGate !== null) await world.seasonGate;
      return HttpResponse.json(
        seasonFixture({
          id: 7,
          pathLength,
          currentTurnNo: world.current,
          status: world.status,
        }),
      );
    }),
    http.get('*/api/v1/seasons/7/turns/:turnNo', ({ params }) => {
      const turnNo = parseWholeNumber(String(params.turnNo), 1000) ?? 0;
      world.turnGets.push(turnNo);
      return HttpResponse.json(
        turnResultFixture({
          turnNo,
          pathLength,
          status: world.status,
        }),
      );
    }),
    http.post('*/api/v1/seasons/7/turns', async ({ request }) => {
      world.posts.push(await request.json());
      if (world.postGate !== null) await world.postGate;
      if (world.failWith !== null) return world.failWith();
      world.current += 1;
      if (world.current >= pathLength) world.status = 'FINISHED';
      return HttpResponse.json(
        turnResultFixture({
          turnNo: world.current,
          pathLength,
          status: world.status,
        }),
        { status: 201 },
      );
    }),
  );
  return world;
}

export function failWithProblem(status: number, code: string): () => Response {
  return () => problem(status, { code, status });
}

export function gate(): { promise: Promise<void>; release: () => void } {
  let release = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}
