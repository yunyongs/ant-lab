import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { server } from '../test/server.ts';
import { APP_NAME } from './appName.ts';

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
      await within(header).findByText('시즌 7 · 3/12턴 · 진행 중'),
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
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '로그아웃' }));
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
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '로그아웃' }));
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
  });

  it('gives the logout button a 44 px tap target class and a name', () => {
    renderRoute('/');
    expect(
      screen.getByRole('button', { name: '로그아웃' }),
    ).toBeInTheDocument();
  });
});
