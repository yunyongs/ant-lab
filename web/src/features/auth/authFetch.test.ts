import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { apiClient } from '../../api/client.ts';
import { ProblemError } from '../../api/problem.ts';
import { accessToken, problem } from '../../test/authHandlers.ts';
import { server } from '../../test/server.ts';
import { authFetch } from './authFetch.ts';
import { clearSession, getNotice, getStatus, setSession } from './session.ts';

const ORIGIN = window.location.origin;

function refreshReturns(token: string) {
  const counter = { calls: 0 };
  server.use(
    http.post('*/api/v1/auth/refresh', () => {
      counter.calls += 1;
      return accessToken(token);
    }),
  );
  return counter;
}

// A pool endpoint that accepts only the given bearer token.
function poolAccepts(token: string, delayMs = 0) {
  const seen: (string | null)[] = [];
  server.use(
    http.get('*/api/v1/pool', async ({ request }) => {
      const header = request.headers.get('authorization');
      seen.push(header);
      if (delayMs) await delay(delayMs);
      return header === `Bearer ${token}`
        ? HttpResponse.json({ assets: [] })
        : problem(401, { code: 'UNAUTHENTICATED', status: 401 });
    }),
  );
  return seen;
}

describe('authFetch', () => {
  it('attaches the bearer token only while logged in', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get('*/api/v1/pool', ({ request }) => {
        seen.push(request.headers.get('authorization'));
        return HttpResponse.json({ assets: [] });
      }),
    );
    await apiClient.GET('/api/v1/pool');
    setSession('test-access-1');
    await apiClient.GET('/api/v1/pool');
    expect(seen).toEqual([null, 'Bearer test-access-1']);
  });

  it('refreshes once on a 401 and replays with the new token', async () => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-2');
    const seen = poolAccepts('test-access-2');
    const { data } = await apiClient.GET('/api/v1/pool');
    expect(data?.assets).toEqual([]);
    expect(seen).toEqual(['Bearer test-access-1', 'Bearer test-access-2']);
    expect(refresh.calls).toBe(1);
  });

  it('replays a POST with a byte-equal body', async () => {
    setSession('test-access-1');
    refreshReturns('test-access-2');
    const bodies: string[] = [];
    server.use(
      http.post('*/api/v1/seasons', async ({ request }) => {
        bodies.push(await request.text());
        return request.headers.get('authorization') === 'Bearer test-access-2'
          ? HttpResponse.json({}, { status: 201 })
          : problem(401, { code: 'UNAUTHENTICATED', status: 401 });
      }),
    );
    await apiClient.POST('/api/v1/seasons', {
      body: { name: '시즌', n: 1 } as never,
    });
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toBe(bodies[0]);
    expect(bodies[0]).toBe(JSON.stringify({ name: '시즌', n: 1 }));
  });

  it('shares one refresh between five concurrent 401s', async () => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-2');
    const seen = poolAccepts('test-access-2', 10);
    const results = await Promise.all(
      Array.from({ length: 5 }, () => apiClient.GET('/api/v1/pool')),
    );
    expect(results.every((r) => r.data !== undefined)).toBe(true);
    expect(refresh.calls).toBe(1);
    expect(seen.filter((h) => h === 'Bearer test-access-2')).toHaveLength(5);
  });

  it('replays without refreshing when the token changed meanwhile', async () => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-3');
    const seen = poolAccepts('test-access-2', 30);
    const call = apiClient.GET('/api/v1/pool');
    await delay(5);
    setSession('test-access-2');
    await call;
    expect(refresh.calls).toBe(0);
    expect(seen).toEqual(['Bearer test-access-1', 'Bearer test-access-2']);
  });

  it('ends the session when the replay is also a 401', async () => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-2');
    const seen = poolAccepts('never-matches');
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ProblemError);
    expect(error).toMatchObject({ status: 401 });
    expect(seen).toHaveLength(2);
    expect(refresh.calls).toBe(1);
    expect(getStatus()).toBe('anonymous');
    expect(getNotice()).toBe('expired');
  });

  it('returns the original 401 and does not replay when refresh fails', async () => {
    setSession('test-access-1');
    const seen = poolAccepts('never-matches');
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 401 });
    expect(seen).toHaveLength(1);
    expect(getStatus()).toBe('anonymous');
  });

  it('does not refresh for a visitor who is not logged in', async () => {
    const refresh = refreshReturns('test-access-2');
    server.use(
      http.get('*/api/v1/pool', () =>
        problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
      ),
    );
    clearSession();
    await expect(apiClient.GET('/api/v1/pool')).rejects.toMatchObject({
      status: 401,
    });
    expect(refresh.calls).toBe(0);
  });

  it('never refreshes or attaches a token for an auth path', async () => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-2');
    let header: string | null = 'unset';
    server.use(
      http.post('*/api/v1/auth/login', ({ request }) => {
        header = request.headers.get('authorization');
        return problem(401, { code: 'INVALID_CREDENTIALS', status: 401 });
      }),
    );
    const response = await authFetch(
      new Request(`${ORIGIN}/api/v1/auth/login`, {
        method: 'POST',
        body: '{}',
        headers: { 'content-type': 'application/json' },
      }),
    );
    expect(response.status).toBe(401);
    expect(header).toBeNull();
    expect(refresh.calls).toBe(0);
    expect(getStatus()).toBe('authenticated');
  });

  it.each([
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [409, 'SEASON_LIMIT'],
    [500, 'INTERNAL_ERROR'],
  ])('does not refresh or replay on a %i', async (status, code) => {
    setSession('test-access-1');
    const refresh = refreshReturns('test-access-2');
    let calls = 0;
    server.use(
      http.get('*/api/v1/pool', () => {
        calls += 1;
        return problem(status, { code, status });
      }),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toMatchObject({
      status,
    });
    expect(calls).toBe(1);
    expect(refresh.calls).toBe(0);
    expect(getStatus()).toBe('authenticated');
  });
});
