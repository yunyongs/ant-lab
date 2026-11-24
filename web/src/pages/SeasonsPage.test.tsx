import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { server } from '../test/server.ts';

describe('SeasonsPage', () => {
  it('leads with the in-progress seasons, then the others, with server values', async () => {
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 9' });
    const headings = screen
      .getAllByRole('heading', { level: 2 })
      .map((h) => h.textContent);
    expect(headings).toEqual(['시즌 9', '시즌 7', '시즌 4']);

    const first = screen.getByRole('article', { name: '진행 중인 시즌 9' });
    expect(within(first).getByText('진행 중 · 3 / 24턴')).toBeInTheDocument();
    expect(within(first).getByText('-30.00%')).toBeInTheDocument();
    expect(within(first).getByText('62,300,000원')).toBeInTheDocument();
    expect(within(first).getByText('98,750,000원')).toBeInTheDocument();
    expect(within(first).getByText('-1,250,000원')).toBeInTheDocument();
    expect(within(first).getByText('(-1.25%)')).toBeInTheDocument();
    expect(await within(first).findByText('+2.02%')).toBeInTheDocument();
    expect(within(first).getByText('기준 대비')).toBeInTheDocument();
    expect(
      within(first).getByRole('link', { name: '4턴으로 이어하기' }),
    ).toHaveAttribute('href', '/seasons/9');

    const second = screen.getByRole('article', { name: '진행 중인 시즌 7' });
    expect(
      within(second).getByText('진행 중 · 팀 구성 · 0 / 12턴'),
    ).toBeInTheDocument();
    expect(within(second).getByText('-20.00%')).toBeInTheDocument();
    expect(within(second).getByText('100,000,000원')).toBeInTheDocument();
    expect(within(second).getByText('시작 전')).toBeInTheDocument();
    expect(within(second).queryByText('기준 대비')).toBeNull();
    expect(
      within(second).getByRole('link', { name: '팀 구성 이어하기' }),
    ).toHaveAttribute('href', '/seasons/7');

    const third = screen
      .getAllByRole('listitem')
      .find((c) => c.querySelector('h2')?.textContent === '시즌 4');
    if (!third) throw new Error('missing card');
    expect(within(third).getByText('끝남')).toBeInTheDocument();
    expect(
      within(third).getByRole('link', { name: '결과 보기' }),
    ).toHaveAttribute('href', '/seasons/4');

    const start = screen.getByRole('link', { name: '새 시즌 시작' });
    expect(start).toHaveAttribute('href', '/seasons/new');
    const text = document.body.textContent;
    const at = (needle: string) => text.indexOf(needle);
    expect(at('4턴으로 이어하기')).toBeLessThan(at('매니저 레이팅'));
    expect(at('매니저 레이팅')).toBeLessThan(at('새 시즌 시작'));
    expect(at('새 시즌 시작')).toBeLessThan(at('결과 보기'));
  });

  it('puts in-progress seasons first and keeps the server order inside each group', async () => {
    server.use(
      http.get('*/api/v1/seasons', () =>
        HttpResponse.json({
          items: [
            seasonFixture({ id: 4, status: 'FINISHED' }),
            seasonFixture({ id: 9, currentTurnNo: 3 }),
            seasonFixture({ id: 5, status: 'HALTED' }),
            seasonFixture({ id: 7 }),
          ],
        }),
      ),
    );
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 9' });
    const headings = screen
      .getAllByRole('heading', { level: 2 })
      .map((h) => h.textContent);
    expect(headings).toEqual(['시즌 9', '시즌 7', '시즌 4', '시즌 5']);
  });

  it('shows no featured card when nothing is in progress', async () => {
    server.use(
      http.get('*/api/v1/seasons', () =>
        HttpResponse.json({
          items: [seasonFixture({ id: 4, status: 'FINISHED' })],
        }),
      ),
    );
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 4' });
    expect(screen.queryByRole('article')).toBeNull();
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
              latestTotalValue: '99001000',
              totalReturn: '-0.00999000',
              feeRateKr: '0.00015000',
              feeRateUs: '0.00025000',
              fxSpreadRate: '0.00400000',
              abandonedTurnNo: 2,
              notStarted: false,
              leagueId: null,
            },
          ],
        }),
      ),
    );
    renderRoute('/');
    expect(await screen.findByText('정리 중')).toBeInTheDocument();
    expect(screen.getByText('99,001,000원')).toBeInTheDocument();
    expect(screen.getByText('-1.00%')).toBeInTheDocument();
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
