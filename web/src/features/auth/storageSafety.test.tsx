import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { accessToken, problem } from '../../test/authHandlers.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';

const TOKENS = ['test-access-1', 'test-access-2', 'test-access-3'];

// This is the only file allowed to touch storage and cookies: it asserts the
// app wrote nothing there.
function snapshot(): string {
  return [
    // eslint-disable-next-line no-restricted-properties -- test asserts the app wrote nothing here
    document.cookie,
    document.body.innerHTML,
    window.location.href,
    JSON.stringify(history.state),
  ].join('\n');
}

describe('storage safety', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps the access token out of storage, cookies, the DOM and the URL', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const openDb = vi.fn();
    vi.stubGlobal('indexedDB', { open: openDb });

    let refreshes = 0;
    server.use(
      http.post('*/api/v1/auth/refresh', () => {
        refreshes += 1;
        return accessToken(refreshes === 1 ? 'test-access-1' : 'test-access-2');
      }),
      // Only the second token passes, so the app restores, hits a 401,
      // refreshes, replays and then logs out.
      http.get('*/api/v1/pool', ({ request }) =>
        request.headers.get('authorization') === 'Bearer test-access-2'
          ? HttpResponse.json({ assets: [] })
          : problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
      ),
    );

    const snapshots: string[] = [snapshot()];
    renderRoute('/', { session: 'restoring', strict: true });
    await screen.findByText(/API 연결됨/);
    snapshots.push(snapshot());
    expect(refreshes).toBe(2);

    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '로그아웃' }));
    await screen.findByRole('heading', { name: '로그인' });
    snapshots.push(snapshot());

    expect(setItem).not.toHaveBeenCalled();
    expect(openDb).not.toHaveBeenCalled();
    // eslint-disable-next-line no-restricted-globals -- test asserts the app wrote nothing here
    expect(localStorage.length).toBe(0);
    // eslint-disable-next-line no-restricted-globals -- test asserts the app wrote nothing here
    expect(sessionStorage.length).toBe(0);
    for (const text of snapshots) {
      for (const token of TOKENS) expect(text).not.toContain(token);
    }
  });
});
