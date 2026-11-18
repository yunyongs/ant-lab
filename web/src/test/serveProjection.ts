import { http, HttpResponse } from 'msw';
import { problem } from './authHandlers.ts';
import { projectionFixture } from './fixtures/projection.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { server } from './server.ts';

export interface ProjectionWorld {
  // One entry per projection request: the `phase` query value (null if none).
  phases: (string | null)[];
  seasonGets: number;
}

type Answer = (phase: string | null) => Response;

// Season `id` plus its projection. `answer` may return any Response (a
// Problem, a network error); the default is a fixture for the asked phase.
export function serveProjection(
  options: {
    id?: number;
    season?: Parameters<typeof seasonFixture>[0];
    answer?: Answer;
  } = {},
): ProjectionWorld {
  const id = options.id ?? 7;
  const world: ProjectionWorld = { phases: [], seasonGets: 0 };
  server.use(
    http.get(`*/api/v1/seasons/${String(id)}`, () => {
      world.seasonGets += 1;
      return HttpResponse.json(
        seasonFixture({ id, currentTurnNo: 4, ...options.season }),
      );
    }),
    http.get(`*/api/v1/seasons/${String(id)}/projection`, ({ request }) => {
      const phase = new URL(request.url).searchParams.get('phase');
      world.phases.push(phase);
      if (options.answer !== undefined) return options.answer(phase);
      return HttpResponse.json(
        projectionFixture({
          phase: phase === 'START' || phase === 'END' ? phase : 'CURRENT',
        }),
      );
    }),
  );
  return world;
}

export const engineUnavailable503 = () =>
  problem(503, { code: 'ENGINE_UNAVAILABLE', status: 503 });

export const pathTooLong422 = () =>
  problem(422, { code: 'PATH_TOO_LONG', status: 422 });
