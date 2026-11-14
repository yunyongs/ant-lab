import { http, HttpResponse } from 'msw';
import { problem } from './authHandlers.ts';
import { reportFixture } from './fixtures/report.ts';
import { seasonFixture } from './fixtures/seasons.ts';
import { server } from './server.ts';

export interface ReportWorld {
  reportGets: number;
  seasonGets: number;
}

// Season `id` with the given overrides plus its report. `report` may be a
// DTO, or a function returning any Response (a Problem, a network error).
export function serveReport(
  options: {
    id?: number;
    season?: Parameters<typeof seasonFixture>[0];
    report?: ReturnType<typeof reportFixture> | (() => Response);
  } = {},
): ReportWorld {
  const id = options.id ?? 7;
  const world: ReportWorld = { reportGets: 0, seasonGets: 0 };
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
    http.get(`*/api/v1/seasons/${String(id)}/report`, () => {
      world.reportGets += 1;
      const report = options.report ?? reportFixture();
      return typeof report === 'function'
        ? report()
        : HttpResponse.json(report);
    }),
  );
  return world;
}

export const notFinished409 = () =>
  problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 });
