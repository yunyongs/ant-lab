import { screen } from '@testing-library/react';
import { http, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { accessToken, problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

describe('router', () => {
  it('shows a not-found view inside the shell for unknown paths', () => {
    renderRoute('/no-such-page');
    expect(
      screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '홈으로 돌아가기' }),
    ).toHaveAttribute('href', '/');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(
      screen.getByRole('navigation', { name: '주 메뉴' }),
    ).toBeInTheDocument();
  });

  it('redirects a logged-out visit to the login screen', async () => {
    renderRoute('/', { session: 'anonymous' });
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: '주 메뉴' })).toBeNull();
  });

  it.each(['/login', '/signup'])(
    'sends a logged-in visit to %s back to the home page',
    async (path) => {
      renderRoute(path);
      expect(
        await screen.findByRole('heading', { name: '홈' }),
      ).toBeInTheDocument();
    },
  );

  describe('session restore', () => {
    function restoreCounters(delayMs = 0) {
      const c = { refresh: 0, pool: 0 };
      server.use(
        http.post('*/api/v1/auth/refresh', async () => {
          c.refresh += 1;
          if (delayMs) await delay(delayMs);
          return accessToken('test-access-1');
        }),
        http.get('*/api/v1/pool', () => {
          c.pool += 1;
          return Response.json({ assets: [] });
        }),
      );
      return c;
    }

    it('waits with a status line and makes no other call meanwhile', async () => {
      const c = restoreCounters(80);
      renderRoute('/', { session: 'restoring' });
      expect(screen.getByRole('status')).toHaveTextContent(
        '로그인 상태를 확인하는 중입니다',
      );
      await delay(20);
      expect(c.pool).toBe(0);
      expect(
        await screen.findByRole('heading', { name: '홈' }),
      ).toBeInTheDocument();
      expect(c.refresh).toBe(1);
    });

    it('sends exactly one refresh under StrictMode and shows the app', async () => {
      const c = restoreCounters();
      renderRoute('/', { session: 'restoring', strict: true });
      expect(
        await screen.findByRole('heading', { name: '홈' }),
      ).toBeInTheDocument();
      expect(c.refresh).toBe(1);
    });

    it('lands on the login screen when the refresh is rejected', async () => {
      renderRoute('/', { session: 'restoring', strict: true });
      expect(
        await screen.findByRole('heading', { name: '로그인' }),
      ).toBeInTheDocument();
      expect(
        screen.queryByText('로그인이 끝났습니다. 다시 로그인해 주세요.'),
      ).toBeNull();
    });

    it('lands on the login screen when the API is unreachable', async () => {
      server.use(http.post('*/api/v1/auth/refresh', () => Response.error()));
      renderRoute('/', { session: 'restoring' });
      expect(
        await screen.findByRole('heading', { name: '로그인' }),
      ).toBeInTheDocument();
    });
  });

  it('sends the visitor to the login screen after a second 401', async () => {
    server.use(
      http.post('*/api/v1/auth/refresh', () => accessToken('test-access-2')),
      http.get('*/api/v1/pool', () =>
        problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
      ),
    );
    renderRoute('/');
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('로그인이 끝났습니다. 다시 로그인해 주세요.'),
    ).toBeInTheDocument();
  });

  it('logs out when another tab announces a logout', async () => {
    renderRoute('/');
    await screen.findByRole('heading', { name: '홈' });
    const other = new BroadcastChannel('antlab-auth');
    other.postMessage({ type: 'logout' });
    other.close();
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('다른 창에서 로그아웃했습니다.'),
    ).toBeInTheDocument();
  });
});
