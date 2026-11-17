import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  DATE_PATTERN,
  expectNoDatePattern,
} from '../test/expectNoDatePattern.ts';
import {
  poisonedReviewStrings,
  reviewFixture,
} from '../test/fixtures/review.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from '../test/authHandlers.ts';
import { feedOfTurn } from '../test/fixtures/feed.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveFeedWorld } from '../test/serveFeed.ts';
import { server } from '../test/server.ts';
import { serveReport } from '../test/serveReport.ts';
import { serveProjection } from '../test/serveProjection.ts';
import { serveReview } from '../test/serveReview.ts';

// Real dates exist on exactly one screen: the review of a finished season.
describe('reveal boundary', () => {
  it('prints dates on the finished review, and on no other screen after it was cached', async () => {
    serveReview();
    serveReport();
    const { router, container } = renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    expect(container.textContent).toMatch(DATE_PATTERN);
    for (const path of [
      '/seasons/7',
      '/seasons/7/turns/3',
      '/seasons/7/trade',
      '/seasons/7/formation',
      '/seasons/7/report',
    ]) {
      await act(async () => {
        await router.navigate(path);
      });
      await waitFor(() => {
        expect(
          screen.queryByRole('heading', { name: '시즌 7 복기' }),
        ).toBeNull();
      });
      // Let the page settle before reading it.
      await new Promise((resolve) => setTimeout(resolve, 60));
      expectNoDatePattern(container);
      for (const leaked of poisonedReviewStrings) {
        expect(container.innerHTML).not.toContain(leaked);
      }
    }
  });

  it('keeps an in-progress season free of dates and of any review request', async () => {
    const world = serveReview({
      season: { status: 'IN_PROGRESS', currentTurnNo: 3 },
    });
    server.use(
      http.get('*/api/v1/seasons/8', () =>
        HttpResponse.json(seasonFixture({ id: 8, currentTurnNo: 2 })),
      ),
    );
    const { router, container } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '시즌 7' });
    expectNoDatePattern(container);
    for (const path of [
      '/seasons/7/review',
      '/seasons/8',
      '/seasons/8/review',
    ]) {
      await act(async () => {
        await router.navigate(path);
      });
      await new Promise((resolve) => setTimeout(resolve, 60));
      expectNoDatePattern(container);
      for (const leaked of poisonedReviewStrings) {
        expect(container.innerHTML).not.toContain(leaked);
      }
    }
    expect(world.reviewGets).toBe(0);
    expect(world.turnGets.every((n) => n <= 3)).toBe(true);
  });

  it('keeps a non-empty feed free of dates and of any review or later-turn request', async () => {
    const world = serveFeedWorld({ current: 3 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    let reviewGets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/review', () => {
        reviewGets += 1;
        return problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 });
      }),
    );
    const { router, container } = renderRoute('/seasons/7');
    await screen.findByRole('region', { name: '소식' });
    expectNoDatePattern(container);
    for (const path of ['/seasons/7/turns/2', '/seasons/7/turns/4']) {
      await act(async () => {
        await router.navigate(path);
      });
      await new Promise((resolve) => setTimeout(resolve, 60));
      expectNoDatePattern(container);
    }
    expect(world.turnGets.every((n) => n <= 3)).toBe(true);
    expect(world.turnGets).toContain(3);
    expect(reviewGets).toBe(0);
  });

  it('P12: the projection page of a running season asks for no review and no later turn', async () => {
    const turnGets: number[] = [];
    let reviewGets = 0;
    serveProjection({ season: { currentTurnNo: 3 } });
    server.use(
      http.get('*/api/v1/seasons/7/review', () => {
        reviewGets += 1;
        return problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 });
      }),
      http.get('*/api/v1/seasons/7/turns/:turnNo', ({ params }) => {
        turnGets.push(parseWholeNumber(String(params.turnNo), 1000) ?? 0);
        return problem(500, { code: 'INTERNAL_ERROR', status: 500 });
      }),
    );
    const { container } = renderRoute('/seasons/7/projection');
    await screen.findByRole('img', { name: /예상 분포 띠 그림/ });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expectNoDatePattern(container);
    expect(reviewGets).toBe(0);
    expect(turnGets.every((n) => n <= 3)).toBe(true);
  });

  it('a review fixture really carries the poisoned strings', () => {
    const text = JSON.stringify(reviewFixture());
    for (const value of poisonedReviewStrings) expect(text).toContain(value);
  });
});
