import { http, HttpResponse } from 'msw';
import type { components } from '../api/schema';
import { parseWholeNumber } from '../features/format/integer.ts';
import { holdingsFixture } from './fixtures/holdings.ts';
import { orderResultFixture } from './fixtures/orders.ts';
import { pricesFixture } from './fixtures/prices.ts';
import { server } from './server.ts';
import { serveTurnWorld, type TurnWorld } from './serveTurns.ts';

type HoldingDto = components['schemas']['HoldingDto'];

export interface FeedWorld extends TurnWorld {
  held: HoldingDto[];
  orders: unknown[];
  holdingsGets: number;
  pricesGets: number[];
  // Answer of the order POST; a fill by default.
  orderAnswer: () => Response;
}

// The turn world plus the order screen's data: holdings at the current turn,
// prices per requested turn, and a recorder for order bodies.
export function serveFeedWorld(
  start: { current: number; pathLength?: number } = { current: 5 },
): FeedWorld {
  const base = serveTurnWorld(start);
  const world: FeedWorld = Object.assign(base, {
    held: [] as HoldingDto[],
    orders: [] as unknown[],
    holdingsGets: 0,
    pricesGets: [] as number[],
    orderAnswer: () =>
      HttpResponse.json(orderResultFixture({ turnNo: base.current }), {
        status: 201,
      }),
  });
  server.use(
    http.get('*/api/v1/seasons/7/holdings', () => {
      world.holdingsGets += 1;
      return HttpResponse.json(
        holdingsFixture({ turnNo: world.current, holdings: world.held }),
      );
    }),
    http.get('*/api/v1/seasons/7/prices', ({ request }) => {
      const text = new URL(request.url).searchParams.get('turnNo') ?? '0';
      const turnNo = parseWholeNumber(text, 1000) ?? 0;
      world.pricesGets.push(turnNo);
      return HttpResponse.json(pricesFixture(turnNo));
    }),
    http.post('*/api/v1/seasons/7/orders', async ({ request }) => {
      world.orders.push(await request.json());
      return world.orderAnswer();
    }),
  );
  return world;
}
