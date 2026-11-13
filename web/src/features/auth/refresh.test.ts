import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { accessToken } from '../../test/authHandlers.ts';
import { server } from '../../test/server.ts';
import { refreshSession, restoreSession } from './refresh.ts';
import { withAuthLock } from './authLock.ts';
import {
  clearSession,
  getAccessToken,
  getNotice,
  getStatus,
  setSession,
} from './session.ts';

function countRefresh(respond: () => Response | Promise<Response>) {
  const counter = { calls: 0 };
  server.use(
    http.post('*/api/v1/auth/refresh', () => {
      counter.calls += 1;
      return respond();
    }),
  );
  return counter;
}

describe('refreshSession', () => {
  it('shares one request between concurrent callers', async () => {
    const counter = countRefresh(async () => {
      await delay(20);
      return accessToken('test-access-2');
    });
    setSession('test-access-1');
    const results = await Promise.all([
      refreshSession(),
      refreshSession(),
      refreshSession(),
    ]);
    expect(results).toEqual([true, true, true]);
    expect(counter.calls).toBe(1);
    expect(getAccessToken()).toBe('test-access-2');
    expect(getStatus()).toBe('authenticated');
  });

  it('starts a new request after the previous one settled', async () => {
    const counter = countRefresh(() => accessToken('test-access-2'));
    await refreshSession();
    await refreshSession();
    expect(counter.calls).toBe(2);
  });

  it('ends the session on a 401 and says it expired', async () => {
    setSession('test-access-1');
    await expect(refreshSession()).resolves.toBe(false);
    expect(getStatus()).toBe('anonymous');
    expect(getAccessToken()).toBeNull();
    expect(getNotice()).toBe('expired');
  });

  it('gives no expiry notice when there was no session to lose', async () => {
    await expect(refreshSession()).resolves.toBe(false);
    expect(getStatus()).toBe('anonymous');
    expect(getNotice()).toBeNull();
  });

  it('ends the session on a network error', async () => {
    countRefresh(() => HttpResponse.error());
    setSession('test-access-1');
    await expect(refreshSession()).resolves.toBe(false);
    expect(getStatus()).toBe('anonymous');
  });

  it('ends the session when a 200 body is not an access token', async () => {
    countRefresh(
      () =>
        new HttpResponse('<html></html>', {
          headers: { 'content-type': 'text/html' },
        }),
    );
    setSession('test-access-1');
    await expect(refreshSession()).resolves.toBe(false);
    expect(getAccessToken()).toBeNull();
  });
});

describe('a session that ended meanwhile', () => {
  it('does not refresh once the session ended while it waited for the lock', async () => {
    let chain: Promise<unknown> = Promise.resolve();
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: <T>(
          _name: string,
          _options: unknown,
          callback: () => Promise<T>,
        ) => {
          const run = chain.then(callback);
          chain = run.catch(() => undefined);
          return run;
        },
      },
    });
    try {
      const counter = countRefresh(() => accessToken('test-access-2'));
      setSession('test-access-1');
      let release: () => void = () => undefined;
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      const held = withAuthLock(() => gate);
      const refresh = refreshSession();
      clearSession();
      release();
      await held;
      await expect(refresh).resolves.toBe(false);
      expect(counter.calls).toBe(0);
      expect(getStatus()).toBe('anonymous');
      expect(getAccessToken()).toBeNull();
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });

  it('drops a token that arrives after the session ended', async () => {
    countRefresh(async () => {
      await delay(30);
      return accessToken('test-access-2');
    });
    setSession('test-access-1');
    const pending = refreshSession();
    clearSession();
    await expect(pending).resolves.toBe(false);
    expect(getStatus()).toBe('anonymous');
    expect(getAccessToken()).toBeNull();
  });
});

describe('restoreSession', () => {
  it('sends one refresh however often it is called', async () => {
    const counter = countRefresh(() => accessToken('test-access-1'));
    await Promise.all([restoreSession(), restoreSession()]);
    await restoreSession();
    expect(counter.calls).toBe(1);
    expect(getStatus()).toBe('authenticated');
  });

  it('does nothing once the status is known', async () => {
    const counter = countRefresh(() => accessToken('test-access-1'));
    setSession('test-access-1');
    await restoreSession();
    expect(counter.calls).toBe(0);
  });

  it('leaves a visitor without a cookie anonymous', async () => {
    await restoreSession();
    expect(getStatus()).toBe('anonymous');
  });
});

describe('across tabs', () => {
  function installFakeLocks() {
    let chain: Promise<unknown> = Promise.resolve();
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: <T>(
          _name: string,
          _options: unknown,
          callback: () => Promise<T>,
        ) => {
          const run = chain.then(callback);
          chain = run.catch(() => undefined);
          return run;
        },
      },
    });
  }

  async function runTwoTabs() {
    let active = 0;
    let maxActive = 0;
    const log: string[] = [];
    server.use(
      http.post('*/api/v1/auth/refresh', async () => {
        active += 1;
        maxActive = Math.max(maxActive, active);
        log.push('start');
        await delay(30);
        log.push('end');
        active -= 1;
        return accessToken('test-access-2');
      }),
    );
    // Two module instances behave like two tabs: separate in-tab state.
    const tabA = await import('./refresh.ts');
    vi.resetModules();
    const tabB = await import('./refresh.ts');
    const results = await Promise.all([
      tabA.refreshSession(),
      tabB.refreshSession(),
    ]);
    return { results, maxActive, log };
  }

  it('never has two refresh requests in flight at once', async () => {
    installFakeLocks();
    try {
      const { results, maxActive, log } = await runTwoTabs();
      expect(results).toEqual([true, true]);
      expect(maxActive).toBe(1);
      expect(log).toEqual(['start', 'end', 'start', 'end']);
    } finally {
      Reflect.deleteProperty(navigator, 'locks');
    }
  });

  it('would overlap without the lock (control)', async () => {
    const { maxActive } = await runTwoTabs();
    expect(maxActive).toBe(2);
  });
});
