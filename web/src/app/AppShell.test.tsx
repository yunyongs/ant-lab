import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
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
    expect(within(nav).getByRole('link', { name: '홈' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await screen.findByText(/API 연결됨/);
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
