import { http, HttpResponse } from 'msw';
import { analysisFixture } from './fixtures/analysis.ts';
import { server } from './server.ts';

export interface AnalysisWorld {
  analysisGets: number;
}

// Overrides the default analysis handler of season `id`. `analysis` may be a
// DTO or a function returning any Response (a Problem, a network error).
export function serveAnalysis(
  options: {
    id?: number;
    analysis?: ReturnType<typeof analysisFixture> | (() => Response);
  } = {},
): AnalysisWorld {
  const id = options.id ?? 7;
  const world: AnalysisWorld = { analysisGets: 0 };
  server.use(
    http.get(`*/api/v1/seasons/${String(id)}/report/analysis`, () => {
      world.analysisGets += 1;
      const analysis = options.analysis ?? analysisFixture();
      return typeof analysis === 'function'
        ? analysis()
        : HttpResponse.json(analysis);
    }),
  );
  return world;
}
