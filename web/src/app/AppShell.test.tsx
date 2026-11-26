import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { server } from '../test/server.ts';
import { serveReport } from '../test/serveReport.ts';
import { serveReview } from '../test/serveReview.ts';
import { failWithProblem, serveTurnWorld } from '../test/serveTurns.ts';
import { APP_NAME } from './appName.ts';
import styles from './AppShell.module.css';

describe('AppShell', () => {
  it('renders header, main and the labelled bottom navigation', async () => {
    renderRoute('/');
    expect(
      within(screen.getByRole('banner')).getByText(APP_NAME),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('main')).toHaveLength(1);
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '시즌' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await screen.findByText(/API 연결됨/);
  });

  it('says why the menu items are disabled outside a season', () => {
    renderRoute('/');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByText('시즌을 열면 쓸 수 있습니다')).toBeVisible();
    expect(within(nav).getByText('매매')).toHaveAttribute(
      'aria-describedby',
      'nav-disabled-reason',
    );
  });

  it('shows no disabled reason inside a season', async () => {
    serveTurnWorld({ current: 4 });
    renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    expect(screen.queryByText('시즌을 열면 쓸 수 있습니다')).toBeNull();
  });

  it('applies the wide content class only to turn and trade screens', async () => {
    serveTurnWorld({ current: 4 });
    const rendered = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    expect(
      rendered.container.querySelector(`.${styles.wide}`),
    ).toBeInTheDocument();
    expect(
      rendered.container
        .querySelector('header')
        ?.querySelector(`.${styles.wide}`),
    ).toBeInTheDocument();

    rendered.unmount();
    const trade = renderRoute('/seasons/7/trade');
    await screen.findByRole('heading', { name: '4턴 매매' });
    expect(
      trade.container.querySelector(`.${styles.wide}`),
    ).toBeInTheDocument();

    trade.unmount();
    const report = renderRoute('/seasons/7/report');
    expect(report.container.querySelector(`.${styles.wide}`)).toBeNull();
    expect(
      report.container
        .querySelector('header')
        ?.querySelector(`.${styles.wide}`),
    ).toBeNull();
  });

  it('shows five menu items: season active, the rest disabled outside a season', () => {
    renderRoute('/');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getAllByRole('listitem')).toHaveLength(5);
    expect(within(nav).getByRole('link', { name: '시즌' })).toHaveAttribute(
      'href',
      '/',
    );
    for (const label of ['턴', '매매', '성적표', '복기']) {
      expect(within(nav).queryByRole('link', { name: label })).toBeNull();
      expect(within(nav).getByText(label)).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    }
  });

  it('keeps the season item active on the new-season form', () => {
    renderRoute('/seasons/new');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '시즌' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('inside a season: turn item links and is active, header shows the season line from one request', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        calls += 1;
        return HttpResponse.json(seasonFixture({ id: 7, currentTurnNo: 3 }));
      }),
    );
    renderRoute('/seasons/7');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '턴' })).toHaveAttribute(
      'href',
      '/seasons/7',
    );
    expect(within(nav).getByRole('link', { name: '턴' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '시즌' })).not.toHaveAttribute(
      'aria-current',
    );
    const header = screen.getByRole('banner');
    expect(
      await within(header).findByText('3/12턴 · 포메이션 40·30·20·10'),
    ).toBeInTheDocument();
    await screen.findByRole('heading', { name: '시즌 7' });
    await screen.findByText(/API 연결됨/);
    expect(calls).toBe(1);
  });

  it('logs out: calls the API, clears cached data and shows the login screen', async () => {
    let logoutCalls = 0;
    server.use(
      http.post('*/api/v1/auth/logout', () => {
        logoutCalls += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { queryClient } = renderRoute('/');
    await screen.findByText(/API 연결됨/);
    queryClient.setQueryData(['protected-data'], { balance: 'x' });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '메뉴' }));
    await user.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(logoutCalls).toBe(1);
    await waitFor(() => {
      expect(queryClient.getQueryData(['protected-data'])).toBeUndefined();
    });
  });

  it('still logs out locally when the logout request fails', async () => {
    server.use(http.post('*/api/v1/auth/logout', () => HttpResponse.error()));
    renderRoute('/');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '메뉴' }));
    await user.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
  });

  it('keeps the turn item active on a past turn', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/turns/3');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '턴' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await screen.findByRole('heading', { name: '3턴 결과' });
  });

  it('opens the trade item inside a season and marks it active there', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/trade');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '매매' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '턴' })).not.toHaveAttribute(
      'aria-current',
    );
    await screen.findByRole('heading', { name: '5턴 매매' });
  });

  it('keeps the trade item active on the formation page', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/formation');
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '매매' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await screen.findByRole('heading', { name: '5턴 전술 변경' });
  });

  it('links the report item inside a season and marks it active only on the report page', async () => {
    serveReport();
    const first = renderRoute('/seasons/7/trade');
    let nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '성적표' })).toHaveAttribute(
      'href',
      '/seasons/7/report',
    );
    expect(
      within(nav).getByRole('link', { name: '성적표' }),
    ).not.toHaveAttribute('aria-current');
    first.unmount();
    renderRoute('/seasons/7/report');
    nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '성적표' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '턴' })).not.toHaveAttribute(
      'aria-current',
    );
    await screen.findByRole('heading', { name: '시즌 7 성적표 (확정)' });
  });

  it('links the review item inside a season and marks it active only on the review page', async () => {
    serveReview();
    const first = renderRoute('/seasons/7/trade');
    let nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '복기' })).toHaveAttribute(
      'href',
      '/seasons/7/review',
    );
    expect(within(nav).getByRole('link', { name: '복기' })).not.toHaveAttribute(
      'aria-current',
    );
    first.unmount();
    renderRoute('/seasons/7/review');
    nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '복기' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(
      within(nav).getByRole('link', { name: '성적표' }),
    ).not.toHaveAttribute('aria-current');
    await screen.findByRole('heading', { name: '시즌 7 복기' });
  });

  it('drops a page notice when the player moves to another season', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.failWith = failWithProblem(409, 'TURN_MISMATCH');
    server.use(
      http.get('*/api/v1/seasons/8', () =>
        HttpResponse.json(seasonFixture({ id: 8, currentTurnNo: 1 })),
      ),
    );
    const user = userEvent.setup();
    const { router } = renderRoute('/seasons/7');
    await user.click(
      await screen.findByRole('button', { name: '5턴으로 넘기기' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
    await act(async () => {
      await router.navigate('/seasons/8');
    });
    await screen.findByRole('heading', { name: '1턴 결과' });
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
