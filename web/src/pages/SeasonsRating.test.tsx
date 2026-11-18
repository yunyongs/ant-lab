import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { invalidateSeasonState } from '../features/seasons/queries.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { problem } from '../test/authHandlers.ts';
import { ratingFixture, ratingWithHistory } from '../test/fixtures/rating.ts';
import { scoreFixture } from '../test/fixtures/score.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import { serveReport } from '../test/serveReport.ts';
import { serveScore } from '../test/serveScore.ts';
import { settle } from '../test/settle.ts';

function card(): HTMLElement {
  const heading = screen.getByRole('heading', {
    level: 2,
    name: '매니저 레이팅',
  });
  const section = heading.closest('section');
  if (section === null) throw new Error('no rating section');
  return section;
}

describe('SeasonsPage rating card', () => {
  it('S8: prints the value, the count and the history in server order with links', async () => {
    const world = serveScore({ rating: ratingWithHistory });
    const { container } = renderRoute('/');
    await screen.findByText('61.25점');
    expect(within(card()).getByText('집계 시즌 4개')).toBeInTheDocument();
    const details = card().querySelector('details');
    expect(details?.open).toBe(false);
    const links = within(card()).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual([
      '시즌 2012 · 64.10점 · 레이팅 61.25',
      '시즌 1999 · 58.00점 · 레이팅 57.80',
      '시즌 7 · 52.30점 · 레이팅 —',
      '시즌 4 · 40.70점 · 레이팅 —',
    ]);
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/seasons/2012/report',
      '/seasons/1999/report',
      '/seasons/7/report',
      '/seasons/4/report',
    ]);
    expect(world.ratingGets).toBe(1);
    expectNoDatePattern(container);
  });

  it('S9: a missing rating says how many more are needed; zero counts are not printed', async () => {
    serveScore({
      rating: ratingFixture({
        countedSeasons: 0,
        seasonsUntilPublic: 2,
        notStartedCount: 1,
        noScoreCount: 2,
        pendingSeasons: 3,
      }),
    });
    renderRoute('/');
    expect(
      await screen.findByText(
        '집계 시즌이 3개가 되면 레이팅이 보입니다. 2개 더 필요합니다(지금 0개).',
      ),
    ).toBeInTheDocument();
    expect(
      within(card()).getByText('3턴 전에 포기해 뺀 시즌 1개'),
    ).toBeInTheDocument();
    expect(
      within(card()).getByText('경로 분포가 없어 뺀 시즌 2개'),
    ).toBeInTheDocument();
    expect(
      within(card()).getByText('엔진이 답하지 못해 아직 점수가 없는 시즌 3개'),
    ).toBeInTheDocument();
  });

  it('S9: zero counts print nothing', async () => {
    serveScore({
      rating: ratingFixture({ countedSeasons: 0, seasonsUntilPublic: 3 }),
    });
    renderRoute('/');
    await screen.findByText(/3개 더 필요합니다\(지금 0개\)/);
    expect(within(card()).queryByRole('list')).toBeNull();
  });

  it('S10: a null rating with no missing seasons is not invented; a value is shown even with one counted season', async () => {
    serveScore({
      rating: ratingFixture({ countedSeasons: 5, seasonsUntilPublic: 0 }),
    });
    const first = renderRoute('/');
    expect(
      await screen.findByText('레이팅이 아직 없습니다.'),
    ).toBeInTheDocument();
    expect(card().textContent).not.toMatch(/점/);
    first.unmount();
    serveScore({
      rating: ratingFixture({
        rating: '70.00',
        countedSeasons: 1,
        seasonsUntilPublic: 0,
      }),
    });
    renderRoute('/');
    expect(await screen.findByText('70.00점')).toBeInTheDocument();
    expect(screen.getByText('집계 시즌 1개')).toBeInTheDocument();
  });

  it('S11: an empty season list sends no rating request and shows no card', async () => {
    let ratingGets = 0;
    server.use(
      http.get('*/api/v1/seasons', () => HttpResponse.json({ items: [] })),
      http.get('*/api/v1/members/me/rating', () => {
        ratingGets += 1;
        return HttpResponse.json(ratingFixture());
      }),
    );
    const { queryClient } = renderRoute('/');
    await screen.findByText('아직 시즌이 없습니다');
    await settle(queryClient);
    expect(screen.queryByRole('heading', { name: '매니저 레이팅' })).toBeNull();
    expect(ratingGets).toBe(0);
  });

  it('S11: a rating failure leaves one local line with a retry and the season cards', async () => {
    let calls = 0;
    const world = serveScore({
      rating: () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(ratingWithHistory);
      },
    });
    const user = userEvent.setup();
    renderRoute('/');
    expect(
      await within(
        await screen.findByRole('region', { name: '매니저 레이팅' }),
      ).findByText('레이팅을 지금 불러오지 못했습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '시즌 9' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    await user.click(
      within(card()).getByRole('button', { name: '다시 불러오기' }),
    );
    await screen.findByText('61.25점');
    expect(world.ratingGets).toBe(2);
  });

  it('S11: a network error is also a local line', async () => {
    serveScore({ rating: () => HttpResponse.error() });
    renderRoute('/');
    expect(
      await screen.findByText('레이팅을 지금 불러오지 못했습니다.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '시즌 9' })).toBeInTheDocument();
  });

  it('S12: one rating GET on the list; leaving and returning sends none', async () => {
    const world = serveScore({ rating: ratingWithHistory });
    const { router, queryClient } = renderRoute('/');
    await screen.findByText('61.25점');
    await router.navigate('/seasons/new');
    await settle(queryClient);
    await router.navigate('/');
    await screen.findByText('61.25점');
    await settle(queryClient);
    expect(world.ratingGets).toBe(1);
  });

  async function go(
    router: { navigate: (to: string) => Promise<void> },
    to: string,
  ) {
    await act(async () => {
      await router.navigate(to);
    });
  }

  it('S12: a score success makes the next visit send exactly one', async () => {
    serveReport();
    const world = serveScore({
      rating: ratingWithHistory,
      score: scoreFixture(),
    });
    const { router, queryClient } = renderRoute('/');
    await screen.findByText('61.25점');
    expect(world.ratingGets).toBe(1);
    await go(router, '/seasons/7/report');
    await screen.findByText('58.30점');
    await settle(queryClient);
    expect(world.ratingGets).toBe(1);
    await go(router, '/');
    await screen.findByText('61.25점');
    await waitFor(() => {
      expect(world.ratingGets).toBe(2);
    });
  });

  it('S12: a season-state change sends none while the card is not mounted, and one on the next visit', async () => {
    const world = serveScore({ rating: ratingWithHistory });
    const { router, queryClient } = renderRoute('/');
    await screen.findByText('61.25점');
    await go(router, '/seasons/new');
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { name: '매니저 레이팅' }),
      ).toBeNull();
    });
    await settle(queryClient);
    await invalidateSeasonState(queryClient, 7);
    await settle(queryClient);
    expect(world.ratingGets).toBe(1);
    await go(router, '/');
    await screen.findByText('61.25점');
    await waitFor(() => {
      expect(world.ratingGets).toBe(2);
    });
  });

  it('S9: pendingSeasons, noScoreCount and notStartedCount of 1 are printed; the pending line offers a reload', async () => {
    let calls = 0;
    const world = serveScore({
      rating: () => {
        calls += 1;
        return HttpResponse.json(
          calls === 1
            ? ratingFixture({
                countedSeasons: 0,
                seasonsUntilPublic: 3,
                notStartedCount: 1,
                noScoreCount: 1,
                pendingSeasons: 1,
              })
            : ratingFixture({ countedSeasons: 1, seasonsUntilPublic: 2 }),
        );
      },
    });
    const user = userEvent.setup();
    renderRoute('/');
    await screen.findByText('엔진이 답하지 못해 아직 점수가 없는 시즌 1개');
    expect(screen.getByText('3턴 전에 포기해 뺀 시즌 1개')).toBeInTheDocument();
    expect(
      screen.getByText('경로 분포가 없어 뺀 시즌 1개'),
    ).toBeInTheDocument();
    await user.click(
      within(card()).getByRole('button', { name: '다시 불러오기' }),
    );
    await screen.findByText(/2개 더 필요합니다\(지금 1개\)/);
    expect(world.ratingGets).toBe(2);
    expect(
      within(card()).queryByRole('button', { name: '다시 불러오기' }),
    ).toBeNull();
  });

  it('S14: no date pattern with season ids that look like years', async () => {
    serveScore({ rating: ratingWithHistory });
    const { container } = renderRoute('/');
    await screen.findByText('61.25점');
    expectNoDatePattern(container);
  });
});
