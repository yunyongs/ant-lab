import { act, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  DATE_PATTERN,
  expectNoDatePattern,
} from '../test/expectNoDatePattern.ts';
import {
  feedOfReviewTurn,
  poisonedFeedStrings,
  poisonedReviewStrings,
  reviewFixture,
  reviewTurn,
} from '../test/fixtures/review.ts';
import { projectionFixture } from '../test/fixtures/projection.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from '../test/authHandlers.ts';
import { feedOfTurn } from '../test/fixtures/feed.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { rankingFixture } from '../test/fixtures/ranking.ts';
import { serveFeedWorld } from '../test/serveFeed.ts';
import { server } from '../test/server.ts';
import { serveAnalysis } from '../test/serveAnalysis.ts';
import { serveReport } from '../test/serveReport.ts';
import { serveProjection } from '../test/serveProjection.ts';
import { serveReview } from '../test/serveReview.ts';
import { serveTurnWorld } from '../test/serveTurns.ts';
import { settle } from '../test/settle.ts';

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

  it('shows the analysis blocks only on the report and the review, never on a season screen', async () => {
    serveReview();
    serveReport();
    serveAnalysis();
    const { router, container } = renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { level: 2, name: '소음 반응' });
    for (const path of [
      '/seasons/7',
      '/seasons/7/turns/3',
      '/seasons/7/trade',
    ]) {
      await act(async () => {
        await router.navigate(path);
      });
      await new Promise((resolve) => setTimeout(resolve, 60));
      for (const title of [
        '소음 반응',
        '플랫폼의 수익',
        '관심 추종',
        '수비수 득점',
      ]) {
        expect(container.textContent).not.toContain(title);
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

  it('keeps a non-empty ranking board free of dates and of any later-turn request', async () => {
    const world = serveTurnWorld({ current: 3 });
    world.ranking = (turnNo) => rankingFixture(turnNo);
    const { router, container, queryClient } = renderRoute('/seasons/7');
    await screen.findByRole('region', { name: '순위' });
    await screen.findByText('종목 나');
    expectNoDatePattern(container);
    for (const path of ['/seasons/7/turns/2', '/seasons/7/turns/4']) {
      await act(async () => {
        await router.navigate(path);
      });
      await settle(queryClient);
      expectNoDatePattern(container);
    }
    expect(world.rankingGets).toContain(3);
    expect(world.rankingGets.every((n) => n <= 3)).toBe(true);
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

  it('R10: keeps the review feed strings off every other screen after the review was cached', async () => {
    serveReview({
      review: reviewFixture({
        turns: [
          reviewTurn(1, { feed: feedOfReviewTurn() }),
          ...Array.from({ length: 11 }, (_, i) => reviewTurn(i + 2)),
        ],
      }),
    });
    serveReport();
    server.use(
      http.get('*/api/v1/seasons/7/projection', () =>
        HttpResponse.json(projectionFixture()),
      ),
    );
    const { router, container } = renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    for (const shown of poisonedFeedStrings) {
      expect(container.innerHTML).toContain(shown);
    }
    for (const path of [
      '/seasons/7',
      '/seasons/7/turns/3',
      '/seasons/7/trade',
      '/seasons/7/formation',
      '/seasons/7/projection',
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
      await new Promise((resolve) => setTimeout(resolve, 60));
      for (const leaked of poisonedFeedStrings) {
        expect(container.innerHTML).not.toContain(leaked);
      }
    }
  });

  it('a review fixture really carries the poisoned strings', () => {
    const text = JSON.stringify(reviewFixture());
    for (const value of poisonedReviewStrings) expect(text).toContain(value);
  });
});
