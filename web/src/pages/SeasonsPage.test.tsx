import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

describe('SeasonsPage', () => {
  it('lists the seasons in server order with server values', async () => {
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 9' });
    const cards = screen.getAllByRole('listitem');
    const seasonCards = cards.filter((c) => c.querySelector('h2'));
    expect(
      seasonCards.map((c) => within(c).getByRole('heading').textContent),
    ).toEqual(['시즌 9', '시즌 7', '시즌 4']);

    const first = seasonCards[0];
    if (!first) throw new Error('missing card');
    expect(within(first).getByText('진행 중')).toBeInTheDocument();
    expect(within(first).getByText('3 / 24턴')).toBeInTheDocument();
    expect(within(first).getByText('-0.30000000 (-30%)')).toBeInTheDocument();
    expect(within(first).getByText('62,300,000원')).toBeInTheDocument();
    expect(
      within(first).getByRole('link', { name: '이어하기' }),
    ).toHaveAttribute('href', '/seasons/9');

    const second = seasonCards[1];
    if (!second) throw new Error('missing card');
    expect(within(second).getByText('진행 중 · 팀 구성')).toBeInTheDocument();
    expect(within(second).getByText('-0.20000000 (-20%)')).toBeInTheDocument();
    expect(within(second).getByText('100,000,000원')).toBeInTheDocument();
    expect(
      within(second).getByRole('link', { name: '팀 구성하러 가기' }),
    ).toHaveAttribute('href', '/seasons/7');

    const third = seasonCards[2];
    if (!third) throw new Error('missing card');
    expect(within(third).getByText('끝남')).toBeInTheDocument();
    expect(
      within(third).getByRole('link', { name: '결과 보기' }),
    ).toHaveAttribute('href', '/seasons/4');

    expect(screen.getByRole('link', { name: '새 시즌 시작' })).toHaveAttribute(
      'href',
      '/seasons/new',
    );
  });

  it('labels an abandoning season', async () => {
    server.use(
      http.get('*/api/v1/seasons', () =>
        HttpResponse.json({
          items: [
            {
              id: 3,
              status: 'ABANDONING',
              currentTurnNo: 2,
              pathLength: 12,
              riskLimit: '-0.10000000',
              startFormation: {
                goalkeeper: 25,
                defender: 25,
                midfielder: 25,
                forward: 25,
              },
              currentFormation: {
                goalkeeper: 25,
                defender: 25,
                midfielder: 25,
                forward: 25,
              },
              initialCash: '100000000',
              cashBalance: '1000',
              feeRateKr: '0.00015000',
              feeRateUs: '0.00025000',
              fxSpreadRate: '0.00400000',
            },
          ],
        }),
      ),
    );
    renderRoute('/');
    expect(await screen.findByText('정리 중')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '시즌 보기' })).toBeInTheDocument();
  });

  it('shows the empty state with a first-season link', async () => {
    server.use(
      http.get('*/api/v1/seasons', () => HttpResponse.json({ items: [] })),
    );
    renderRoute('/');
    expect(await screen.findByText('아직 시즌이 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '첫 시즌 시작' })).toHaveAttribute(
      'href',
      '/seasons/new',
    );
  });

  it('shows a loading status first', async () => {
    renderRoute('/');
    expect(screen.getByText('시즌을 불러오는 중입니다…')).toBeInTheDocument();
    await screen.findByText('시즌 9');
  });

  it('shows the catalog text for a Problem', async () => {
    server.use(
      http.get('*/api/v1/seasons', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    renderRoute('/');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.',
    );
  });

  it('shows the unreachable text and refetches on retry', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons', () => {
        calls += 1;
        return HttpResponse.error();
      }),
    );
    renderRoute('/');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.',
    );
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '다시 불러오기' }));
    await screen.findByRole('alert');
    expect(calls).toBe(2);
  });

  it('goes to the login screen on a 401 without an alert on the page', async () => {
    server.use(
      http.get('*/api/v1/seasons', () =>
        problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
      ),
    );
    renderRoute('/');
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
