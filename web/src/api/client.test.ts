import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { poolFixture } from '../test/fixtures/pool.ts';
import { server } from '../test/server.ts';
import { authClient } from './authClient.ts';
import { apiClient } from './client.ts';
import { setSession } from '../features/auth/session.ts';
import { ProblemError, UnreachableError } from './problem.ts';

const problem = (status: number, body: object) =>
  HttpResponse.json(body, {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

describe('apiClient', () => {
  it('returns the JSON body of a 200 answer', async () => {
    const { data } = await apiClient.GET('/api/v1/pool');
    expect(data?.assets).toHaveLength(poolFixture.assets.length);
  });

  it('requests the same-origin path', async () => {
    let url = '';
    server.use(
      http.get('*/api/v1/pool', ({ request }) => {
        url = request.url;
        return HttpResponse.json({ assets: [] });
      }),
    );
    await apiClient.GET('/api/v1/pool');
    expect(url).toBe(`${window.location.origin}/api/v1/pool`);
  });

  it('throws ProblemError with status and known code', async () => {
    server.use(
      http.get('*/api/v1/pool', () => problem(409, { code: 'SEASON_LIMIT' })),
    );
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ProblemError);
    expect(error).toMatchObject({ status: 409, code: 'SEASON_LIMIT' });
  });

  it.each([
    ['an unrecognised code', { code: 'NEW_CODE' }],
    ['no code', { title: 'x' }],
    ['a non-string code', { code: 7 }],
  ])('maps a Problem with %s to UNKNOWN', async (_name, body) => {
    server.use(http.get('*/api/v1/pool', () => problem(400, body)));
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 400, code: 'UNKNOWN' });
  });

  it.each([
    ['a truncated Problem body', 502, 'application/problem+json', '{"code":'],
    ['a null Problem body', 400, 'application/problem+json', 'null'],
    ['a non-Problem JSON 500', 500, 'application/json', '{"x":1}'],
    [
      'a proxy text 502',
      502,
      'text/plain',
      'Error occurred while trying to proxy',
    ],
  ])('throws UnreachableError for %s', async (_name, status, type, text) => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse(text, { status, headers: { 'content-type': type } }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('throws UnreachableError for a non-Problem 502', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('<html></html>', {
            status: 502,
            headers: { 'content-type': 'text/html' },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('throws UnreachableError for a 200 that is not JSON', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('<html></html>', {
            headers: { 'content-type': 'text/html' },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('rejects on a network error', async () => {
    server.use(http.get('*/api/v1/pool', () => HttpResponse.error()));
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(Error);
  });

  it('accepts a problem media type with parameters and any letter case', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('{"code":"SEASON_LIMIT"}', {
            status: 409,
            headers: {
              'content-type': 'Application/Problem+JSON; charset=utf-8',
            },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toMatchObject({
      status: 409,
      code: 'SEASON_LIMIT',
    });
  });

  it('does not take a text/html answer that mentions the problem type', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('{"code":"SEASON_LIMIT"}', {
            status: 409,
            headers: {
              'content-type': 'text/html; note="application/problem+json"',
            },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('rejects application/jsonp and accepts Application/JSON on a 200', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('{"assets":[]}', {
            headers: { 'content-type': 'application/jsonp' },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('{"assets":[]}', {
            headers: { 'content-type': 'Application/JSON' },
          }),
      ),
    );
    const { data } = await apiClient.GET('/api/v1/pool');
    expect(data?.assets).toEqual([]);
  });
});

describe('ProblemError.errors', () => {
  it('carries the field violations of a 400 Problem', async () => {
    server.use(
      http.get('*/api/v1/pool', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          errors: [{ field: 'password', reason: 'Size' }],
        }),
      ),
    );
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toMatchObject({
      code: 'INVALID_ARGUMENT',
      errors: [{ field: 'password', reason: 'Size' }],
    });
  });

  it.each([
    ['a string', 'oops'],
    ['entries without a reason', [{ field: 'email' }]],
    ['a non-string field', [{ field: 7, reason: 'Size' }]],
    ['a null entry', [null]],
  ])('falls back to an empty list for %s', async (_name, errors) => {
    server.use(
      http.get('*/api/v1/pool', () =>
        problem(400, { code: 'INVALID_ARGUMENT', errors }),
      ),
    );
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toMatchObject({ code: 'INVALID_ARGUMENT', errors: [] });
  });
});

describe('authClient', () => {
  it('never sends the bearer token', async () => {
    setSession('test-access-1');
    let header: string | null = 'unset';
    server.use(
      http.post('*/api/v1/auth/login', ({ request }) => {
        header = request.headers.get('authorization');
        return HttpResponse.json({ accessToken: 'test-access-9' });
      }),
    );
    await authClient.POST('/api/v1/auth/login', {
      body: { email: 'a@example.com', password: 'x' },
    });
    expect(header).toBeNull();
  });
});
