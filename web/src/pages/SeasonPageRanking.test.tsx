import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { feedOfTurn } from '../test/fixtures/feed.ts';
import { rankingFixture } from '../test/fixtures/ranking.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import { expandRanking } from '../test/expandRanking.ts';
import { serveTurnWorld } from '../test/serveTurns.ts';
import { settle } from '../test/settle.ts';

describe('ranking on the now page', () => {
  it('lists the current turn board after the feed and requests only that turn', async () => {
    const world = serveTurnWorld({ current: 5 });
    world.ranking = (turnNo) => rankingFixture(turnNo);
    world.feed = (turnNo) => [feedOfTurn(turnNo)[1]!];
    const { container } = renderRoute('/seasons/7');
    const region = await expandRanking();
    await within(region).findByText('종목 나');
    const feed = screen.getByRole('region', { name: '소식' });
    // The board comes after the feed in the page.
    expect(
      feed.compareDocumentPosition(region) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(world.rankingGets).toEqual([5]);
    expectNoDatePattern(container);
  });

  it('keeps the turn result and feed when the board fails, and retries on request', async () => {
    const world = serveTurnWorld({ current: 5 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    let answerOk = false;
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/turns/5/ranking', () => {
        gets += 1;
        return answerOk
          ? HttpResponse.json(rankingFixture(5))
          : problem(500, { code: 'INTERNAL_ERROR', status: 500 });
      }),
    );
    renderRoute('/seasons/7');
    const region = await expandRanking();
    const alert = await within(region).findByRole('alert');
    expect(alert).toBeInTheDocument();
    // Only the ranking section holds the error.
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('region', { name: '소식' })).toBeInTheDocument();
    expect(screen.getAllByText('104,820,000원')).not.toHaveLength(0);
    answerOk = true;
    const before = gets;
    await userEvent
      .setup()
      .click(within(alert).getByRole('button', { name: '다시 불러오기' }));
    await within(region).findByRole('tablist');
    expect(gets).toBeGreaterThan(before);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('requests no board while the turn result has failed', async () => {
    const world = serveTurnWorld({ current: 5 });
    server.use(
      http.get('*/api/v1/seasons/7/turns/5', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    const { queryClient } = renderRoute('/seasons/7');
    await screen.findByRole('alert');
    await settle(queryClient);
    expect(screen.queryByRole('region', { name: '순위' })).toBeNull();
    expect(world.rankingGets).toEqual([]);
  });

  it('requests no board at team build (turn 0)', async () => {
    const world = serveTurnWorld({ current: 0 });
    const { queryClient } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '시즌 7' });
    await settle(queryClient);
    expect(screen.queryByRole('region', { name: '순위' })).toBeNull();
    expect(world.rankingGets).toEqual([]);
  });

  it('requests no turn result and no board for a turn after the current one', async () => {
    const world = serveTurnWorld({ current: 3 });
    const { queryClient } = renderRoute('/seasons/7/turns/4');
    expect(
      await screen.findByText('아직 진행하지 않은 턴입니다.'),
    ).toBeInTheDocument();
    await settle(queryClient);
    expect(world.turnGets).toEqual([]);
    expect(world.rankingGets).toEqual([]);
  });

  it('fetches a played board once: turn 3, now, turn 3 again', async () => {
    const world = serveTurnWorld({ current: 5 });
    world.ranking = (turnNo) => rankingFixture(turnNo);
    const { router } = renderRoute('/seasons/7/turns/3');
    await screen.findByRole('tablist', { name: '순위 종류' });
    for (const path of ['/seasons/7', '/seasons/7/turns/3']) {
      await act(async () => {
        await router.navigate(path);
      });
      if (path === '/seasons/7') await expandRanking();
      await screen.findByRole('tablist', { name: '순위 종류' });
    }
    expect(world.rankingGets.filter((n) => n === 3)).toHaveLength(1);
    expect(world.rankingGets.filter((n) => n === 5)).toHaveLength(1);
    expect(world.rankingGets.every((n) => n <= 5)).toBe(true);
  });

  it('asks for the board of the new turn after an advance, never a later one', async () => {
    const world = serveTurnWorld({ current: 5 });
    world.ranking = (turnNo) => rankingFixture(turnNo);
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    await expandRanking();
    await screen.findByRole('tablist', { name: '순위 종류' });
    await user.click(screen.getByRole('button', { name: '6턴으로 넘기기' }));
    await screen.findByRole('heading', { name: '6턴 결과' });
    await waitFor(() => {
      expect(world.rankingGets).toContain(6);
    });
    expect(world.rankingGets.every((n) => n <= world.current)).toBe(true);
  });

  it.each(['ABANDONING', 'HALTED'] as const)(
    'shows the board read-only in a %s season',
    async (status) => {
      const world = serveTurnWorld({ current: 5 });
      world.status = status;
      world.ranking = (turnNo) => rankingFixture(turnNo);
      renderRoute('/seasons/7');
      const region = await expandRanking();
      await within(region).findByText('종목 나');
      expect(
        screen.queryByRole('button', { name: /매수|매도|턴으로 넘기기/ }),
      ).toBeNull();
    },
  );

  it('shows the last board of a finished season, read-only', async () => {
    const world = serveTurnWorld({ current: 12 });
    world.status = 'FINISHED';
    world.ranking = (turnNo) => rankingFixture(turnNo);
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '순위' });
    await within(region).findByText('종목 나');
    expect(world.rankingGets).toEqual([12]);
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    expectNoDatePattern(container);
  });
});
