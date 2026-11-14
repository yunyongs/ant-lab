import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import {
  DATE_PATTERN,
  expectNoDatePattern,
} from '../test/expectNoDatePattern.ts';
import { formationChangeFixture } from '../test/fixtures/formationChanges.ts';
import {
  poisonedReviewStrings,
  reviewEvent,
  reviewFixture,
  reviewTurn,
} from '../test/fixtures/review.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { turnResultFixture } from '../test/fixtures/turns.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import { reviewNotFinished409, serveReview } from '../test/serveReview.ts';
import { turnKey } from '../features/turn/queries.ts';

function rows(): HTMLElement[] {
  const heading = screen.getByRole('heading', { level: 2, name: '턴별 복기' });
  const section = heading.closest('section');
  if (section === null) throw new Error('no turn section');
  return within(section)
    .getAllByRole('listitem')
    .filter((li) => li.tagName === 'LI' && li.parentElement?.tagName === 'OL');
}

describe('ReviewPage', () => {
  it('shows the reproducibility triple verbatim, the turns in server order and no extra fields', async () => {
    serveReview({
      review: reviewFixture({
        turns: [
          reviewTurn(1, {
            events: [
              reviewEvent({
                hindsight: 'Invented hindsight',
                source: 'https://a.test ; https://b.test',
              }),
            ],
          }),
          reviewTurn(2, { eventSummary: null }),
          ...Array.from({ length: 10 }, (_, i) => reviewTurn(i + 3)),
        ],
      }),
    });
    const { container } = renderRoute('/seasons/7/review');
    expect(
      await screen.findByRole('heading', { name: '시즌 7 복기' }),
    ).toBeInTheDocument();
    const repro = screen
      .getByRole('heading', { name: '재현 정보' })
      .closest('section') as HTMLElement;
    expect(within(repro).getByText('-8123456789012345678')).toBeInTheDocument();
    expect(within(repro).getByText('engine-9.9.9')).toBeInTheDocument();
    expect(within(repro).getByText('2026-11-12')).toBeInTheDocument();
    expect(
      within(repro).getByText(
        '시드·엔진 버전·데이터 기준일이 같으면 같은 경로가 다시 만들어집니다.',
      ),
    ).toBeInTheDocument();
    const list = rows();
    expect(list).toHaveLength(12);
    expect(list[0]).toHaveTextContent('1턴');
    expect(list[0]).toHaveTextContent('2001-01');
    expect(list[0]).toHaveTextContent('Invented summary 1');
    expect(list[0]).toHaveTextContent('금융 · 시작');
    expect(list[0]).toHaveTextContent('Invented bank run');
    expect(list[0]).toHaveTextContent('사후: Invented hindsight');
    expect(list[1]).toHaveTextContent('2001-02');
    expect(list[1]).toHaveTextContent('한 줄 요약이 없는 달입니다');
    expect(within(list[1] as HTMLElement).queryByText(/사후/)).toBeNull();
    const links = within(list[0] as HTMLElement).getAllByRole('link');
    expect(links.map((a) => a.textContent)).toEqual(['출처 1', '출처 2']);
    for (const a of links) {
      expect(a).toHaveAttribute('target', '_blank');
      expect(a).toHaveAttribute('rel', 'noopener noreferrer');
    }
    expect(links[0]).toHaveAttribute('href', 'https://a.test');
    // The finished review is the one place with real dates.
    expect(container.textContent).toMatch(DATE_PATTERN);
    // Whitelisted fields only: the event code never reaches the screen.
    expect(container.innerHTML).not.toContain('EVENT_TYPE_CODE_X');
  });

  it('shows a single source link as plain "출처" and any other part as text', async () => {
    serveReview({
      review: reviewFixture({
        turns: [
          reviewTurn(1, {
            events: [
              reviewEvent({ source: 'https://a.test' }),
              reviewEvent({ title: 'Second', source: 'javascript:alert(1)' }),
            ],
          }),
          ...Array.from({ length: 11 }, (_, i) => reviewTurn(i + 2)),
        ],
      }),
    });
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    const first = rows()[0] as HTMLElement;
    expect(within(first).getAllByRole('link')).toHaveLength(1);
    expect(within(first).getByRole('link')).toHaveTextContent(/^출처$/);
    expect(first).toHaveTextContent('출처: javascript:alert(1)');
    expect(first.innerHTML).not.toContain('href="javascript');
  });

  it('shows each turn value from the server as text and no chart', async () => {
    serveReview();
    const { container } = renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(rows()[11]).toHaveTextContent('총 평가액 100,000,012원');
    });
    expect(rows()[0]).toHaveTextContent(
      '총 평가액 100,000,001원 · 턴 수익률 0.01000000 (1%)',
    );
    expect(container.querySelector('svg, canvas')).toBeNull();
  });

  it('lists formation changes with the reason as stored, or "사유 없음"', async () => {
    serveReview({
      review: reviewFixture({
        formationChanges: [
          formationChangeFixture({ turnNo: 4, reason: '  spaced reason  ' }),
          formationChangeFixture({ turnNo: 6, reason: null }),
        ],
      }),
    });
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    const section = screen
      .getByRole('heading', { name: '전술 변경 기록' })
      .closest('section') as HTMLElement;
    const items = within(section).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent(
      '4턴 골키퍼 40 · 수비수 30 · 미드필더 20 · 공격수 10 → 골키퍼 30 · 수비수 30 · 미드필더 25 · 공격수 15',
    );
    expect(items[0]?.textContent).toContain('사유:   spaced reason  ');
    expect(items[1]).toHaveTextContent('사유 없음');
  });

  it('says so when the formation was never changed, and shows the abandoned turn only when given', async () => {
    serveReview();
    const first = renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    expect(
      screen.getByText('이 시즌에는 전술을 바꾸지 않았습니다.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/에서 포기한 시즌입니다/)).toBeNull();
    first.unmount();
    serveReview({ review: reviewFixture({ abandonedTurnNo: 8 }) });
    renderRoute('/seasons/7/review');
    expect(
      await screen.findByText('8턴에서 포기한 시즌입니다.'),
    ).toBeInTheDocument();
  });

  it('requests each played turn exactly once, and none again after leaving and returning', async () => {
    const world = serveReview();
    const { router } = renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(world.turnGets).toHaveLength(12);
    });
    expect([...world.turnGets].sort((a, b) => a - b)).toEqual(
      Array.from({ length: 12 }, (_, i) => i + 1),
    );
    await act(async () => {
      await router.navigate('/seasons/7/report');
    });
    await act(async () => {
      await router.navigate('/seasons/7/review');
    });
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    expect(world.turnGets).toHaveLength(12);
    expect(world.reviewGets).toBe(1);
  });

  it('does not request turns that the shared cache already holds', async () => {
    const world = serveReview();
    const { queryClient } = renderRoute('/seasons/7/review');
    for (const n of [11, 12]) {
      queryClient.setQueryData(
        turnKey(7, n),
        turnResultFixture({ turnNo: n, totalValue: '555555555' }),
      );
    }
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(world.turnGets).toHaveLength(10);
    });
    expect(world.turnGets.every((n) => n <= 10)).toBe(true);
    expect(rows()[11]).toHaveTextContent('총 평가액 555,555,555원');
  });

  it('keeps the review when one turn fails and retries only that turn', async () => {
    const world = serveReview();
    world.failingTurns.add(5);
    const user = userEvent.setup();
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(rows()[4]).toHaveTextContent('값을 불러오지 못했습니다');
    });
    expect(rows()[3]).toHaveTextContent('총 평가액 100,000,004원');
    expect(screen.queryByRole('alert')).toBeNull();
    world.failingTurns.clear();
    const before = world.turnGets.length;
    await user.click(
      screen.getByRole('button', { name: '못 불러온 턴 다시 불러오기' }),
    );
    await waitFor(() => {
      expect(rows()[4]).toHaveTextContent('총 평가액 100,000,005원');
    });
    expect(world.turnGets.slice(before)).toEqual([5]);
    expect(
      screen.queryByRole('button', { name: '못 불러온 턴 다시 불러오기' }),
    ).toBeNull();
  });

  it('asks only for turns up to the current turn and marks the rest as not played', async () => {
    const world = serveReview({ season: { currentTurnNo: 8 } });
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(world.turnGets).toHaveLength(8);
    });
    expect(Math.max(...world.turnGets)).toBe(8);
    for (const n of [9, 10, 11, 12]) {
      expect(rows()[n - 1]).toHaveTextContent('진행하지 않은 턴');
    }
  });

  it('handles a 60-turn path with one request per turn', async () => {
    const world = serveReview({
      season: { pathLength: 60, currentTurnNo: 60 },
      review: reviewFixture({ pathLength: 60 }),
    });
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    await waitFor(() => {
      expect(world.turnGets).toHaveLength(60);
    });
    expect(new Set(world.turnGets).size).toBe(60);
    expect(rows()).toHaveLength(60);
  });

  it.each([
    ['IN_PROGRESS', '복기에는 각 턴의 실제 연월과 사건이 나옵니다.'],
    ['ABANDONING', '정리 중인 시즌이라 아직 복기를 볼 수 없습니다.'],
  ] as const)(
    'shows the pending view for %s with no review and no turn request',
    async (status, text) => {
      const world = serveReview({ season: { status, currentTurnNo: 4 } });
      const { container } = renderRoute('/seasons/7/review');
      expect(
        (await screen.findByText(new RegExp(text))).closest('[role="status"]'),
      ).not.toBeNull();
      expect(screen.queryByRole('alert')).toBeNull();
      expectNoDatePattern(container);
      for (const leaked of poisonedReviewStrings) {
        expect(container.innerHTML).not.toContain(leaked);
      }
      expect(
        screen.getByRole('link', { name: '턴으로 돌아가기' }),
      ).toHaveAttribute('href', '/seasons/7');
      await new Promise((resolve) => setTimeout(resolve, 30));
      expect(world.reviewGets).toBe(0);
      expect(world.turnGets).toEqual([]);
    },
  );

  it('shows the pending view when a stale FINISHED cache meets a 409, and refetches the season', async () => {
    let seasonGets = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        seasonGets += 1;
        return HttpResponse.json(
          seasonGets === 1
            ? seasonFixture({ id: 7, status: 'FINISHED', currentTurnNo: 12 })
            : seasonFixture({ id: 7, status: 'IN_PROGRESS', currentTurnNo: 4 }),
        );
      }),
      http.get('*/api/v1/seasons/7/review', () => reviewNotFinished409()),
    );
    renderRoute('/seasons/7/review');
    expect(
      await screen.findByText('시즌이 끝나면 복기가 열립니다'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    await waitFor(() => {
      expect(seasonGets).toBe(2);
    });
  });

  it('shows a Problem as a catalog message and retries with one more GET', async () => {
    let calls = 0;
    serveReview({
      review: () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(reviewFixture());
      },
    });
    const user = userEvent.setup();
    renderRoute('/seasons/7/review');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    expect(calls).toBe(2);
  });

  it('answers a 404 and a network error with their messages', async () => {
    serveReview({
      review: () => problem(404, { code: 'NOT_FOUND', status: 404 }),
    });
    const first = renderRoute('/seasons/7/review');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '찾는 항목이 없습니다.',
    );
    first.unmount();
    serveReview({ review: () => HttpResponse.error() });
    renderRoute('/seasons/7/review');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다.',
    );
  });

  it('is not found, with no request, for a route id that is not a number', () => {
    let requests = 0;
    server.use(
      http.get('*/api/v1/seasons/*', () => {
        requests += 1;
        return HttpResponse.json(seasonFixture());
      }),
    );
    renderRoute('/seasons/x/review');
    expect(
      screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument();
    expect(requests).toBe(0);
  });

  it('links to the report and back to the turn', async () => {
    serveReview();
    renderRoute('/seasons/7/review');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
    expect(screen.getByRole('link', { name: '성적표 보기' })).toHaveAttribute(
      'href',
      '/seasons/7/report',
    );
    expect(
      screen.getAllByRole('link', { name: '턴으로 돌아가기' })[0],
    ).toHaveAttribute('href', '/seasons/7');
  });
});
